package com.coflnet.ahflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AhFlipperConfig {
    public int maxAuctions = 14;
    public long reserveCoins = 1_000_000L;
    public long maxBankrollCoins = 0L;
    public double ahFeePercent = 5.0;
    public double minProfitPercent = 10.0;
    public double minDiscountPercent = 20.0;
    public double craftCostTolerancePercent = 10.0;
    public long minItemCoins = 2_000_000L;
    public int addonMinSalesPerWeek = 4;
    public int cleanShortWindowMinSales = 5;
    public long comparableAddonMinCoins = 500_000L;
    public long reforgeMatchMinCoins = 1_000_000L;
    public int comparableMinSalesPerWeek = 2;
    public int comparableTrustSales = 10;
    public double comparableWeightPercent = 70.0;
    public double comparableWeightCurve = 1.5;
    public double comparableOutlierPercent = 30.0;
    public double comparableAddonSharePercent = 10.0;
    public double addonLossPercent = 4.0;
    public double minAddonValueMillions = 3.0;
    public long addonMinValueCoins = 3_000_000L;
    public double addonMinBazaarVolume = 5.0;
    public double enchantBazaarValueThresholdMillions = 3.0;
    public double maxDeviationPercent = 100.0;
    public double cvOutlierPercent = 50.0;
    public int minTotalSales = 120;
    public int minCleanSales = 60;
    public long cleanSalesAddonValueCoins = 15_000_000L;
    public int analysisCacheMinutes = 2;
    public int coflLbinMinCount = 2;
    /** BINs from the LBin up to this much above it confirm the LBin (min coflLbinMinCount). */
    public double coflLbinMatchPercent = 5.0;
    /** No active BIN at the Cofl LBin (+-match%): look this far below/above for the real one. */
    public double coflLbinSearchPercent = 5.0;
    /** Items with an addon comparison: only BINs this close to the Cofl LBin count. */
    public double coflLbinExactMatchPercent = 1.0;
    /** Share of a rarely sold addon's value that is taken off the item value. */
    public double rareAddonDeductPercent = 90.0;
    /** Reforges are only checked for sales when their reforge stone costs at least this. */
    public long reforgeStoneCheckMinCoins = 1_000_000L;
    /** Pets with candies are worth this much less. */
    public double petCandyDiscountPercent = 10.0;
    /** Pets in the AH flipper (live scanner, bid macro, buy). */
    public boolean petsEnabled = true;
    public double scoreCleanWeight = 0.40;
    public double scoreSalesWeight = 0.25;
    public double scoreVolumeWeight = 0.20;
    public double scoreStabilityWeight = 0.15;
    public int scoreSalesTarget = 100;
    public int scoreVolumeTarget = 500;
    public int maxSoldPages = 3;
    public int autoBuyBatchSeconds = 0;
    public int autoBuyMaxAgeSeconds = 20;
    public int selectorIntervalMs = 10;
    public int buyButtonPollMs = 10;
    public int buyButtonPollMax = 1000;
    public int livePollMs = 250;
    public int buyClickMinMs = 25;
    public int buyClickMaxMs = 33;
    public int buyClickMaxAttempts = 40;
    public double minOrderCoins = 1_000_000.0;
    public double maxBankFractionPerOrder = 0.8;
    public boolean autoList = true;
    public String listDuration = "2 days";
    public int coflWorthMultiplier = 1000;
    public boolean autoBuy = false;
    public boolean debugAnalyze = false;
    public boolean liveFeed = true;
    public int livePollSeconds = 1;
    public long liveMinPrice = 2_000_000L;
    public double liveMaxBankrollFraction = 0.8;
    public boolean dashboard = true;
    public boolean tooltipValue = true;
    public boolean tooltipDetails = true;
    public int dashboardX = 6;
    public int dashboardY = 6;
    public int dashboardMaxRows = 8;
    public boolean autoScan = false;
    public double scanMinVolume = 5.0;
    public int scanMaxTags = 350;
    public long scanRequestDelayMs = 1050L;
    public int scanActiveCacheSeconds = 25;
    public int scanPriceRefreshMinutes = 10;
    public int scanMaxCandidatesPerCycle = 8;
    public boolean bidMacro = false;
    public double bidMacroMinProfitPercent = 15.0;
    public long bidMacroMinValueCoins = 3_000_000L;
    public int bidMacroMaxRemainingMinutes = 120;
    public int bidMacroScanIntervalSeconds = 60;
    public int bidMacroMaxCandidatesPerScan = 15;
    public int bidMacroMaxBidsPerScan = 15;
    public double bidMacroMaxBankrollFraction = 0.8;
    public boolean bidMacroAutoList = true;
    public boolean investmentGui = true;
    public long investmentMaxBuyPrice = 100_000L;
    public long investmentMinPrice = 1_000L;
    public int investmentMaxTags = 300;
    public int investmentTagDelayMs = 250;
    public int investmentRefreshMinutes = 15;
    public int investmentMinSales = 20;
    public int investmentMinHourSamples = 2;
    public double investmentSlippagePercent = 5.0;
    public double investmentMinMarginPercent = 5.0;
    public double investmentMaxBuyPremiumPercent = 35.0;
    public long investmentMinCoinsPerHour = 1_000L;
    public int investmentMaxQty = 10;
    public long investmentBankrollCoins = 0L;
    public int investmentTopCount = 5;
    public boolean fireSaleSkins = false;
    public long fireSaleMinPrice = 1_000_000L;
    public long fireSaleMaxPrice = 10_000_000_000L;
    public int fireSaleMaxTags = 120;
    public int fireSaleTagDelayMs = 250;
    public int fireSaleRefreshMinutes = 30;
    /** Minimum sales in the last 30 days. */
    public int fireSaleMinSales = 5;
    public double fireSaleMinRoiPercent = 15.0;
    /** @deprecated momentum turned out to be a negative signal; no longer used as filter. */
    @Deprecated
    public double fireSaleMinTrendPercent = 0.0;
    public double fireSaleMinConfidence = 0.45;
    /**
     * Minimum days since the first AH sale of a skin. Fresh Fire Sale skins
     * keep falling for months (-35..-55% within 90 days in the data).
     */
    public int fireSaleMinAgeDays = 120;
    /** Planned holding period for the target price. */
    public int fireSaleHoldDays = 45;
    public double fireSaleSlippagePercent = 5.0;
    public int fireSaleMaxQty = 3;
    public int fireSaleTopCount = 5;
    public int fireSaleBacktestMinSignals = 8;
    public double fireSaleBacktestFillProbability = 0.60;
    public int fireSaleNewSkinDays = 60;
    public int fireSaleCompsTopCount = 5;
    public double fireSaleCompsMinRoiPercent = 10.0;
    public int sessionMinutes = 0;
    public int pauseMinutes = 5;
    public int configVersion = 0;
    public boolean ahPanelEnabled = true;
    public boolean ahPanelGlass = true;
    public boolean ahPanelEdges = false;
    public int ahPanelRows = 5;
    public boolean ahPanelFooter = true;
    public boolean bidsPanelEnabled = false;
    public boolean bidsPanelGlass = true;
    public boolean bidsPanelEdges = false;
    public int bidsPanelRows = 5;
    public boolean bidsPanelFooter = true;
    public boolean investmentPanelEnabled = true;
    public boolean investmentPanelGlass = true;
    public boolean investmentPanelEdges = false;
    public int investmentPanelRows = 5;
    public boolean investmentPanelFooter = true;
    public boolean profitPanelEnabled = true;
    public boolean profitPanelGlass = true;
    public boolean profitPanelEdges = false;
    public int profitPanelRows = 5;
    public boolean profitPanelFooter = true;
    public List<String> blacklist = new ArrayList<>();
    /** Bid macro never outbids these players and never bids where they are highest. */
    public List<String> dontOutbidPlayers = new ArrayList<>();
    /** Standard clients wait this long before each flip action (AH buy/bid, Bazaar order). */
    public long standardDelayMs = 1000L;
    public Map<String, String> blacklistTags = new ConcurrentHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_CONFIG_VERSION = 3;
    private static final long TEMP_BLOCK_MS = 5 * 60_000L;
    private static final Map<String, Long> TEMP_BLOCKED = new ConcurrentHashMap<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("ahflipper.json");
    }

    public static String baseTag(String tag) {
        if (tag == null) {
            return "";
        }
        String value = tag.trim().toUpperCase(Locale.ROOT);
        while (value.startsWith("STARRED_")) {
            value = value.substring("STARRED_".length());
        }
        return value;
    }

    public boolean isBlockedItem(String tag, String displayName) {
        String base = baseTag(tag);
        if (!base.isEmpty() && blacklistTags != null
                && blacklistTags.containsKey(base)) {
            return true;
        }
        if (isTemporarilyBlocked(displayName, tag)
                || (!base.isEmpty() && isTemporarilyBlocked(displayName, base))) {
            return true;
        }
        return isBlocked(displayName, tag);
    }

    public boolean toggleBlacklist(String tag, String displayName) {
        String base = baseTag(tag);
        if (base.isEmpty()) {
            return false;
        }
        if (blacklistTags == null) {
            blacklistTags = new ConcurrentHashMap<>();
        }
        if (blacklistTags.remove(base) != null) {
            return false;
        }
        blacklistTags.put(base, displayName == null || displayName.isBlank()
                ? base : displayName);
        return true;
    }

    @Deprecated
    public boolean isBlocked(String displayName, String tag) {
        if (isTemporarilyBlocked(displayName, tag)) {
            return true;
        }
        if (blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        String name = normalize(displayName);
        String itemTag = normalize(tag);
        for (String entry : blacklist) {
            String blocked = normalize(entry);
            if (blocked.isEmpty()) {
                continue;
            }
            if ((!name.isEmpty() && name.contains(blocked))
                    || (!itemTag.isEmpty() && itemTag.contains(blocked))) {
                return true;
            }
        }
        return false;
    }

    public static void blockTemporarily(String tag, String displayName) {
        String key = tempKey(tag, displayName);
        if (key.isEmpty()) {
            return;
        }
        TEMP_BLOCKED.put(key, System.currentTimeMillis() + TEMP_BLOCK_MS);
        System.out.println("[AH-Flipper] Temporär blockiert (5 Min): "
                + displayName + " [" + tag + "]");
    }

    public static boolean isTemporarilyBlocked(String displayName, String tag) {
        if (TEMP_BLOCKED.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        TEMP_BLOCKED.entrySet().removeIf(entry -> entry.getValue() <= now);
        Long until = TEMP_BLOCKED.get(tempKey(tag, displayName));
        return until != null && until > now;
    }

    private static String tempKey(String tag, String displayName) {
        String itemTag = normalize(tag);
        String name = normalize(displayName);
        if (itemTag.isEmpty() && name.isEmpty()) {
            return "";
        }
        return itemTag + "\u0000" + name;
    }

    public boolean addDontOutbid(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.matches("[A-Za-z0-9_]{2,16}")) {
            return false;
        }
        if (dontOutbidPlayers == null) {
            dontOutbidPlayers = new ArrayList<>();
        }
        for (String entry : dontOutbidPlayers) {
            if (entry.equalsIgnoreCase(name)) {
                return false;
            }
        }
        dontOutbidPlayers.add(name);
        AhProtectedBidders.warmUp();
        return true;
    }

    public boolean removeDontOutbid(String value) {
        if (dontOutbidPlayers == null || value == null) {
            return false;
        }
        return dontOutbidPlayers.removeIf(entry -> entry.equalsIgnoreCase(value.trim()));
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

    public static AhFlipperConfig load() {
        try {
            Path path = file();
            if (Files.exists(path)) {
                AhFlipperConfig loaded;
                try {
                    loaded = GSON.fromJson(Files.readString(path), AhFlipperConfig.class);
                } catch (Exception parseError) {
                    Path broken = path.resolveSibling(path.getFileName()
                            + ".broken-" + System.currentTimeMillis());
                    try {
                        Files.copy(path, broken, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {
                    }
                    System.out.println("[AH-Flipper] Config defekt, Backup: " + broken
                            + " (" + parseError.getMessage() + ")");
                    return new AhFlipperConfig();
                }
                if (loaded != null) {
                    if (loaded.configVersion < 2) {
                        loaded.investmentGui = true;
                        loaded.investmentPanelEnabled = true;
                        loaded.ahPanelEnabled = true;
                    }
                    if (loaded.configVersion < 3) {
                        // New Fire Sale model: the old defaults (1 sale, 20%
                        // confidence) let through skins without a real market.
                        loaded.fireSaleMinSales = Math.max(5, loaded.fireSaleMinSales);
                        loaded.fireSaleMinConfidence = Math.max(0.45,
                                loaded.fireSaleMinConfidence);
                    }
                    if (loaded.fireSaleMinAgeDays < 30) {
                        loaded.fireSaleMinAgeDays = 120;
                    }
                    if (loaded.fireSaleMinAgeDays > 730) {
                        loaded.fireSaleMinAgeDays = 730;
                    }
                    if (loaded.fireSaleHoldDays < 7 || loaded.fireSaleHoldDays > 180) {
                        loaded.fireSaleHoldDays = 45;
                    }
                    loaded.addonMinSalesPerWeek = Math.min(4,
                            Math.max(1, loaded.addonMinSalesPerWeek));
                    if (loaded.maxAuctions > 14) {
                        loaded.maxAuctions = 14;
                    }
                    if (loaded.cvOutlierPercent <= 0) {
                        loaded.cvOutlierPercent = 50.0;
                    }
                    if (loaded.maxDeviationPercent <= 0
                            || loaded.maxDeviationPercent == 60.0) {
                        loaded.maxDeviationPercent = 100.0;
                    }
                    if (loaded.minTotalSales <= 0) {
                        loaded.minTotalSales = 120;
                    }
                    if (loaded.minCleanSales <= 0) {
                        loaded.minCleanSales = 60;
                    }
                    if (loaded.cleanSalesAddonValueCoins <= 0) {
                        loaded.cleanSalesAddonValueCoins = 15_000_000L;
                    }
                    if (loaded.cleanShortWindowMinSales <= 0) {
                        loaded.cleanShortWindowMinSales = 5;
                    }
                    if (loaded.comparableAddonMinCoins <= 0) {
                        loaded.comparableAddonMinCoins = 500_000L;
                    }
                    if (loaded.reforgeMatchMinCoins <= 0) {
                        loaded.reforgeMatchMinCoins = 1_000_000L;
                    }
                    if (loaded.comparableMinSalesPerWeek <= 0) {
                        loaded.comparableMinSalesPerWeek = 2;
                    }
                    if (loaded.comparableTrustSales <= 0) {
                        loaded.comparableTrustSales = 10;
                    }
                    if (loaded.comparableWeightPercent <= 0
                            || loaded.comparableWeightPercent > 100) {
                        loaded.comparableWeightPercent = 70.0;
                    }
                    if (loaded.comparableWeightCurve <= 0) {
                        loaded.comparableWeightCurve = 1.5;
                    }
                    if (loaded.comparableOutlierPercent <= 0) {
                        loaded.comparableOutlierPercent = 30.0;
                    }
                    if (loaded.comparableAddonSharePercent <= 0) {
                        loaded.comparableAddonSharePercent = 10.0;
                    }
                    if (loaded.addonLossPercent < 0) {
                        loaded.addonLossPercent = 4.0;
                    }
                    if (loaded.minItemCoins <= 0) {
                        loaded.minItemCoins = 2_000_000L;
                    }
                    if (loaded.craftCostTolerancePercent < 0) {
                        loaded.craftCostTolerancePercent = 0;
                    }
                    if (loaded.craftCostTolerancePercent > loaded.minDiscountPercent) {
                        loaded.craftCostTolerancePercent = loaded.minDiscountPercent;
                    }
                    if (loaded.liveMinPrice <= 0) {
                        loaded.liveMinPrice = 2_000_000L;
                    }
                    if (loaded.analysisCacheMinutes <= 0) {
                        loaded.analysisCacheMinutes = 2;
                    }
                    if (loaded.coflLbinMinCount < 1) {
                        loaded.coflLbinMinCount = 2;
                    }
                    if (loaded.coflLbinMinCount > 5) {
                        loaded.coflLbinMinCount = 5;
                    }
                    // 1.0 was the old default and only counted BINs at the same price
                    if (loaded.coflLbinMatchPercent <= 0 || loaded.coflLbinMatchPercent == 1.0) {
                        loaded.coflLbinMatchPercent = 5.0;
                    }
                    if (loaded.coflLbinMatchPercent > 10) {
                        loaded.coflLbinMatchPercent = 10.0;
                    }
                    if (loaded.coflLbinExactMatchPercent <= 0
                            || loaded.coflLbinExactMatchPercent > 10) {
                        loaded.coflLbinExactMatchPercent = 1.0;
                    }
                    if (loaded.coflLbinSearchPercent <= 0) {
                        loaded.coflLbinSearchPercent = 5.0;
                    }
                    if (loaded.coflLbinSearchPercent > 15) {
                        loaded.coflLbinSearchPercent = 15.0;
                    }
                    if (loaded.rareAddonDeductPercent < 0 || loaded.rareAddonDeductPercent > 100) {
                        loaded.rareAddonDeductPercent = 90.0;
                    }
                    if (loaded.reforgeStoneCheckMinCoins <= 0) {
                        loaded.reforgeStoneCheckMinCoins = 1_000_000L;
                    }
                    if (loaded.petCandyDiscountPercent < 0 || loaded.petCandyDiscountPercent > 90) {
                        loaded.petCandyDiscountPercent = 10.0;
                    }
                    if (loaded.selectorIntervalMs <= 0) {
                        loaded.selectorIntervalMs = 10;
                    }
                    if (loaded.buyButtonPollMs <= 0) {
                        loaded.buyButtonPollMs = 10;
                    }
                    if (loaded.buyButtonPollMax <= 0) {
                        loaded.buyButtonPollMax = 1000;
                    }
                    if (loaded.livePollMs < 250) {
                        loaded.livePollMs = 250;
                    }
                    if (loaded.livePollMs > 10_000) {
                        loaded.livePollMs = 10_000;
                    }
                    if (loaded.buyClickMinMs < 1) {
                        loaded.buyClickMinMs = 25;
                    }
                    if (loaded.buyClickMaxMs < loaded.buyClickMinMs) {
                        loaded.buyClickMaxMs = Math.max(loaded.buyClickMinMs, 33);
                    }
                    if (loaded.buyClickMaxAttempts <= 0) {
                        loaded.buyClickMaxAttempts = 40;
                    }
                    if (loaded.addonMinBazaarVolume <= 0) {
                        loaded.addonMinBazaarVolume = 5.0;
                    }
                    if (loaded.maxBankFractionPerOrder <= 0
                            || loaded.maxBankFractionPerOrder == 0.5) {
                        loaded.maxBankFractionPerOrder = 0.8;
                    }
                    if (loaded.maxBankFractionPerOrder > 1.0) {
                        loaded.maxBankFractionPerOrder = 1.0;
                    }
                    if (loaded.liveMaxBankrollFraction <= 0
                            || loaded.liveMaxBankrollFraction == 0.5) {
                        loaded.liveMaxBankrollFraction = 0.8;
                    }
                    if (loaded.liveMaxBankrollFraction > 1.0) {
                        loaded.liveMaxBankrollFraction = 1.0;
                    }
                    if (loaded.maxBankrollCoins < 0) {
                        loaded.maxBankrollCoins = 0L;
                    }
                    if (loaded.blacklist == null) {
                        loaded.blacklist = new ArrayList<>();
                    }
                    if (loaded.blacklistTags == null) {
                        loaded.blacklistTags = new ConcurrentHashMap<>();
                    }
                    if (!Double.isFinite(loaded.bidMacroMinProfitPercent)
                            || loaded.bidMacroMinProfitPercent <= 0) {
                        loaded.bidMacroMinProfitPercent = 15.0;
                    }
                    if (loaded.bidMacroMinValueCoins <= 0) {
                        loaded.bidMacroMinValueCoins = 3_000_000L;
                    }
                    if (loaded.bidMacroMaxRemainingMinutes <= 0
                            || loaded.bidMacroMaxRemainingMinutes > 120) {
                        loaded.bidMacroMaxRemainingMinutes = 120;
                    }
                    if (loaded.bidMacroScanIntervalSeconds < 30) {
                        loaded.bidMacroScanIntervalSeconds = 60;
                    }
                    if (loaded.bidMacroMaxCandidatesPerScan <= 0
                            || loaded.bidMacroMaxCandidatesPerScan == 4) {
                        loaded.bidMacroMaxCandidatesPerScan = 15;
                    }
                    if (loaded.bidMacroMaxCandidatesPerScan > 15) {
                        loaded.bidMacroMaxCandidatesPerScan = 15;
                    }
                    if (loaded.bidMacroMaxBidsPerScan <= 0
                            || loaded.bidMacroMaxBidsPerScan == 3) {
                        loaded.bidMacroMaxBidsPerScan = 15;
                    }
                    if (loaded.bidMacroMaxBidsPerScan > 15) {
                        loaded.bidMacroMaxBidsPerScan = 15;
                    }
                    if (loaded.bidMacroMaxBankrollFraction <= 0
                            || loaded.bidMacroMaxBankrollFraction > 1.0) {
                        loaded.bidMacroMaxBankrollFraction = 0.8;
                    }
                    if (loaded.investmentMaxBuyPrice <= 0) {
                        loaded.investmentMaxBuyPrice = 100_000L;
                    }
                    if (loaded.investmentMinPrice < 0) {
                        loaded.investmentMinPrice = 1_000L;
                    }
                    if (loaded.investmentMaxTags <= 0) {
                        loaded.investmentMaxTags = 300;
                    }
                    if (loaded.investmentMaxTags > 400) {
                        loaded.investmentMaxTags = 400;
                    }
                    if (loaded.investmentTagDelayMs < 0) {
                        loaded.investmentTagDelayMs = 250;
                    }
                    if (loaded.investmentTagDelayMs > 2000) {
                        loaded.investmentTagDelayMs = 2000;
                    }
                    if (loaded.investmentRefreshMinutes < 2) {
                        loaded.investmentRefreshMinutes = 15;
                    }
                    if (loaded.investmentMinSales < 2) {
                        loaded.investmentMinSales = 20;
                    }
                    if (loaded.investmentMinHourSamples < 1) {
                        loaded.investmentMinHourSamples = 2;
                    }
                    if (loaded.investmentSlippagePercent <= 0
                            || loaded.investmentSlippagePercent > 25) {
                        loaded.investmentSlippagePercent = 5.0;
                    }
                    if (loaded.investmentMinMarginPercent < 0) {
                        loaded.investmentMinMarginPercent = 5.0;
                    }
                    if (loaded.investmentMaxBuyPremiumPercent < 0
                            || loaded.investmentMaxBuyPremiumPercent > 200) {
                        loaded.investmentMaxBuyPremiumPercent = 35.0;
                    }
                    if (loaded.investmentMinCoinsPerHour < 0) {
                        loaded.investmentMinCoinsPerHour = 1_000L;
                    }
                    if (loaded.investmentMaxQty < 1) {
                        loaded.investmentMaxQty = 10;
                    }
                    if (loaded.investmentMaxQty > 50) {
                        loaded.investmentMaxQty = 50;
                    }
                    if (loaded.investmentBankrollCoins < 0) {
                        loaded.investmentBankrollCoins = 0L;
                    }
                    if (loaded.investmentTopCount < 1) {
                        loaded.investmentTopCount = 5;
                    }
                    if (loaded.investmentTopCount > 10) {
                        loaded.investmentTopCount = 10;
                    }
                    if (loaded.fireSaleMinPrice < 0) {
                        loaded.fireSaleMinPrice = 1_000_000L;
                    }
                    if (loaded.fireSaleMaxPrice <= 0) {
                        loaded.fireSaleMaxPrice = 10_000_000_000L;
                    }
                    if (loaded.fireSaleMaxTags <= 0) {
                        loaded.fireSaleMaxTags = 120;
                    }
                    if (loaded.fireSaleMaxTags > 300) {
                        loaded.fireSaleMaxTags = 300;
                    }
                    if (loaded.fireSaleTagDelayMs < 0) {
                        loaded.fireSaleTagDelayMs = 250;
                    }
                    if (loaded.fireSaleTagDelayMs > 2000) {
                        loaded.fireSaleTagDelayMs = 2000;
                    }
                    if (loaded.fireSaleRefreshMinutes < 5) {
                        loaded.fireSaleRefreshMinutes = 30;
                    }
                    if (loaded.fireSaleMinSales < 1) {
                        loaded.fireSaleMinSales = 1;
                    }
                    if (loaded.fireSaleMinRoiPercent < 0) {
                        loaded.fireSaleMinRoiPercent = 15.0;
                    }
                    if (loaded.fireSaleMinTrendPercent < -5.0) {
                        loaded.fireSaleMinTrendPercent = 0.0;
                    }
                    if (loaded.fireSaleMinTrendPercent > 10.0) {
                        loaded.fireSaleMinTrendPercent = 10.0;
                    }
                    if (loaded.fireSaleMinConfidence < 0) {
                        loaded.fireSaleMinConfidence = 0.20;
                    }
                    if (loaded.fireSaleMinConfidence > 0.95) {
                        loaded.fireSaleMinConfidence = 0.95;
                    }
                    if (loaded.fireSaleSlippagePercent <= 0
                            || loaded.fireSaleSlippagePercent > 25) {
                        loaded.fireSaleSlippagePercent = 5.0;
                    }
                    if (loaded.fireSaleMaxQty < 1) {
                        loaded.fireSaleMaxQty = 3;
                    }
                    if (loaded.fireSaleMaxQty > 20) {
                        loaded.fireSaleMaxQty = 20;
                    }
                    if (loaded.fireSaleTopCount < 1) {
                        loaded.fireSaleTopCount = 5;
                    }
                    if (loaded.fireSaleTopCount > 10) {
                        loaded.fireSaleTopCount = 10;
                    }
                    if (loaded.fireSaleBacktestMinSignals < 2) {
                        loaded.fireSaleBacktestMinSignals = 8;
                    }
                    if (loaded.fireSaleBacktestMinSignals > 100) {
                        loaded.fireSaleBacktestMinSignals = 100;
                    }
                    if (loaded.fireSaleBacktestFillProbability < 0.05
                            || loaded.fireSaleBacktestFillProbability > 1.0) {
                        loaded.fireSaleBacktestFillProbability = 0.60;
                    }
                    if (loaded.fireSaleNewSkinDays < 7) {
                        loaded.fireSaleNewSkinDays = 60;
                    }
                    if (loaded.fireSaleCompsTopCount < 1) {
                        loaded.fireSaleCompsTopCount = 5;
                    }
                    if (loaded.fireSaleCompsTopCount > 10) {
                        loaded.fireSaleCompsTopCount = 10;
                    }
                    if (loaded.fireSaleCompsMinRoiPercent < 0) {
                        loaded.fireSaleCompsMinRoiPercent = 10.0;
                    }
                    loaded.ahPanelRows = Math.max(1, Math.min(5, loaded.ahPanelRows));
                    loaded.bidsPanelRows = Math.max(1, Math.min(5, loaded.bidsPanelRows));
                    loaded.investmentPanelRows = Math.max(1,
                            Math.min(5, loaded.investmentPanelRows));
                    loaded.profitPanelRows = Math.max(1,
                            Math.min(5, loaded.profitPanelRows));
                    loaded.configVersion = CURRENT_CONFIG_VERSION;
                    return loaded;
                }
            }
        } catch (Exception ignored) {
        }
        AhFlipperConfig defaults = new AhFlipperConfig();
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
            System.out.println("[AH-Flipper] Config speichern fehlgeschlagen: "
                    + e.getMessage());
        }
    }
}
