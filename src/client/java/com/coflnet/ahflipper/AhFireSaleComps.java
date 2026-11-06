package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Age-matched comparison for Fire Sale skins that are too new for the main
 * model. Question answered: "what did similar skins do when bought at the same
 * age and held for the same time?"
 *
 * <p>The old version compared the first and last price of old skins (often
 * +1000% over five years) and projected that onto brand-new skins, which made
 * fresh releases look like great buys. In reality recent Fire Sale skins lost
 * 35-55% in their first three months. This version measures the forward
 * return from the same age on, using comps released in the last three years.
 */
public final class AhFireSaleComps {
    private static final long DAY_MS = AhFireSaleModel.DAY_MS;
    /** Comps should come from the current market, not from 2020 skins. */
    private static final long COMP_RELEASE_WINDOW_MS = 3L * 365L * DAY_MS;

    public static final class CompRow {
        public String tag = "";
        public String name = "";
        public String tier = "";
        public String type = "";
        public long buyPrice;
        public long target;
        public double expectedRoiPercent;
        public int compCount;
        public double compMedianRoi;
        public double compP25Roi;
        public double compP75Roi;
        public double compWinRate;
        public int ageDays;
        public int holdingDays;
        public double confidence;
        public int listings;
        /** True when the comps say buying now is not worth it yet. */
        public boolean waitRecommended;
        public String note = "";
    }

    public static final class Snapshot {
        public volatile boolean running;
        public volatile long updatedAt;
        public volatile String error = "";
        public volatile int compPool;
        public volatile int newSkins;
        public volatile String note = "";
        public volatile List<CompRow> rows = List.of();
    }

    private record Comp(String type, String tier, long releasedAt,
                        List<AhFireSaleSkinTracker.HistoryPoint> points) {
    }

    private static final Snapshot SNAPSHOT = new Snapshot();
    private static volatile boolean running;

    private AhFireSaleComps() {
    }

