package com.coflnet.ahflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AhFlipperConfig {
    public int maxAuctions = 14;
    public long reserveCoins = 1_000_000L;
    public double ahFeePercent = 5.0;
    public double minProfitPercent = 10.0;
    public double minDiscountPercent = 20.0;
    public double craftCostTolerancePercent = 10.0;
    public long minItemCoins = 2_000_000L;
    public int addonMinSalesPerWeek = 5;
    public int cleanShortWindowMinSales = 5;
    public long comparableAddonMinCoins = 500_000L;
    public int comparableMinSalesPerWeek = 2;
    public int comparableTrustSales = 10;
    public double comparableWeightPercent = 70.0;
    public double comparableOutlierPercent = 30.0;
    public double comparableAddonSharePercent = 10.0;
    public double addonLossPercent = 4.0;
    public double comparableLossPercent = 6.0;
    public double minAddonValueMillions = 3.0;
    public long addonMinValueCoins = 3_000_000L;
    public double addonMinBazaarVolume = 5.0;
    public double enchantBazaarValueThresholdMillions = 3.0;
    public double maxDeviationPercent = 60.0;
    public double cvOutlierPercent = 50.0;
    public int minTotalSales = 120;
    public int minCleanSales = 60;
    public long cleanSalesAddonValueCoins = 15_000_000L;
    public int petLevel1MinSales = 120;
    public int petLevelMinSales = 30;
    public int analysisCacheMinutes = 2;
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
    public int livePollMs = 1000;
    public int buyClickMinMs = 25;
    public int buyClickMaxMs = 33;
    public int buyClickMaxAttempts = 40;
    public double minOrderCoins = 1_000_000.0;
    public double maxBankFractionPerOrder = 0.8;
    public boolean autoList = true;
    public String listDuration = "2 days";
    public int coflWorthMultiplier = 1000;
    public boolean coflStream = false;
    public boolean liveFeed = true;
    public int livePollSeconds = 1;
    public long liveMinPrice = 2_000_000L;
    public double liveMaxBankrollFraction = 0.8;
    public boolean dashboard = true;
    public boolean tooltipValue = true;
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
    public int sessionMinutes = 0;
    public int pauseMinutes = 5;
    public List<String> blacklist = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("ahflipper.json");
    }

    public static AhFlipperConfig load() {
        try {
            Path path = file();
            if (Files.exists(path)) {
                AhFlipperConfig loaded = GSON.fromJson(Files.readString(path), AhFlipperConfig.class);
                if (loaded != null) {
                    if (loaded.maxAuctions > 14) {
                        loaded.maxAuctions = 14;
                    }
                    if (loaded.petLevel1MinSales <= 0 || loaded.petLevel1MinSales == 200
                            || loaded.petLevel1MinSales == 150) {
                        loaded.petLevel1MinSales = 120;
                    }
                    if (loaded.petLevelMinSales <= 0) {
                        loaded.petLevelMinSales = 30;
                    }
                    if (loaded.cvOutlierPercent <= 0) {
                        loaded.cvOutlierPercent = 50.0;
                    }
                    if (loaded.minTotalSales <= 0 || loaded.minTotalSales == 200
                            || loaded.minTotalSales == 150) {
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
                    if (loaded.comparableAddonMinCoins <= 0
                            || loaded.comparableAddonMinCoins == 1_000_000L) {
                        loaded.comparableAddonMinCoins = 500_000L;
                    }
                    if (loaded.comparableMinSalesPerWeek <= 0
                            || loaded.comparableMinSalesPerWeek == 10
                            || loaded.comparableMinSalesPerWeek == 5) {
                        loaded.comparableMinSalesPerWeek = 2;
                    }
                    if (loaded.comparableTrustSales <= 0
                            || loaded.comparableTrustSales == 30) {
                        loaded.comparableTrustSales = 10;
                    }
                    if (loaded.comparableWeightPercent <= 0
                            || loaded.comparableWeightPercent > 100) {
                        loaded.comparableWeightPercent = 70.0;
                    }
                    if (loaded.comparableOutlierPercent <= 0) {
                        loaded.comparableOutlierPercent = 30.0;
                    }
                    if (loaded.comparableAddonSharePercent <= 0
                            || loaded.comparableAddonSharePercent == 30.0) {
                        loaded.comparableAddonSharePercent = 10.0;
                    }
                    if (loaded.addonLossPercent < 0) {
                        loaded.addonLossPercent = 4.0;
                    }
                    if (loaded.comparableLossPercent < 0) {
                        loaded.comparableLossPercent = 6.0;
                    }
                    if (loaded.minItemCoins <= 0 || loaded.minItemCoins == 4_000_000L) {
                        loaded.minItemCoins = 2_000_000L;
                    }
                    if (loaded.craftCostTolerancePercent < 0) {
                        loaded.craftCostTolerancePercent = 0;
                    }
                    if (loaded.craftCostTolerancePercent > loaded.minDiscountPercent) {
                        loaded.craftCostTolerancePercent = loaded.minDiscountPercent;
                    }
                    if (loaded.liveMinPrice <= 0 || loaded.liveMinPrice == 4_000_000L) {
                        loaded.liveMinPrice = 2_000_000L;
                    }
                    if (loaded.analysisCacheMinutes <= 0 || loaded.analysisCacheMinutes > 2) {
                        loaded.analysisCacheMinutes = 2;
                    }
                    if (loaded.selectorIntervalMs <= 0 || loaded.selectorIntervalMs > 10) {
                        loaded.selectorIntervalMs = 10;
                    }
                    if (loaded.buyButtonPollMs <= 0 || loaded.buyButtonPollMs > 10) {
                        loaded.buyButtonPollMs = 10;
                    }
                    if (loaded.buyButtonPollMax != 1000) {
                        loaded.buyButtonPollMax = 1000;
                    }
                    if (loaded.livePollMs < 250) {
                        loaded.livePollMs = 1000;
                    }
                    if (loaded.livePollMs > 10_000) {
                        loaded.livePollMs = 10_000;
                    }
                    loaded.buyClickMinMs = 25;
                    loaded.buyClickMaxMs = 33;
                    if (loaded.buyClickMaxAttempts <= 0) {
                        loaded.buyClickMaxAttempts = 40;
                    }
                    if (loaded.addonMinBazaarVolume <= 0
                            || loaded.addonMinBazaarVolume == 10.0) {
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
                    return loaded;
                }
            }
        } catch (Exception ignored) {
        }
        return new AhFlipperConfig();
    }

    public void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
