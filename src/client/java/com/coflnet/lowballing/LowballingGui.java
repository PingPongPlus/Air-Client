package com.coflnet.lowballing;

import com.coflnet.ahflipper.AhItem;
import com.coflnet.flipper.trading.ScreenUtil;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LowballingGui {
    private static final List<String> UI_WORDS = List.of(
            "kick", "trade", "coins", "coin", "close", "back", "accept", "decline",
            "confirm", "cancel", "deny", "visit", "island", "menu", "next", "previous",
            "your stuff", "your items", "their stuff", "their items", "gift"
    );

    private LowballingGui() {
    }

    public static boolean isContainerOpen() {
        return Boolean.TRUE.equals(ScreenUtil.sync(
                () -> Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>));
    }

    public static boolean isSignOpen() {
        return Boolean.TRUE.equals(ScreenUtil.sync(
                () -> Minecraft.getInstance().gui.screen() instanceof AbstractSignEditScreen));
    }

    public static boolean isAnyScreenOpen() {
        return Boolean.TRUE.equals(ScreenUtil.sync(
                () -> Minecraft.getInstance().gui.screen() != null));
    }

    public static String currentTitle() {
        String title = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            return screen == null ? "" : ScreenUtil.cleanName(screen.getTitle().getString());
        });
        return title == null ? "" : title;
    }

    public static int findButtonSlot(String... needles) {
        if (needles == null || needles.length == 0) {
            return -1;
        }
        Integer found = ScreenUtil.sync(() -> {
            AbstractContainerScreen<?> container = currentContainer();
            if (container == null) {
                return -1;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                String name = ScreenUtil.cleanName(stack.getHoverName().getString())
                        .toLowerCase(Locale.ROOT);
                for (String needle : needles) {
                    if (needle != null && !needle.isBlank()
                            && name.contains(needle.toLowerCase(Locale.ROOT))) {
                        return i;
                    }
                }
            }
            return -1;
        });
        return found == null ? -1 : found;
    }

    public static int findHubSlot(int number) {
        if (LowballingMod.CONFIG.hubSlotOverride >= 0) {
            return LowballingMod.CONFIG.hubSlotOverride;
        }
        String numberText = String.valueOf(number);
        return findButtonSlot("skyblock hub #" + numberText, "hub #" + numberText,
                "skyblock hub " + numberText, "hub " + numberText);
    }

    public static int findKickSlot() {
        if (LowballingMod.CONFIG.kickSlotOverride >= 0) {
            return LowballingMod.CONFIG.kickSlotOverride;
        }
        return findButtonSlot("kick player", "kick");
    }

    public static int findCoinsSlot() {
        if (LowballingMod.CONFIG.coinsSlotOverride >= 0) {
            return LowballingMod.CONFIG.coinsSlotOverride;
        }
        return findButtonSlot("coins trans", "coin trans", "coins transfer",
                "coin transfer");
    }

    public static int findTradeSlot() {
        if (LowballingMod.CONFIG.tradeSlotOverride >= 0) {
            return LowballingMod.CONFIG.tradeSlotOverride;
        }
        return findButtonSlot("trade");
    }

    /** Hypixel trade window: title "You                  <name>". */
    public static boolean isOpenTradeWith(String name) {
        if (!isContainerOpen()) {
            return false;
        }
        String title = currentTitle().toLowerCase(Locale.ROOT).trim();
        if (!title.startsWith("you ")) {
            return false;
        }
        return name == null || name.isBlank()
                || title.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean isTradeWindow(String previousTitle) {
        String title = currentTitle();
        if (title.isBlank()) {
            return false;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("player menu") || lower.contains("trade request")) {
            return false;
        }
        if (lower.contains("trade") || lower.contains("deal")) {
            return true;
        }
        if (findButtonSlot("accept", "confirm", "decline", "cancel") >= 0) {
            return true;
        }
        if (findKickSlot() < 0 && findCoinsSlot() < 0 && findTradeSlot() < 0) {
            return true;
        }
        return previousTitle != null && !previousTitle.isBlank()
                && !title.equalsIgnoreCase(previousTitle);
    }

    public static List<Integer> findTradeItemSlots() {
        if (LowballingMod.CONFIG.tradeItemSlotOverride >= 0) {
            return List.of(LowballingMod.CONFIG.tradeItemSlotOverride);
        }
        List<Integer> found = ScreenUtil.sync(() -> {
            List<Integer> slots = new ArrayList<>();
            AbstractContainerScreen<?> container = currentContainer();
            if (container == null) {
                return slots;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (!isTradeItemCandidate(stack)) {
                    continue;
                }
                AhItem item = AhItem.fromStack(stack);
                if (item.tag.isBlank()) {
                    continue;
                }
                slots.add(i);
            }
            return slots;
        });
        return found == null ? Collections.emptyList() : found;
    }

    private static boolean isTradeItemCandidate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String itemId = stack.getItem().toString().toLowerCase(Locale.ROOT);
        if (itemId.contains("stained_glass_pane") || itemId.contains("glass_pane")
                || itemId.contains("terracotta") || itemId.contains("barrier")
                || itemId.contains("arrow")
                || stack.getItem() instanceof SignItem) {
            return false;
        }
        AhItem parsed = AhItem.fromStack(stack);
        if (isFillerTag(parsed.tag)) {
            return false;
        }
        String name = ScreenUtil.cleanName(stack.getHoverName().getString())
                .toLowerCase(Locale.ROOT);
        if (name.isBlank()) {
            return false;
        }
        for (String word : UI_WORDS) {
            if (name.contains(word)) {
                return false;
            }
        }
        for (String line : lore(stack)) {
            String lower = ScreenUtil.cleanName(line).toLowerCase(Locale.ROOT);
            if (lower.contains("click to kick") || lower.contains("click to trade")
                    || lower.contains("click to add coins")
                    || lower.contains("click to transfer coins")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFillerTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String upper = tag.toUpperCase(Locale.ROOT);
        return upper.contains("STAINED_GLASS_PANE")
                || upper.endsWith("GLASS_PANE")
                || upper.contains("TERRACOTTA")
                || upper.equals("BARRIER")
                || upper.equals("ARROW");
    }

    public static List<String> tooltipLinesAt(int slot) {
        List<String> lines = ScreenUtil.sync(() -> {
            AbstractContainerScreen<?> container = currentContainer();
            if (container == null || slot < 0 || slot >= containerSize(container)) {
                return Collections.<String>emptyList();
            }
            ItemStack stack = itemAt(container, slot);
            if (stack == null || stack.isEmpty()) {
                return Collections.<String>emptyList();
            }
            List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.EMPTY,
                    Minecraft.getInstance().player, TooltipFlag.NORMAL);
            ItemTooltipCallback.EVENT.invoker().getTooltip(stack,
                    Item.TooltipContext.EMPTY, TooltipFlag.NORMAL, tooltip);
            List<String> result = new ArrayList<>();
            for (Component line : tooltip) {
                result.add(line.getString());
            }
            return result;
        });
        return lines == null ? Collections.emptyList() : lines;
    }

    public static List<String> loreAt(int slot) {
        List<String> lines = ScreenUtil.sync(() -> {
            AbstractContainerScreen<?> container = currentContainer();
            if (container == null || slot < 0 || slot >= containerSize(container)) {
                return Collections.<String>emptyList();
            }
            return lore(itemAt(container, slot));
        });
        return lines == null ? Collections.emptyList() : lines;
    }

    public static AhItem itemAt(int slot) {
        AhItem item = ScreenUtil.sync(() -> {
            AbstractContainerScreen<?> container = currentContainer();
            if (container == null || slot < 0 || slot >= containerSize(container)) {
                return new AhItem();
            }
            return AhItem.fromStack(itemAt(container, slot).copy());
        });
        return item == null ? new AhItem() : item;
    }

    public static boolean hasSlot(int slot) {
        Boolean found = ScreenUtil.sync(() -> {
            AbstractContainerScreen<?> container = currentContainer();
            return container != null && slot >= 0 && slot < containerSize(container)
                    && !itemAt(container, slot).isEmpty();
        });
        return found != null && found;
    }

    public static void clickSlot(int slot, int button) {
        if (slot < 0) {
            return;
        }
        ScreenUtil.clickSlot(slot, button);
    }

    public static void close() {
        ScreenUtil.closeCurrentScreen();
    }

    public static void dump(String prefix) {
        ScreenUtil.onClient(() -> {
            AbstractContainerScreen<?> container = currentContainer();
            if (container == null) {
                System.out.println(prefix + " kein Container offen");
                return;
            }
            int size = containerSize(container);
            System.out.println(prefix + " Screen '" + ScreenUtil.cleanName(
                    container.getTitle().getString()) + "' Slots=" + size);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                System.out.println(prefix + " Slot " + i + ": "
                        + ScreenUtil.cleanName(stack.getHoverName().getString()));
                for (String line : lore(stack)) {
                    System.out.println(prefix + "   | " + ScreenUtil.cleanName(line));
                }
            }
        });
    }

    private static AbstractContainerScreen<?> currentContainer() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen instanceof AbstractContainerScreen<?> container ? container : null;
    }

    private static int containerSize(AbstractContainerScreen<?> container) {
        if (container.getMenu() instanceof ChestMenu chest) {
            return chest.getContainer().getContainerSize();
        }
        return container.getMenu().getItems().size();
    }

    private static ItemStack itemAt(AbstractContainerScreen<?> container, int index) {
        if (container.getMenu() instanceof ChestMenu chest) {
            return chest.getContainer().getItem(index);
        }
        if (index < container.getMenu().getItems().size()) {
            return container.getMenu().getItems().get(index);
        }
        return ItemStack.EMPTY;
    }

    private static List<String> lore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Collections.emptyList();
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        for (Component line : lore.lines()) {
            lines.add(line.getString());
        }
        return lines;
    }
}
