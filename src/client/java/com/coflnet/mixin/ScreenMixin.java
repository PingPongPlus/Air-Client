package com.coflnet.mixin;

import com.coflnet.CoflModClient;
import com.coflnet.ahflipper.AhFlipperMod;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void coflToggleKeys(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        try {
            Screen screen = (Screen) (Object) this;
            if (screen instanceof ChatScreen
                    || screen instanceof AbstractContainerScreen<?>
                    || screen.getFocused() instanceof EditBox) {
                return;
            }
            if (AhFlipperMod.handleToggleKeys(input)) {
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            System.out.println("[ScreenMixin] keyPressed failed: " + e.getMessage());
        }
    }
}
