package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AhScreenUtil {

    public enum AhScreen {
        NONE, SIGN, AH_MAIN, BIN_VIEW, CONFIRM_PURCHASE, MANAGE_AUCTIONS, MANAGE_ORDERS, CREATE_BIN, CONFIRM_LISTING, YOUR_BIDS, SELLER_AUCTIONS, OTHER
    }

    public static AhScreen currentScreen() {
        AhScreen screen = ScreenUtil.sync(AhScreenUtil::readScreen);
        return screen == null ? AhScreen.NONE : screen;
    }

    private static AhScreen readScreen() {
        Minecraft client = Minecraft.getInstance();
        Screen screen = client.gui.screen();
        if (screen == null) {
            return AhScreen.NONE;
        }
        if (screen instanceof AbstractSignEditScreen) {
            return AhScreen.SIGN;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return AhScreen.NONE;
        }
        String title = ScreenUtil.cleanName(container.getTitle().getString());
        String lower = title.toLowerCase(Locale.ROOT);
        int size = containerSize(container);
        if (lower.contains("confirm purchase")) {
            return AhScreen.CONFIRM_PURCHASE;
        }
        if (lower.contains("confirm bin auction") || lower.contains("confirm auction")) {
            return AhScreen.CONFIRM_LISTING;
        }
        if (lower.contains("create bin auction") || lower.contains("create auction")) {
            return AhScreen.CREATE_BIN;
        }
        if (lower.contains("manage order") || lower.contains("orders")) {
            return AhScreen.MANAGE_ORDERS;
        }
        if (lower.contains("manage") && lower.contains("auction")) {
            return AhScreen.MANAGE_AUCTIONS;
        }
        if (lower.contains("your bids") || lower.contains("ended auctions") || lower.contains("bids")) {
            return AhScreen.YOUR_BIDS;
        }
        if (lower.contains("'s auctions")) {
            return AhScreen.SELLER_AUCTIONS;
        }
        if (lower.contains("auction house") || lower.contains("auctions browser")) {
            return AhScreen.AH_MAIN;
        }
        if (lower.contains("bin auction view") || (lower.contains("auction") && size == 54)) {
            return AhScreen.BIN_VIEW;
        }
        return isAhTitle(title) ? AhScreen.OTHER : AhScreen.NONE;
    }

    public static String currentScreenTitle() {
        return ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            return screen == null ? "" : screen.getTitle().getString();
        });
    }

    public static int findSlot(AbstractContainerScreen<?> container, String text) {
        int size = containerSize(container);
        for (int i = 0; i < size; i++) {
            ItemStack stack = itemAt(container, i);
            if (stack.isEmpty()) {
                continue;
            }
            if (ScreenUtil.fuzzyTextMatch(stack.getHoverName().getString(), text)) {
                return i;
            }
        }
        return -1;
    }

    public static int findSlotByName(String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (ScreenUtil.fuzzyTextMatch(stack.getHoverName().getString(), name)) {
                    return i;
                }
            }
            return -1;
        });
        return found == null ? -1 : found;
    }

    public static boolean isAhTitle(String title) {
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return lower.contains("auction") || lower.contains("confirm purchase")
                || lower.contains("manage order");
    }

    public static Integer findSoldAuctionSlot() {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                boolean sold = false;
                boolean own = false;
                for (String line : loreLines(stack)) {
                    String lower = ScreenUtil.cleanName(line).toLowerCase(Locale.ROOT);
                    if (lower.contains("status: sold")) {
                        sold = true;
                    }
                    if (lower.contains("this is your own auction")) {
                        own = true;
                    }
                }
                if (sold && own) {
                    return i;
                }
            }
            return -1;
        });
        return found == null ? -1 : found;
    }

    public static boolean isRelevant(AbstractContainerScreen<?> container) {
        return isAhTitle(container.getTitle().getString());
    }

    public static void dumpScreen() {
        ScreenUtil.onClient(() -> {
            Minecraft client = Minecraft.getInstance();
            Screen screen = client.gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                String name = screen == null ? "keins" : screen.getTitle().getString();
                System.out.println("[AH-Flipper] Recon: kein Container offen (" + name + ")");
                return;
            }
            logContainer(container);
        });
    }

    public static void logContainer(AbstractContainerScreen<?> container) {
        String title = container.getTitle().getString();
        int size = containerSize(container);
        String item = itemName(container);
        String seller = seller(container);
        long price = binPrice(container);
        System.out.println("[AH-Flipper] === Screen: '" + title + "' | Slots: " + size
                + " | Klasse: " + container.getClass().getSimpleName()
                + (item != null ? " | Item: " + item : "")
                + (seller != null ? " | Seller: " + seller : "")
                + (price > 0 ? " | Preis: " + price : "")
                + (isOwnAuction(container) ? " | EIGENE AUKTION" : "")
                + " ===");
        for (int i = 0; i < size; i++) {
            ItemStack stack = itemAt(container, i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            System.out.println("[AH-Flipper] Slot " + i + ": " + stack.getHoverName().getString());
            for (String line : loreLines(stack)) {
                System.out.println("[AH-Flipper]     | " + line);
            }
        }
    }

    public static String itemName(AbstractContainerScreen<?> container) {
        ItemStack stack = itemAt(container, 13);
        return stack.isEmpty() ? null : stack.getHoverName().getString();
    }

    public static String seller(AbstractContainerScreen<?> container) {
        String found = null;
        int size = containerSize(container);
        for (int i = 0; i < size && found == null; i++) {
            for (String line : loreLines(itemAt(container, i))) {
                String plain = ScreenUtil.cleanName(line).trim();
                if (plain.startsWith("Seller:")) {
                    found = plain.substring("Seller:".length()).trim();
                    break;
                }
            }
        }
        if (found == null) {
            return null;
        }
        String cleaned = stripRank(found);
        return cleaned.isEmpty() ? null : cleaned;
    }

    public static int findPlayerAuctionSlot(String player) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            String wanted = stripRank(ScreenUtil.cleanName(player == null ? "" : player));
            int size = containerSize(container);
            int fallback = -1;
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                boolean hasPrice = false;
                boolean sellerMatch = false;
                boolean own = false;
                for (String line : loreLines(stack)) {
                    String plain = ScreenUtil.cleanName(line).trim();
                    if (plain.startsWith("Seller:")) {
                        String seller = stripRank(plain.substring("Seller:".length()).trim());
                        if (!wanted.isBlank() && seller.equalsIgnoreCase(wanted)) {
                            sellerMatch = true;
                        }
                    }
                    if (plain.startsWith("Buy it now:") || plain.startsWith("Price:")
                            || plain.startsWith("Cost:")) {
                        hasPrice = true;
                    }
                    if (plain.contains("This is your own auction!")) {
                        own = true;
                    }
                }
                if (own || !hasPrice) {
                    continue;
                }
                if (sellerMatch) {
                    return i;
                }
                if (fallback < 0) {
                    fallback = i;
                }
            }
            return fallback;
        });
        return found == null ? -1 : found;
    }

    private static String stripRank(String name) {
        String cleaned = name == null ? "" : name.trim();
        while (cleaned.startsWith("[")) {
            int close = cleaned.indexOf(']');
            if (close < 0) {
                break;
            }
            cleaned = cleaned.substring(close + 1).trim();
        }
        return cleaned;
    }

    public static long binPrice(AbstractContainerScreen<?> container) {
        int size = containerSize(container);
        for (int i = 0; i < size; i++) {
            for (String line : loreLines(itemAt(container, i))) {
                String plain = line.trim();
                if (plain.startsWith("Buy it now:") || plain.startsWith("Price:") || plain.startsWith("Cost:")) {
                    long parsed = parseCoins(plain);
                    if (parsed > 0) {
                        return parsed;
                    }
                }
            }
        }
        return -1;
    }

    public static boolean isOwnAuction(AbstractContainerScreen<?> container) {
        int size = containerSize(container);
        for (int i = 0; i < size; i++) {
            for (String line : loreLines(itemAt(container, i))) {
                if (line.contains("This is your own auction!")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isOwnAuctionCurrent() {
        Boolean own = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            return isOwnAuction(container);
        });
        return own != null && own;
    }

    public static long priceAt(AbstractContainerScreen<?> container, int slot) {
        for (String line : loreLines(itemAt(container, slot))) {
            String plain = line.trim();
            if (plain.startsWith("Buy it now:") || plain.startsWith("Price:") || plain.startsWith("Cost:")) {
                long parsed = parseCoins(plain);
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return -1;
    }

    public static long currentPriceAt(int slot) {
        Long value = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1L;
            }
            return priceAt(container, slot);
        });
        return value == null ? -1 : value;
    }

    public static ItemStack currentItem(int slot) {
        ItemStack stack = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return ItemStack.EMPTY;
            }
            return itemAt(container, slot).copy();
        });
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public static boolean currentSlotHasText(int slot, String text) {
        Boolean found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            ItemStack stack = itemAt(container, slot);
            if (stack.isEmpty()) {
                return false;
            }
            if (ScreenUtil.fuzzyTextMatch(stack.getHoverName().getString(), text)) {
                return true;
            }
            for (String line : loreLines(stack)) {
                if (ScreenUtil.fuzzyTextMatch(line, text)) {
                    return true;
                }
            }
            return false;
        });
        return found != null && found;
    }

    public static Integer findSlotWithText(String text, int minSlot, int maxSlot) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int size = containerSize(container);
            int start = Math.max(0, minSlot);
            int end = Math.min(size - 1, maxSlot);
            for (int i = start; i <= end; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (ScreenUtil.fuzzyTextMatch(stack.getHoverName().getString(), text)) {
                    return i;
                }
                for (String line : loreLines(stack)) {
                    if (ScreenUtil.fuzzyTextMatch(line, text)) {
                        return i;
                    }
                }
            }
            return -1;
        });
        return found;
    }

    public static Integer findSlotWithLore(String text) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                for (String line : loreLines(stack)) {
                    if (ScreenUtil.fuzzyTextMatch(line, text)) {
                        return i;
                    }
                }
            }
            return -1;
        });
        return found;
    }

    public static int findInventorySlot(String tag, String displayName) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen instanceof AbstractContainerScreen<?> container) {
                int chestSize = containerSize(container);
                var items = container.getMenu().getItems();
                int fallback = -1;
                for (int i = chestSize; i < items.size(); i++) {
                    ItemStack stack = items.get(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    AhItem item = AhItem.fromStack(stack);
                    if (tag != null && !tag.isBlank() && tag.equals(item.tag)) {
                        return i;
                    }
                    if (fallback < 0 && matchesInventoryName(displayName, item)) {
                        fallback = i;
                    }
                }
                if (fallback >= 0) {
                    return fallback;
                }
            }
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return -1;
            }
            var inventory = client.player.getInventory();
            int fallback = -1;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                AhItem item = AhItem.fromStack(stack);
                if (tag != null && !tag.isBlank() && tag.equals(item.tag)) {
                    return i;
                }
                if (fallback < 0 && matchesInventoryName(displayName, item)) {
                    fallback = i;
                }
            }
            return fallback;
        });
        return found == null ? -1 : found;
    }

    public static AhItem findInventoryItem(String tag, String displayName) {
        AhItem found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen instanceof AbstractContainerScreen<?> container) {
                int chestSize = containerSize(container);
                var items = container.getMenu().getItems();
                AhItem fallback = null;
                for (int i = chestSize; i < items.size(); i++) {
                    ItemStack stack = items.get(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    AhItem item = AhItem.fromStack(stack);
                    if (tag != null && !tag.isBlank() && tag.equals(item.tag)) {
                        return item;
                    }
                    if (fallback == null && matchesInventoryName(displayName, item)) {
                        fallback = item;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            }
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return null;
            }
            AhItem fallback = null;
            var inventory = client.player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                AhItem item = AhItem.fromStack(stack);
                if (tag != null && !tag.isBlank() && tag.equals(item.tag)) {
                    return item;
                }
                if (fallback == null && matchesInventoryName(displayName, item)) {
                    fallback = item;
                }
            }
            return fallback;
        });
        return found;
    }

    private static boolean matchesInventoryName(String displayName, AhItem item) {
        return displayName != null && !displayName.isBlank()
                && ScreenUtil.cleanName(displayName)
                        .equalsIgnoreCase(ScreenUtil.cleanName(item.displayName));
    }

    public static long parseCoins(String text) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c == ',' || c == '.' || c == ' ') {
                continue;
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static List<String> loreLines(ItemStack stack) {
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
}