    public static Snapshot snapshot() {
        return SNAPSHOT;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void runAsync() {
        if (running) {
            return;
        }
        Thread thread = new Thread(AhFireSaleComps::run, "skycofl-firesale-comps");
        thread.setDaemon(true);
        thread.start();
    }

    public static void run() {
        synchronized (AhFireSaleComps.class) {
            if (running) {
                return;
            }
            running = true;
        }
        SNAPSHOT.running = true;
        SNAPSHOT.error = "";
        SNAPSHOT.updatedAt = System.currentTimeMillis();
        try {
            AhFlipperConfig config = AhFlipperMod.CONFIG;
            double fee = Math.max(0, Math.min(100, config.ahFeePercent)) / 100.0;
            int hold = Math.max(7, Math.min(180, config.fireSaleHoldDays));
            int minAge = Math.max(30, config.fireSaleMinAgeDays);
            long now = System.currentTimeMillis();
            Map<String, AhFireSaleSkinTracker.SkinMeta> skinList =
                    AhFireSaleSkinTracker.fireSaleSkins();
            Map<String, AhMarketData.MarketEntry> market = AhMarketData.market();

            List<Comp> pool = new ArrayList<>();
            for (Map.Entry<String, AhFireSaleSkinTracker.SkinMeta> entry : skinList.entrySet()) {
                AhFireSaleSkinTracker.HistoryData history =
                        AhFireSaleSkinTracker.cachedHistory(entry.getKey());
                if (history == null || history.full().size() < 30) {
                    continue;
                }
                long released = history.full().get(0).time();
                pool.add(new Comp(entry.getValue().type(), entry.getValue().tier(),
                        released, history.full()));
            }
            List<Comp> recentPool = new ArrayList<>();
            for (Comp comp : pool) {
                if (now - comp.releasedAt() <= COMP_RELEASE_WINDOW_MS) {
                    recentPool.add(comp);
                }
            }
            List<Comp> basePool = recentPool.size() >= 5 ? recentPool : pool;
            SNAPSHOT.compPool = basePool.size();

            List<CompRow> rows = new ArrayList<>();
            int newSkins = 0;
            for (Map.Entry<String, AhFireSaleSkinTracker.SkinMeta> entry : skinList.entrySet()) {
                String tag = entry.getKey();
                AhFireSaleSkinTracker.SkinMeta meta = entry.getValue();
                AhFireSaleSkinTracker.HistoryData history =
                        AhFireSaleSkinTracker.cachedHistory(tag);
                long released = history != null && !history.full().isEmpty()
                        ? history.full().get(0).time() : 0;
                AhFireSaleHistoryStore.SkinRecord record = AhFireSaleHistoryStore.get(tag);
                if (released <= 0 && record != null) {
                    released = record.releaseGuessAt > 0 ? record.releaseGuessAt
                            : record.firstSeenAt;
                }
                if (released <= 0) {
                    continue;
                }
                int age = (int) Math.max(0, (now - released) / DAY_MS);
                if (age >= minAge) {
                    continue;
                }
                long buyPrice = 0;
                int listings = 0;
                if (record != null && record.snapshots != null && !record.snapshots.isEmpty()) {
                    AhFireSaleHistoryStore.Snapshot snapshot =
                            record.snapshots.get(record.snapshots.size() - 1);
                    buyPrice = snapshot.price;
                    listings = snapshot.listings;
                }
                if (buyPrice <= 0) {
                    AhMarketData.MarketEntry marketEntry = market.get(tag);
                    if (marketEntry != null) {
                        buyPrice = marketEntry.now > 0 ? marketEntry.now
                                : marketEntry.recent > 0 ? marketEntry.recent
                                : marketEntry.monthly;
                    }
                }
                if (buyPrice <= 0 || buyPrice < config.fireSaleMinPrice
                        || (config.fireSaleMaxPrice > 0 && buyPrice > config.fireSaleMaxPrice)) {
                    continue;
                }
                newSkins++;
                List<Double> rois = forwardReturns(basePool, meta, age, hold, fee);
                if (rois.size() < 3) {
                    continue;
                }
                rois.sort(null);
                double medianRoi = percentile(rois, 0.5);
                double p25 = percentile(rois, 0.25);
                double p75 = percentile(rois, 0.75);
                int wins = 0;
                for (double roi : rois) {
                    if (roi > 0) {
                        wins++;
                    }
                }
                double winRate = wins / (double) rois.size();
                // Comp ROIs are already net of the fee.
                long target = Math.round(buyPrice * (1.0 + medianRoi / 100.0) / (1.0 - fee));
                double dispersion = Math.abs(p75 - p25);
                double consistency = 1.0 - AhFireSaleModel.clamp(dispersion
                        / Math.max(20.0, Math.abs(medianRoi)), 0, 1);
                double compScore = AhFireSaleModel.clamp(rois.size() / 10.0, 0, 1);
                double confidence = 0.5 * compScore + 0.5 * consistency;

                CompRow row = new CompRow();
                row.tag = tag;
                row.name = meta.name();
                row.tier = meta.tier();
                row.type = meta.type();
                row.buyPrice = buyPrice;
                row.target = target;
                row.expectedRoiPercent = medianRoi;
                row.compCount = rois.size();
                row.compMedianRoi = medianRoi;
                row.compP25Roi = p25;
                row.compP75Roi = p75;
                row.compWinRate = winRate;
                row.ageDays = age;
                row.holdingDays = hold;
                row.confidence = confidence;
                row.listings = listings;
                row.waitRecommended = medianRoi < config.fireSaleCompsMinRoiPercent
                        || winRate < 0.6;
                row.note = String.format(Locale.US, "%dd alt · Win %.0f%% · %s",
                        age, winRate * 100, row.waitRecommended
                                ? "ABWARTEN (bis ~" + minAge + "d)" : "Kaufsignal");
                rows.add(row);
            }
            rows.sort((first, second) -> Double.compare(
                    second.expectedRoiPercent, first.expectedRoiPercent));
            int limit = Math.max(1, Math.min(10, config.fireSaleCompsTopCount));
            if (rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            SNAPSHOT.newSkins = newSkins;
            SNAPSHOT.rows = List.copyOf(rows);
            SNAPSHOT.note = "Gleiches Alter, " + hold + "d Haltedauer, Comps der letzten 3 Jahre";
            SNAPSHOT.updatedAt = System.currentTimeMillis();
        } catch (Exception e) {
            SNAPSHOT.error = e.getMessage() == null ? e.toString() : e.getMessage();
            System.out.println("[AH-Invest] Comps-Fehler: " + e);
        } finally {
            running = false;
            SNAPSHOT.running = false;
        }
    }

    /**
     * Net return of every comp bought at {@code age} days and sold {@code hold}
     * days later. Same type (pet / item skin) is preferred.
     */
    private static List<Double> forwardReturns(List<Comp> pool,
                                               AhFireSaleSkinTracker.SkinMeta meta,
                                               int age, int hold, double fee) {
        List<Double> sameType = new ArrayList<>();
        List<Double> all = new ArrayList<>();
        for (Comp comp : pool) {
            if (comp.points().isEmpty()) {
                continue;
            }
            Double roi = forwardReturn(comp, age, hold, fee);
            if (roi == null) {
                continue;
            }
            all.add(roi);
            if (meta != null && meta.type() != null && meta.type().equals(comp.type())) {
                sameType.add(roi);
            }
        }
        return sameType.size() >= 5 ? sameType : all;
    }

    private static Double forwardReturn(Comp comp, int age, int hold, double fee) {
        long start = comp.releasedAt() + age * DAY_MS;
        long end = start + hold * DAY_MS;
        long entry = priceAround(comp.points(), start);
        long exit = priceAround(comp.points(), end);
        if (entry <= 0 || exit <= 0) {
            return null;
        }
        return (exit * (1.0 - fee) - entry) * 100.0 / entry;
    }

    private static long priceAround(List<AhFireSaleSkinTracker.HistoryPoint> points, long at) {
        List<AhFireSaleSkinTracker.HistoryPoint> near = new ArrayList<>();
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            if (Math.abs(point.time() - at) <= 3 * DAY_MS) {
                near.add(point);
            }
        }
        return near.size() < 2 ? 0 : AhFireSaleModel.medianAvg(near);
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.round(AhFireSaleModel.clamp(fraction, 0, 1)
                * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
