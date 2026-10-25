package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AhScanner {
    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();
    private static volatile boolean running;
    private static volatile Thread worker;
    private static volatile long cycleStartedAt;
    private static volatile long cycleFinishedAt;
    private static volatile int cycleTags;
    private static volatile int cycleChecked;
    private static volatile int cycleHits;
    private static volatile int totalHits;

    private AhScanner() {
    }

    static void start() {
        if (running) {
            return;
        }
        running = true;
        Thread thread = new Thread(AhScanner::loop, "skycofl-ah-scan");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        System.out.println("[AH-Flipper] Scanner gestartet");
    }

    static void stop() {
        running = false;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        System.out.println("[AH-Flipper] Scanner gestoppt");
    }

    static boolean isRunning() {
        return running;
    }

    static String status() {
        if (!running && cycleFinishedAt == 0) {
            return "§caus";
        }
        long age = cycleFinishedAt > 0
                ? (System.currentTimeMillis() - cycleFinishedAt) / 1000 : -1;
        return (running ? "§aan" : "§caus")
                + " §7· Zyklus §f" + cycleTags + " §7Tags / §f" + cycleChecked
                + " §7geprüft / §f" + cycleHits + " §7Treffer"
                + " §7· gesamt §f" + totalHits
                + (age >= 0 ? " §7· vor §f" + age + "s" : "");
    }

    private static void loop() {
        try {
            while (running) {
                if (!ScreenUtil.onSkyBlock()) {
                    ScreenUtil.sleep(5000);
                    continue;
                }
                try {
                    runCycle();
                } catch (Exception e) {
                    System.out.println("[AH-Flipper] Scan-Fehler: " + e);
                    ScreenUtil.sleep(5000);
                }
                if (!running) {
                    break;
                }
                ScreenUtil.sleep(2000);
            }
        } finally {
            running = false;
            worker = null;
        }
    }

    private static void runCycle() {
        Map<String, AhMarketData.MarketEntry> market = AhMarketData.market();
        if (market.isEmpty()) {
            System.out.println("[AH-Flipper] Scan: keine Marktpreise verfügbar");
            return;
        }
        List<AhMarketData.MarketEntry> tags = new ArrayList<>();
        for (AhMarketData.MarketEntry entry : market.values()) {
            if (entry.value() < AhFlipperMod.CONFIG.minItemCoins) {
                continue;
            }
            if (entry.volume < AhFlipperMod.CONFIG.scanMinVolume) {
                continue;
            }
            tags.add(entry);
        }
        tags.sort((a, b) -> Double.compare(priority(b), priority(a)));
        int limit = Math.max(1, AhFlipperMod.CONFIG.scanMaxTags);
        if (tags.size() > limit) {
            tags = new ArrayList<>(tags.subList(0, limit));
        }
        cycleTags = tags.size();
        cycleChecked = 0;
        cycleHits = 0;
        cycleStartedAt = System.currentTimeMillis();
        System.out.println("[AH-Flipper] Scan-Zyklus: " + tags.size() + " Tags von "
                + market.size() + " Marktpreisen");

        long purse = ScreenUtil.readPurse();
        long bankroll = purse > AhFlipperMod.CONFIG.reserveCoins
                ? purse - AhFlipperMod.CONFIG.reserveCoins : -1;
        long maxPrice = bankroll > 0
                ? Math.round(bankroll * AhFlipperMod.CONFIG.maxBankFractionPerOrder) : -1;
        int maxHits = Math.max(1, AhFlipperMod.CONFIG.scanMaxCandidatesPerCycle);

        for (AhMarketData.MarketEntry entry : tags) {
            if (!running || cycleHits >= maxHits) {
                break;
            }
            List<AhMarketData.ActiveBin> bins = AhMarketData.activeBins(entry.tag);
            cycleChecked++;
            if (bins != null) {
                checkBins(entry, bins, maxPrice, maxHits);
            }
            ScreenUtil.sleep(Math.max(250, AhFlipperMod.CONFIG.scanRequestDelayMs));
        }

        cycleFinishedAt = System.currentTimeMillis();
        System.out.println("[AH-Flipper] Scan-Zyklus fertig: " + cycleChecked + " Tags, "
                + cycleHits + " Treffer, " + (cycleFinishedAt - cycleStartedAt) / 1000 + "s");
    }

    private static void checkBins(AhMarketData.MarketEntry entry,
                                  List<AhMarketData.ActiveBin> bins, long maxPrice, int maxHits) {
        long fee = Math.round(entry.value()
                * (AhFlipperMod.CONFIG.ahFeePercent / 100.0));
        for (int i = 0; i < bins.size(); i++) {
            if (!running || cycleHits >= maxHits) {
                return;
            }
            AhMarketData.ActiveBin bin = bins.get(i);
            if (AhFlipperMod.isOwnSeller(bin.seller(), bin.playerName())) {
                continue;
            }
            if (bin.price() < AhFlipperMod.CONFIG.minItemCoins) {
                continue;
            }
            if (maxPrice > 0 && bin.price() > maxPrice) {
                continue;
            }
            long estimatedProfit = entry.value() - fee - bin.price();
            double estimatedPercent = bin.price() > 0
                    ? estimatedProfit * 100.0 / bin.price() : -1;
            if (estimatedPercent < AhFlipperMod.CONFIG.minProfitPercent) {
                continue;
            }
            AhMarketData.Analysis clean = AhMarketData.analysis(entry.tag, "&Clean=true");
            if (clean == null
                    || clean.totalSales < AhFlipperMod.CONFIG.minCleanSales
                    || clean.coeffVariation > AhFlipperMod.CONFIG.maxDeviationPercent / 100.0) {
                continue;
            }
            long sevenDayAverage = clean.avgPrice > 0
                    ? Math.round(clean.avgPrice) : clean.medianPrice;
            long cleanAverage = AhMarketData.lowestCleanAverage(entry.tag, 0, "",
                    sevenDayAverage);
            long cleanBin = AhMarketData.binReference(entry.tag, bin.uuid());
            long reference = AhMarketData.lowestReference(cleanAverage, cleanBin);
            if (reference > 0) {
                cleanAverage = reference;
            }
            long cleanValue = cleanAverage > 0 ? cleanAverage : entry.value();
            long cleanProfit = cleanValue
                    - Math.round(cleanValue * (AhFlipperMod.CONFIG.ahFeePercent / 100.0))
                    - bin.price();
            double cleanPercent = bin.price() > 0 ? cleanProfit * 100.0 / bin.price() : -1;
            if (cleanPercent < AhFlipperMod.CONFIG.minProfitPercent) {
                continue;
            }
            if (seenRecently(bin.uuid())) {
                continue;
            }
            SEEN.put(bin.uuid(), System.currentTimeMillis());
            cycleHits++;
            totalHits++;
            AhFlipperMod.scanHit(bin.uuid(), entry.tag, cleanValue, bin.price(),
                    cleanProfit, cleanPercent);
            return;
        }
    }

    private static boolean seenRecently(String uuid) {
        long now = System.currentTimeMillis();
        Long at = SEEN.get(uuid);
        if (at != null && now - at < 2 * 60_000L) {
            return true;
        }
        if (SEEN.size() > 5000) {
            SEEN.entrySet().removeIf(entry -> now - entry.getValue() > 60 * 60_000L);
        }
        return false;
    }

    private static double priority(AhMarketData.MarketEntry entry) {
        return (double) entry.value() * Math.min(200.0, Math.max(1.0, entry.volume));
    }
}
