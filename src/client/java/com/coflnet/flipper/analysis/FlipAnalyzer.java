package com.coflnet.flipper.analysis;

import com.coflnet.flipper.FlipperConfig;
import com.coflnet.flipper.data.BazaarPriceHistory;
import com.coflnet.flipper.data.ItemNames;
import com.coflnet.flipper.data.MarketData;
import com.coflnet.flipper.data.UndercutTracker;
import com.coflnet.flipper.trading.ScreenUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class FlipAnalyzer {

    public static class FlipPlan {
        public String productId;
        public String displayName;
        public double buyPrice;
        public double sellPrice;
        public double profitPerUnit;
        public double marginPercent;
        public double lossPerUnit;
        public double fillProbability;
        public double kellyFraction;
        public double coinsPerHour;
        public double hourlyVolume;
        public double score;
        public long qty;
        public double stake;
        public double uBuy;
        public double uSell;
        public double c;
        public double r;
        public double e;
        public double lambda;
        public double limitPerHour;

        public double expectedProfit() {
            return profitPerUnit * qty;
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public static List<FlipPlan> compute(MarketData data, FlipperConfig cfg, double bankroll, Set<String> existingProductIds, int availableSlots) {
        List<FlipPlan> plans = new ArrayList<>();
        if (data == null || bankroll <= 0 || availableSlots <= 0) {
            return plans;
        }
        double taxFactor = 1.0 - cfg.taxPercent / 100.0;
        double checkHours = Math.max(1.0, cfg.undercutCheckMinutes) / 60.0;
        double budgetPerItem = bankroll * cfg.maxBankFractionPerItem;
        if (budgetPerItem < 1) {
            return plans;
        }
        int inventoryCapacity = Math.max(64, ScreenUtil.freeInventorySlots() * 64);

        for (MarketData.Product product : data.products.values()) {
            if (existingProductIds != null && existingProductIds.contains(product.productId)) {
                continue;
            }
            if (cfg.blacklist.contains(product.productId)) {
                continue;
            }
            double buyPrice = round1(product.bestBid + 0.1);
            double sellPrice = round1(product.bestAsk - 0.1);
            if (sellPrice <= buyPrice) {
                continue;
            }
            double profit = round1(sellPrice * taxFactor - buyPrice);
            if (profit <= 0) {
                continue;
            }
            double marginPercent = profit / buyPrice * 100.0;
            if (marginPercent < cfg.minMarginPercent) {
                continue;
            }
            if (marginPercent > cfg.maxMarginPercent) {
                continue;
            }
            if (buyPrice < cfg.minUnitCoins) {
                continue;
            }
            double weeklyFlow = Math.min(product.sellMovingWeek, product.buyMovingWeek);
            if (weeklyFlow < cfg.minWeeklyVolume) {
                continue;
            }
            if (product.bestBid2 > 0
                    && product.bestBid - product.bestBid2 > Math.max(product.bestBid * 0.05, 500)) {
                continue;
            }
            if (product.avgSellPrice > 0
                    && Math.abs(buyPrice - product.avgSellPrice) / product.avgSellPrice * 100.0 > cfg.maxPriceDeviationPercent) {
                continue;
            }
            if (product.avgBuyPrice > 0
                    && Math.abs(sellPrice - product.avgBuyPrice) / product.avgBuyPrice * 100.0 > cfg.maxPriceDeviationPercent) {
                continue;
            }

            BazaarPriceHistory.Snapshot history = BazaarPriceHistory.lookup(product.productId);
            if (cfg.requireHistoricalPrice) {
                if (history == null || history.volume < cfg.minHistoryVolume) {
                    continue;
                }
                double reference = history.referencePrice();
                if (reference <= 0) {
                    continue;
                }
                double maxDeviation = cfg.maxHistoricalDeviationPercent;
                if (Math.abs(buyPrice - reference) / reference * 100.0 > maxDeviation) {
                    continue;
                }
                if (Math.abs(sellPrice - reference) / reference * 100.0 > maxDeviation) {
                    continue;
                }
            } else if (history != null && history.referencePrice() > 0) {
                double reference = history.referencePrice();
                double maxDeviation = cfg.maxHistoricalDeviationPercent;
                if (Math.abs(buyPrice - reference) / reference * 100.0 > maxDeviation
                        || Math.abs(sellPrice - reference) / reference * 100.0 > maxDeviation) {
                    continue;
                }
            }

            double v = weeklyFlow / 168.0;
            if (v <= 0) {
                continue;
            }
            double uBuy = UndercutTracker.effectiveU(product.productId, v, cfg, true);
            double uSell = UndercutTracker.effectiveU(product.productId, v, cfg, false);
            double cBuy = 1.0 / (1.0 + uBuy * checkHours / 2.0);
            double cSell = 1.0 / (1.0 + uSell * checkHours / 2.0);
            double c = Math.min(cBuy, cSell);
            double flowPerHour = v * c;
            if (flowPerHour <= 0) {
                continue;
            }

            double qCap = Math.min(inventoryCapacity, 71680.0);
            qCap = Math.min(qCap, 1_000_000_000.0 / sellPrice);
            qCap = Math.min(qCap, budgetPerItem / buyPrice);
            if (qCap < 1.0) {
                continue;
            }
            double qRaw = 2.0 * v * c * checkHours;
            double q = Math.max(1.0, Math.min(qRaw, qCap));
            double minOrderQty = Math.ceil(cfg.minOrderCoins / buyPrice);
            if (minOrderQty > qCap) {
                continue;
            }
            if (q < minOrderQty) {
                q = minOrderQty;
            }

            double realizedFlow = Math.min(flowPerHour, q / checkHours);
            if (realizedFlow <= 0) {
                continue;
            }
            double p = realizedFlow * profit;
            double u = Math.max(uBuy, uSell);
            double r = 0.5 * (q / flowPerHour) * Math.min(u, 1.0 / checkHours);
            double limitBase = cfg.beta * buyPrice + sellPrice;
            double limitPerHour = realizedFlow * limitBase * (1.0 + r);
            double e = profit / (limitBase * (1.0 + r));
            if (p <= 0 || e <= 0 || limitPerHour <= 0) {
                continue;
            }

            FlipPlan plan = new FlipPlan();
            plan.productId = product.productId;
            plan.displayName = ItemNames.displayName(product.productId);
            plan.buyPrice = buyPrice;
            plan.sellPrice = sellPrice;
            plan.profitPerUnit = profit;
            plan.marginPercent = marginPercent;
            plan.fillProbability = c;
            plan.kellyFraction = 0;
            plan.lossPerUnit = (buyPrice - product.bestBid) + Math.max(cfg.kellyLossVolatilityPercent / 100.0 * buyPrice, 1.0);
            plan.coinsPerHour = p;
            plan.hourlyVolume = v;
            plan.qty = (long) Math.floor(q);
            if (plan.qty < 1) {
                plan.qty = 1;
            }
            plan.stake = plan.qty * buyPrice;
            plan.uBuy = uBuy;
            plan.uSell = uSell;
            plan.c = c;
            plan.r = r;
            plan.e = e;
            plan.limitPerHour = limitPerHour;
            plans.add(plan);
        }

        plans.sort(Comparator.comparingDouble((FlipPlan p) -> p.e).reversed());
        double limitCap = cfg.limitBudgetCoins * cfg.limitFillTarget;
        double activeHours = Math.max(0.1, cfg.activeHours);
        double usedLimit = 0;
        double lambda = 0;
        List<FlipPlan> withinLimit = new ArrayList<>();
        for (FlipPlan plan : plans) {
            double need = plan.limitPerHour * activeHours;
            if (usedLimit + need > limitCap && !withinLimit.isEmpty()) {
                lambda = withinLimit.get(withinLimit.size() - 1).e;
                break;
            }
            usedLimit += need;
            withinLimit.add(plan);
        }
        double coinsPerHourWeight = Math.max(0.01, cfg.coinsPerHourWeight);
        double profitPercentWeight = Math.max(0.0, cfg.profitPercentWeight);
        for (FlipPlan plan : withinLimit) {
            plan.lambda = lambda;
            double limitFactor = 1.0 - (lambda > 0 ? lambda / plan.e : 0.0);
            double marginFactor = 1.0 + Math.max(0.0, plan.marginPercent) / 100.0 * profitPercentWeight;
            plan.score = plan.coinsPerHour * coinsPerHourWeight * marginFactor * limitFactor;
        }
        withinLimit.removeIf(plan -> plan.score <= 0);
        withinLimit.sort(Comparator.comparingDouble((FlipPlan plan) -> plan.score).reversed());

        double remainingCoins = bankroll;
        List<FlipPlan> result = new ArrayList<>();
        for (FlipPlan plan : withinLimit) {
            if (result.size() >= availableSlots) {
                break;
            }
            double affordableCoins = Math.min(remainingCoins, budgetPerItem);
            long affordable = (long) Math.floor(affordableCoins / plan.buyPrice);
            long qty = Math.min(plan.qty, affordable);
            if (qty < 1) {
                continue;
            }
            if (qty * plan.buyPrice < cfg.minOrderCoins) {
                continue;
            }
            plan.qty = qty;
            plan.stake = qty * plan.buyPrice;
            remainingCoins -= plan.stake;
            result.add(plan);
        }
        return result;
    }
}
