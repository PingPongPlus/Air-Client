package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared valuation model for Fire Sale skins, used by the live scanner and the
 * backtest so both always use the same formula.
 *
 * <p>Calibrated on the full Cofl daily history of all Fire Sale skins
 * (2024-11 .. 2026-11):
 * <ul>
 *   <li>Freshly released skins fall for months: bought in the first ~60 days
 *       they lose 35-55% over the next 90 days. A minimum age is mandatory.</li>
 *   <li>Momentum does not work: after a rise over the last 30 days the next
 *       30-90 days are clearly negative (mean reversion).</li>
 *   <li>What works is buying clearly <b>below</b> the recent fair value and
 *       selling at that fair value again.</li>
 * </ul>
 *
 * <p>Formula:
 * <pre>
 *   fair   = min(median30, median90)              (median of daily averages)
 *   drift  = min(0, log-slope over 365 days)      (only falling markets count)
 *   target = fair * exp(drift * holdDays)
 *   roi    = (target * (1 - fee) - buy) / buy
 * </pre>
 * A rising long-term trend is deliberately NOT added to the target; it made
 * the old projections ~2x too optimistic.
 */
final class AhFireSaleModel {
    static final long DAY_MS = 24L * 60L * 60_000L;
    /** Lowest per-day drift that is applied (-0.6%/day). */
    private static final double MIN_DRIFT = -0.006;
    /** A 30-day median below 75% of the 90-day median is a structural break. */
    static final double BREAK_RATIO = 0.75;
    /** Minimum number of days with sales in the last 90 days. */
    static final int MIN_DAYS_WITH_DATA = 20;

    static final class Projection {
        int ageDays;
        int daysWithData90;
        int monthlySales;
        int weeklySales;
        long median30;
        long median90;
        long fair;
        long target;
        double driftPerDay;
        double trend30PercentPerDay;
        double dispersion;
        double discountPercent;
        double roiPercent;
        double confidence;
        /** Empty when the data is usable; otherwise a short German reason. */
        String dataProblem = "";
    }

    private AhFireSaleModel() {
    }

    /**
     * @param points  daily history sorted by time (may contain points after {@code now};
     *                they are ignored, so the backtest can pass the full series)
     * @param now     evaluation time
     * @param buyPrice price the skin would be bought at
     * @param holdDays planned holding period
     * @param fee     AH fee fraction (0.05 = 5%)
     */
    static Projection project(List<AhFireSaleSkinTracker.HistoryPoint> points, long now,
                              long buyPrice, int holdDays, double fee) {
        Projection result = new Projection();
        if (points == null || points.isEmpty() || buyPrice <= 0) {
            result.dataProblem = "keine Historie";
            return result;
        }
        long first = points.get(0).time();
        if (first > now) {
            result.dataProblem = "keine Historie";
            return result;
        }
        result.ageDays = (int) Math.max(0, (now - first) / DAY_MS);
        List<AhFireSaleSkinTracker.HistoryPoint> w7 = window(points, now, 7);
        List<AhFireSaleSkinTracker.HistoryPoint> w30 = window(points, now, 30);
        List<AhFireSaleSkinTracker.HistoryPoint> w90 = window(points, now, 90);
        List<AhFireSaleSkinTracker.HistoryPoint> w365 = window(points, now, 365);
        result.daysWithData90 = w90.size();
        result.monthlySales = volume(w30);
        result.weeklySales = volume(w7);
        if (w90.isEmpty()) {
            result.dataProblem = "keine Verkaeufe in 90 Tagen";
            return result;
        }
        result.median90 = medianAvg(w90);
        result.median30 = w30.isEmpty() ? result.median90 : medianAvg(w30);
        if (result.median90 <= 0) {
            result.dataProblem = "kein Preis";
            return result;
        }
        result.fair = Math.min(result.median30, result.median90);
        double slope365 = logSlopePerDay(w365);
        result.driftPerDay = Math.max(MIN_DRIFT, Math.min(0.0, slope365));
        int hold = Math.max(1, holdDays);
        result.target = Math.round(result.fair * Math.exp(result.driftPerDay * hold));
        result.roiPercent = (result.target * (1.0 - fee) - buyPrice) * 100.0 / buyPrice;
        result.discountPercent = (1.0 - buyPrice / (double) result.fair) * 100.0;
        result.trend30PercentPerDay = logSlopePerDay(w30) * 100.0;
        result.dispersion = relativeMad(w90, result.median90);
        result.confidence = confidence(result);
        if (result.daysWithData90 < MIN_DAYS_WITH_DATA) {
            result.dataProblem = "zu wenig Historie (" + result.daysWithData90
                    + "/" + MIN_DAYS_WITH_DATA + " Tage)";
        } else if (result.median30 < result.median90 * BREAK_RATIO) {
            result.dataProblem = "Preisbruch (30d-Median < 75% des 90d-Medians)";
        }
        return result;
    }

