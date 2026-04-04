package youraveragedev.safeserver;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("safeserver-state-manager");

    private final Set<UUID> authenticatingPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, GameType> originalGameModes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3> initialPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3> originalPositionsBeforeAuth = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ServerOpListEntry> originalOpEntries = new ConcurrentHashMap<>();

    private MinecraftServer serverInstance;

    public void setServerInstance(MinecraftServer server) {
        this.serverInstance = server;
    }

    public boolean isPlayerAuthenticating(UUID playerUuid) {
        return authenticatingPlayers.contains(playerUuid);
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
        ServerOpListEntry originalOpEntry = server.getPlayerList().getOps().get(playerIdentity);
        originalOpEntries.put(playerUuid, originalOpEntry);

        if (originalOpEntry != null) {
            server.getPlayerList().deop(playerIdentity);
            LOGGER.info("Temporarily de-opped player {} ({}) for authentication.", playerName, playerUuid);
        }

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
        ServerOpListEntry originalOpEntry = originalOpEntries.remove(playerUuid);

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

            if (originalOpEntry != null && serverInstance != null) {
                NameAndId playerIdentity = new NameAndId(player.getGameProfile());
                if (!serverInstance.getPlayerList().isOp(playerIdentity)) {
                    serverInstance.getPlayerList().getOps().add(originalOpEntry);
                }
                LOGGER.info("Restored OP status for player {}.", playerName);
            }
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
        originalOpEntries.remove(playerUuid);
    }

    public void handlePlayerDisconnect(ServerPlayer player, MinecraftServer server) {
        UUID playerUuid = player.getUUID();
        String playerName = player.getName().getString();

        if (!authenticatingPlayers.contains(playerUuid)) {
            originalOpEntries.remove(playerUuid);
            return;
        }

        LOGGER.info("Player {} ({}) disconnecting during authentication. Attempting state restoration before save...", playerName, playerUuid);

        GameType originalMode = originalGameModes.get(playerUuid);
        Vec3 originalPos = originalPositionsBeforeAuth.get(playerUuid);
        ServerOpListEntry originalOpEntry = originalOpEntries.get(playerUuid);

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

            NameAndId playerIdentity = new NameAndId(player.getGameProfile());
            if (originalOpEntry != null && !server.getPlayerList().isOp(playerIdentity)) {
                server.getPlayerList().getOps().add(originalOpEntry);
                LOGGER.info("Restored OP status for {} before disconnect save.", playerName);
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
        ServerOpListEntry originalOpEntry = serverInstance.getPlayerList().getOps().get(playerIdentity);
        originalOpEntries.put(playerUuid, originalOpEntry);

        if (originalOpEntry != null) {
            serverInstance.getPlayerList().deop(playerIdentity);
            LOGGER.info("Temporarily de-opped player {} ({}) due to password reset while online.", playerName, playerUuid);
        }

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
}
