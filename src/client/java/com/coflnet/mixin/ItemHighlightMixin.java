package com.coflnet.mixin;

import CoflCore.handlers.DescriptionHandler;
import com.coflnet.CoflModClient;
import com.coflnet.gui.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(AbstractContainerScreen.class) // Target AbstractContainerScreen, which is the base for most container UIs
public abstract class ItemHighlightMixin {

    // You might need to shadow fields from AbstractContainerScreen to get context like x/y/width/height
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;
    @Shadow protected int imageHeight;

    @Shadow @Nullable protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        try {
            // The keyPressed method now receives a KeyInput record object.
            if (CoflModClient.uploadItemKeyBinding.matches(input)) {
                if (hoveredSlot != null && hoveredSlot.hasItem()) {
                    CoflModClient.uploadItem(hoveredSlot.getItem());
                }
            }
        } catch (Exception e) {
            System.out.println("[ItemHighlightMixin] keyPressed failed: " + e.getMessage());
        }
    }

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void yourmodid_onDrawBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            if (hoveredSlot != null && hoveredSlot.hasItem()) {
                CoflModClient.maybeUploadHoveredMapContent(hoveredSlot.getItem());
            }

            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>)(Object)this;
            for (Slot slot : screen.getMenu().slots) {
                if (!slot.hasItem())
                    continue;
                DescriptionHandler.DescModification[] tooltips = DescriptionHandler.getTooltipData(CoflModClient.getIdFromStack(slot.getItem()));
                if(tooltips == null)
                    continue;
                for (DescriptionHandler.DescModification tooltip : tooltips) {
                    if (tooltip.type.equals("HIGHLIGHT")) {
                        int hexColor = Integer.parseInt(tooltip.value, 16) | 0xFF000000;
                        if (tooltip.line < 0) {
                            RenderUtils.drawRectOutline(context, slot.x, slot.y, 16, 16, 2, 0x00000000, hexColor);
                        } else {
                            RenderUtils.drawRect(context, slot.x, slot.y, 16, 16, hexColor);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ItemHighlightMixin] extractSlots failed: " + e.getMessage());
        }
    }
}