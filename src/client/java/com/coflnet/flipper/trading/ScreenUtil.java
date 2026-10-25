package com.coflnet.flipper.trading;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class ScreenUtil {

    private ScreenUtil() {
    }

    public enum BzScreen {
        NONE, BAZAAR, SEARCH, PRODUCT, BUY_AMOUNT, BUY_PRICE, BUY_CONFIRM,
        SELL_AMOUNT, SELL_PRICE, SELL_CONFIRM, ORDERS, ORDER_OPTIONS, SIGN, UNKNOWN
    }

    public static void onClient(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    public static <T> T sync(java.util.concurrent.Callable<T> callable) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.isSameThread()) {
            try {
                return callable.call();
            } catch (Throwable t) {
                return null;
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static int containerSize(AbstractContainerScreen<?> container) {
        if (container.getMenu() instanceof net.minecraft.world.inventory.ChestMenu chest) {
            return chest.getContainer().getContainerSize();
        }
        return container.getMenu().getItems().size();
    }

    private static ItemStack itemAt(AbstractContainerScreen<?> container, int index) {
        if (container.getMenu() instanceof net.minecraft.world.inventory.ChestMenu chest) {
            return chest.getContainer().getItem(index);
        }
        return container.getMenu().getItems().get(index);
    }

    public static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = ChatFormatting.stripFormatting(raw);
        if (stripped == null) {
            stripped = raw;
        }
        return stripped.replace('\u00A0', ' ').trim();
    }

    public static String normalize(String raw) {
        return cleanName(raw).toLowerCase().replaceAll("\\s+", " ");
    }

    public static String cleanSpecials(String raw) {
        if (raw == null) {
            return "";
        }
        return cleanName(raw).replaceAll("[^\\p{L}\\p{N}\\s]", "").replaceAll("\\s+", " ").trim();
    }

    public static int freeInventorySlots() {
        Integer free = sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return 36;
            }
            int count = 0;
            for (int i = 0; i < 36; i++) {
                if (client.player.getInventory().getItem(i).isEmpty()) {
                    count++;
                }
            }
            return count;
        });
        return free == null ? 36 : free;
    }

    public static boolean fuzzyTextMatch(String name, String text) {
        String n = normalize(name);
        String t = normalize(text);
        if (t.isEmpty()) {
            return false;
        }
        if (n.contains(t)) {
            return true;
        }
        String[] tokens = t.split(" ");
        for (String token : tokens) {
            if (token.length() >= 3 && !n.contains(token)) {
                return false;
            }
        }
        return tokens.length > 0;
    }

    public static boolean fuzzyItemMatch(String name, String target) {
        String n = cleanSpecials(normalize(name));
        String t = cleanSpecials(normalize(target));
        if (n.isEmpty() || t.isEmpty()) {
            return false;
        }
        if (n.equals(t)) {
            return true;
        }
        if (n.contains(t) || t.contains(n)) {
            return true;
        }
        int maxDist = Math.max(1, Math.min(n.length(), t.length()) / 5);
        return levenshtein(n, t) <= maxDist;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }

    public static boolean sameName(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String left = cleanSpecials(normalize(a));
        String right = cleanSpecials(normalize(b));
        return !left.isEmpty() && left.equals(right);
    }

    public static boolean clickIfNameContains(String text, int button) {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            if (client.player == null) {
                return false;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty() || stack.getCustomName() == null) {
                    continue;
                }
                if (fuzzyTextMatch(stack.getCustomName().getString(), text)) {
                    client.gameMode.handleContainerInput(
                            container.getMenu().containerId, i, button, ContainerInput.PICKUP, client.player);
                    return true;
                }
            }
            return false;
        }));
    }

    public static boolean clickIfNameExact(String text, int button) {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            if (client.player == null) {
                return false;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty() || stack.getCustomName() == null) {
                    continue;
                }
                if (sameName(stack.getCustomName().getString(), text)) {
                    client.gameMode.handleContainerInput(
                            container.getMenu().containerId, i, button, ContainerInput.PICKUP, client.player);
                    return true;
                }
            }
            return false;
        }));
    }

    public static String productName() {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return null;
            }
            ItemStack stack = itemAt(container, 13);
            if (stack.isEmpty() || stack.getCustomName() == null) {
                return null;
            }
            return cleanName(stack.getCustomName().getString());
        });
    }

    public static boolean clickSignSlot(int button) {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            if (client.player == null) {
                return false;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (stack.getItem() instanceof SignItem
                        || (stack.getCustomName() != null
                        && normalize(stack.getCustomName().getString()).contains("custom"))) {
                    client.gameMode.handleContainerInput(
                            container.getMenu().containerId, i, button, ContainerInput.PICKUP, client.player);
                    return true;
                }
            }
            return false;
        }));
    }

    public static class GuiOrder {
        public String itemName;
        public boolean sellSide;
        public double unitPrice;
        public long amount;
        public long filled;
        public long claimable;
        public boolean coinsClaimable;
        public int slot;
    }

    public static List<GuiOrder> scanOrders() {
        List<GuiOrder> result = sync(() -> {
            List<GuiOrder> list = new ArrayList<>();
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return list;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty() || stack.getItem() == Items.STAINED_GLASS_PANE.black()) {
                    continue;
                }
                if (stack.getItem() == Items.ARROW) {
                    break;
                }
                if (stack.getCustomName() == null) {
                    continue;
                }
                String title = cleanName(stack.getCustomName().getString());
                String lowerTitle = title.toLowerCase();
                var lore = stack.get(DataComponents.LORE);
                if (lore == null) {
                    continue;
                }
                GuiOrder order = new GuiOrder();
                order.slot = i;
                boolean hasBuyPrefix = lowerTitle.startsWith("buy ");
                boolean hasSellPrefix = lowerTitle.startsWith("sell ");
                boolean loreLooksLikeOrder = false;
                boolean sawClaimLine = false;
                long filledPct = -1;
                long filledFromRatio = 0;
                StringBuilder loreDump = new StringBuilder();
                for (Component line : lore.lines()) {
                    String text = cleanName(line.getString());
                    String lower = text.toLowerCase();
                    if (loreDump.length() < 300) {
                        loreDump.append(text).append(" // ");
                    }
                    if (lower.contains("to claim") || lower.contains("click to claim")) {
                        sawClaimLine = true;
                        long n = parseFirstNumber(text);
                        if (n > 0) {
                            order.claimable = n;
                        }
                        if (lower.contains("coins")) {
                            order.coinsClaimable = true;
                        }
                        loreLooksLikeOrder = true;
                    }
                    if (lower.contains("filled")) {
                        int colon = text.indexOf(':');
                        int slash = text.indexOf('/');
                        if (colon >= 0 && slash > colon + 1) {
                            try {
                                filledFromRatio = Long.parseLong(text.substring(colon + 1, slash).replace(",", "").trim());
                                String after = text.substring(slash + 1);
                                long total = parseFirstNumber(after);
                                if (total > 0) {
                                    order.amount = total;
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        } else if (text.contains("%")) {
                            filledPct = parseNumberBefore(text, '%');
                        }
                        loreLooksLikeOrder = true;
                    } else if (text.contains("%") && (lower.contains("fill") || lower.contains("progress"))) {
                        filledPct = parseNumberBefore(text, '%');
                        loreLooksLikeOrder = true;
                    }
                    if (lower.contains("order amount")) {
                        order.amount = parseFirstNumber(text);
                        loreLooksLikeOrder = true;
                    }
                    if (lower.contains("offer amount")) {
                        order.amount = parseFirstNumber(text);
                        if (!hasSellPrefix && !hasBuyPrefix) {
                            order.sellSide = true;
                        }
                        loreLooksLikeOrder = true;
                    }
                    if (lower.contains("unit")) {
                        double price = parseNumberAfter(text, "unit:");
                        if (price <= 0) {
                            price = parseNumberAfter(text, "unit");
                        }
                        if (price > 0) {
                            order.unitPrice = price;
                        }
                        loreLooksLikeOrder = true;
                    }
                }
                if (!hasBuyPrefix && !hasSellPrefix && !loreLooksLikeOrder) {
                    continue;
                }
                if (hasBuyPrefix) {
                    order.sellSide = false;
                    order.itemName = title.substring(4).trim();
                } else if (hasSellPrefix) {
                    order.sellSide = true;
                    order.itemName = title.substring(5).trim();
                } else {
                    order.itemName = title.trim();
                }
                if (filledFromRatio > 0) {
                    order.filled = filledFromRatio;
                } else if (filledPct > 0 && order.amount > 0) {
                    order.filled = filledPct * order.amount / 100;
                }
                if (order.claimable <= 0 && sawClaimLine) {
                    order.claimable = order.filled > 0 ? order.filled : order.amount;
                }
                if (order.claimable <= 0 && order.filled >= order.amount && order.amount > 0) {
                    order.claimable = order.amount;
                }
                if (order.unitPrice > 0 && order.amount > 0) {
                    System.out.println("[Flipper] ScanOrder: " + order.itemName
                            + " sell=" + order.sellSide + " unit=" + order.unitPrice
                            + " amt=" + order.amount + " filled=" + order.filled
                            + " claim=" + order.claimable + " coins=" + order.coinsClaimable
                            + " lore=" + loreDump);
                    list.add(order);
                } else {
                    System.out.println("[Flipper] ScanOrder übersprungen: " + title
                            + " unit=" + order.unitPrice + " amt=" + order.amount
                            + " lore=" + loreDump);
                }
            }
            return list;
        });
        return result == null ? new ArrayList<>() : result;
    }

    private static long parseFirstNumber(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\d,]{1,}").matcher(text);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group().replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static double parseNumberAfter(String text, String word) {
        int index = text.toLowerCase().indexOf(word);
        if (index < 0) {
            return 0;
        }
        String rest = text.substring(index + word.length());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\d,]+(?:\\.\\d+)?").matcher(rest);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group().replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static long parseNumberBefore(String text, char marker) {
        int index = text.indexOf(marker);
        if (index < 0) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\d,]+(?:\\.\\d+)?)\\s*" + marker).matcher(text);
        if (matcher.find()) {
            try {
                return (long) Double.parseDouble(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    public static boolean clickConfirmItem(String acceptContains) {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            if (client.player == null) {
                return false;
            }
            ItemStack slot13 = itemAt(container, 13);
            if (!slot13.isEmpty() && slot13.getCustomName() != null
                    && fuzzyTextMatch(slot13.getCustomName().getString(), acceptContains)) {
                client.gameMode.handleContainerInput(
                        container.getMenu().containerId, 13, 0, ContainerInput.PICKUP, client.player);
                return true;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (!stack.isEmpty() && stack.getCustomName() != null
                        && fuzzyTextMatch(stack.getCustomName().getString(), "Confirm")) {
                    client.gameMode.handleContainerInput(
                            container.getMenu().containerId, i, 0, ContainerInput.PICKUP, client.player);
                    return true;
                }
            }
            return false;
        }));
    }

    public static boolean clickOrderSlotByIndex(int slot, String displayName, boolean sellSide, int button) {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            if (client.player == null) {
                return false;
            }
            if (slot < 0 || slot >= containerSize(container)) {
                return false;
            }
            ItemStack stack = itemAt(container, slot);
            if (matchesOrderEntry(stack, displayName, sellSide)) {
                client.gameMode.handleContainerInput(
                        container.getMenu().containerId, slot, button, ContainerInput.PICKUP, client.player);
                return true;
            }
            return false;
        }));
    }

    public static boolean clickOrderEntry(String displayName, boolean sellSide, int button) {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return false;
            }
            if (client.player == null) {
                return false;
            }
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                if (matchesOrderEntry(stack, displayName, sellSide)) {
                    client.gameMode.handleContainerInput(
                            container.getMenu().containerId, i, button, ContainerInput.PICKUP, client.player);
                    return true;
                }
            }
            return false;
        }));
    }

    private static boolean matchesOrderEntry(ItemStack stack, String displayName, boolean sellSide) {
        if (stack.isEmpty() || stack.getItem() == Items.STAINED_GLASS_PANE.black()) {
            return false;
        }
        if (stack.getCustomName() == null) {
            return false;
        }
        String name = cleanName(stack.getCustomName().getString());
        String lower = name.toLowerCase();
        String target = displayName.toLowerCase();
        boolean nameMatches = fuzzyItemMatch(name, displayName)
                || lower.contains(target) || target.contains(lower);
        if (!nameMatches) {
            return false;
        }
        boolean hasSell = lower.contains("sell");
        boolean hasBuy = lower.contains("buy");
        boolean sideMatches = sellSide ? hasSell : (hasBuy || !hasSell);
        if (!sideMatches) {
            return false;
        }
        if (lower.startsWith("buy ") || lower.startsWith("sell ")) {
            return true;
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            String text = cleanName(line.getString()).toLowerCase();
            if (text.contains("order amount") || text.contains("offer amount")
                    || text.contains("unit") || text.contains("filled")
                    || text.contains("coins") || text.contains("claim")) {
                return true;
            }
        }
        return false;
    }

    public static BzScreen currentScreen() {
        return sync(ScreenUtil::readScreen);
    }

    private static BzScreen readScreen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) {
            return BzScreen.NONE;
        }
        if (screen instanceof AbstractSignEditScreen) {
            return BzScreen.SIGN;
        }
        if (screen instanceof AbstractContainerScreen<?> container) {
            String title = cleanName(container.getTitle().getString());
            String titleLower = title.toLowerCase();
            int size = containerSize(container);
            if (titleLower.contains("orders")) {
                return BzScreen.ORDERS;
            }
            if (titleLower.contains("order options")) {
                return BzScreen.ORDER_OPTIONS;
            }
            if (hasButton(container, "Create Buy Order")
                    || hasButton(container, "Create Sell Offer")
                    || hasButton(container, "Sell Instantly")
                    || hasButton(container, "Buy Instantly")) {
                return BzScreen.PRODUCT;
            }
            if (titleLower.contains("bazaar")) {
                if (size == 54 && title.contains("\"")) {
                    return BzScreen.SEARCH;
                }
                return BzScreen.BAZAAR;
            }
            if (titleLower.contains("how many do you want?")) {
                return BzScreen.BUY_AMOUNT;
            }
            if (titleLower.contains("how much do you want to pay?")) {
                return BzScreen.BUY_PRICE;
            }
            if (titleLower.contains("confirm buy order")) {
                return BzScreen.BUY_CONFIRM;
            }
            if (titleLower.contains("how many are you selling?")) {
                return BzScreen.SELL_AMOUNT;
            }
            if (titleLower.contains("at what price are you selling?")) {
                return BzScreen.SELL_PRICE;
            }
            if (titleLower.contains("confirm sell offer")) {
                return BzScreen.SELL_CONFIRM;
            }
            return BzScreen.UNKNOWN;
        }
        return BzScreen.UNKNOWN;
    }

    public static boolean waitForScreen(BzScreen target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastUnknownLog = 0;
        int noneStreak = 0;
        while (System.currentTimeMillis() < deadline) {
            Trader trader = Trader.get();
            if (trader != null && trader.isAhPriority()) {
                return false;
            }
            BzScreen current = currentScreen();
            if (current == target) {
                return true;
            }
            if (current == BzScreen.UNKNOWN) {
                long now = System.currentTimeMillis();
                if (now - lastUnknownLog > 3000) {
                    lastUnknownLog = now;
                    System.out.println("[Flipper] Unbekannter Screen beim Warten auf " + target
                            + ": " + screenDebugInfo());
                }
            }
            if (current == BzScreen.NONE) {
                noneStreak++;
                if (noneStreak > 30) {
                    return false;
                }
            } else {
                noneStreak = 0;
            }
            sleep(80 + ThreadLocalRandom.current().nextLong(140));
        }
        return false;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static long jitter(long baseMs) {
        return (long) (baseMs * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4));
    }

    public static long jitterAround(long baseMs, long spreadMs) {
        return baseMs + ThreadLocalRandom.current().nextLong(-spreadMs, spreadMs + 1);
    }

    public static void jitterSleep(long baseMs, long spreadMs) {
        sleep(jitterAround(baseMs, spreadMs));
    }

    public static void clickSlot(int slot, int button) {
        onClient(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return;
            }
            Player player = client.player;
            if (player == null) {
                return;
            }
            client.gameMode.handleContainerInput(
                    container.getMenu().containerId,
                    slot,
                    button,
                    ContainerInput.PICKUP,
                    player
            );
        });
    }

    public static String screenTitle() {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            Screen screen = client.gui.screen();
            if (screen == null) {
                return null;
            }
            String title = cleanName(screen.getTitle().getString());
            return title;
        });
    }

    public static long[] orderProgress(int slot) {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return null;
            }
            ItemStack stack = itemAt(container, slot);
            if (stack.isEmpty()) {
                return null;
            }
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) {
                return null;
            }
            for (Component line : lore.lines()) {
                String text = cleanName(line.getString());
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("[Ff]illed[^\\d]*([\\d,]+)\\s*/\\s*([\\d,]+)")
                        .matcher(text);
                if (matcher.find()) {
                    try {
                        long filled = Long.parseLong(matcher.group(1).replace(",", ""));
                        long total = Long.parseLong(matcher.group(2).replace(",", ""));
                        return new long[]{filled, total};
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
            return null;
        });
    }

    public static String inventoryItemNames() {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return "kein Spieler";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 36 && sb.length() < 400; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getCustomName() != null) {
                    sb.append('[').append(cleanName(stack.getCustomName().getString()))
                            .append(" x").append(stack.getCount()).append("] ");
                }
            }
            return sb.toString();
        });
    }

    public static String containerSignature() {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int size = containerSize(container);
            for (int i = 0; i < size; i++) {
                ItemStack stack = itemAt(container, i);
                sb.append(i).append(':')
                        .append(stack.isEmpty() ? "-" : (stack.getCustomName() != null
                                ? cleanName(stack.getCustomName().getString()) : stack.getItem().toString()))
                        .append('|');
            }
            return sb.toString();
        });
    }

    public static String screenDebugInfo() {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            Screen screen = client.gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return screen == null ? "kein Screen" : screen.getClass().getSimpleName();
            }
            String title = cleanName(container.getTitle().getString());
            StringBuilder sb = new StringBuilder();
            sb.append("title='").append(title).append("' size=").append(containerSize(container)).append(" slots=[");
            for (int i = 0; i < containerSize(container); i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty() || stack.getCustomName() == null) {
                    continue;
                }
                String name = cleanName(stack.getCustomName().getString());
                if (sb.length() > 400) {
                    sb.append("...");
                    break;
                }
                sb.append(i).append(":").append(name).append(" | ");
            }
            sb.append("]");
            return sb.toString();
        });
    }

    public static String slotName(int slot) {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return null;
            }
            ItemStack stack = itemAt(container, slot);
            if (stack.isEmpty() || stack.getCustomName() == null) {
                return null;
            }
            String name = cleanName(stack.getCustomName().getString());
            return name;
        });
    }

    public static boolean hasButton(AbstractContainerScreen<?> container, String text) {
        for (int i = 0; i < containerSize(container); i++) {
            ItemStack stack = itemAt(container, i);
            if (stack.isEmpty() || stack.getCustomName() == null) {
                continue;
            }
            if (fuzzyTextMatch(stack.getCustomName().getString(), text)) {
                return true;
            }
        }
        return false;
    }

    public static int findButton(String text) {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            for (int i = 0; i < containerSize(container); i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty() || stack.getCustomName() == null) {
                    continue;
                }
                if (fuzzyTextMatch(stack.getCustomName().getString(), text)) {
                    return i;
                }
            }
            return -1;
        });
    }

    public static int findSignSlot() {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int containerSize = containerSize(container);
            for (int i = 0; i < containerSize; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (stack.getItem() instanceof SignItem) {
                    return i;
                }
                if (stack.getCustomName() != null) {
                    String name = cleanName(stack.getCustomName().getString());
                    if (name.toLowerCase().contains("custom")) {
                        return i;
                    }
                }
            }
            return -1;
        });
    }

    public static int findItemSlot(String displayName) {
        return findItemSlot(displayName, false);
    }

    public static int findItemSlot(String displayName, boolean exactOnly) {
        Integer result = sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int containerSize = containerSize(container);
            int containsMatch = -1;
            int containsBestLength = Integer.MAX_VALUE;
            for (int i = 0; i < containerSize; i++) {
                ItemStack stack = itemAt(container, i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (stack.getItem() == Items.STAINED_GLASS_PANE.black()) {
                    continue;
                }
                if (stack.getCustomName() == null) {
                    continue;
                }
                String name = cleanName(stack.getCustomName().getString());
                String lower = name.toLowerCase();
                if (lower.contains("search") || lower.contains("go back") || lower.contains("close")) {
                    continue;
                }
                if (name.equalsIgnoreCase(displayName)) {
                    return i;
                }
                if (exactOnly) {
                    continue;
                }
                String target = displayName.toLowerCase();
                boolean fuzzy = fuzzyItemMatch(name, displayName);
                if ((fuzzy || lower.contains(target) || target.contains(lower))
                        && name.length() <= displayName.length() + 15) {
                    if (name.length() < containsBestLength) {
                        containsBestLength = name.length();
                        containsMatch = i;
                    }
                }
            }
            return containsMatch;
        });
        return result == null ? -1 : result;
    }

    public static int findOrderSlot(String displayName, boolean sellSide) {
        return sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                return -1;
            }
            int containerSize = containerSize(container);
            for (int i = 0; i < containerSize; i++) {
                ItemStack stack = itemAt(container, i);
                if (matchesOrderEntry(stack, displayName, sellSide)) {
                    return i;
                }
            }
            return -1;
        });
    }

    public static void closeCurrentScreen() {
        onClient(() -> {
            Minecraft client = Minecraft.getInstance();
            Screen screen = client.gui.screen();
            if (screen != null) {
                screen.onClose();
            }
        });
    }

    private static volatile long lastPurseDebugAt;

    public static long readPurse() {
        Long purse = sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.level == null || client.player == null) {
                return null;
            }
            Scoreboard scoreboard = client.level.getScoreboard();
            Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BY_ID.apply(1));
            if (objective == null) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            for (ScoreHolder holder : scoreboard.getTrackedPlayers()) {
                if (scoreboard.getPlayerScoreInfo(holder, objective) == null) {
                    continue;
                }
                PlayerTeam team = scoreboard.getPlayersTeam(holder.getScoreboardName());
                if (team != null) {
                    String line = team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString();
                    if (!line.trim().isEmpty()) {
                        lines.add(cleanName(line));
                    }
                }
            }
            for (String line : lines) {
                if (line.startsWith("Purse")) {
                    String number = line.substring("Purse".length()).replace(":", "")
                            .replace(",", "").replace(" ", "").trim();
                    if (number.isEmpty()) {
                        continue;
                    }
                    try {
                        return (long) Double.parseDouble(number);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastPurseDebugAt > 30_000L) {
                lastPurseDebugAt = now;
                System.out.println("[Flipper] Purse nicht gefunden. Sidebar-Zeilen: " + lines);
            }
            return null;
        });
        return purse == null ? -1L : purse;
    }

    public static boolean onSkyBlock() {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return false;
            }
            if (client.getCurrentServer() != null && client.getCurrentServer().ip != null
                    && client.getCurrentServer().ip.toLowerCase().contains("hypixel")) {
                return true;
            }
            return hasSkyBlockScoreboard(client);
        }));
    }

    private static boolean hasSkyBlockScoreboard(Minecraft client) {
        if (client.level == null) {
            return false;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BY_ID.apply(1));
        if (objective == null) {
            return false;
        }
        if (cleanName(objective.getDisplayName().getString()).toUpperCase().contains("SKYBLOCK")) {
            return true;
        }
        for (ScoreHolder holder : scoreboard.getTrackedPlayers()) {
            if (scoreboard.getPlayerScoreInfo(holder, objective) == null) {
                continue;
            }
            PlayerTeam team = scoreboard.getPlayersTeam(holder.getScoreboardName());
            if (team == null) {
                continue;
            }
            String line = cleanName(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
            if (line.startsWith("Purse") || line.startsWith("Bits")) {
                return true;
            }
        }
        return false;
    }

    public static boolean inCombatOrDead() {
        return Boolean.TRUE.equals(sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return true;
            }
            if (client.player.isDeadOrDying()) {
                return true;
            }
            int sinceHurt = client.player.tickCount - client.player.getLastHurtByMobTimestamp();
            return sinceHurt >= 0 && sinceHurt < 100;
        }));
    }

    public static long countItemsInInventory(String displayName) {
        Long count = sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return 0L;
            }
            long total = 0;
            for (int i = 0; i < 36; i++) {
                total += matchingCount(client.player.getInventory().getItem(i), displayName);
            }
            return total;
        });
        return count == null ? 0L : count;
    }

    private static long matchingCount(ItemStack stack, String displayName) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.getCustomName() != null) {
            String name = cleanName(stack.getCustomName().getString());
            if (fuzzyItemMatch(name, displayName)) {
                return stack.getCount();
            }
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                String text = cleanName(line.getString());
                if (fuzzyItemMatch(text, displayName)) {
                    return stack.getCount();
                }
            }
        }
        return 0;
    }

    public static String formatCoins(double coins) {
        if (coins >= 1_000_000_000) {
            return String.format(java.util.Locale.US, "%.2fB", coins / 1_000_000_000.0);
        }
        if (coins >= 1_000_000) {
            return String.format(java.util.Locale.US, "%.1fM", coins / 1_000_000.0);
        }
        if (coins >= 1_000) {
            return String.format(java.util.Locale.US, "%.1fK", coins / 1_000.0);
        }
        return String.format(java.util.Locale.US, "%.0f", coins);
    }

    public static String formatPrice(double price) {
        if (price == Math.floor(price)) {
            return String.valueOf((long) price);
        }
        return String.format(java.util.Locale.US, "%.1f", price);
    }
}
