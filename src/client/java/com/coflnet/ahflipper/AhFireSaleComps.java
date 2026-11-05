package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Comparable-based analysis for newly released Fire Sale skins that do not
 * have enough own price history yet. Older skins with similar type/tier are
 * used as comps, like relative valuation in real investing.
 */
public final class AhFireSaleComps {
    private static final long DAY_MS = 24L * 60L * 60_000L;

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
        public int holdingDays;
        public double confidence;
        public int listings;
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

    private record CompStat(double roi, double ageDays, String type, String tier) {
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
        if (running) {
            return;
        }
        running = true;
        SNAPSHOT.running = true;
        SNAPSHOT.error = "";
        SNAPSHOT.updatedAt = System.currentTimeMillis();
        try {
            AhFlipperConfig config = AhFlipperMod.CONFIG;
            Map<String, AhFireSaleSkinTracker.SkinMeta> skinList =
                    AhFireSaleSkinTracker.fireSaleSkins();
            Map<String, AhMarketData.MarketEntry> market = AhMarketData.market();
            List<CompStat> pool = new ArrayList<>();
            Map<String, List<CompStat>> byTypeTier = new HashMap<>();
            Map<String, List<CompStat>> byType = new HashMap<>();
            for (Map.Entry<String, AhFireSaleSkinTracker.SkinMeta> entry : skinList.entrySet()) {
                String tag = entry.getKey();
                AhFireSaleSkinTracker.SkinMeta meta = entry.getValue();
                List<AhFireSaleSkinTracker.HistoryPoint> points = pointsForComps(tag);
                if (points.size() < 2) {
                    continue;
                }
                double first = points.get(0).avg();
                double last = points.get(points.size() - 1).avg();
                if (first <= 0 || last <= 0) {
                    continue;
                }
                double roi = (last - first) * 100.0 / first;
                double age = Math.max(1,
                        (points.get(points.size() - 1).time() - points.get(0).time())
                                / (double) DAY_MS);
                CompStat stat = new CompStat(roi, age, meta.type(), meta.tier());
                pool.add(stat);
                byTypeTier.computeIfAbsent(key(meta.type(), meta.tier()),
                        ignored -> new ArrayList<>()).add(stat);
                byType.computeIfAbsent(meta.type(), ignored -> new ArrayList<>()).add(stat);
            }
            SNAPSHOT.compPool = pool.size();

            long now = System.currentTimeMillis();
            List<CompRow> rows = new ArrayList<>();
            int newSkins = 0;
            for (Map.Entry<String, AhFireSaleSkinTracker.SkinMeta> entry : skinList.entrySet()) {
                String tag = entry.getKey();
                AhFireSaleSkinTracker.SkinMeta meta = entry.getValue();
                int historyPoints = pointsForComps(tag).size();
                boolean recentRelease = meta.releaseGuessAt() > 0
                        && now - meta.releaseGuessAt() < config.fireSaleNewSkinDays * DAY_MS;
                boolean newSkin = historyPoints < 2
                        || (recentRelease && historyPoints < 3);
                if (!newSkin) {
                    continue;
                }
                long buyPrice = 0;
                int listings = 0;
                AhFireSaleHistoryStore.SkinRecord record = AhFireSaleHistoryStore.get(tag);
                if (record != null && record.snapshots != null
                        && !record.snapshots.isEmpty()) {
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
                if (buyPrice < config.fireSaleMinPrice
                        || (config.fireSaleMaxPrice > 0 && buyPrice > config.fireSaleMaxPrice)
                        || listings <= 0) {
                    continue;
                }
                newSkins++;
                List<CompStat> comps = compsFor(meta, byTypeTier, byType, pool);
                if (comps.size() < 3) {
                    continue;
                }
                List<Double> rois = new ArrayList<>();
                List<Double> ages = new ArrayList<>();
                for (CompStat comp : comps) {
                    rois.add(comp.roi());
                    ages.add(comp.ageDays());
                }
                rois.sort(null);
                ages.sort(null);
                double medianRoi = median(rois);
                double p25 = percentile(rois, 0.25);
                double p75 = percentile(rois, 0.75);
                if (medianRoi <= 0) {
                    continue;
                }
                long target = Math.round(buyPrice * (1.0 + medianRoi / 100.0));
                target = Math.min(target, Math.round(buyPrice * (1.0 + p75 / 100.0)));
                target = Math.max(target, Math.round(buyPrice * 1.05));
                double netRoi = (target * 0.95 - buyPrice) * 100.0 / buyPrice;
                if (netRoi < config.fireSaleCompsMinRoiPercent) {
                    continue;
                }
                int holding = (int) Math.round(median(ages));
                holding = Math.max(7, Math.min(180, holding));
                double dispersion = Math.abs(p75 - p25);
                double consistency = 1.0 - clamp(dispersion
                        / Math.max(20.0, Math.abs(medianRoi)), 0, 1);
                double compScore = clamp(comps.size() / 10.0, 0, 1);
                double supply = 1.0 - clamp(listings / 20.0, 0, 1);
                double confidence = 0.5 * compScore + 0.3 * consistency + 0.2 * supply;
                if (confidence < 0.20) {
                    continue;
                }
                CompRow row = new CompRow();
                row.tag = tag;
                row.name = meta.name();
                row.tier = meta.tier();
                row.type = meta.type();
                row.buyPrice = buyPrice;
                row.target = target;
                row.expectedRoiPercent = netRoi;
                row.compCount = comps.size();
                row.compMedianRoi = medianRoi;
                row.compP25Roi = p25;
                row.compP75Roi = p75;
                row.holdingDays = holding;
                row.confidence = confidence;
                row.listings = listings;
                row.note = comps.size() + " Comps \u00b7 "
                        + (historyPoints < 2 ? "neu" : "wenig Historie");
                rows.add(row);
            }
            rows.sort((first, second) -> Double.compare(
                    second.expectedRoiPercent * second.confidence,
                    first.expectedRoiPercent * first.confidence));
            int limit = Math.max(1, Math.min(10, config.fireSaleCompsTopCount));
            if (rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            SNAPSHOT.newSkins = newSkins;
            SNAPSHOT.rows = List.copyOf(rows);
            SNAPSHOT.note = "Relative Bewertung \u00fcber \u00e4ltere Skins "
                    + "(verf\u00fcgbare Historie; persistente Historie verbessert die Comps)";
            SNAPSHOT.updatedAt = System.currentTimeMillis();
        } catch (Exception e) {
            SNAPSHOT.error = e.getMessage() == null ? e.toString() : e.getMessage();
            System.out.println("[AH-Invest] Comps-Fehler: " + e);
        } finally {
            running = false;
            SNAPSHOT.running = false;
        }
    }

    private static List<AhFireSaleSkinTracker.HistoryPoint> pointsForComps(String tag) {
        java.util.TreeMap<Long, AhFireSaleSkinTracker.HistoryPoint> byDay =
                new java.util.TreeMap<>();
        AhFireSaleSkinTracker.HistoryData history =
                AhFireSaleSkinTracker.cachedHistory(tag);
        if (history != null) {
            List<AhFireSaleSkinTracker.HistoryPoint> points =
                    history.month().size() >= 2 ? history.month() : history.week();
            for (AhFireSaleSkinTracker.HistoryPoint point : points) {
                byDay.put(point.time() / DAY_MS, point);
            }
        }
        AhFireSaleHistoryStore.SkinRecord record = AhFireSaleHistoryStore.get(tag);
        if (record != null && record.snapshots != null) {
            for (AhFireSaleHistoryStore.Snapshot snapshot : record.snapshots) {
                if (snapshot.at > 0 && snapshot.price > 0) {
                    byDay.put(snapshot.at / DAY_MS,
                            new AhFireSaleSkinTracker.HistoryPoint(snapshot.at,
                                    snapshot.price, snapshot.price, snapshot.price, 1));
                }
            }
        }
        return new ArrayList<>(byDay.values());
    }

    private static List<CompStat> compsFor(AhFireSaleSkinTracker.SkinMeta meta,
                                           Map<String, List<CompStat>> byTypeTier,
                                           Map<String, List<CompStat>> byType,
                                           List<CompStat> all) {
        List<CompStat> comps = byTypeTier.get(key(meta.type(), meta.tier()));
        if (comps != null && comps.size() >= 3) {
            return comps;
        }
        comps = byType.get(meta.type());
        if (comps != null && comps.size() >= 3) {
            return comps;
        }
        return all;
    }

    private static String key(String type, String tier) {
        return (type == null ? "" : type) + "|" + (tier == null ? "" : tier);
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.round(clamp(fraction, 0, 1) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
