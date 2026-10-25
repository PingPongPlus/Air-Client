package com.coflnet.mixin;

import com.coflnet.config.AngryCoopProtectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
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

import java.util.Locale;
import java.util.Optional;

@Mixin(AbstractContainerScreen.class)
public abstract class AuctionProtectionMixin {

    @Shadow @Nullable protected Slot hoveredSlot;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onAngryCoopMouseClicked(MouseButtonEvent click, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!AngryCoopProtectionManager.isEnabled()) {
                return;
            }

            int button = click.button();
            if (button != 0 && button != 1) {
                return;
            }

            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return;
            }

            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
            String screenTitle = stripFormatting(screen.getTitle().getString());
            ScreenMode mode = determineScreenMode(screenTitle);
            if (mode == null) {
                return;
            }

            Slot clickedSlot = hoveredSlot;
            if (clickedSlot == null || !clickedSlot.hasItem()) {
                return;
            }

            ItemStack clickedStack = clickedSlot.getItem();
            if (clickedStack.isEmpty()) {
                return;
            }

            boolean ctrlPressed = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

            String playerNameLower = client.player.getGameProfile().name().toLowerCase(Locale.ROOT);
            String clickedName = stripFormatting(clickedStack.getHoverName().getString()).trim();

            if (isClaimAll(clickedName)) {
                if (!ctrlPressed && hasForeignEntry(screen, playerNameLower, client.player.getInventory(), mode)) {
                    client.player.sendSystemMessage(Component.literal(getClaimAllMessage(mode)));
                    cir.setReturnValue(true);
                }
                return;
            }

            Optional<String> foreignActor = getForeignActor(clickedStack, playerNameLower, mode);
            if (foreignActor.isEmpty()) {
                return;
            }

            if (ctrlPressed) {
                return;
            }

            client.player.sendSystemMessage(Component.literal(getBlockedClickMessage(mode, foreignActor.get())));
            cir.setReturnValue(true);
        } catch (Exception e) {
            System.out.println("[AuctionProtectionMixin] mouseClicked failed: " + e.getMessage());
        }
    }

    private boolean isClaimAll(String name) {
        return name.trim().equalsIgnoreCase("Claim All");
    }

    private ScreenMode determineScreenMode(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("manage auctions")) {
            return ScreenMode.SELLER;
        }
        if (lower.contains("your bids")) {
            return ScreenMode.BIDDER;
        }
        return null;
    }

    private Optional<String> getForeignActor(ItemStack stack, String playerNameLower, ScreenMode mode) {
        var loreComponent = stack.get(DataComponents.LORE);
        if (loreComponent == null) {
            return Optional.empty();
        }

        for (Component line : loreComponent.lines()) {
            String raw = stripFormatting(line.getString());
            String lower = raw.toLowerCase(Locale.ROOT);

            if (mode == ScreenMode.SELLER) {
                if (lower.contains("this is your own auction")) {
                    return Optional.empty();
                }

                if (lower.startsWith("seller:")) {
                    if (!lower.contains(playerNameLower)) {
                        String seller = raw.substring("Seller:".length()).trim();
                        return Optional.of(seller.isEmpty() ? "Unknown" : seller);
                    } else {
                        return Optional.empty();
                    }
                }
            } else {
                if (lower.startsWith("bidder:")) {
                    if (lower.startsWith("bidder: you")) {
                        return Optional.empty();
                    }
                    if (!lower.contains(playerNameLower)) {
                        String bidder = raw.substring("Bidder:".length()).trim();
                        return Optional.of(bidder.isEmpty() ? "Unknown" : bidder);
                    } else {
                        return Optional.empty();
                    }
                }

                if (lower.contains("you are the highest bidder")) {
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    private boolean hasForeignEntry(AbstractContainerScreen<?> screen, String playerNameLower, Inventory playerInventory, ScreenMode mode) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == playerInventory) {
                continue;
            }

            if (!slot.hasItem()) {
                continue;
            }

            if (getForeignActor(slot.getItem(), playerNameLower, mode).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private String getClaimAllMessage(ScreenMode mode) {
        String reason = mode == ScreenMode.SELLER
                ? "auctions listed by co-op members"
                : "bids placed by co-op members";
        return "§c[SkyCofl Angry Coop] §fBlocked Claim All because " + reason + " were detected. Hold §bCtrl§f to override.";
    }

    private String getBlockedClickMessage(ScreenMode mode, String foreignName) {
        String action = mode == ScreenMode.SELLER ? "listed by" : "bid on by";
        return "§c[SkyCofl Angry Coop] §fBlocked claiming auction " + action + " §e" + foreignName + "§f. Hold §bCtrl§f to override.";
    }

    private String stripFormatting(String text) {
        if (text == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        boolean skip = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                skip = true;
                continue;
            }
            if (skip) {
                skip = false;
                continue;
            }
            builder.append(c);
        }
        return builder.toString();
    }
}
