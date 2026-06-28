package youraveragedev.safeserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import youraveragedev.safeserver.Safeserver;
import youraveragedev.safeserver.SafeserverConstants;

import java.util.UUID;

public class AuthCommands {

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Safeserver modInstance) {
        dispatcher.register(Commands.literal("setpassword")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("password", StringArgumentType.string())
                        .then(Commands.argument("confirmPassword", StringArgumentType.string())
                                .executes(context -> runSetPasswordCommand(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "password"),
                                        StringArgumentType.getString(context, "confirmPassword"),
                                        modInstance)))));

        dispatcher.register(Commands.literal("login")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(context -> runLoginCommand(
                                context.getSource(),
                                StringArgumentType.getString(context, "password"),
                                modInstance))));

        registerNewCommands(dispatcher, modInstance);
    }

    private static int runSetPasswordCommand(CommandSourceStack source, String password, String confirmPassword, Safeserver modInstance) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(SafeserverConstants.PLAYER_ONLY_COMMAND_ERROR));
            return 0;
        }

        UUID playerUuid = player.getUUID();
        String playerName = player.getName().getString();

        if (!password.equals(confirmPassword)) {
            source.sendFailure(Component.literal(SafeserverConstants.PASSWORD_MISMATCH_ERROR));
            return 0;
        }

        if (password.length() < SafeserverConstants.MIN_PASSWORD_LENGTH) {
            source.sendFailure(Component.literal(SafeserverConstants.PASSWORD_LENGTH_ERROR));
            return 0;
        }

        boolean isAuthenticating = modInstance.isPlayerAuthenticating(playerUuid);
        boolean hasPassword = modInstance.hasPassword(playerUuid);

        if (isAuthenticating && !hasPassword) {
            boolean success = modInstance.registerPlayer(playerUuid, password);
            if (success) {
                source.sendSuccess(() -> Component.literal(SafeserverConstants.PASSWORD_SET_SUCCESS), false);
                Safeserver.LOGGER.info("Player {} set their password and is now authenticated.", playerName);
                return 1;
            }

            source.sendFailure(Component.literal("Failed to set password. " + SafeserverConstants.CONTACT_ADMIN_ERROR));
            Safeserver.LOGGER.error("Failed to set password for player {}.", playerName);
            return 0;
        } else if (!isAuthenticating && hasPassword) {
            boolean success = modInstance.resetAndSetPassword(playerUuid, password);
            if (success) {
                source.sendSuccess(() -> Component.literal(SafeserverConstants.PASSWORD_RESET_SUCCESS), false);
                Safeserver.LOGGER.info("Player {} reset their password.", playerName);
                return 1;
            }

            source.sendFailure(Component.literal("Failed to reset password. " + SafeserverConstants.CONTACT_ADMIN_ERROR));
            Safeserver.LOGGER.error("Failed to reset password for player {}.", playerName);
            return 0;
        } else if (isAuthenticating) {
            source.sendFailure(Component.literal(SafeserverConstants.ALREADY_HAS_PASSWORD_ERROR));
            return 0;
        } else {
            source.sendFailure(Component.literal(SafeserverConstants.NO_PASSWORD_NEEDED_ERROR));
            return 0;
        }
    }

    private static int runLoginCommand(CommandSourceStack source, String password, Safeserver modInstance) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(SafeserverConstants.PLAYER_ONLY_COMMAND_ERROR));
            return 0;
        }

        UUID playerUuid = player.getUUID();
        String playerName = player.getName().getString();

        if (!modInstance.isPlayerAuthenticating(playerUuid)) {
            source.sendFailure(Component.literal(SafeserverConstants.ALREADY_AUTHENTICATED_ERROR));
            return 0;
        }

        if (!modInstance.hasPassword(playerUuid)) {
            source.sendFailure(Component.literal(SafeserverConstants.NO_PASSWORD_SET_ERROR));
            return 0;
        }

        boolean success = modInstance.authenticatePlayer(playerUuid, password);
        if (success) {
            source.sendSuccess(() -> Component.literal(SafeserverConstants.LOGIN_SUCCESS), false);
            Safeserver.LOGGER.info("Player {} successfully authenticated.", playerName);
            return 1;
        }

        String remoteAddress = String.valueOf(player.connection.getRemoteAddress());
        source.sendFailure(Component.literal(SafeserverConstants.INCORRECT_PASSWORD_ERROR));
        Safeserver.LOGGER.warn("Failed login attempt for player {} from {}.", playerName, remoteAddress);
        return 0;
    }

    private static void registerNewCommands(CommandDispatcher<CommandSourceStack> dispatcher, Safeserver modInstance) {
        dispatcher.register(Commands.literal("changepassword")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("oldPassword", StringArgumentType.string())
                        .then(Commands.argument("newPassword", StringArgumentType.string())
                                .then(Commands.argument("confirmNewPassword", StringArgumentType.string())
                                        .executes(context -> runChangePasswordCommand(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "oldPassword"),
                                                StringArgumentType.getString(context, "newPassword"),
                                                StringArgumentType.getString(context, "confirmNewPassword"),
                                                modInstance))))));

        dispatcher.register(Commands.literal("resetpassword")
                .requires(source -> source.getEntity() == null
                        || (source.getEntity() instanceof ServerPlayer player
                        && source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()))))
                .then(Commands.argument("targetPlayer", EntityArgument.player())
                        .executes(context -> runResetPasswordCommand(
                                context.getSource(),
                                EntityArgument.getPlayer(context, "targetPlayer"),
                                modInstance))));
    }

    private static int runChangePasswordCommand(CommandSourceStack source, String oldPassword, String newPassword, String confirmNewPassword, Safeserver modInstance) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(SafeserverConstants.PLAYER_ONLY_COMMAND_ERROR));
            return 0;
        }

        UUID playerUuid = player.getUUID();

        if (modInstance.isPlayerAuthenticating(playerUuid)) {
            source.sendFailure(Component.literal(SafeserverConstants.MUST_BE_LOGGED_IN_ERROR));
            return 0;
        }

        if (!newPassword.equals(confirmNewPassword)) {
            source.sendFailure(Component.literal(SafeserverConstants.PASSWORD_MISMATCH_ERROR));
            return 0;
        }

        if (newPassword.length() < SafeserverConstants.MIN_PASSWORD_LENGTH) {
            source.sendFailure(Component.literal(SafeserverConstants.PASSWORD_LENGTH_ERROR));
            return 0;
        }

        boolean success = modInstance.changePlayerPassword(playerUuid, oldPassword, newPassword);
        if (success) {
            source.sendSuccess(() -> Component.literal(SafeserverConstants.PASSWORD_CHANGE_SUCCESS), false);
            Safeserver.LOGGER.info("Player {} changed their password.", player.getName().getString());
            return 1;
        }

        source.sendFailure(Component.literal(SafeserverConstants.CHECK_OLD_PASSWORD_ERROR));
        Safeserver.LOGGER.warn("Failed password change attempt for player {}.", player.getName().getString());
        return 0;
    }

    private static int runResetPasswordCommand(CommandSourceStack source, ServerPlayer targetPlayer, Safeserver modInstance) {
        UUID targetUuid = targetPlayer.getUUID();
        String targetName = targetPlayer.getName().getString();
        String sourceName = source.getTextName();

        if (!modInstance.hasPassword(targetUuid)) {
            source.sendFailure(Component.literal("Player " + targetName + " does not have a password set by this mod."));
            return 0;
        }

        boolean success = modInstance.resetPlayerPassword(targetUuid);
        if (success) {
            source.sendSuccess(() -> Component.literal("Password for player " + targetName + " has been reset. They will need to set a new one."), false);
            Safeserver.LOGGER.info("Password for player {} ({}) was reset by {}.", targetName, targetUuid, sourceName);
            return 1;
        }

        source.sendFailure(Component.literal("Failed to reset password for player " + targetName + ". They might not have a password set."));
        Safeserver.LOGGER.error("Failed attempt by {} to reset password for player {} ({}).", sourceName, targetName, targetUuid);
        return 0;
    }
}
