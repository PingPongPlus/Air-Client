package com.coflnet.mixin;

import com.coflnet.config.SellProtectionManager;
import com.coflnet.utils.SellAmountParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class SellProtectionMixin {

    @Shadow @Nullable protected Slot hoveredSlot;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onSellProtectionMouseClicked(MouseButtonEvent click, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        try {
            // Check if sell protection is enabled
            if (!SellProtectionManager.isEnabled()) {
                return;
            }

            // Only check left and right clicks
            int button = click.button();
            if (button != 0 && button != 1) {
                return;
            }

            // Check if we're in a chest with "➜" in the title
            String screenTitle = ((AbstractContainerScreen<?>) (Object) this).getTitle().getString();
            if (!screenTitle.contains("➜")) {
                return;
            }

            // Get the slot being clicked
            Slot clickedSlot = hoveredSlot;
            if (clickedSlot == null || !clickedSlot.hasItem()) {
                return;
            }

            ItemStack clickedItem = clickedSlot.getItem();
            String itemName = "";
            
            // Get item name from custom name or item display name
            if (clickedItem.getCustomName() != null) {
                itemName = clickedItem.getCustomName().getString();
            } else {
                itemName = clickedItem.getItem().getDefaultInstance().getHoverName().getString();
            }

            // Check if ctrl is pressed
            boolean ctrlPressed = GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                                  GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

            // Check for sell protection patterns and extract amounts
            boolean shouldBlock = false;
            String protection = "";
            long sellAmount = SellAmountParser.getDefaultProtectionAmount(); // Default to trigger protection

            if (itemName.contains("Sell Instantly")) {
                sellAmount = SellAmountParser.extractSellInstantlyAmount(clickedItem);
                if (button == 0 && !ctrlPressed && sellAmount > SellProtectionManager.getMaxAmount()) { // Left click without ctrl
                    shouldBlock = true;
                    protection = "Sell Instantly";
                }
            } else if (itemName.contains("Sell Sacks Now") || itemName.contains("Sell Inventory Now")) {
                sellAmount = SellAmountParser.extractInventorySackAmount(clickedItem);
                // Block both left and right clicks for these items if amount exceeds threshold
                if (!ctrlPressed && sellAmount > SellProtectionManager.getMaxAmount()) {
                    shouldBlock = true;
                    protection = itemName.contains("Sell Sacks Now") ? "Sell Sacks Now" : "Sell Inventory Now";
                }
            }

            if (shouldBlock) {
                // Send warning message to chat
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    String formattedAmount = formatCoins(sellAmount);
                    String message = "§c[SkyCofl Sell Protection] §fBlocked click on §e" + protection + "§f (§6" + formattedAmount + " coins§f)! Hold §bCtrl§f to override.";
                    client.player.sendSystemMessage(Component.literal(message));
                }

                // Cancel the click
                cir.setReturnValue(true);
                return;
            }
            // No special handling needed for ctrl+click - just let it proceed normally

        } catch (Exception e) {
            System.out.println("[SellProtectionMixin] mouseClicked failed: " + e.getMessage());
        }
    }

    private String formatCoins(long coins) {
        if (coins >= 1000000000) {
            return String.format(java.util.Locale.US, "%.1fB", coins / 1000000000.0);
        } else if (coins >= 1000000) {
            return String.format(java.util.Locale.US, "%.1fM", coins / 1000000.0);
        } else if (coins >= 1000) {
            return String.format(java.util.Locale.US, "%.1fK", coins / 1000.0);
        } else {
            return String.valueOf(coins);
        }
    }
}
