package com.coflnet.mixin;

import com.coflnet.CoflModClient;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(at = @At("HEAD"), method = "onClose")
    private void onClose(CallbackInfo ci){
        try {
            CoflModClient.posToUpload = null;
        } catch (Exception e) {
            System.out.println("[ScreenMixin] close failed: " + e.getMessage());
        }
    }
}
