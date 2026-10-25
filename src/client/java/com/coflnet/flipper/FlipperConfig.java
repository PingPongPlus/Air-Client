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
    public double profitPercentWeight = 1.4;
    public int cycleIntervalSeconds = 15;
    public int apiRefreshSeconds = 5;
    public int maxNewOrdersPerCycle = 3;
    public int outbidCyclesBeforeCancel = 2;
    public double cancelOrderAgeDays = 6.0;
    public double kellyLossVolatilityPercent = 2.0;
    public double stakeMultiplier = 2.0;
    public List<String> blacklist = new ArrayList<>();
    public Map<String, String> searchNameOverrides = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("flipper.json");
    }

    public static FlipperConfig load() {
        try {
            Path path = file();
            if (Files.exists(path)) {
                FlipperConfig loaded = GSON.fromJson(Files.readString(path), FlipperConfig.class);
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
                    if (loaded.maxNewOrdersPerCycle < 3) {
                        loaded.maxNewOrdersPerCycle = 3;
                    }
                    if (loaded.minWeeklyVolume == 2_000L) {
                        loaded.minWeeklyVolume = 168L;
                    }
                    if (loaded.minUnitCoins < 600_000.0) {
                        loaded.minUnitCoins = 600_000.0;
                    }
                    if (loaded.minOrderCoins < 10_000_000.0) {
                        loaded.minOrderCoins = 10_000_000.0;
                    }
                    if (loaded.maxBankFractionPerItem <= 0 || loaded.maxBankFractionPerItem > 1) {
                        loaded.maxBankFractionPerItem = 0.5;
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
                    if (loaded.profitPercentWeight <= 0) {
                        loaded.profitPercentWeight = 1.4;
                    }
                    return loaded;
                }
            }
        } catch (Exception ignored) {
        }
        return new FlipperConfig();
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