    /**
     * 0..1. Volume, age (the 1-2 year old skins behaved best, very old ones are
     * weaker in the current market), price stability and data coverage.
     */
    private static double confidence(Projection projection) {
        double volume = clamp(Math.log10(projection.monthlySales + 1.0) / Math.log10(31.0),
                0, 1);
        double age;
        int days = projection.ageDays;
        if (days < 90) {
            age = 0;
        } else if (days < 180) {
            age = 0.4 + 0.4 * (days - 90) / 90.0;
        } else if (days < 365) {
            age = 0.8 + 0.2 * (days - 180) / 185.0;
        } else if (days <= 730) {
            age = 1.0;
        } else {
            age = 0.7;
        }
        double stability = 1.0 - clamp(projection.dispersion / 0.5, 0, 1);
        double coverage = clamp(projection.daysWithData90 / 60.0, 0, 1);
        return clamp(0.30 * volume + 0.25 * age + 0.25 * stability + 0.20 * coverage, 0, 1);
    }

    /** Points in (now - days, now]. */
    static List<AhFireSaleSkinTracker.HistoryPoint> window(
            List<AhFireSaleSkinTracker.HistoryPoint> points, long now, int days) {
        long from = now - days * DAY_MS;
        List<AhFireSaleSkinTracker.HistoryPoint> result = new ArrayList<>();
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            if (point.time() > from && point.time() <= now && point.avg() > 0) {
                result.add(point);
            }
        }
        return result;
    }

    static int volume(List<AhFireSaleSkinTracker.HistoryPoint> points) {
        int total = 0;
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            total += Math.max(0, point.volume());
        }
        return total;
    }

    static long medianAvg(List<AhFireSaleSkinTracker.HistoryPoint> points) {
        if (points.isEmpty()) {
            return 0;
        }
        long[] values = new long[points.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = points.get(i).avg();
        }
        java.util.Arrays.sort(values);
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle]
                : Math.round((values[middle - 1] + values[middle]) / 2.0);
    }

    /** Median of the daily averages in the 3 days up to {@code now} (no look-ahead). */
    static long recentPrice(List<AhFireSaleSkinTracker.HistoryPoint> points, long now) {
        return medianAvg(window(points, now, 3));
    }

    /** Ordinary least squares of ln(price) over days; 0 with fewer than 8 points. */
    static double logSlopePerDay(List<AhFireSaleSkinTracker.HistoryPoint> points) {
        if (points.size() < 8) {
            return 0;
        }
        long first = points.get(0).time();
        double n = points.size();
        double sumX = 0;
        double sumY = 0;
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            sumX += (point.time() - first) / (double) DAY_MS;
            sumY += Math.log(point.avg());
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double sxx = 0;
        double sxy = 0;
        for (AhFireSaleSkinTracker.HistoryPoint point : points) {
            double x = (point.time() - first) / (double) DAY_MS - meanX;
            sxx += x * x;
            sxy += x * (Math.log(point.avg()) - meanY);
        }
        return sxx <= 1e-9 ? 0 : sxy / sxx;
    }

    /** Median absolute deviation of the daily averages relative to the median. */
    private static double relativeMad(List<AhFireSaleSkinTracker.HistoryPoint> points,
                                      long median) {
        if (points.isEmpty() || median <= 0) {
            return 0;
        }
        double[] deviations = new double[points.size()];
        for (int i = 0; i < deviations.length; i++) {
            deviations[i] = Math.abs(points.get(i).avg() - median);
        }
        java.util.Arrays.sort(deviations);
        int middle = deviations.length / 2;
        double mad = deviations.length % 2 == 1 ? deviations[middle]
                : (deviations[middle - 1] + deviations[middle]) / 2.0;
        return mad / median;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
