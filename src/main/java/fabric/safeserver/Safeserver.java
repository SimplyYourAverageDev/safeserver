package fabric.safeserver;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Safeserver implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(Safeserver.class);
	private static final String PASSWORD_FILE = "passwords.properties";

	private final Map<UUID, Boolean> loggedInPlayers = new HashMap<>();
	private final Map<UUID, Vec3d> playerJoinPositions = new HashMap<>();
	private final Map<UUID, Boolean> playerWasOpped = new HashMap<>();
	private Properties passwordProperties;

	@Override
	public void onInitialize() {
		loadPasswordProperties();
		registerCommands();
		registerPlayerEvents();
		registerServerTickEvent();
	}

	private void loadPasswordProperties() {
		passwordProperties = new Properties();
		File file = new File(PASSWORD_FILE);
		if (file.exists()) {
			try (FileReader reader = new FileReader(file)) {
				passwordProperties.load(reader);
			} catch (IOException e) {
				LOGGER.error("Failed to load password properties", e);
			}
		}
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerSetPasswordCommand(dispatcher);
			registerLoginCommand(dispatcher);
		});
	}

	private void registerPlayerEvents() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> handlePlayerJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> handlePlayerDisconnect(handler.getPlayer()));
	}

	private void registerServerTickEvent() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				handleServerTick(player);
			}
		});
	}

	private void handlePlayerJoin(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		loggedInPlayers.put(playerId, false);
		playerJoinPositions.put(playerId, player.getPos());
		player.changeGameMode(GameMode.SPECTATOR);
		LOGGER.info("Player {} joined the game.", player.getName().getString());

		boolean wasOpped = player.hasPermissionLevel(2);
		playerWasOpped.put(playerId, wasOpped);

		if (wasOpped) {
			deopPlayer(player);
		}

		sendJoinInstructions(player);
	}

	private void handlePlayerDisconnect(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		loggedInPlayers.remove(playerId);
		playerJoinPositions.remove(playerId);
		playerWasOpped.remove(playerId);
		LOGGER.info("Player {} left the game.", player.getName().getString());
	}

	private void handleServerTick(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		if (!loggedInPlayers.getOrDefault(playerId, false)) {
			Vec3d joinPos = playerJoinPositions.get(playerId);
			if (joinPos != null) {
				teleportPlayerToJoinPosition(player, joinPos);
			}
		}
	}

	private void registerSetPasswordCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("setPassword")
				.then(CommandManager.argument("password", StringArgumentType.greedyString())
						.executes(context -> {
							ServerPlayerEntity player = context.getSource().getPlayer();
							if (player == null) {
								context.getSource().sendError(Text.of("This command can only be executed by a player."));
								return 0;
							}

							String password = StringArgumentType.getString(context, "password");
							if (hasPassword(player)) {
								player.sendMessage(Text.of("You have already set a password and cannot modify it."), false);
								LOGGER.info("Player {} tried to set a password but already has one.", player.getName().getString());
							} else {
								setPassword(player, password);
							}
							return 1;
						})));
	}

	private void registerLoginCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("login")
				.then(CommandManager.argument("password", StringArgumentType.greedyString())
						.executes(context -> {
							ServerPlayerEntity player = context.getSource().getPlayer();
							if (player != null) {
								String password = StringArgumentType.getString(context, "password");
								login(player, password);
							} else {
								context.getSource().sendError(Text.of("Error: Player entity is null."));
							}
							return 1;
						})));
	}

	private boolean hasPassword(ServerPlayerEntity player) {
		return passwordProperties.containsKey(player.getUuid().toString());
	}

	private void setPassword(ServerPlayerEntity player, String password) {
		String playerIdString = player.getUuid().toString();
		passwordProperties.setProperty(playerIdString, password);
		try (FileWriter writer = new FileWriter(PASSWORD_FILE)) {
			passwordProperties.store(writer, "Player Passwords");
			player.sendMessage(Text.of("Password set successfully!"), false);
			player.sendMessage(Text.of("You can now log in using /login <password>."), false);
			LOGGER.info("Player {} set their password.", player.getName().getString());
		} catch (IOException e) {
			LOGGER.error("Failed to set password", e);
			player.sendMessage(Text.of("Failed to set password. Please try again."), false);
		}
	}

	private void login(ServerPlayerEntity player, String password) {
		String playerIdString = player.getUuid().toString();
		String storedPassword = passwordProperties.getProperty(playerIdString);
		if (storedPassword != null && storedPassword.equals(password)) {
			loggedInPlayers.put(player.getUuid(), true);
			player.changeGameMode(GameMode.SURVIVAL);
			player.sendMessage(Text.of("Login successful! Enjoy playing on the server."), false);
			LOGGER.info("Player {} logged in successfully.", player.getName().getString());

			if (playerWasOpped.getOrDefault(player.getUuid(), false)) {
				reopPlayer(player);
			}
		} else {
			player.sendMessage(Text.of("Invalid password. Please try again."), false);
			LOGGER.info("Player {} entered an invalid password.", player.getName().getString());
		}
	}

	private void deopPlayer(ServerPlayerEntity player) {
		player.server.getPlayerManager().removeFromOperators(player.getGameProfile());
		player.server.getPlayerManager().sendCommandTree(player);
		player.sendMessage(Text.of("You have been temporarily de-opped. Please enter your password to regain operator status."), false);
	}

	private void reopPlayer(ServerPlayerEntity player) {
		player.server.getPlayerManager().addToOperators(player.getGameProfile());
		player.sendMessage(Text.of("Your operator status has been restored."), false);
	}

	private void sendJoinInstructions(ServerPlayerEntity player) {
		if (!hasPassword(player)) {
			player.sendMessage(Text.of("Welcome to the server! To play, you need to set a password."), false);
			player.sendMessage(Text.of("Use the command /setPassword <password> to set your password."), false);
			player.sendMessage(Text.of("After setting your password, use /login <password> to log in and start playing."), false);
		} else {
			player.sendMessage(Text.of("Please enter your password using /login <your password> to start playing."), false);
		}
	}

	private void teleportPlayerToJoinPosition(ServerPlayerEntity player, Vec3d joinPos) {
		player.teleport(player.getServerWorld(), joinPos.x, joinPos.y, joinPos.z, new HashSet<PositionFlag>(), player.getYaw(), player.getPitch(), false);
		player.changeGameMode(GameMode.SPECTATOR);
	}
}