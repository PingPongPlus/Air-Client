package com.coflnet.flipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FlipperConfig {
    public double taxPercent = 1.25;
    public int maxOrders = 14;
    public long reserveCoins = 1_000_000L;
    public double minMarginPercent = 0.5;
    public double maxMarginPercent = 25.0;
    public double minBuyOrderMarginPercent = 5.0;
    public double maxPriceDeviationPercent = 30.0;
    public double maxHistoricalDeviationPercent = 50.0;
    public double minHistoryVolume = 20.0;
    public boolean requireHistoricalPrice = true;
    public double referencePriceMillions = 10.0;
    public int sessionMinutes = 0;
    public int pauseMinutes = 5;
    public long minWeeklyVolume = 168L;
    public double minUnitCoins = 600_000.0;
    public double minOrderCoins = 10_000_000.0;
    public double maxBankFractionPerItem = 0.5;
    /**
     * Hard cap of coins put into ONE item (buy order value = qty x price).
     * 0 = no limit (only maxBankFractionPerItem applies). Items whose single
     * unit is more expensive than this are skipped.
     */
    public double maxCoinsPerItem = 0.0;
    public double minUnitPrice = 10.0;
    public double beta = 1.0;
    public double undercutCheckMinutes = 10.0;
    public double undercutSeedFactor = 0.05;
    public double activeHours = 4.0;
    public double limitBudgetCoins = 15_000_000_000.0;
    public double limitFillTarget = 0.85;
    public double blacklistMinutes = 5.0;
    public double maxLiquidityShare = 0.25;
    public double coinsPerHourWeight = 1.6;
    public double minCoinsPerHour = 0.0;
    public double profitPercentWeight = 1.4;
    public int cycleIntervalSeconds = 15;
    public int apiRefreshSeconds = 5;
    public int maxNewOrdersPerCycle = 3;
    public int outbidCyclesBeforeCancel = 2;
    public double cancelOrderAgeDays = 6.0;
    public double kellyLossVolatilityPercent = 2.0;
    public double stakeMultiplier = 2.0;
    public List<String> blacklist = new ArrayList<>();
    /** Sell offers worth this much per session (millions, 0 = off), then only selling. */
    public double maxCoinsPerSessionMillions = 0;
    /** Buy orders + sell offers on the Bazaar together at most (millions, 0 = off). */
    public double maxInvestedMillions = 0;
    /** Item mode: flip only the items in {@link #onlyItems}. */
    public boolean onlyItemsMode = false;
    public List<String> onlyItems = new ArrayList<>();
    public Map<String, String> searchNameOverrides = new HashMap<>();
    public boolean bazaarPanelEnabled = false;
    public boolean bazaarPanelGlass = true;
    public boolean bazaarPanelEdges = false;
    public int bazaarPanelRows = 5;
    public boolean bazaarPanelFooter = true;
    public int configVersion = 0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_CONFIG_VERSION = 2;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("flipper.json");
    }

    public boolean isBlocked(String productId, String displayName) {
        if (blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        String id = normalize(productId);
        String name = normalize(displayName);
        for (String entry : blacklist) {
            String blocked = normalize(entry);
            if (blocked.isEmpty()) {
                continue;
            }
            if ((!id.isEmpty() && id.contains(blocked))
                    || (!name.isEmpty() && name.contains(blocked))) {
                return true;
            }
        }
        return false;
    }

    public boolean addBlocked(String value) {
        String display = value == null ? "" : value.trim();
        if (display.isEmpty()) {
            return false;
        }
        if (blacklist == null) {
            blacklist = new ArrayList<>();
        }
        String normalized = normalize(display);
        for (String entry : blacklist) {
            if (normalize(entry).equals(normalized)) {
                return false;
            }
        }
        blacklist.add(display);
        return true;
    }

    public boolean addOnlyItem(String value) {
        String display = value == null ? "" : value.trim();
        if (display.isEmpty()) {
            return false;
        }
        if (onlyItems == null) {
            onlyItems = new ArrayList<>();
        }
        String normalized = normalize(display);
        for (String entry : onlyItems) {
            if (normalize(entry).equals(normalized)) {
                return false;
            }
        }
        onlyItems.add(display);
        return true;
    }

    public boolean removeOnlyItem(String value) {
        if (onlyItems == null || value == null) {
            return false;
        }
        String normalized = normalize(value);
        return onlyItems.removeIf(entry -> normalize(entry).equals(normalized));
    }

    public boolean removeBlocked(String value) {
        if (blacklist == null || value == null) {
            return false;
        }
        String normalized = normalize(value);
        return blacklist.removeIf(entry -> normalize(entry).equals(normalized));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "").trim().toLowerCase(Locale.ROOT);
    }

    public static FlipperConfig load() {
        try {
            Path path = file();
            if (Files.exists(path)) {
                FlipperConfig loaded;
                try {
                    loaded = GSON.fromJson(Files.readString(path), FlipperConfig.class);
                } catch (Exception parseError) {
                    Path broken = path.resolveSibling(path.getFileName()
                            + ".broken-" + System.currentTimeMillis());
                    try {
                        Files.copy(path, broken,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {
                    }
                    System.out.println("[Flipper] Config defekt, Backup: " + broken
                            + " (" + parseError.getMessage() + ")");
                    return new FlipperConfig();
                }
                if (loaded != null) {
                    if (loaded.apiRefreshSeconds > 5) {
                        loaded.apiRefreshSeconds = 5;
                    }
                    if (loaded.cycleIntervalSeconds > 15) {
                        loaded.cycleIntervalSeconds = 15;
                    }
                    if (loaded.maxOrders > 14) {
                        loaded.maxOrders = 14;
                    }
                    if (loaded.maxNewOrdersPerCycle < 1) {
                        loaded.maxNewOrdersPerCycle = 3;
                    }
                    if (loaded.minUnitCoins < 0) {
                        loaded.minUnitCoins = 0;
                    }
                    if (loaded.minOrderCoins < 0) {
                        loaded.minOrderCoins = 0;
                    }
                    if (loaded.maxBankFractionPerItem <= 0 || loaded.maxBankFractionPerItem > 1) {
                        loaded.maxBankFractionPerItem = 0.5;
                    }
                    if (loaded.maxCoinsPerItem < 0) {
                        loaded.maxCoinsPerItem = 0.0;
                    }
                    if (loaded.beta <= 0) {
                        loaded.beta = 1.0;
                    }
                    if (loaded.undercutCheckMinutes <= 0) {
                        loaded.undercutCheckMinutes = 10.0;
                    }
                    if (loaded.blacklistMinutes <= 0) {
                        loaded.blacklistMinutes = 5.0;
                    }
                    if (loaded.maxHistoricalDeviationPercent < 10.0) {
                        loaded.maxHistoricalDeviationPercent = 50.0;
                    }
                    if (loaded.minHistoryVolume < 1.0) {
                        loaded.minHistoryVolume = 20.0;
                    }
                    if (loaded.coinsPerHourWeight <= 0) {
                        loaded.coinsPerHourWeight = 1.6;
                    }
                    if (!Double.isFinite(loaded.minCoinsPerHour) || loaded.minCoinsPerHour < 0) {
                        loaded.minCoinsPerHour = 0.0;
                    }
                    if (loaded.profitPercentWeight <= 0) {
                        loaded.profitPercentWeight = 1.4;
                    }
                    if (loaded.blacklist == null) {
                        loaded.blacklist = new ArrayList<>();
                    }
                    loaded.bazaarPanelRows = Math.max(1,
                            Math.min(5, loaded.bazaarPanelRows));
                    loaded.configVersion = CURRENT_CONFIG_VERSION;
                    return loaded;
                }
            }
        } catch (Exception ignored) {
        }
        FlipperConfig defaults = new FlipperConfig();
        defaults.configVersion = CURRENT_CONFIG_VERSION;
        return defaults;
    }

    public void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(this),
                    java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[Flipper] Config speichern fehlgeschlagen: "
                    + e.getMessage());
        }
    }
}
