package com.coflnet.flipper.data;

import com.coflnet.flipper.FlipperConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UndercutTracker {

    public static class Entry {
        public double lastBid;
        public double lastAsk;
        public long lastSeenAt;
        public double observedMinutes;
        public long buyChanges;
        public long sellChanges;
        public double uBuy;
        public double uSell;
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Entry>>() {
    }.getType();
    private static boolean loaded;
    private static long lastSaveAt;

    private UndercutTracker() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("bazaar-undercuts.json");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = file();
            if (Files.exists(path)) {
                Map<String, Entry> stored = GSON.fromJson(Files.readString(path), TYPE);
                if (stored != null) {
                    ENTRIES.putAll(stored);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void update(MarketData data) {
        if (data == null || data.products.isEmpty()) {
            return;
        }
        ensureLoaded();
        long now = System.currentTimeMillis();
        for (MarketData.Product product : data.products.values()) {
            Entry entry = ENTRIES.computeIfAbsent(product.productId, id -> new Entry());
            if (entry.lastSeenAt > 0) {
                double minutes = Math.min(60.0, (now - entry.lastSeenAt) / 60_000.0);
                entry.observedMinutes += minutes;
                if (entry.lastBid > 0 && product.bestBid > entry.lastBid + 1e-6) {
                    entry.buyChanges++;
                }
                if (entry.lastAsk > 0 && product.bestAsk < entry.lastAsk - 1e-6) {
                    entry.sellChanges++;
                }
            }
            entry.lastBid = product.bestBid;
            entry.lastAsk = product.bestAsk;
            entry.lastSeenAt = now;
            recompute(entry);
        }
        if (now - lastSaveAt > 300_000L) {
            lastSaveAt = now;
            save();
        }
    }

    private static void recompute(Entry entry) {
        if (entry.observedMinutes < 10.0) {
            return;
        }
        double hours = entry.observedMinutes / 60.0;
        entry.uBuy = entry.buyChanges / hours;
        entry.uSell = entry.sellChanges / hours;
    }

    public static double effectiveU(String productId, double v, FlipperConfig cfg, boolean buySide) {
        ensureLoaded();
        double seed = Math.max(0.0, cfg.undercutSeedFactor * v);
        Entry entry = ENTRIES.get(productId);
        if (entry == null || entry.observedMinutes < 30.0) {
            return seed;
        }
        double measured = buySide ? entry.uBuy : entry.uSell;
        double weight = Math.min(1.0, entry.observedMinutes / 180.0);
        return (1.0 - weight) * seed + weight * measured;
    }

    public static synchronized void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(ENTRIES, TYPE));
        } catch (Exception ignored) {
        }
    }

    public static void reset() {
        ENTRIES.clear();
        save();
    }
}
