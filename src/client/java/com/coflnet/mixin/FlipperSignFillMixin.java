package com.coflnet.mixin;

import com.coflnet.flipper.trading.SignInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class FlipperSignFillMixin {
    @Inject(method = "openTextEdit", at = @At("HEAD"))
    private void flipperFillSign(SignBlockEntity sign, boolean front, CallbackInfo ci) {
        String text = SignInput.takeIfFresh(10_000L);
        if (text == null) {
            return;
        }
        try {
            Component[] lines = sign.getFrontText().getMessages(Minecraft.getInstance().isTextFilteringEnabled());
            lines[0] = Component.literal(text);
            lines[1] = Component.literal("");
            lines[2] = Component.literal("");
            lines[3] = Component.literal("");
            sign.updateText(signText -> new SignText(
                    lines, lines,
                    signText.getColor(),
                    signText.hasGlowingText()
            ), front);
            Minecraft client = Minecraft.getInstance();
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(200);
                    client.execute(() -> {
                        if (client.gui.screen() != null) {
                            client.gui.screen().onClose();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception ignored) {
        }
    }
}
