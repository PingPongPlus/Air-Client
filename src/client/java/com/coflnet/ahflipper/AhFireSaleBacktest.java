package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backtests the Fire Sale skin rules on the available Cofl month/week history
 * and calibrates the filters. The persistent store will extend the history
 * over time for longer horizons.
 */
public final class AhFireSaleBacktest {
    private static final long DAY_MS = 24L * 60L * 60_000L;
    public static final int[] HORIZONS = {7, 14, 30, 90, 180};

    public static final class HorizonStats {
        public int days;
        public int allSignals;
        public int signals;
        public int wins;
        public double winRate;
        public double medianRoi;
        public double averageRoi;
        public double worstRoi;
        public double bestRoi;
        public int targetHits;
        public double targetHitRate;
        public double averageDaysToTarget;

        public String summary() {
            return String.format(Locale.US,
                    "%dd: %d Signale, Win %.0f%%, Median %.1f%%, Ø %.1f%%, schlechteste %.1f%%",
                    days, signals, winRate * 100, medianRoi, averageRoi, worstRoi);
        }
    }

    public static final class Recommendation {
        public double minRoiPercent;
        public double minTrendPercent;
        public int minSales;
        public double minConfidence;
        public int signals;
        public double winRate;
        public double medianRoi;
        public double averageRoi;
        public int calibrationHorizonDays;
        public boolean appliedDefaults = true;
    }

    public static final class Snapshot {
        public volatile boolean running;
        public volatile long updatedAt;
        public volatile String error = "";
        public volatile int skinCount;
        public volatile int signalCount;
        public volatile String outOfSample = "";
        public volatile String note = "";
        public final Map<Integer, HorizonStats> horizons = new java.util.concurrent.ConcurrentHashMap<>();
        public volatile Recommendation recommended;
    }

    private record Signal(long at, double predictedRoi, double trendPercent,
                          int monthlySales, double confidence, double actualRoi,
                          boolean targetHit, int daysToTarget) {
    }

    private static final Snapshot SNAPSHOT = new Snapshot();
    private static volatile boolean running;

    private AhFireSaleBacktest() {
    }

    public static Snapshot snapshot() {
        return SNAPSHOT;
    }

