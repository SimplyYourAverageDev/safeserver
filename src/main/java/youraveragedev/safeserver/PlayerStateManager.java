package youraveragedev.safeserver;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("safeserver-state-manager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type OP_BACKUP_MAP_TYPE = new TypeToken<Map<String, OpBackup>>() {}.getType();

    private final Set<UUID> authenticatingPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, GameType> originalGameModes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3> initialPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3> originalPositionsBeforeAuth = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OpBackup> originalOpEntries = new ConcurrentHashMap<>();

    private MinecraftServer serverInstance;
    private Path opBackupFilePath;

    public void setServerInstance(MinecraftServer server) {
        this.serverInstance = server;
    }

    public void setOpBackupFilePath(Path opBackupFilePath) {
        this.opBackupFilePath = opBackupFilePath;
        loadOpBackups();
    }

    public boolean isPlayerAuthenticating(UUID playerUuid) {
        return authenticatingPlayers.contains(playerUuid);
    }

    public void prepareOpSafety(GameProfile profile, MinecraftServer server) {
        backupAndRemoveOp(new NameAndId(profile), server, "before joining");
    }

    public void restorePreJoinOpSafety(GameProfile profile, MinecraftServer server) {
        NameAndId playerIdentity = new NameAndId(profile);
        restoreOpStatus(playerIdentity.id(), playerIdentity, server, playerIdentity.name());
    }

    public void applyAuthenticationState(ServerPlayer player, MinecraftServer server) {
        UUID playerUuid = player.getUUID();
        String playerName = player.getName().getString();

        authenticatingPlayers.add(playerUuid);
        originalGameModes.put(playerUuid, player.gameMode.getGameModeForPlayer());

        Vec3 originalPos = new Vec3(player.getX(), player.getY(), player.getZ());
        originalPositionsBeforeAuth.put(playerUuid, originalPos);

        Vec3 safePos = calculateSafeSpawnPosition(server, playerName);
        initialPositions.put(playerUuid, safePos);

        NameAndId playerIdentity = new NameAndId(player.getGameProfile());
        backupAndRemoveOp(playerIdentity, server, "for authentication");

        player.setGameMode(GameType.SPECTATOR);
        player.connection.teleport(safePos.x, safePos.y, safePos.z, 0, 0);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true));

        LOGGER.info("Applied spectator mode and blindness to {} for authentication.", playerName);
    }

    public void sendWelcomeMessages(ServerPlayer player, boolean hasPassword) {
        if (hasPassword) {
            player.sendSystemMessage(Component.literal(SafeserverConstants.WELCOME_BACK_MESSAGE));
        } else {
            player.sendSystemMessage(Component.literal(SafeserverConstants.WELCOME_NEW_MESSAGE));
            player.sendSystemMessage(Component.literal(SafeserverConstants.SET_PASSWORD_PROMPT));
        }
    }

    public boolean restorePlayerState(UUID playerUuid) {
        GameType originalMode = originalGameModes.remove(playerUuid);
        initialPositions.remove(playerUuid);
        Vec3 originalPos = originalPositionsBeforeAuth.remove(playerUuid);
        OpBackup originalOpEntry = originalOpEntries.get(playerUuid.toString());

        ServerPlayer player = serverInstance != null ? serverInstance.getPlayerList().getPlayer(playerUuid) : null;
        boolean success = true;

        if (player != null) {
            String playerName = player.getName().getString();

            if (originalPos != null) {
                player.connection.teleport(originalPos.x, originalPos.y, originalPos.z, player.getYRot(), player.getXRot());
                LOGGER.info("Teleported player {} back to original location after authentication.", playerName);
            } else {
                success = restoreToSpawn(player) && success;
            }

            GameType modeToRestore = determineGameModeToRestore(originalMode, playerName);
            if (modeToRestore != null) {
                player.setGameMode(modeToRestore);
            } else {
                success = false;
            }

            if (player.hasEffect(MobEffects.BLINDNESS)) {
                player.removeEffect(MobEffects.BLINDNESS);
                LOGGER.info("Removed blindness from player {} after authentication.", playerName);
            }

            restoreOpStatus(playerUuid, new NameAndId(player.getGameProfile()), serverInstance, playerName);
        } else {
            LOGGER.warn("Could not restore state for UUID {} (player not found online).", playerUuid);
            cleanupPlayerState(playerUuid);
            success = false;
        }

        authenticatingPlayers.remove(playerUuid);
        return success;
    }

    public void cleanupPlayerState(UUID playerUuid) {
        authenticatingPlayers.remove(playerUuid);
        originalGameModes.remove(playerUuid);
        initialPositions.remove(playerUuid);
        originalPositionsBeforeAuth.remove(playerUuid);
    }

    public void handlePlayerDisconnect(ServerPlayer player, MinecraftServer server) {
        UUID playerUuid = player.getUUID();
        String playerName = player.getName().getString();

        if (!authenticatingPlayers.contains(playerUuid)) {
            return;
        }

        LOGGER.info("Player {} ({}) disconnecting during authentication. Attempting state restoration before save...", playerName, playerUuid);

        GameType originalMode = originalGameModes.get(playerUuid);
        Vec3 originalPos = originalPositionsBeforeAuth.get(playerUuid);
        OpBackup originalOpEntry = originalOpEntries.get(playerUuid.toString());

        boolean restoredSomething = false;

        try {
            if (originalPos != null) {
                player.connection.teleport(originalPos.x, originalPos.y, originalPos.z, player.getYRot(), player.getXRot());
                LOGGER.info("Requested teleport for {} back to {} before disconnect save.", playerName, originalPos);
                restoredSomething = true;
            }

            GameType modeToRestore = determineGameModeToRestore(originalMode, playerName);
            if (modeToRestore != null && player.gameMode.getGameModeForPlayer() != modeToRestore) {
                player.setGameMode(modeToRestore);
                LOGGER.info("Restored gamemode for {} to {} before disconnect save.", playerName, modeToRestore);
                restoredSomething = true;
            }

            if (player.hasEffect(MobEffects.BLINDNESS)) {
                player.removeEffect(MobEffects.BLINDNESS);
                LOGGER.info("Removed blindness from {} before disconnect save.", playerName);
                restoredSomething = true;
            }

            if (originalOpEntry != null) {
                restoreOpStatus(playerUuid, new NameAndId(player.getGameProfile()), server, playerName);
                restoredSomething = true;
            }
        } catch (Exception e) {
            LOGGER.error("Error attempting immediate state restoration for {} during disconnect: {}", playerName, e.getMessage(), e);
        }

        cleanupPlayerState(playerUuid);

        if (restoredSomething) {
            LOGGER.info("Cleaned up authentication state for {} after attempting pre-disconnect restoration.", playerName);
        } else {
            LOGGER.warn("Cleaned up authentication state for {} (pre-disconnect restoration may not have completed fully).", playerName);
        }
    }

    public void enforcePositionFreeze() {
        for (UUID playerUuid : Set.copyOf(authenticatingPlayers)) {
            ServerPlayer player = serverInstance != null ? serverInstance.getPlayerList().getPlayer(playerUuid) : null;
            Vec3 initialPos = initialPositions.get(playerUuid);

            if (player != null && initialPos != null) {
                if (player.getX() != initialPos.x || player.getY() != initialPos.y || player.getZ() != initialPos.z) {
                    player.connection.teleport(initialPos.x, initialPos.y, initialPos.z, player.getYRot(), player.getXRot());
                }
            } else {
                LOGGER.warn("Cleaning up inconsistent authentication state for UUID: {}", playerUuid);
                cleanupPlayerState(playerUuid);
            }
        }
    }

    public void forcePlayerIntoAuthenticationState(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        String playerName = player.getName().getString();

        LOGGER.info("Forcing player {} ({}) into authentication state after password reset.", playerName, playerUuid);

        authenticatingPlayers.add(playerUuid);
        originalGameModes.put(playerUuid, player.gameMode.getGameModeForPlayer());

        Vec3 originalPos = new Vec3(player.getX(), player.getY(), player.getZ());
        originalPositionsBeforeAuth.put(playerUuid, originalPos);

        Vec3 safePos = calculateSafeSpawnPosition(serverInstance, playerName);
        initialPositions.put(playerUuid, safePos);

        NameAndId playerIdentity = new NameAndId(player.getGameProfile());
        backupAndRemoveOp(playerIdentity, serverInstance, "due to password reset while online");

        player.setGameMode(GameType.SPECTATOR);
        player.connection.teleport(safePos.x, safePos.y, safePos.z, 0, 0);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true));
        player.sendSystemMessage(Component.literal(SafeserverConstants.RESET_PASSWORD_MESSAGE));
        player.sendSystemMessage(Component.literal(SafeserverConstants.RESET_PASSWORD_PROMPT));
    }

    private Vec3 calculateSafeSpawnPosition(MinecraftServer server, String playerName) {
        LevelData.RespawnData respawnData = server.getRespawnData();
        ServerLevel respawnLevel = server.getLevel(respawnData.dimension());
        ServerLevel fallbackOverworld = server.getLevel(Level.OVERWORLD);
        ServerLevel level = respawnLevel != null ? respawnLevel : fallbackOverworld;

        if (level == null) {
            LOGGER.warn("Could not resolve a spawn level for player {}. Defaulting to fallback.", playerName);
            return new Vec3(
                SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET,
                SafeserverConstants.FALLBACK_Y_COORDINATE,
                SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET
            );
        }

        BlockPos spawnPos = respawnData.pos();
        int safeY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
        return new Vec3(
            spawnPos.getX() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET,
            safeY + SafeserverConstants.SAFE_SPAWN_Y_OFFSET,
            spawnPos.getZ() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET
        );
    }

    private boolean restoreToSpawn(ServerPlayer player) {
        String playerName = player.getName().getString();
        LOGGER.warn("Could not find original position for player {} during state restoration. Restoring to spawn.", playerName);

        if (serverInstance == null) {
            LOGGER.error("Could not get server instance to restore player {} to spawn!", playerName);
            return false;
        }

        LevelData.RespawnData respawnData = serverInstance.getRespawnData();
        ServerLevel respawnLevel = serverInstance.getLevel(respawnData.dimension());

        if (respawnLevel == null) {
            LOGGER.error("Could not resolve a respawn level to restore player {} to spawn!", playerName);
            return false;
        }

        BlockPos spawnPos = respawnData.pos();
        int spawnY = respawnLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
        player.teleportTo(
            respawnLevel,
            spawnPos.getX() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET,
            spawnY,
            spawnPos.getZ() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET,
            Set.of(),
            respawnData.yaw(),
            respawnData.pitch(),
            false
        );
        return true;
    }

    private GameType determineGameModeToRestore(GameType originalMode, String playerName) {
        if (originalMode != null) {
            LOGGER.info("Restored original gamemode ({}) for player {}.", originalMode, playerName);
            return originalMode;
        }

        LOGGER.warn("Could not find original gamemode for player {}. Setting to default.", playerName);
        if (serverInstance != null) {
            return serverInstance.getDefaultGameType();
        }

        LOGGER.error("Could not get server instance to determine default gamemode for player {}. Restoration may fail.", playerName);
        return null;
    }

    private void backupAndRemoveOp(NameAndId playerIdentity, MinecraftServer server, String reason) {
        if (server == null) {
            return;
        }

        ServerOpListEntry currentOpEntry = server.getPlayerList().getOps().get(playerIdentity);
        if (currentOpEntry == null) {
            return;
        }

        UUID playerUuid = playerIdentity.id();
        originalOpEntries.computeIfAbsent(playerUuid.toString(), ignored -> OpBackup.from(playerIdentity, currentOpEntry));
        saveOpBackups();

        server.getPlayerList().deop(playerIdentity);
        LOGGER.info("Temporarily de-opped player {} ({}) {}.", playerIdentity.name(), playerUuid, reason);
    }

    private void restoreOpStatus(UUID playerUuid, NameAndId playerIdentity, MinecraftServer server, String playerName) {
        OpBackup originalOpEntry = originalOpEntries.get(playerUuid.toString());
        if (originalOpEntry == null || server == null) {
            return;
        }

        NameAndId backupIdentity = originalOpEntry.toNameAndId();
        NameAndId identityToRestore = backupIdentity != null ? backupIdentity : playerIdentity;
        if (!server.getPlayerList().isOp(identityToRestore)) {
            server.getPlayerList().getOps().add(originalOpEntry.toServerOpListEntry(identityToRestore));
        }

        originalOpEntries.remove(playerUuid.toString());
        saveOpBackups();
        LOGGER.info("Restored OP status for player {}.", playerName);
    }

    private void loadOpBackups() {
        if (opBackupFilePath == null || !Files.exists(opBackupFilePath)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(opBackupFilePath)) {
            Map<String, OpBackup> loadedBackups = GSON.fromJson(reader, OP_BACKUP_MAP_TYPE);
            if (loadedBackups != null) {
                originalOpEntries.putAll(loadedBackups);
                LOGGER.info("Loaded {} pending OP backups from {}", loadedBackups.size(), opBackupFilePath);
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            LOGGER.error("Failed to load OP backups from {}: {}", opBackupFilePath, e.getMessage());
        }
    }

    private synchronized void saveOpBackups() {
        if (opBackupFilePath == null) {
            return;
        }

        try {
            Files.createDirectories(opBackupFilePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(opBackupFilePath)) {
                GSON.toJson(originalOpEntries, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save OP backups to {}: {}", opBackupFilePath, e.getMessage());
        }
    }

    private record OpBackup(String uuid, String name, int permissionLevel, boolean bypassesPlayerLimit) {
        static OpBackup from(NameAndId playerIdentity, ServerOpListEntry opEntry) {
            return new OpBackup(
                playerIdentity.id().toString(),
                playerIdentity.name(),
                opEntry.permissions().level().id(),
                opEntry.getBypassesPlayerLimit()
            );
        }

        NameAndId toNameAndId() {
            try {
                return new NameAndId(UUID.fromString(uuid), name);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Ignoring invalid OP backup UUID for player {}: {}", name, uuid);
                return null;
            }
        }

        ServerOpListEntry toServerOpListEntry(NameAndId playerIdentity) {
            PermissionLevel level = PermissionLevel.byId(permissionLevel);
            LevelBasedPermissionSet permissions = LevelBasedPermissionSet.forLevel(level);
            return new ServerOpListEntry(playerIdentity, permissions, bypassesPlayerLimit);
        }
    }
}
