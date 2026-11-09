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

    public static void loadCoflDescriptions() {
        ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen instanceof AbstractContainerScreen<?> container
                    && com.coflnet.CoflModClient.instance != null) {
                com.coflnet.CoflModClient.instance.loadDescriptionsForInv(container);
            }
            return null;
        });
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

    public static int findSlotByTag(String tag) {
        if (tag == null || tag.isBlank()) {
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
                AhItem item = AhItem.fromStack(stack);
                if (item.tag.equalsIgnoreCase(tag)) {
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

    /**
     * Contents of the item slot (13) of the "Create BIN Auction" menu. Hypixel
     * keeps an item in that slot even after the menu was closed, and refuses any
     * other item until it is taken out ("You already have an item in the auction
     * slot!"), so every listing has to look at it first.
     */
    public record CreateSlot(boolean empty, String tag, String name) {
    }

    /** Null while no create menu is open or the slot has not been loaded yet. */
    public static CreateSlot readCreateSlot() {
        return ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)
                    || readScreen() != AhScreen.CREATE_BIN) {
                return null;
            }
            ItemStack stack = itemAt(container, 13);
            if (stack.isEmpty()) {
                return null;
            }
            String hover = ScreenUtil.cleanName(stack.getHoverName().getString())
                    .toLowerCase(Locale.ROOT);
            if (hover.contains("click an item")) {
                return new CreateSlot(true, "", "");
            }
            String name = "";
            boolean pickup = hover.contains("auction for item");
            for (String line : loreLines(stack)) {
                String plain = ScreenUtil.cleanName(line).trim();
                if (plain.isEmpty()) {
                    continue;
                }
                if (name.isEmpty()) {
                    name = plain;
                }
                if (plain.toLowerCase(Locale.ROOT).contains("click to pickup")) {
                    pickup = true;
                }
            }
            if (!pickup) {
                return null;
            }
            return new CreateSlot(false, AhItem.fromStack(stack).tag, name);
        });
    }

    /** The price currently set in the create menu ("Item price: 500 coins"), -1 if unknown. */
    public static long currentCreatePrice() {
        Long found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)
                    || readScreen() != AhScreen.CREATE_BIN) {
                return -1L;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                String hover = ScreenUtil.cleanName(stack.getHoverName().getString());
                if (hover.toLowerCase(Locale.ROOT).startsWith("item price")) {
                    return parseCoins(hover);
                }
            }
            return -1L;
        });
        return found == null ? -1L : found;
    }

    /**
     * The auctions button of the AH main menu.
     *
     * @param slot    its slot, -1 if not there (yet)
     * @param loading still shows "Loading...", clicks on it are ignored by Hypixel
     * @param owned   "You own N auctions in progress or which recently ended", -1 if unknown
     */
    public record AuctionsEntry(int slot, boolean loading, int owned) {
    }

    private static final java.util.regex.Pattern OWNED_AUCTIONS =
            java.util.regex.Pattern.compile("(?i)you own (\\d+) auction");

    public static AuctionsEntry readAuctionsEntry() {
        AuctionsEntry entry = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return null;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                String hover = ScreenUtil.cleanName(stack.getHoverName().getString())
                        .toLowerCase(Locale.ROOT);
                boolean create = hover.contains("create auction");
                if (!create && !hover.contains("manage auctions")
                        && !hover.contains("auctions management")) {
                    continue;
                }
                boolean loading = false;
                int owned = create ? 0 : -1;
                for (String line : loreLines(stack)) {
                    String plain = ScreenUtil.cleanName(line);
                    if (plain.toLowerCase(Locale.ROOT).contains("loading")) {
                        loading = true;
                    }
                    java.util.regex.Matcher matcher = OWNED_AUCTIONS.matcher(plain);
                    if (matcher.find()) {
                        owned = Integer.parseInt(matcher.group(1));
                    }
                }
                return new AuctionsEntry(i, loading, owned);
            }
            return null;
        });
        return entry == null ? new AuctionsEntry(-1, false, -1) : entry;
    }

    /**
     * The entry of a bought/won item in "Your Bids": same item, already ended and
     * not won by someone else. A running auction ("Ends in:") or one with another
     * buyer cannot be claimed as an item, and clicking those made every claim fail.
     */
    public static int findClaimableBidSlot(String tag, String name, String ownName) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            boolean byTag = tag != null && !tag.isBlank();
            String own = ownName == null ? "" : ownName.toLowerCase(Locale.ROOT);
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                AhItem item = AhItem.fromStack(stack);
                boolean match = byTag ? tag.equalsIgnoreCase(item.tag)
                        : ScreenUtil.sameName(name, item.displayName);
                if (!match) {
                    continue;
                }
                boolean claimable = true;
                for (String line : loreLines(stack)) {
                    String lower = ScreenUtil.cleanName(line).trim().toLowerCase(Locale.ROOT);
                    if (lower.startsWith("ends in:")) {
                        claimable = false;
                    } else if ((lower.startsWith("buyer:") || lower.startsWith("bidder:"))
                            && !own.isEmpty() && !lower.contains(own)
                            && !lower.contains("refreshing")) {
                        claimable = false;
                    }
                }
                if (claimable) {
                    return i;
                }
            }
            return -1;
        });
        return found == null ? -1 : found;
    }

    /** Own ended auction in "Manage Auctions": sold ones, optionally also expired ones. */
    public static int findEndedAuctionSlot(boolean includeExpired) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                boolean sold = false;
                boolean expired = false;
                boolean own = false;
                for (String line : loreLines(itemAt(container, i))) {
                    String lower = ScreenUtil.cleanName(line).toLowerCase(Locale.ROOT);
                    sold |= lower.contains("status: sold");
                    expired |= lower.contains("status: expired");
                    own |= lower.contains("this is your own auction");
                }
                if (own && (sold || (includeExpired && expired))) {
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
            String plain = ScreenUtil.cleanName(line).trim();
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

    public static long currentPrice() {
        Long value = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1L;
            }
            long best = -1;
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                long price = priceAt(container, i);
                if (price > best) {
                    best = price;
                }
            }
            return best;
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

    public static boolean isContainerOpen() {
        return Boolean.TRUE.equals(ScreenUtil.sync(
                () -> Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>));
    }

    public static boolean noScreenOpen() {
        return Boolean.TRUE.equals(ScreenUtil.sync(
                () -> Minecraft.getInstance().gui.screen() == null));
    }

    public static ItemStack currentAuctionItem() {
        ItemStack stack = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return ItemStack.EMPTY;
            }
            ItemStack preferred = itemAt(container, 13);
            if (!preferred.isEmpty() && AhItem.hasSkyBlockId(preferred)) {
                return preferred.copy();
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                if (i == 13) {
                    continue;
                }
                ItemStack candidate = itemAt(container, i);
                if (candidate.isEmpty() || !AhItem.hasSkyBlockId(candidate)) {
                    continue;
                }
                String name = ScreenUtil.cleanName(candidate.getHoverName().getString())
                        .toLowerCase(Locale.ROOT);
                if (name.contains("bid") || name.contains("buy") || name.contains("auction")
                        || name.contains("confirm") || name.contains("cancel")) {
                    continue;
                }
                return candidate.copy();
            }
            return ItemStack.EMPTY;
        });
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /**
     * Slot whose item name is exactly one of {@code names} (case and colour codes
     * ignored), -1 if none. Unlike findButton there is no fuzzy match: "48 hours"
     * must not hit "Duration: 6 Hours" in the previous menu.
     */
    public static int findSlotNamedExactly(String... names) {
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
                String name = ScreenUtil.cleanName(stack.getHoverName().getString()).trim();
                for (String wanted : names) {
                    if (wanted != null && name.equalsIgnoreCase(wanted.trim())) {
                        return i;
                    }
                }
            }
            return -1;
        });
        return found == null ? -1 : found;
    }

    /** "Duration: 2 Days" -> "2 days" from the create menu, "" if not readable. */
    public static String currentCreateDuration() {
        String found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)
                    || readScreen() != AhScreen.CREATE_BIN) {
                return "";
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                String name = ScreenUtil.cleanName(stack.getHoverName().getString()).trim();
                if (name.toLowerCase(Locale.ROOT).startsWith("duration:")) {
                    return name.substring("duration:".length()).trim().toLowerCase(Locale.ROOT);
                }
            }
            return "";
        });
        return found == null ? "" : found;
    }

    /** "2 days" == "2 days", also "48 hours" / "2d" for 2 days. */
    public static boolean durationMatches(String shown, String wanted) {
        if (shown == null || shown.isBlank() || wanted == null) {
            return false;
        }
        String a = normalizeDuration(shown);
        String b = normalizeDuration(wanted);
        return !a.isEmpty() && a.equals(b);
    }

    /** Duration text -> hours as text ("2 days" -> "48"), "" if unknown. */
    public static String normalizeDuration(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)(\\d+)\\s*(d|day|days|h|hour|hours|m|min|minute|minutes)\\b")
                .matcher(text.trim());
        if (!matcher.find()) {
            return "";
        }
        long amount = Long.parseLong(matcher.group(1));
        char unit = Character.toLowerCase(matcher.group(2).charAt(0));
        long minutes = switch (unit) {
            case 'd' -> amount * 24 * 60;
            case 'h' -> amount * 60;
            default -> amount;
        };
        return Long.toString(minutes);
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

    /**
     * Best inventory match: same id and same name first (owning a second copy with
     * another reforge must not list the wrong one), then same id, then same name.
     */
    public static int findInventorySlot(String tag, String displayName) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen instanceof AbstractContainerScreen<?> container) {
                int chestSize = containerSize(container);
                var items = container.getMenu().getItems();
                int best = -1;
                int bestScore = 0;
                for (int i = chestSize; i < items.size(); i++) {
                    int score = inventoryScore(items.get(i), tag, displayName);
                    if (score > bestScore) {
                        bestScore = score;
                        best = i;
                    }
                }
                if (best >= 0) {
                    return best;
                }
            }
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return -1;
            }
            var inventory = client.player.getInventory();
            int best = -1;
            int bestScore = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                int score = inventoryScore(inventory.getItem(i), tag, displayName);
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            return best;
        });
        return found == null ? -1 : found;
    }

    /**
     * Like {@link #findInventorySlot}, but only slots that can be clicked in the
     * open menu. The fallback above also finds worn armor, whose inventory index
     * is a completely different slot of the menu.
     */
    public static int findClickableInventorySlot(String tag, String displayName) {
        Integer found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int chestSize = containerSize(container);
            var items = container.getMenu().getItems();
            int best = -1;
            int bestScore = 0;
            for (int i = chestSize; i < items.size(); i++) {
                int score = inventoryScore(items.get(i), tag, displayName);
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            return best;
        });
        return found == null ? -1 : found;
    }

    private static int inventoryScore(ItemStack stack, String tag, String displayName) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        AhItem item = AhItem.fromStack(stack);
        boolean sameName = matchesInventoryName(displayName, item);
        if (tag != null && !tag.isBlank() && AhItem.sameTag(tag, item.tag)) {
            return sameName ? 3 : 2;
        }
        return sameName ? 1 : 0;
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
                    if (tag != null && !tag.isBlank() && AhItem.sameTag(tag, item.tag)) {
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

    /**
     * Number of items in the player inventory matching the tag (or the name
     * when no tag is known). Used as a before/after baseline so an already owned
     * copy of the same item is not mistaken for a freshly bought one.
     */
    public static int countInventoryMatches(String tag, String displayName) {
        Integer found = ScreenUtil.sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return 0;
            }
            boolean byTag = tag != null && !tag.isBlank();
            int count = 0;
            var inventory = client.player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                AhItem item = AhItem.fromStack(stack);
                boolean match = byTag ? tag.equals(item.tag)
                        : matchesInventoryName(displayName, item);
                if (match) {
                    count += stack.getCount();
                }
            }
            return count;
        });
        return found == null ? 0 : found;
    }

    private static boolean matchesInventoryName(String displayName, AhItem item) {
        return displayName != null && !displayName.isBlank()
                && ScreenUtil.cleanName(displayName)
                        .equalsIgnoreCase(ScreenUtil.cleanName(item.displayName));
    }

    public static long currentBidPrice() {
        Long found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1L;
            }
            long best = -1;
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                for (String line : loreLines(itemAt(container, i))) {
                    String plain = ScreenUtil.cleanName(line).trim();
                    String lower = plain.toLowerCase(Locale.ROOT);
                    if (!(lower.startsWith("top bid:") || lower.startsWith("highest bid:")
                            || lower.startsWith("current bid:") || lower.startsWith("bid:")
                            || lower.startsWith("starting bid:"))) {
                        continue;
                    }
                    long parsed = parseCoins(plain);
                    if (parsed > best) {
                        best = parsed;
                    }
                }
            }
            return best;
        });
        return found == null ? -1 : found;
    }

    public static long currentRequiredBid() {
        Long found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return -1L;
            }
            int size = containerSize(container);
            long best = -1;
            for (int i = 0; i < size; i++) {
                for (String line : loreLines(itemAt(container, i))) {
                    String plain = ScreenUtil.cleanName(line).trim();
                    String lower = plain.toLowerCase(Locale.ROOT);
                    if (!(lower.startsWith("new bid:") || lower.startsWith("you pay:")
                            || lower.startsWith("bid amount:")
                            || lower.startsWith("minimum bid:")
                            || lower.startsWith("min bid:")
                            || lower.startsWith("next bid:")
                            || lower.contains("you need to bid at least"))) {
                        continue;
                    }
                    long parsed = parseCoins(plain);
                    if (parsed > best) {
                        best = parsed;
                    }
                }
            }
            return best;
        });
        return found == null ? -1 : found;
    }

    public static boolean currentAuctionUnavailable() {
        Boolean found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            int size = containerSize(container);
            boolean action = false;
            boolean unavailable = false;
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                String name = ScreenUtil.cleanName(stack.getHoverName().getString())
                        .toLowerCase(Locale.ROOT);
                if (name.contains("buy item right now") || name.contains("click to purchase")
                        || name.contains("submit bid") || name.contains("click to bid")
                        || name.contains("bid now") || name.contains("confirm bid")) {
                    action = true;
                }
                for (String line : loreLines(stack)) {
                    String lower = ScreenUtil.cleanName(line).toLowerCase(Locale.ROOT);
                    if (lower.contains("buy item right now") || lower.contains("click to purchase")
                            || lower.contains("submit bid") || lower.contains("click to bid")
                            || lower.contains("bid now") || lower.contains("confirm bid")) {
                        action = true;
                    }
                    if (lower.contains("collect auction") || lower.contains("collect your")
                            || lower.contains("someone else")
                            || lower.contains("auction ended")
                            || lower.contains("auction has ended")
                            || lower.contains("no longer available")
                            || lower.contains("sold to")
                            || lower.contains("you were outbid")
                            || lower.contains("outbid by")) {
                        unavailable = true;
                    }
                }
            }
            return unavailable && !action;
        });
        return Boolean.TRUE.equals(found);
    }

    public static boolean currentIsHighestBidder() {
        Boolean found = ScreenUtil.sync(() -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                for (String line : loreLines(itemAt(container, i))) {
                    String lower = ScreenUtil.cleanName(line).toLowerCase(Locale.ROOT);
                    if (lower.contains("you are the highest bidder")
                            || lower.contains("you are currently the highest bidder")
                            || lower.contains("you have the highest bid")) {
                        return true;
                    }
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(found);
    }

    public static int findBidButton() {
        int slot = findSlotWithText("submit bid", 0, 53);
        if (slot >= 0) {
            return slot;
        }
        slot = findSlotWithText("click to bid", 0, 53);
        if (slot >= 0) {
            return slot;
        }
        slot = findSlotWithText("place bid", 0, 53);
        if (slot >= 0) {
            return slot;
        }
        return findSlotWithText("bid now", 0, 53);
    }

    public static int findConfirmBidButton() {
        int slot = findSlotWithText("confirm bid", 0, 53);
        if (slot >= 0) {
            return slot;
        }
        return findSlotWithText("confirm", 0, 53);
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
