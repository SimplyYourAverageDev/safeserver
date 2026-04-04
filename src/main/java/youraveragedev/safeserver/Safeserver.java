package youraveragedev.safeserver;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import youraveragedev.safeserver.command.AuthCommands;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public class Safeserver implements ModInitializer {
	public static final String MOD_ID = "safeserver";
	private static Safeserver instance;

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Password storage with thread safety
	private final ConcurrentHashMap<String, String> playerPasswords = new ConcurrentHashMap<>();
	
	// Player state management
	private final PlayerStateManager stateManager = new PlayerStateManager();
	
	// Async file operations
	private final Executor fileExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Safeserver-FileIO");
		t.setDaemon(true);
		return t;
	});

	// Gson instance for JSON handling
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// Define the type for deserialization
	private static final Type PASSWORD_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
	// Path to the password storage file
	private Path passwordFilePath;

	private MinecraftServer serverInstance; // Store server instance

	@Override
	public void onInitialize() {
		instance = this;

		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Initializing Safeserver...");

		// Determine password file path
		passwordFilePath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("passwords.json");

		// Load passwords from file
		loadPasswords();
		
		// Initialize state manager
		stateManager.setServerInstance(null); // Will be set in onEndTick

		// Player Join Logic
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			UUID playerUuid = player.getUUID();
			String playerUuidString = playerUuid.toString();
			String playerName = player.getName().getString();

			LOGGER.info("Player {} ({}) joined. Checking authentication...", playerName, playerUuidString);

			if (playerPasswords.containsKey(playerUuidString)) {
				// Returning player needing login
				if (!stateManager.isPlayerAuthenticating(playerUuid)) {
					LOGGER.info("Player {} needs to log in.", playerName);
					stateManager.applyAuthenticationState(player, server);
					stateManager.sendWelcomeMessages(player, true);
				}
			} else {
				// First join, needing password set
				if (!stateManager.isPlayerAuthenticating(playerUuid)) {
					LOGGER.info("Player {} needs to set a password.", playerName);
					stateManager.applyAuthenticationState(player, server);
					stateManager.sendWelcomeMessages(player, false);
				}
			}
		});

		// Player Disconnect Logic
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.player;
			UUID playerUuid = player.getUUID();
			String playerName = player.getName().getString();
			
			stateManager.handlePlayerDisconnect(player, server);
		});

		// Register Commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			AuthCommands.registerCommands(dispatcher, this);
			LOGGER.info("Registered authentication commands.");
		});

		// Register Gameplay Blocking Events
		registerGameplayBlockingEvents();

		// Register Server Tick for position freeze
		ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
		
		LOGGER.info("Safeserver Initialized! Loaded {} passwords.", playerPasswords.size());
	}

	private void onEndTick(MinecraftServer server) {
		this.serverInstance = server;
		stateManager.setServerInstance(server);
		
		// Use optimized position enforcement from state manager
		stateManager.enforcePositionFreeze();
	}

	private void registerGameplayBlockingEvents() {
		// Block Block Breaking
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (stateManager.isPlayerAuthenticating(player.getUUID())) {
				player.sendOverlayMessage(Component.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE));
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		// Block Block Usage
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (stateManager.isPlayerAuthenticating(player.getUUID())) {
				player.sendOverlayMessage(Component.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE));
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		// Block Item Usage
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (stateManager.isPlayerAuthenticating(player.getUUID())) {
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		// Block Attacking Entities
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (stateManager.isPlayerAuthenticating(player.getUUID())) {
                player.sendOverlayMessage(Component.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE));
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

		// Block Using Entities (e.g., trading, mounting)
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (stateManager.isPlayerAuthenticating(player.getUUID())) {
                player.sendOverlayMessage(Component.literal(SafeserverConstants.AUTH_INTERACT_MESSAGE));
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

		// Note: Movement blocking is more complex and might require cancelling packets
        // or using server ticks to teleport players back. For now, we focus on interactions.
		LOGGER.info("Registered gameplay blocking event listeners.");
	}

	public static Safeserver getInstance() {
		return instance;
	}

	public boolean shouldAllowPlayerCommand(ServerPlayer player, String command) {
		if (!stateManager.isPlayerAuthenticating(player.getUUID())) {
			return true;
		}

		String commandRoot = command.trim().split(" ", 2)[0];
		if (commandRoot.equalsIgnoreCase("login") || commandRoot.equalsIgnoreCase("setpassword")) {
			return true;
		}

		player.sendSystemMessage(Component.literal(SafeserverConstants.AUTH_COMMAND_MESSAGE));
		LOGGER.debug("Blocked command attempt \"{}\" for unauthenticated player {}", command, player.getName().getString());
		return false;
	}

	// Helper method to hash passwords (using SHA-256)
	public static String hashPassword(String password) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
			StringBuilder hexString = new StringBuilder();
			for (byte b : hash) {
				String hex = Integer.toHexString(0xff & b);
				if (hex.length() == 1) hexString.append('0');
				hexString.append(hex);
			}
			return hexString.toString();
		} catch (NoSuchAlgorithmException e) {
			LOGGER.error("Failed to initialize SHA-256 for password hashing.", e);
			return SafeserverConstants.HASHING_ERROR_VALUE;
		}
	}

	// --- Password Persistence --- 

	private void loadPasswords() {
        if (Files.exists(passwordFilePath)) {
            try (BufferedReader reader = Files.newBufferedReader(passwordFilePath)) {
                Map<String, String> loadedMap = GSON.fromJson(reader, PASSWORD_MAP_TYPE);
                if (loadedMap != null) {
                    playerPasswords.putAll(loadedMap);
                    LOGGER.info("Successfully loaded passwords from {}", passwordFilePath);
                } else {
					LOGGER.warn("Password file {} was empty or invalid JSON.", passwordFilePath);
				}
            } catch (IOException | com.google.gson.JsonSyntaxException e) {
                LOGGER.error("Failed to load passwords from {}: {}", passwordFilePath, e.getMessage());
            }
        } else {
            LOGGER.info("Password file not found at {}, creating a new one on first save.", passwordFilePath);
            // Optionally create the directory structure here if needed upon first load attempt
            // try { Files.createDirectories(passwordFilePath.getParent()); } catch (IOException e) { ... }
        }
    }

    private void savePasswords() {
        CompletableFuture.runAsync(this::savePasswordsSync, fileExecutor)
            .exceptionally(throwable -> {
                LOGGER.error("Async password save failed", throwable);
                return null;
            });
    }
    
    private synchronized void savePasswordsSync() {
        try {
            Files.createDirectories(passwordFilePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(passwordFilePath)) {
                GSON.toJson(playerPasswords, writer);
                LOGGER.info("Successfully saved passwords to {}", passwordFilePath);
            } catch (IOException e) {
                LOGGER.error("Failed to write passwords to {}: {}", passwordFilePath, e.getMessage());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create directory for password file {}: {}", passwordFilePath.getParent(), e.getMessage());
        }
    }

	// --- Authentication Logic --- 

	public boolean isPlayerAuthenticating(UUID playerUuid) {
		return stateManager.isPlayerAuthenticating(playerUuid);
	}

	public boolean hasPassword(UUID playerUuid) {
		return playerPasswords.containsKey(playerUuid.toString());
	}

	public boolean registerPlayer(UUID playerUuid, String password) {
		if (hasPassword(playerUuid)) {
			return false; // Already registered
		}
		String hashedPassword = hashPassword(password);
		if (SafeserverConstants.HASHING_ERROR_VALUE.equals(hashedPassword)) {
			LOGGER.error("Could not register player {} due to hashing error.", playerUuid);
			return false;
		}
		playerPasswords.put(playerUuid.toString(), hashedPassword);
		
		savePasswords(); // Save passwords after registration

		// Restore original state and remove from auth list
		boolean restored = stateManager.restorePlayerState(playerUuid);
		if(!restored) {
			LOGGER.warn("Could not fully restore state for {} after registration, but proceeding.", playerUuid);
		}

		return true;
	}

	public boolean authenticatePlayer(UUID playerUuid, String password) {
		if (!hasPassword(playerUuid) || !isPlayerAuthenticating(playerUuid)) {
			return false; // Not registered or not in auth process
		}
		String storedPasswordHash = playerPasswords.get(playerUuid.toString());
		String providedPasswordHash = hashPassword(password);

		if (storedPasswordHash.equals(providedPasswordHash)) {
			// Restore original state and remove from auth list
			boolean restored = stateManager.restorePlayerState(playerUuid);
			if(!restored) {
				LOGGER.warn("Could not fully restore state for {} after login, but proceeding.", playerUuid);
			}
			return true;
		} else {
			return false; // Incorrect password
		}
	}


	// --- New/Modified Password Management Methods ---

	/**
	 * Changes the password for the currently logged-in player.
	 * Requires the correct old password.
	 */
	public boolean changePlayerPassword(UUID playerUuid, String oldPassword, String newPassword) {
		if (isPlayerAuthenticating(playerUuid)) {
			LOGGER.warn("Attempt to change password while player {} is still authenticating.", playerUuid);
			return false; // Should not change password while authenticating
		}
		if (!hasPassword(playerUuid)) {
			LOGGER.warn("Attempt to change password for player {} who has no password set.", playerUuid);
			return false; // Should have a password to change it
		}

		String storedPasswordHash = playerPasswords.get(playerUuid.toString());
		String providedOldPasswordHash = hashPassword(oldPassword);

		if (!storedPasswordHash.equals(providedOldPasswordHash)) {
			return false; // Incorrect old password
		}

		String newPasswordHash = hashPassword(newPassword);
		if (SafeserverConstants.HASHING_ERROR_VALUE.equals(newPasswordHash)) {
			LOGGER.error("Could not change password for player {} due to hashing error.", playerUuid);
			return false;
		}

		playerPasswords.put(playerUuid.toString(), newPasswordHash);
		savePasswords();
		LOGGER.info("Player {} successfully changed their password.", playerUuid);
		return true;
	}

	/**
	 * Resets and sets a new password for an authenticated player.
	 * This allows logged-in players to use /setpassword to reset their password.
	 */
	public boolean resetAndSetPassword(UUID playerUuid, String newPassword) {
		if (isPlayerAuthenticating(playerUuid)) {
			LOGGER.warn("Attempt to reset password while player {} is still authenticating.", playerUuid);
			return false; // Should not reset password while authenticating
		}
		if (!hasPassword(playerUuid)) {
			LOGGER.warn("Attempt to reset password for player {} who has no password set.", playerUuid);
			return false; // Should have a password to reset it
		}

		String newPasswordHash = hashPassword(newPassword);
		if (SafeserverConstants.HASHING_ERROR_VALUE.equals(newPasswordHash)) {
			LOGGER.error("Could not reset password for player {} due to hashing error.", playerUuid);
			return false;
		}

		playerPasswords.put(playerUuid.toString(), newPasswordHash);
		savePasswords();
		LOGGER.info("Player {} successfully reset their password using setpassword command.", playerUuid);
		return true;
	}

	/**
	 * Resets the password for a target player (removes their entry).
	 * This forces them to set a new password on next join.
	 */
	public boolean resetPlayerPassword(UUID targetPlayerUuid) {
		String targetUuidString = targetPlayerUuid.toString();
		if (!playerPasswords.containsKey(targetUuidString)) {
			return false; // Player doesn't have a password / isn't registered with the mod
		}

		playerPasswords.remove(targetUuidString);
		savePasswords();

		// If the target player is currently online, force them back into authentication state
		ServerPlayer targetPlayer = (this.serverInstance != null) ? this.serverInstance.getPlayerList().getPlayer(targetPlayerUuid) : null;
		if (targetPlayer != null && !stateManager.isPlayerAuthenticating(targetPlayerUuid)) {
			stateManager.forcePlayerIntoAuthenticationState(targetPlayer);
		} else {
            LOGGER.info("Password reset for offline player UUID {}. They will need to set a new password on next login.", targetPlayerUuid);
        }

		return true;
	}
}
