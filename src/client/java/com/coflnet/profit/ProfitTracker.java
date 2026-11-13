package com.coflnet.profit;

import com.coflnet.flipper.trading.BazaarChatEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent cash-flow tracker. Buy orders / auctions subtract, claimed coins
 * and refunds add. Only the positive net is exposed as profit.
 */
public final class ProfitTracker {
    public static final String BAZAAR = "bazaar";
    public static final String AH = "ah";
    public static final String LOWBALLING = "lowballing";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final Map<String, Long> NETS = new LinkedHashMap<>();
    private static final Map<String, Long> PURSE_BEFORE = new LinkedHashMap<>();
    private static final String VERSION_KEY = "__version";
    private static final long FORMAT_VERSION = 3L;
    private static boolean loaded;

    private ProfitTracker() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("CoflSky").resolve("profit-trackers.json");
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        long version = 0L;
        try {
            Path path = file();
            if (!Files.exists(path)) {
                NETS.put(BAZAAR, 0L);
                NETS.put(AH, 0L);
                NETS.put(LOWBALLING, 0L);
                return;
            }
            Map<?, ?> raw = GSON.fromJson(Files.readString(path), Map.class);
            if (raw == null) {
                return;
            }
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().toString();
                if (VERSION_KEY.equals(key)) {
                    if (entry.getValue() instanceof Number number) {
                        version = number.longValue();
                    }
                    continue;
                }
                if (entry.getValue() instanceof Number number) {
                    NETS.put(key, number.longValue());
                }
            }
        } catch (Exception e) {
            System.out.println("[Profit] Load failed: " + e.getMessage());
        }
        // Before v3, Bazaar claims were credited without deducting buy orders.
        // Those totals cannot be corrected without the missing purchase history.
        if (version < FORMAT_VERSION) {
            NETS.put(BAZAAR, 0L);
            save();
        }
    }

    public static void spend(String module, long amount) {
        if (amount <= 0) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded();
            NETS.merge(module, -amount, Long::sum);
            save();
        }
    }

    public static void receive(String module, long amount) {
        if (amount <= 0) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded();
            NETS.merge(module, amount, Long::sum);
            save();
        }
    }

    /** Record confirmed cash movements once, independently of the trading bot. */
    public static void onBazaarEvent(BazaarChatEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type) {
            case BUY_SETUP -> spend(BAZAAR, Math.round(event.price));
            case CLAIMED_COINS, CANCELLED, EXPIRED -> receive(BAZAAR, event.coins);
            // Item claims and sell offers move inventory, not coins. Refunds are
            // credited only when the server explicitly reports a coin amount.
            default -> { }
        }
    }

    public static void reset(String module) {
        synchronized (LOCK) {
            ensureLoaded();
            NETS.put(module, 0L);
            save();
        }
    }

    public static void resetAll() {
        synchronized (LOCK) {
            ensureLoaded();
            NETS.put(BAZAAR, 0L);
            NETS.put(AH, 0L);
            NETS.put(LOWBALLING, 0L);
            save();
        }
    }

    public static long net(String module) {
        synchronized (LOCK) {
            ensureLoaded();
            return NETS.getOrDefault(module, 0L);
        }
    }

    public static long display(String module) {
        return Math.max(0L, net(module));
    }

    public static long invested(String module) {
        return Math.max(0L, -net(module));
    }

    public static long overallNet() {
        synchronized (LOCK) {
            ensureLoaded();
            return NETS.getOrDefault(BAZAAR, 0L)
                    + NETS.getOrDefault(AH, 0L)
                    + NETS.getOrDefault(LOWBALLING, 0L);
        }
    }

    public static long overallDisplay() {
        return Math.max(0L, overallNet());
    }

    public static long overallInvested() {
        return Math.max(0L, -overallNet());
    }

    public static Map<String, Long> nets() {
        synchronized (LOCK) {
            ensureLoaded();
            return new LinkedHashMap<>(NETS);
        }
    }

    public static void startPurseWatch(String module) {
        long purse = com.coflnet.flipper.trading.ScreenUtil.readPurse();
        if (purse > 0) {
            PURSE_BEFORE.put(module, purse);
        }
    }

    public static void finishPurseWatch(String module) {
        Long before = PURSE_BEFORE.remove(module);
        if (before == null || before <= 0) {
            return;
        }
        long after = com.coflnet.flipper.trading.ScreenUtil.readPurse();
        if (after > before) {
            receive(module, after - before);
        }
    }

    private static void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(VERSION_KEY, FORMAT_VERSION);
            out.putAll(NETS);
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(out), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[Profit] Save failed: " + e.getMessage());
        }
    }

    public static String formatSigned(long coins) {
        String value = com.coflnet.flipper.trading.ScreenUtil.formatCoins(Math.abs(coins));
        return coins >= 0 ? "+" + value : "-" + value;
    }

    public static String moduleLabel(String module) {
        return switch (module) {
            case BAZAAR -> "Bazaar";
            case AH -> "AH-Flipper";
            case LOWBALLING -> "Lowballing";
            default -> module == null ? "?" : module;
        };
    }
}
