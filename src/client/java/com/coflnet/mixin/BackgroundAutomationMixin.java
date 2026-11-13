package com.coflnet.mixin;

import com.coflnet.session.BackgroundAutomation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class BackgroundAutomationMixin {
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void cofl$keepAutomationRunning(boolean pauseOnly, CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (BackgroundAutomation.isActive(client)) {
            // Both Escape and focus loss use this path. A pause menu blocks the
            // bots' GUI/movement steps, even on servers that do not pause ticks.
            if (client.gui.screen() == null) {
                client.mouseHandler.releaseMouse();
            }
            ci.cancel();
        }
    }
}
