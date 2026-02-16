package youraveragedev.safeserver;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("safeserver-state-manager");
    
    private final Set<UUID> authenticatingPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, GameMode> originalGameModes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3d> initialPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vec3d> originalPositionsBeforeAuth = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> originalOpStatus = new ConcurrentHashMap<>();
    
    private MinecraftServer serverInstance;
    
    public void setServerInstance(MinecraftServer server) {
        this.serverInstance = server;
    }
    
    public boolean isPlayerAuthenticating(UUID playerUuid) {
        return authenticatingPlayers.contains(playerUuid);
    }
    
    public void applyAuthenticationState(ServerPlayerEntity player, MinecraftServer server) {
        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();
        
        authenticatingPlayers.add(playerUuid);
        originalGameModes.put(playerUuid, player.interactionManager.getGameMode());
        
        Vec3d originalPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        originalPositionsBeforeAuth.put(playerUuid, originalPos);
        
        Vec3d safePos = calculateSafeSpawnPosition(server, playerName);
        initialPositions.put(playerUuid, safePos);
        
        PlayerConfigEntry playerEntry = new PlayerConfigEntry(player.getGameProfile());
        boolean wasOp = server.getPlayerManager().isOperator(playerEntry);
        originalOpStatus.put(playerUuid, wasOp);
        if (wasOp) {
            server.getPlayerManager().removeFromOperators(playerEntry);
            LOGGER.info("Temporarily de-opped player {} ({}) for authentication.", playerName, playerUuid);
        }
        
        player.changeGameMode(GameMode.SPECTATOR);
        player.networkHandler.requestTeleport(safePos.getX(), safePos.getY(), safePos.getZ(), 0, 0);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true));
        
        LOGGER.info("Applied spectator mode and blindness to {} for authentication.", playerName);
    }
    
    public void sendWelcomeMessages(ServerPlayerEntity player, boolean hasPassword) {
        if (hasPassword) {
            player.sendMessage(Text.literal(SafeserverConstants.WELCOME_BACK_MESSAGE), false);
        } else {
            player.sendMessage(Text.literal(SafeserverConstants.WELCOME_NEW_MESSAGE), false);
            player.sendMessage(Text.literal(SafeserverConstants.SET_PASSWORD_PROMPT), false);
        }
    }
    
    public boolean restorePlayerState(UUID playerUuid) {
        GameMode originalMode = originalGameModes.remove(playerUuid);
        initialPositions.remove(playerUuid);
        Vec3d originalPos = originalPositionsBeforeAuth.remove(playerUuid);
        Boolean wasOp = originalOpStatus.remove(playerUuid);
        
        ServerPlayerEntity player = (serverInstance != null) ? serverInstance.getPlayerManager().getPlayer(playerUuid) : null;
        boolean success = true;
        
        if (player != null) {
            String playerName = player.getName().getString();
            
            if (originalPos != null) {
                player.networkHandler.requestTeleport(originalPos.getX(), originalPos.getY(), originalPos.getZ(), player.getYaw(), player.getPitch());
                LOGGER.info("Teleported player {} back to original location after authentication.", playerName);
            } else {
                success = restoreToSpawn(player) && success;
            }
            
            GameMode modeToRestore = determineGameModeToRestore(originalMode, playerName);
            if (modeToRestore != null) {
                player.changeGameMode(modeToRestore);
            } else {
                success = false;
            }
            
            if (player.hasStatusEffect(StatusEffects.BLINDNESS)) {
                player.removeStatusEffect(StatusEffects.BLINDNESS);
                LOGGER.info("Removed blindness from player {} after authentication.", playerName);
            }
            
            if (wasOp != null && wasOp && serverInstance != null) {
                PlayerConfigEntry playerEntry = new PlayerConfigEntry(player.getGameProfile());
                if (!serverInstance.getPlayerManager().isOperator(playerEntry)) {
                    serverInstance.getPlayerManager().addToOperators(playerEntry);
                }
                LOGGER.info("Restored OP status for player {}.", playerName);
            } else if (wasOp == null) {
                LOGGER.warn("Original OP status for player {} (UUID {}) was unexpectedly missing during state restoration.", playerName, playerUuid);
            }
        } else {
            LOGGER.warn("Could not restore state for UUID {} (Player not found online).", playerUuid);
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
        originalOpStatus.remove(playerUuid);
    }
    
    public void handlePlayerDisconnect(ServerPlayerEntity player, MinecraftServer server) {
        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();
        
        if (!authenticatingPlayers.contains(playerUuid)) {
            originalOpStatus.remove(playerUuid);
            return;
        }
        
        LOGGER.info("Player {} ({}) disconnecting during authentication. Attempting state restoration before save...", playerName, playerUuid);
        
        GameMode originalMode = originalGameModes.get(playerUuid);
        Vec3d originalPos = originalPositionsBeforeAuth.get(playerUuid);
        Boolean wasOp = originalOpStatus.get(playerUuid);
        
        boolean restoredSomething = false;
        try {
            if (originalPos != null) {
                player.networkHandler.requestTeleport(originalPos.getX(), originalPos.getY(), originalPos.getZ(), player.getYaw(), player.getPitch());
                LOGGER.info("Requested teleport for {} back to {} before disconnect save.", playerName, originalPos);
                restoredSomething = true;
            }
            
            GameMode modeToRestore = determineGameModeToRestore(originalMode, playerName);
            if (modeToRestore != null && player.interactionManager.getGameMode() != modeToRestore) {
                player.changeGameMode(modeToRestore);
                LOGGER.info("Restored gamemode for {} to {} before disconnect save.", playerName, modeToRestore);
                restoredSomething = true;
            }
            
            if (player.hasStatusEffect(StatusEffects.BLINDNESS)) {
                player.removeStatusEffect(StatusEffects.BLINDNESS);
                LOGGER.info("Removed blindness from {} before disconnect save.", playerName);
                restoredSomething = true;
            }
            
            PlayerConfigEntry playerEntry = new PlayerConfigEntry(player.getGameProfile());
            if (wasOp != null && wasOp && !server.getPlayerManager().isOperator(playerEntry)) {
                server.getPlayerManager().addToOperators(playerEntry);
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
            ServerPlayerEntity player = (serverInstance != null) ? serverInstance.getPlayerManager().getPlayer(playerUuid) : null;
            Vec3d initialPos = initialPositions.get(playerUuid);
            
            if (player != null && initialPos != null) {
                if (player.getX() != initialPos.getX() || player.getY() != initialPos.getY() || player.getZ() != initialPos.getZ()) {
                    player.networkHandler.requestTeleport(initialPos.getX(), initialPos.getY(), initialPos.getZ(), player.getYaw(), player.getPitch());
                }
            } else if (player == null || initialPos == null) {
                LOGGER.warn("Cleaning up inconsistent authentication state for UUID: {}", playerUuid);
                cleanupPlayerState(playerUuid);
            }
        }
    }
    
    public void forcePlayerIntoAuthenticationState(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        String playerName = player.getName().getString();
        
        LOGGER.info("Forcing player {} ({}) into authentication state after password reset.", playerName, playerUuid);
        
        authenticatingPlayers.add(playerUuid);
        originalGameModes.put(playerUuid, player.interactionManager.getGameMode());
        
        Vec3d originalPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        originalPositionsBeforeAuth.put(playerUuid, originalPos);
        
        Vec3d safePos = calculateSafeSpawnPosition(serverInstance, playerName);
        initialPositions.put(playerUuid, safePos);
        
        PlayerConfigEntry playerEntry = new PlayerConfigEntry(player.getGameProfile());
        boolean wasOp = serverInstance.getPlayerManager().isOperator(playerEntry);
        originalOpStatus.put(playerUuid, wasOp);
        if (wasOp) {
            serverInstance.getPlayerManager().removeFromOperators(playerEntry);
            LOGGER.info("Temporarily de-opped player {} ({}) due to password reset while online.", playerName, playerUuid);
        }
        
        player.changeGameMode(GameMode.SPECTATOR);
        player.networkHandler.requestTeleport(safePos.getX(), safePos.getY(), safePos.getZ(), 0, 0);
        player.sendMessage(Text.literal(SafeserverConstants.RESET_PASSWORD_MESSAGE), false);
        player.sendMessage(Text.literal(SafeserverConstants.RESET_PASSWORD_PROMPT), false);
    }
    
    private Vec3d calculateSafeSpawnPosition(MinecraftServer server, String playerName) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) {
            WorldProperties.SpawnPoint spawnPoint = overworld.getSpawnPoint();
            BlockPos spawnPos = spawnPoint.getPos();
            int safeY = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
            return new Vec3d(
                spawnPos.getX() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                safeY + SafeserverConstants.SAFE_SPAWN_Y_OFFSET, 
                spawnPos.getZ() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET
            );
        } else {
            LOGGER.warn("Could not get Overworld to determine spawn point for player {}. Defaulting to fallback.", playerName);
            return new Vec3d(
                SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                SafeserverConstants.FALLBACK_Y_COORDINATE, 
                SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET
            );
        }
    }
    
    private boolean restoreToSpawn(ServerPlayerEntity player) {
        String playerName = player.getName().getString();
        LOGGER.warn("Could not find original position for player {} during state restoration. Restoring to spawn.", playerName);
        
        if (serverInstance != null) {
            ServerWorld overworld = serverInstance.getWorld(World.OVERWORLD);
            if (overworld != null) {
                WorldProperties.SpawnPoint spawnPoint = overworld.getSpawnPoint();
                BlockPos spawnPos = spawnPoint.getPos();
                int spawnY = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
                player.networkHandler.requestTeleport(
                    spawnPos.getX() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                    spawnY, 
                    spawnPos.getZ() + SafeserverConstants.SAFE_SPAWN_CENTER_OFFSET, 
                    spawnPoint.yaw(), 
                    spawnPoint.pitch()
                );
                return true;
            } else {
                LOGGER.error("Could not get Overworld to restore player {} to spawn!", playerName);
                return false;
            }
        } else {
            LOGGER.error("Could not get server instance to restore player {} to spawn!", playerName);
            return false;
        }
    }
    
    private GameMode determineGameModeToRestore(GameMode originalMode, String playerName) {
        if (originalMode != null) {
            if (originalMode == GameMode.SPECTATOR) {
                if (serverInstance != null) {
                    GameMode defaultMode = serverInstance.getDefaultGameMode();
                    LOGGER.info("Original gamemode was SPECTATOR, restoring to server default ({}) for player {}.", defaultMode, playerName);
                    return defaultMode;
                } else {
                    LOGGER.error("Could not get server instance to determine default gamemode for player {}. Restoration may fail.", playerName);
                    return null;
                }
            } else {
                LOGGER.info("Restored original gamemode ({}) for player {}.", originalMode, playerName);
                return originalMode;
            }
        } else {
            LOGGER.warn("Could not find original gamemode for player {}. Setting to default.", playerName);
            if (serverInstance != null) {
                return serverInstance.getDefaultGameMode();
            } else {
                LOGGER.error("Could not get server instance to determine default gamemode for player {}. Restoration may fail.", playerName);
                return null;
            }
        }
    }
}
