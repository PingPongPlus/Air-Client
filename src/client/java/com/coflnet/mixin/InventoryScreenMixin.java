package com.coflnet.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends HandledScreenMixin{

    // Need protected constructor for the Screen parent - used for mixin compilation only
    protected InventoryScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At("HEAD"), method = "extractRenderState")
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci){
        try {
            if(sideTextWidget != null)
                sideTextWidget.extractRenderState(context, mouseX, mouseY, deltaTicks);
        } catch (Exception e) {
            System.out.println("[InventoryScreenMixin] render HEAD failed: " + e.getMessage());
        }
    }


    @Inject(at = @At("TAIL"), method = "extractRenderState")
    public void renderMain(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci){
        try {
            super.renderMain(context, mouseX, mouseY, deltaTicks, ci);
        } catch (Exception e) {
            System.out.println("[InventoryScreenMixin] render TAIL failed: " + e.getMessage());
        }
    }
}
