package com.coflnet.mixin;

import com.coflnet.session.BackgroundAutomation;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class BackgroundFramerateMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void cofl$keepAutomationResponsive(CallbackInfoReturnable<Integer> cir) {
        // Minimized and long-idle windows normally drop to 10 FPS. The bots
        // dispatch GUI work on the client thread and need timely frame updates.
        if (cir.getReturnValueI() < BackgroundAutomation.MIN_FPS
                && BackgroundAutomation.isActive(Minecraft.getInstance())) {
            cir.setReturnValue(BackgroundAutomation.MIN_FPS);
        }
    }
}
