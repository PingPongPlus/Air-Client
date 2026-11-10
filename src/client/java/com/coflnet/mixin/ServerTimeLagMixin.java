package com.coflnet.mixin;

import com.coflnet.pathing.LagMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the arrival time of the server's world time packets to the lag monitor. */
@Mixin(ClientPacketListener.class)
public class ServerTimeLagMixin {

    @Inject(method = "handleSetTime", at = @At("HEAD"))
    private void airclient$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        // The handler runs twice: first on the network thread (then re-queued to
        // the client thread). Only the network arrival time says something about
        // the server; the client thread copy is delayed by our own frame time.
        if (Minecraft.getInstance().isSameThread()) {
            return;
        }
        try {
            LagMonitor.onTimePacket(packet.gameTime());
        } catch (RuntimeException ignored) {
        }
    }
}
