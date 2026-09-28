package dev.trainroute.bridge.mixin;

import dev.trainroute.bridge.DiscordBridgeClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    // TAIL runs only after Minecraft's main-thread dispatch check has completed.
    @Inject(method = "onWorldTimeUpdate", at = @At("TAIL"))
    private void bridge$time(WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
        DiscordBridgeClient.timeUpdate(packet.time());
    }

    @Inject(method = "onPlayerRespawn", at = @At("TAIL"))
    private void bridge$reset(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        DiscordBridgeClient.resetTps();
    }
}
