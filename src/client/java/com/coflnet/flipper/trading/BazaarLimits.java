package com.coflnet.flipper.trading;

import com.coflnet.flipper.FlipperConfig;
import com.coflnet.flipper.data.ItemNames;
import com.coflnet.flipper.data.MarketData;
import com.coflnet.session.BotSessions;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Limits of the Bazaar flipper:
 * <ul>
 *   <li><b>Max coins per session</b>: the value of every sell offer it sets up
 *       (also re-created / flipped ones) is added up; at the limit it places no
 *       new buy orders any more and only sells what it has.</li>
 *   <li><b>Max coins invested</b>: buy orders + sell offers on the Bazaar may
 *       not be worth more than this together.</li>
 *   <li><b>Item mode</b>: only the listed items are flipped.</li>
 * </ul>
 */
public final class BazaarLimits {
    private static double sessionSellVolume;
    private static long sessionKey = -1;
    private static boolean capAnnounced;

    private BazaarLimits() {
    }

    /** Every Bazaar chat event; counts the sell offers of this session. */
    static synchronized void onEvent(BazaarChatEvent event) {
        if (event == null || event.price <= 0) {
            return;
        }
        if (event.type == BazaarChatEvent.Type.SELL_SETUP || event.type == BazaarChatEvent.Type.FLIPPED) {
            syncSession();
            sessionSellVolume += event.price;
        }
    }

    /** A new Bazaar session (start, or end of a break) starts counting from 0. */
    private static void syncSession() {
        long key = BotSessions.sessionStartedAt(BotSessions.Bot.BAZAAR);
        if (key != sessionKey) {
            sessionKey = key;
            sessionSellVolume = 0;
            capAnnounced = false;
        }
    }

    public static synchronized double sessionSellVolume() {
        syncSession();
        return sessionSellVolume;
    }

    /** Sell offers of this session reached the configured maximum. */
    public static synchronized boolean sessionCapReached(FlipperConfig cfg) {
        if (cfg.maxCoinsPerSessionMillions <= 0) {
            return false;
        }
        syncSession();
        return sessionSellVolume >= cfg.maxCoinsPerSessionMillions * 1_000_000.0;
    }

    /** True once, when the cap was reached (for one chat message). */
    static synchronized boolean announceCap() {
        if (capAnnounced) {
            return false;
        }
        capAnnounced = true;
        return true;
    }

    /** Coins in open buy orders and sell offers (the part not filled yet). */
    static double invested(List<ScreenUtil.GuiOrder> buyOrders, List<ScreenUtil.GuiOrder> sellOffers) {
        double total = 0;
        for (ScreenUtil.GuiOrder order : buyOrders) {
            total += open(order);
        }
        for (ScreenUtil.GuiOrder order : sellOffers) {
            total += open(order);
        }
        return total;
    }

    private static double open(ScreenUtil.GuiOrder order) {
        long remaining = Math.max(0, order.amount - Math.max(0, order.filled));
        return Math.max(0, order.unitPrice) * remaining;
    }

    /** Item mode on and at least one item listed. */
    public static boolean itemModeActive(FlipperConfig cfg) {
        return cfg.onlyItemsMode && cfg.onlyItems != null && !cfg.onlyItems.isEmpty();
    }

    /** In item mode: every product that is not listed (the analyzer skips them). */
    static Set<String> excludedByItemMode(FlipperConfig cfg, MarketData data) {
        Set<String> excluded = new HashSet<>();
        if (!itemModeActive(cfg) || data == null) {
            return excluded;
        }
        for (String productId : data.products.keySet()) {
            if (!allowed(cfg.onlyItems, productId, ItemNames.displayName(productId))) {
                excluded.add(productId);
            }
        }
        return excluded;
    }

    /** Listed by display name ("Enchanted Diamond") or product id ("ENCHANTED_DIAMOND"). */
    static boolean allowed(List<String> items, String productId, String displayName) {
        String id = normalize(productId);
        String name = normalize(displayName);
        for (String entry : items) {
            String wanted = normalize(entry);
            if (wanted.isEmpty()) {
                continue;
            }
            if (wanted.equals(name) || wanted.equals(id) || wanted.replace(' ', '_').equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)§[0-9A-FK-OR]", "").trim().toLowerCase(Locale.ROOT);
    }
}
