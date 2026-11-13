package com.coflnet.profit;

import com.coflnet.flipper.trading.BazaarChatEvent;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** Parsed server messages through the real tracker and persistence, without Minecraft. */
public final class BazaarProfitTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("bazaar-profit-test-");
        Object loader = FabricLoader.getInstance();
        Field configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        Object original = configDir.get(loader);
        // Keep the loader and production tracker APIs intact while isolating all writes.
        configDir.set(loader, directory);
        try {
            trading();
            migration(directory.resolve("CoflSky/profit-trackers.json"));
        } finally {
            configDir.set(loader, original);
            restart();
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("PASS: Bazaar net profit, partial claims, refunds, losses, restart and migration");
    }

    private static void trading() throws Exception {
        event("[Bazaar] Buy Order Setup! 1,000x Stone for 1,000,000 coins.");
        net(-1_000_000L, "Buy order must debit the full purchase cost");
        check(ProfitTracker.invested(ProfitTracker.BAZAAR) == 1_000_000L,
                "Pending investment must remain available to the UI");
        restart();
        net(-1_000_000L, "Purchase cost must survive a restart before the sale");
        event("[Bazaar] Your Buy Order for 1,000x Stone was filled!");
        event("[Bazaar] Claimed 1,000x Stone worth 1,000,000 coins bought for 1,000 each!");
        event("[Bazaar] Sell Offer Setup! 1,000x Stone for 1,200,000 coins.");
        event("[Bazaar] Your Sell Offer for 1,000x Stone was filled!");
        net(-1_000_000L, "Fills, item claims and offers must not move coins again");
        event("[Bazaar] Claimed 440,000 coins from selling 400x Stone at 1,100 each!");
        event("[Bazaar] Claimed 660,000 coins from selling 600x Stone at 1,100 each!");
        net(100_000L, "1M spent and 1.1M actually claimed must yield 100k, using proceeds after tax");
        restart();
        net(100_000L, "Correct profit must survive a restart");

        ProfitTracker.reset(ProfitTracker.BAZAAR);
        event("[Bazaar] Buy Order Setup! 1,000x Stone for 1,000,000 coins.");
        event("[Bazaar] Cancelled! Refunded 1,000,000 coins from cancelling Buy Order!");
        net(0L, "Cancelling an unfilled buy must return exactly the escrowed cost");

        event("[Bazaar] Buy Order Setup! 1,000x Stone for 1,000,000 coins.");
        event("[Bazaar] Claimed 400x Stone worth 400,000 coins bought for 1,000 each!");
        event("[Bazaar] Cancelled! Refunded 600,000 coins from cancelling Buy Order!");
        net(-400_000L, "A partial cancellation must retain the cost of filled items");
        event("[Bazaar] Sell Offer Setup! 400x Stone for 480,000 coins.");
        event("[Bazaar] Cancelled! Refunded 400x Stone from cancelling Sell Offer!");
        event("[Bazaar] Sell Offer Setup! 400x Stone for 450,000 coins.");
        event("[Bazaar] Order Flipped! 400x Stone for 440,000 coins.");
        net(-400_000L, "Cancelling, repricing and flipping sell offers must not credit items as coins");
        event("[Bazaar] Claimed 440,000 coins from selling 400x Stone at 1,100 each!");
        net(40_000L, "Partial fill, refund and resale must produce the actual margin");

        ProfitTracker.reset(ProfitTracker.BAZAAR);
        event("[Bazaar] Buy Order Setup! 1,000x Stone for 1,000,000 coins.");
        event("[Bazaar] Claimed 900,000 coins from selling 1,000x Stone at 900 each!");
        net(-100_000L, "Losing trades must retain their negative net");
        check(ProfitTracker.display(ProfitTracker.BAZAAR) == 0L,
                "A losing trade must not show positive profit");

        ProfitTracker.reset(ProfitTracker.BAZAAR);
        event("[Bazaar] Buy Order Setup! 5x Stone for 500 coins.");
        event("[Bazaar] Buy Order expired! Refunded 500 coins.");
        net(0L, "An explicit expiry refund must restore its purchase cost");
        event("[Bazaar] Sell Offer expired! 5x Stone");
        event("[Bazaar] Buy Order Setup! malformed amount");
        ProfitTracker.onBazaarEvent(BazaarChatEvent.parse("You claimed 1,000,000 coins from an auction!"));
        net(0L, "Missing amounts, item expiries and unrelated claims must not invent cash movements");
    }

    private static void migration(Path file) throws Exception {
        for (int version = 0; version <= 2; version++) {
            Files.writeString(file, "{\"__version\":" + version
                    + ",\"bazaar\":9999999,\"ah\":12345,\"lowballing\":67890}");
            restart();
            net(0L, "Legacy revenue totals must reset for format " + version);
            check(ProfitTracker.net(ProfitTracker.AH) == 12_345L, "Migration must preserve AH profit");
            check(ProfitTracker.net(ProfitTracker.LOWBALLING) == 67_890L,
                    "Migration must preserve lowballing profit");
            check(JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                    .get("__version").getAsLong() == 3L, "Migration must persist the new version");
            event("[Bazaar] Buy Order Setup! 1,000x Stone for 1,000,000 coins.");
            event("[Bazaar] Claimed 1,100,000 coins from selling 1,000x Stone at 1,100 each!");
            restart();
            net(100_000L, "Migrated profit must not reset again on restart");
        }
    }

    private static void event(String message) {
        BazaarChatEvent event = BazaarChatEvent.parse(message);
        check(event != null, "Fixture must parse: " + message);
        ProfitTracker.onBazaarEvent(event);
    }

    @SuppressWarnings("unchecked")
    private static void restart() throws Exception {
        Field nets = ProfitTracker.class.getDeclaredField("NETS");
        nets.setAccessible(true);
        ((Map<String, Long>) nets.get(null)).clear();
        Field loaded = ProfitTracker.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
    }

    private static void net(long expected, String message) {
        long actual = ProfitTracker.net(ProfitTracker.BAZAAR);
        check(actual == expected, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
