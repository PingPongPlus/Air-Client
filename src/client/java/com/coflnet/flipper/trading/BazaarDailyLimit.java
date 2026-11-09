package com.coflnet.flipper.trading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Tracks the gross value of Bazaar sell offers created during one Hypixel
 * trading day. The day rolls over at 02:00 Europe/Berlin, and the value is
 * persisted so restarts/reconnects cannot reset it early.
 */
public final class BazaarDailyLimit {
    public static final long LIMIT_COINS = 9_500_000_000L;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded;
    private static String tradeDay;
    private static long sold;
    private static long resetAt;

    private BazaarDailyLimit() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("CoflSky").resolve("bazaar-daily-limit.json");
    }

    private static String currentTradeDay() {
        return LocalDateTime.now(ZONE).minusHours(2).toLocalDate().toString();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = file();
            if (Files.exists(path)) {
                Data data = GSON.fromJson(Files.readString(path), Data.class);
                if (data != null) {
                    tradeDay = data.day;
                    sold = Math.max(0L, data.sold);
                    resetAt = data.resetAt;
                }
            }
        } catch (Exception e) {
            System.out.println("[BazaarLimit] Load failed: " + e.getMessage());
        }
        rollIfNeeded();
    }

    private static void rollIfNeeded() {
        String today = currentTradeDay();
        if (!today.equals(tradeDay)) {
            tradeDay = today;
            sold = 0L;
            resetAt = System.currentTimeMillis();
            save();
        }
    }

    public static synchronized long sold() {
        ensureLoaded();
        rollIfNeeded();
        return sold;
    }

    public static synchronized long remaining() {
        ensureLoaded();
        rollIfNeeded();
        return Math.max(0L, LIMIT_COINS - sold);
    }

    public static synchronized boolean reached() {
        ensureLoaded();
        rollIfNeeded();
        return sold >= LIMIT_COINS;
    }

    public static synchronized void addSale(long coins) {
        if (coins <= 0) {
            return;
        }
        ensureLoaded();
        rollIfNeeded();
        sold = Math.min(LIMIT_COINS, sold + coins);
        save();
    }

    /** A cancelled sell offer sold nothing: it no longer counts. */
    public static synchronized void removeSale(long coins) {
        if (coins <= 0) {
            return;
        }
        ensureLoaded();
        rollIfNeeded();
        sold = Math.max(0L, sold - coins);
        save();
    }

    public static synchronized String tradeDay() {
        ensureLoaded();
        rollIfNeeded();
        return tradeDay;
    }

    public static synchronized long resetAt() {
        ensureLoaded();
        rollIfNeeded();
        return resetAt;
    }

    private static void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Data data = new Data();
            data.day = tradeDay;
            data.sold = sold;
            data.resetAt = resetAt;
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[BazaarLimit] Save failed: " + e.getMessage());
        }
    }

    private static final class Data {
        String day;
        long sold;
        long resetAt;
    }
}
