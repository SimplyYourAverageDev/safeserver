package youraveragedev.safeserver.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import youraveragedev.safeserver.Safeserver;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public class PreJoinOpSafetyMixin {
    @Shadow
    @Final
    private GameProfile gameProfile;

    @Inject(method = "returnToWorld", at = @At("HEAD"))
    private void safeserver$prepareOpSafetyBeforeSpawn(CallbackInfo ci) {
        Safeserver instance = Safeserver.getInstance();
        if (instance != null) {
            MinecraftServer server = ((ServerCommonPacketListenerAccessor) this).safeserver$getServer();
            instance.prepareOpSafety(this.gameProfile, server);
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void safeserver$restoreOpSafetyOnConfigurationDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        Safeserver instance = Safeserver.getInstance();
        if (instance != null) {
            MinecraftServer server = ((ServerCommonPacketListenerAccessor) this).safeserver$getServer();
            instance.restorePreJoinOpSafety(this.gameProfile, server);
        }
    }
}