    public static boolean isRunning() {
        return running;
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
            Map<Integer, List<Signal>> signalsByHorizon = new HashMap<>();
            for (int horizon : HORIZONS) {
                signalsByHorizon.put(horizon, new ArrayList<>());
            }
            int skins = 0;
            Map<String, AhFireSaleSkinTracker.SkinMeta> skinList =
                    AhFireSaleSkinTracker.fireSaleSkins();
            for (String tag : skinList.keySet()) {
                List<AhFireSaleSkinTracker.HistoryPoint> points = pointsForBacktest(tag);
                if (points.size() < 2) {
                    continue;
                }
                skins++;
                for (int horizon : HORIZONS) {
                    List<Signal> signals = signalsByHorizon.get(horizon);
                    collectSignals(points, horizon, signals);
                }
            }
            SNAPSHOT.skinCount = skins;
            AhFlipperConfig config = AhFlipperMod.CONFIG;
            int calibrationHorizon = 30;
            for (int horizon : HORIZONS) {
                SNAPSHOT.horizons.put(horizon, stats(horizon,
                        signalsByHorizon.get(horizon), config));
                int count = signalsByHorizon.get(horizon).size();
                int best = signalsByHorizon.get(calibrationHorizon).size();
                if (count > best) {
                    calibrationHorizon = horizon;
                }
            }
            List<Signal> calibration = signalsByHorizon.get(calibrationHorizon);
            Recommendation recommendation = calibrate(calibration, config);
            if (recommendation != null) {
                recommendation.calibrationHorizonDays = calibrationHorizon;
            }
            SNAPSHOT.recommended = recommendation;
            SNAPSHOT.signalCount = recommendation == null ? 0 : recommendation.signals;
            SNAPSHOT.outOfSample = outOfSample(calibration, recommendation);
            SNAPSHOT.note = "Cofl Monats-/Wochenhistorie \u00b7 "
                    + skins + " Skins \u00b7 Ziel-Fill "
                    + Math.round(AhFlipperMod.CONFIG.fireSaleBacktestFillProbability * 100)
                    + "% \u00b7 historische Listings nicht verf\u00fcgbar";
            SNAPSHOT.updatedAt = System.currentTimeMillis();
        } catch (Exception e) {
            SNAPSHOT.error = e.getMessage() == null ? e.toString() : e.getMessage();
            System.out.println("[AH-Invest] Backtest-Fehler: " + e);
        } finally {
            running = false;
            SNAPSHOT.running = false;
        }
    }

    public static void runAsync() {
        if (running) {
            return;
        }
        Thread thread = new Thread(AhFireSaleBacktest::run, "skycofl-firesale-backtest");
        thread.setDaemon(true);
        thread.start();
    }

    private static List<AhFireSaleSkinTracker.HistoryPoint> pointsForBacktest(String tag) {
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

    private static void collectSignals(List<AhFireSaleSkinTracker.HistoryPoint> points,
                                       int horizon, List<Signal> out) {
        long first = points.get(0).time();
        long end = points.get(points.size() - 1).time();
        for (int index = 0; index < points.size(); index++) {
            AhFireSaleSkinTracker.HistoryPoint current = points.get(index);
            long futureTime = current.time() + horizon * DAY_MS;
            if (futureTime > end) {
                continue;
            }
            int futureIndex = -1;
            for (int next = index + 1; next < points.size(); next++) {
                if (points.get(next).time() >= futureTime) {
                    futureIndex = next;
                    break;
                }
            }
            if (futureIndex < 0) {
                continue;
            }
            List<AhFireSaleSkinTracker.HistoryPoint> past =
                    points.subList(0, index + 1);
            long historicalAverage = weightedAverage(past);
            if (historicalAverage <= 0 || current.avg() <= 0) {
                continue;
            }
            double trend = slopePerDay(past);
            long historicalMax = 0;
            for (AhFireSaleSkinTracker.HistoryPoint point : past) {
                historicalMax = Math.max(historicalMax, point.max());
            }
            long target = Math.max(Math.round(historicalAverage * 1.10),
                    current.avg() + Math.max(0, Math.round(trend * 30)));
            target = Math.max(target, Math.round(current.avg() * 1.10));
            target = Math.min(target, Math.round(historicalMax * 1.20));
            double predictedRoi = (target * 0.95 - current.avg()) * 100.0 / current.avg();
            double trendPercent = trend * 100.0 / historicalAverage;
            int monthlySales = monthlySales(past, current.time());
            int historyDays = Math.max(1, (int) ((current.time() - first) / DAY_MS) + 1);
            double cv = coefficientVariation(past, historicalAverage);
            double volumeScore = clamp(Math.log10(monthlySales + 1.0) / Math.log10(20.0), 0, 1);
            double historyScore = clamp(historyDays / 90.0, 0, 1);
            double stability = 1.0 - clamp(cv, 0, 1);
            double confidence = 0.35 * volumeScore + 0.25 * historyScore
                    + 0.20 * stability + 0.20 * 0.5;

            AhFireSaleSkinTracker.HistoryPoint exit = points.get(futureIndex);
            boolean targetHit = false;
            int daysToTarget = 0;
            for (int next = index + 1; next <= futureIndex; next++) {
                AhFireSaleSkinTracker.HistoryPoint point = points.get(next);
                if (point.avg() >= target) {
                    targetHit = true;
                    daysToTarget = Math.max(1,
                            (int) ((point.time() - current.time()) / DAY_MS));
                    break;
                }
            }
            double exitRoi = (exit.avg() * 0.95 - current.avg()) * 100.0 / current.avg();
            double targetRoi = (target * 0.95 - current.avg()) * 100.0 / current.avg();
            double fill = clamp(AhFlipperMod.CONFIG.fireSaleBacktestFillProbability,
                    0.05, 1.0);
            double actualRoi = targetHit
                    ? fill * targetRoi + (1.0 - fill) * exitRoi
                    : exitRoi;
            out.add(new Signal(current.time(), predictedRoi, trendPercent,
                    monthlySales, confidence, actualRoi, targetHit, daysToTarget));
        }
    }

    private static HorizonStats stats(int horizon, List<Signal> signals,
                                      AhFlipperConfig config) {
        HorizonStats stats = new HorizonStats();
        stats.days = horizon;
        if (signals == null || signals.isEmpty()) {
            return stats;
        }
        stats.allSignals = signals.size();
        List<Signal> filtered = filter(signals, config.fireSaleMinRoiPercent,
                config.fireSaleMinTrendPercent, config.fireSaleMinSales,
                config.fireSaleMinConfidence);
        stats.signals = filtered.size();
        if (filtered.isEmpty()) {
            return stats;
        }
        List<Double> rois = new ArrayList<>();
        int wins = 0;
        int targetHits = 0;
        double targetDays = 0;
        double sum = 0;
        double worst = Double.MAX_VALUE;
        double best = -Double.MAX_VALUE;
        for (Signal signal : filtered) {
            rois.add(signal.actualRoi());
            sum += signal.actualRoi();
            if (signal.actualRoi() > 0) {
                wins++;
            }
            if (signal.targetHit()) {
                targetHits++;
                targetDays += signal.daysToTarget();
            }
            worst = Math.min(worst, signal.actualRoi());
            best = Math.max(best, signal.actualRoi());
        }
        rois.sort(null);
        stats.wins = wins;
        stats.winRate = wins / (double) filtered.size();
        stats.medianRoi = median(rois);
        stats.averageRoi = sum / filtered.size();
        stats.worstRoi = worst;
        stats.bestRoi = best;
        stats.targetHits = targetHits;
        stats.targetHitRate = targetHits / (double) filtered.size();
        stats.averageDaysToTarget = targetHits > 0 ? targetDays / targetHits : 0;
        return stats;
    }

    private static Recommendation calibrate(List<Signal> signals, AhFlipperConfig config) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }
        double[] roiOptions = {5, 10, 15, 20, 30, 50};
        double[] trendOptions = {0.0, 0.25, 0.5, 1.0};
        int[] salesOptions = {1, 2, 3, 5};
        double[] confidenceOptions = {0.15, 0.20, 0.25, 0.35, 0.50};
        Recommendation best = null;
        int minSignals = Math.max(3, config.fireSaleBacktestMinSignals);
        for (double roi : roiOptions) {
            for (double trend : trendOptions) {
                for (int sales : salesOptions) {
                    for (double confidence : confidenceOptions) {
                        List<Signal> filtered = filter(signals, roi, trend, sales, confidence);
                        if (filtered.size() < minSignals) {
                            continue;
                        }
                        Recommendation candidate = recommendation(filtered, roi, trend,
                                sales, confidence);
                        if (best == null || candidate.winRate * candidate.medianRoi
                                > best.winRate * best.medianRoi) {
                            best = candidate;
                        }
                    }
                }
            }
        }
        if (best == null) {
            best = defaultRecommendation(signals, config);
        }
        return best;
    }

    private static Recommendation defaultRecommendation(List<Signal> signals,
                                                        AhFlipperConfig config) {
        List<Signal> filtered = filter(signals, config.fireSaleMinRoiPercent,
                config.fireSaleMinTrendPercent, config.fireSaleMinSales,
                config.fireSaleMinConfidence);
        if (filtered.isEmpty()) {
            filtered = signals;
        }
        Recommendation recommendation = recommendation(filtered,
                config.fireSaleMinRoiPercent, config.fireSaleMinTrendPercent,
                config.fireSaleMinSales, config.fireSaleMinConfidence);
        recommendation.appliedDefaults = true;
        return recommendation;
    }

    private static Recommendation recommendation(List<Signal> filtered, double roi,
                                                 double trend, int sales,
                                                 double confidence) {
        Recommendation result = new Recommendation();
        result.minRoiPercent = roi;
        result.minTrendPercent = trend;
        result.minSales = sales;
        result.minConfidence = confidence;
        result.signals = filtered.size();
        int wins = 0;
        double sum = 0;
        List<Double> values = new ArrayList<>();
        for (Signal signal : filtered) {
            values.add(signal.actualRoi());
            sum += signal.actualRoi();
            if (signal.actualRoi() > 0) {
                wins++;
            }
        }
        values.sort(null);
        result.winRate = wins / (double) filtered.size();
        result.medianRoi = median(values);
        result.averageRoi = sum / filtered.size();
        result.appliedDefaults = false;
        return result;
    }

    private static String outOfSample(List<Signal> signals, Recommendation recommendation) {
        if (signals == null || signals.size() < 10 || recommendation == null) {
            return "zu wenig Signale f\u00fcr Out-of-Sample";
        }
        List<Signal> sorted = new ArrayList<>(signals);
        sorted.sort(Comparator.comparingLong(Signal::at));
        int split = (int) Math.round(sorted.size() * 0.70);
        List<Signal> train = sorted.subList(0, split);
        List<Signal> test = sorted.subList(split, sorted.size());
        List<Signal> trainFiltered = filter(train, recommendation.minRoiPercent,
                recommendation.minTrendPercent, recommendation.minSales,
                recommendation.minConfidence);
        List<Signal> testFiltered = filter(test, recommendation.minRoiPercent,
                recommendation.minTrendPercent, recommendation.minSales,
                recommendation.minConfidence);
        if (testFiltered.size() < 3) {
            return "Testfenster: zu wenig Signale (" + testFiltered.size() + ")";
        }
        return String.format(Locale.US,
                "Out-of-Sample: Train %d/%d, Test %d Signale, Test-Win %.0f%%, Test-Median %.1f%%",
                trainFiltered.size(), train.size(), testFiltered.size(),
                winRate(testFiltered) * 100, medianRoi(testFiltered));
    }

    private static List<Signal> filter(List<Signal> signals, double minRoi,
                                       double minTrend, int minSales,
                                       double minConfidence) {
        List<Signal> result = new ArrayList<>();
        for (Signal signal : signals) {
            if (signal.predictedRoi() < minRoi
                    || signal.trendPercent() < minTrend
                    || signal.monthlySales() < minSales
                    || signal.confidence() < minConfidence) {
                continue;
            }
            result.add(signal);
        }
        return result;
    }

    private static double winRate(List<Signal> signals) {
        if (signals.isEmpty()) {
            return 0;
        }
        int wins = 0;
        for (Signal signal : signals) {
            if (signal.actualRoi() > 0) {
                wins++;
            }
        }
        return wins / (double) signals.size();
    }

    private static double medianRoi(List<Signal> signals) {
        List<Double> values = new ArrayList<>();
        for (Signal signal : signals) {
            values.add(signal.actualRoi());
        }
        values.sort(null);
        return median(values);
    }

    private static long weightedAverage(
            List<AhFireSaleSkinTracker.HistoryPoint> points) {
        double sum = 0;
        double weight = 0;
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            double w = Math.max(1, point.volume());
            sum += point.avg() * w;
            weight += w;
        }
        return weight > 0 ? Math.round(sum / weight) : 0;
    }

    private static double slopePerDay(List<AhFireSaleSkinTracker.HistoryPoint> points) {
        if (points.size() < 2) {
            return 0;
        }
        long first = points.get(0).time();
        double sumWeight = 0;
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumXX = 0;
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            double weight = Math.max(1, Math.min(20, point.volume()));
            double x = (point.time() - first) / (double) DAY_MS;
            double y = point.avg();
            sumWeight += weight;
            sumX += weight * x;
            sumY += weight * y;
            sumXY += weight * x * y;
            sumXX += weight * x * x;
        }
        double denominator = sumWeight * sumXX - sumX * sumX;
        if (Math.abs(denominator) < 1e-9) {
            return 0;
        }
        return (sumWeight * sumXY - sumX * sumY) / denominator;
    }

    private static int monthlySales(List<AhFireSaleSkinTracker.HistoryPoint> points,
                                    long now) {
        long cutoff = now - 30L * DAY_MS;
        int sales = 0;
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            if (point.time() >= cutoff) {
                sales += point.volume();
            }
        }
        return sales;
    }

    private static double coefficientVariation(
            List<AhFireSaleSkinTracker.HistoryPoint> points, double average) {
        if (points.isEmpty() || average <= 0) {
            return 0;
        }
        double variance = 0;
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            double difference = point.avg() - average;
            variance += difference * difference;
        }
        return Math.sqrt(variance / points.size()) / average;
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
