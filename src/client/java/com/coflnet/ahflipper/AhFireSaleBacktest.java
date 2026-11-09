package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Walk-forward backtest of {@link AhFireSaleModel} on the full Cofl daily
 * history: for every skin and every week of the last two years the model only
 * sees data up to that day, the result is compared with the real price after
 * the holding period. The grid search calibrates min ROI / min age / min sales
 * / min confidence.
 */
public final class AhFireSaleBacktest {
    private static final long DAY_MS = AhFireSaleModel.DAY_MS;
    /** Only the current market regime is tested (the AH changed a lot over the years). */
    private static final long LOOKBACK_MS = 730L * DAY_MS;
    public static final int[] HORIZONS = {14, 30, 45, 60, 90};

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
        public int minAgeDays;
        /** @deprecated kept for old screens; the model has no trend filter any more. */
        @Deprecated
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

    private record Signal(long at, double predictedRoi, int ageDays, int monthlySales,
                          double confidence, double actualRoi, boolean targetHit,
                          int daysToTarget) {
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
        synchronized (AhFireSaleBacktest.class) {
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
            Map<Integer, List<Signal>> signalsByHorizon = new HashMap<>();
            for (int horizon : HORIZONS) {
                signalsByHorizon.put(horizon, new ArrayList<>());
            }
            int skins = 0;
            for (String tag : AhFireSaleSkinTracker.fireSaleSkins().keySet()) {
                AhFireSaleSkinTracker.HistoryData history =
                        AhFireSaleSkinTracker.cachedHistory(tag);
                if (history == null || history.full().size() < 30) {
                    continue;
                }
                skins++;
                for (int horizon : HORIZONS) {
                    collectSignals(history.full(), horizon, fee,
                            signalsByHorizon.get(horizon));
                }
            }
            SNAPSHOT.skinCount = skins;
            int calibrationHorizon = Math.max(7, Math.min(180, config.fireSaleHoldDays));
            if (!signalsByHorizon.containsKey(calibrationHorizon)) {
                calibrationHorizon = 45;
            }
            for (int horizon : HORIZONS) {
                SNAPSHOT.horizons.put(horizon, stats(horizon,
                        signalsByHorizon.get(horizon), config));
            }
            List<Signal> calibration = signalsByHorizon.get(calibrationHorizon);
            Recommendation recommendation = calibrate(calibration, config);
            if (recommendation != null) {
                recommendation.calibrationHorizonDays = calibrationHorizon;
            }
            SNAPSHOT.recommended = recommendation;
            SNAPSHOT.signalCount = recommendation == null ? 0 : recommendation.signals;
            SNAPSHOT.outOfSample = outOfSample(calibration, recommendation);
            SNAPSHOT.note = "Walk-forward auf Markt-Tageshistorie (2 Jahre) · "
                    + skins + " Skins · 1 Signal/Woche/Skin · Ziel-Fill "
                    + Math.round(config.fireSaleBacktestFillProbability * 100) + "%";
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

    private static void collectSignals(List<AhFireSaleSkinTracker.HistoryPoint> points,
                                       int horizon, double fee, List<Signal> out) {
        long last = points.get(points.size() - 1).time();
        long from = System.currentTimeMillis() - LOOKBACK_MS;
        long lastSignalAt = Long.MIN_VALUE;
        for (int index = 0; index < points.size(); index++) {
            AhFireSaleSkinTracker.HistoryPoint current = points.get(index);
            long at = current.time();
            if (at < from || at + horizon * DAY_MS > last) {
                continue;
            }
            // One signal per skin and week, otherwise overlapping days of the
            // same trade dominate the statistics.
            if (at - lastSignalAt < 7 * DAY_MS) {
                continue;
            }
            long buy = AhFireSaleModel.recentPrice(points, at);
            if (buy <= 0) {
                continue;
            }
            AhFireSaleModel.Projection projection =
                    AhFireSaleModel.project(points, at, buy, horizon, fee);
            if (!projection.dataProblem.isEmpty()) {
                continue;
            }
            long exitAt = at + horizon * DAY_MS;
            List<AhFireSaleSkinTracker.HistoryPoint> exitWindow = new ArrayList<>();
            boolean targetHit = false;
            int daysToTarget = 0;
            for (int next = index + 1; next < points.size(); next++) {
                AhFireSaleSkinTracker.HistoryPoint point = points.get(next);
                if (point.time() > exitAt + 3 * DAY_MS) {
                    break;
                }
                if (!targetHit && point.time() <= exitAt && point.avg() >= projection.target) {
                    targetHit = true;
                    daysToTarget = Math.max(1, (int) ((point.time() - at) / DAY_MS));
                }
                if (point.time() >= exitAt - 3 * DAY_MS) {
                    exitWindow.add(point);
                }
            }
            if (exitWindow.isEmpty()) {
                continue;
            }
            long exit = AhFireSaleModel.medianAvg(exitWindow);
            double exitRoi = (exit * (1.0 - fee) - buy) * 100.0 / buy;
            double targetRoi = (projection.target * (1.0 - fee) - buy) * 100.0 / buy;
            double fill = AhFireSaleModel.clamp(
                    AhFlipperMod.CONFIG.fireSaleBacktestFillProbability, 0.05, 1.0);
            double actualRoi = targetHit ? fill * targetRoi + (1.0 - fill) * exitRoi : exitRoi;
            lastSignalAt = at;
            out.add(new Signal(at, projection.roiPercent, projection.ageDays,
                    projection.monthlySales, projection.confidence, actualRoi,
                    targetHit, daysToTarget));
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
                config.fireSaleMinAgeDays, config.fireSaleMinSales,
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
        double[] roiOptions = {8, 12, 15, 20, 30};
        int[] ageOptions = {60, 90, 120, 180, 365};
        int[] salesOptions = {3, 5, 10, 20};
        double[] confidenceOptions = {0.35, 0.45, 0.55, 0.65};
        Recommendation best = null;
        int minSignals = Math.max(3, config.fireSaleBacktestMinSignals);
        for (double roi : roiOptions) {
            for (int age : ageOptions) {
                for (int sales : salesOptions) {
                    for (double confidence : confidenceOptions) {
                        List<Signal> filtered = filter(signals, roi, age, sales, confidence);
                        if (filtered.size() < minSignals) {
                            continue;
                        }
                        Recommendation candidate = recommendation(filtered, roi, age,
                                sales, confidence);
                        // Median (not mean) and win rate: robust against a few
                        // lucky outliers that would otherwise pick the filters.
                        if (best == null || objective(candidate) > objective(best)) {
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

    private static double objective(Recommendation recommendation) {
        double sizeFactor = Math.min(1.0, recommendation.signals / 30.0);
        return recommendation.winRate * recommendation.medianRoi * (0.5 + 0.5 * sizeFactor);
    }

    private static Recommendation defaultRecommendation(List<Signal> signals,
                                                        AhFlipperConfig config) {
        List<Signal> filtered = filter(signals, config.fireSaleMinRoiPercent,
                config.fireSaleMinAgeDays, config.fireSaleMinSales,
                config.fireSaleMinConfidence);
        if (filtered.isEmpty()) {
            filtered = signals;
        }
        Recommendation recommendation = recommendation(filtered,
                config.fireSaleMinRoiPercent, config.fireSaleMinAgeDays,
                config.fireSaleMinSales, config.fireSaleMinConfidence);
        recommendation.appliedDefaults = true;
        return recommendation;
    }

    private static Recommendation recommendation(List<Signal> filtered, double roi,
                                                 int age, int sales, double confidence) {
        Recommendation result = new Recommendation();
        result.minRoiPercent = roi;
        result.minAgeDays = age;
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
            return "zu wenig Signale für Out-of-Sample";
        }
        List<Signal> sorted = new ArrayList<>(signals);
        sorted.sort(Comparator.comparingLong(Signal::at));
        int split = (int) Math.round(sorted.size() * 0.70);
        List<Signal> train = sorted.subList(0, split);
        List<Signal> test = sorted.subList(split, sorted.size());
        List<Signal> trainFiltered = filter(train, recommendation.minRoiPercent,
                recommendation.minAgeDays, recommendation.minSales,
                recommendation.minConfidence);
        List<Signal> testFiltered = filter(test, recommendation.minRoiPercent,
                recommendation.minAgeDays, recommendation.minSales,
                recommendation.minConfidence);
        if (testFiltered.size() < 3) {
            return "Testfenster: zu wenig Signale (" + testFiltered.size() + ")";
        }
        return String.format(Locale.US,
                "Out-of-Sample: Train %d/%d, Test %d Signale, Test-Win %.0f%%, Test-Median %.1f%%",
                trainFiltered.size(), train.size(), testFiltered.size(),
                winRate(testFiltered) * 100, medianRoi(testFiltered));
    }

    private static List<Signal> filter(List<Signal> signals, double minRoi, int minAge,
                                       int minSales, double minConfidence) {
        List<Signal> result = new ArrayList<>();
        for (Signal signal : signals) {
            if (signal.predictedRoi() < minRoi
                    || signal.ageDays() < minAge
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
}
