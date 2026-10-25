package com.coflnet.mixin;

import com.coflnet.gui.BinGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(AbstractScrollArea.class)
public class ScrollableWidgetMixin {
    @ModifyArg(method = "extractScrollbar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"), index = 4)
    private int drawBox(int x){
        try {
            return Minecraft.getInstance().gui.screen() instanceof BinGUI ? 2 : x;
        } catch (Exception e) {
            System.out.println("[ScrollableWidgetMixin] drawBox failed: " + e.getMessage());
            return x;
        }
    }
}
