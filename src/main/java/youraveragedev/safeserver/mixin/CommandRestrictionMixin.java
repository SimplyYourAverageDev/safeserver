package youraveragedev.safeserver.mixin;

import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import youraveragedev.safeserver.Safeserver;

@Mixin(ServerGamePacketListenerImpl.class)
public class CommandRestrictionMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void safeserver$blockUnsignedChatCommands(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (!Safeserver.getInstance().shouldAllowPlayerCommand(this.player, packet.command())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void safeserver$blockSignedChatCommands(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (!Safeserver.getInstance().shouldAllowPlayerCommand(this.player, packet.command())) {
            ci.cancel();
        }
    }
}
