package com.coflnet.ahflipper;

import com.coflnet.flipper.data.BazaarApi;
import com.coflnet.flipper.data.MarketData;
import com.coflnet.flipper.trading.ScreenUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Scans cheap AH items, builds a 7-day hourly price curve, detects intraday
 * buy/sell windows, liquidity depth and ranks the best coins/hour flips.
 */
public final class AhInvestmentTracker {
    private static final long HOUR_MS = 3_600_000L;
    private static final long WEEK_MS = 7L * 24L * HOUR_MS;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    public static final class InvestmentCandidate {
        public String tag = "";
        public String name = "";
        public long buyPrice;
        public long effectiveBuyPrice;
        public long sellTarget;
        public long qty;
        public long totalProfit;
        public long coinsPerHour;
        public double marginPercent;
        public double confidence;
        public double trendPerHour;
        public double trendPercentPerHour;
        public int buyHourStart;
        public int buyHourEnd;
        public int sellHourStart;
        public int sellHourEnd;
        public long buyHourMedian;
        public long sellHourMedian;
        public int holdingHours;
        public int cleanSales;
        public int hoursWithData;
        public long weeklyMedian;
        public int bookDepth;
        public long[] hourlyMedians = new long[24];
        /** Median of the clean sales of the last 48h. */
        public long clearingPrice;
        /** Cheapest BIN left on the book after buying qty (0 = none). */
        public long nextBinPrice;
        /** Last 48h median vs. the rest of the week, in %. */
        public double momentumPercent;
        /** True when the buy/sell time window held on most single days. */
        public boolean patternValidated;
        public int patternDays;
        public int patternHits;
        public String note = "";
    }

    public static final class Snapshot {
        public volatile boolean enabled;
        public volatile boolean scanning;
        public volatile int totalTags;
        public volatile int processedTags;
        public volatile int skippedTags;
        public volatile int candidateCount;
        public volatile String currentTag = "";
        public volatile long updatedAt;
        public volatile String error = "";
        public volatile long bankroll;
        public volatile List<InvestmentCandidate> top = List.of();
    }

    private static final class HourStat {
        int count;
        long median;
        long p25;
        long p75;
    }

    private static final Snapshot SNAPSHOT = new Snapshot();
    private static volatile boolean running;
    private static volatile boolean refreshRequested;
    private static volatile int generation;
    private static volatile Thread worker;

    private AhInvestmentTracker() {
    }

    static void init() {
        SNAPSHOT.enabled = AhFlipperMod.CONFIG.investmentGui;
        if (SNAPSHOT.enabled) {
            start();
        }
    }

    public static synchronized void start() {
        if (!AhFlipperMod.CONFIG.investmentGui) {
            return;
        }
        SNAPSHOT.enabled = true;
        if (running) {
            return;
        }
        running = true;
        refreshRequested = false;
        SNAPSHOT.error = "";
        int run = ++generation;
        Thread thread = new Thread(() -> loop(run), "skycofl-ah-invest");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        System.out.println("[AH-Invest] Tracker gestartet");
    }

    public static synchronized void stop() {
        running = false;
        generation++;
        refreshRequested = true;
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
        SNAPSHOT.enabled = false;
        SNAPSHOT.scanning = false;
        System.out.println("[AH-Invest] Tracker gestoppt");
    }

    public static void requestRefresh() {
        if (!AhFlipperMod.CONFIG.investmentGui) {
            return;
        }
        if (!running) {
            start();
            return;
        }
        refreshRequested = true;
    }

    public static boolean isRunning() {
        return running;
    }

    public static Snapshot snapshot() {
        return SNAPSHOT;
    }

    private static void loop(int run) {
        while (running && generation == run) {
            try {
                scan(run);
            } catch (Exception e) {
                if (generation == run) {
                    SNAPSHOT.error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                System.out.println("[AH-Invest] Scan-Fehler: " + e);
            } finally {
                if (generation == run) {
                    SNAPSHOT.scanning = false;
                    AhMarketData.trimInvestmentCaches();
                }
            }
            if (!running || generation != run) {
                break;
            }
            long wait = Math.max(2, AhFlipperMod.CONFIG.investmentRefreshMinutes) * 60_000L;
            long slept = 0;
            while (running && generation == run && slept < wait) {
                if (refreshRequested) {
                    refreshRequested = false;
                    break;
                }
                ScreenUtil.sleep(500);
                slept += 500;
            }
        }
        if (generation == run) {
            running = false;
        }
    }

    private static void scan(int run) {
        if (generation != run) {
            return;
        }
        refreshRequested = false;
        SNAPSHOT.scanning = true;
        SNAPSHOT.error = "";
        SNAPSHOT.processedTags = 0;
        SNAPSHOT.skippedTags = 0;
        SNAPSHOT.totalTags = 0;
        SNAPSHOT.currentTag = "";
        long started = System.currentTimeMillis();

        Set<String> bazaar = bazaarProducts();
        if (generation != run) {
            return;
        }
        Map<String, AhMarketData.MarketEntry> market = AhMarketData.market();
        if (market.isEmpty()) {
            SNAPSHOT.error = "Marktdaten nicht abrufbar";
            return;
        }

        AhFlipperConfig config = AhFlipperMod.CONFIG;
        long maxBuy = Math.max(1, config.investmentMaxBuyPrice);
        long minBuy = Math.max(0, config.investmentMinPrice);
        List<String> tags = new ArrayList<>();
        Map<String, Double> volumes = new HashMap<>();
        for (Map.Entry<String, AhMarketData.MarketEntry> entry : market.entrySet()) {
            String tag = entry.getKey();
            if (tag == null || tag.isBlank() || entry.getValue() == null) {
                continue;
            }
            String upper = tag.toUpperCase(Locale.ROOT);
            if (bazaar.contains(upper)
                    || upper.startsWith("PET_")
                    || upper.startsWith("RUNE_")
                    || upper.startsWith("UNIQUE_RUNE_")) {
                continue;
            }
            AhMarketData.MarketEntry marketEntry = entry.getValue();
            long value = marketEntry.now > 0 ? marketEntry.now : marketEntry.value();
            if (value < minBuy || value > maxBuy || marketEntry.volume > 50_000) {
                continue;
            }
            if (config.isBlockedItem(upper, "")) {
                continue;
            }
            tags.add(upper);
            volumes.merge(upper, entry.getValue().volume, Math::max);
        }
        tags.sort(Comparator.comparingDouble(
                (String tag) -> volumes.getOrDefault(tag, 0.0)).reversed());
        int limit = Math.max(10, Math.min(400, config.investmentMaxTags));
        if (tags.size() > limit) {
            tags = new ArrayList<>(tags.subList(0, limit));
        }

        SNAPSHOT.totalTags = tags.size();
        long bankroll = currentBankroll();
        SNAPSHOT.bankroll = bankroll;
        List<InvestmentCandidate> candidates = new ArrayList<>();
        for (String tag : tags) {
            if (!running || generation != run) {
                return;
            }
            SNAPSHOT.currentTag = tag;
            try {
                InvestmentCandidate candidate = analyzeTag(tag, bankroll);
                if (candidate != null) {
                    candidates.add(candidate);
                } else {
                    SNAPSHOT.skippedTags++;
                }
            } catch (Exception e) {
                SNAPSHOT.skippedTags++;
                System.out.println("[AH-Invest] " + tag + " Fehler: " + e.getMessage());
            }
            SNAPSHOT.processedTags++;
            sleepInterruptibly(Math.max(0, config.investmentTagDelayMs));
        }

        candidates.sort((first, second) -> Double.compare(score(second), score(first)));
        int top = Math.max(1, Math.min(10, config.investmentTopCount));
        List<InvestmentCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (InvestmentCandidate candidate : candidates) {
            if (seen.add(candidate.tag)) {
                result.add(candidate);
            }
            if (result.size() >= top) {
                break;
            }
        }
        AhMarketData.trimInvestmentCaches();
        if (generation != run) {
            return;
        }
        SNAPSHOT.candidateCount = candidates.size();
        SNAPSHOT.top = List.copyOf(result);
        SNAPSHOT.updatedAt = System.currentTimeMillis();
        SNAPSHOT.scanning = false;
        System.out.println("[AH-Invest] Scan fertig: " + tags.size() + " Tags, "
                + candidates.size() + " Kandidaten, Top " + result.size()
                + " in " + ((System.currentTimeMillis() - started) / 1000) + "s");
        for (int index = 0; index < result.size(); index++) {
            InvestmentCandidate candidate = result.get(index);
            System.out.println("[AH-Invest] #" + (index + 1) + " " + candidate.name
                    + " [" + candidate.tag + "] buy=" + candidate.effectiveBuyPrice
                    + " sell=" + candidate.sellTarget + " qty=" + candidate.qty
                    + " hold=" + candidate.holdingHours + "h"
                    + " profit=" + candidate.totalProfit
                    + " coins/h=" + candidate.coinsPerHour
                    + String.format(Locale.US, " conf=%.0f%%", candidate.confidence * 100)
                    + String.format(Locale.US, " trend=%.2f%%/h", candidate.trendPercentPerHour));
        }
    }

    /**
     * Short-term flip model. The old version picked the cheapest and the most
     * expensive of 24 hourly medians and treated the gap as profit; with a week
     * of noisy data that gap is almost always positive, even without any real
     * daily pattern, and it sold at a median while ignoring the listings one has
     * to undercut. Now:
     * <ul>
     *   <li>exit = clearing price (median of the last 48h of clean sales),
     *       capped just below the next BIN that stays on the book after our buys;</li>
     *   <li>a buy/sell time window is only used when it held on most single days;</li>
     *   <li>holding time comes from the sales rate (how long until qty items sell);</li>
     *   <li>falling markets are rejected, rising ones are not extrapolated.</li>
     * </ul>
     */
    private static InvestmentCandidate analyzeTag(String tag, long bankroll) {
        AhFlipperConfig config = AhFlipperMod.CONFIG;
        long now = System.currentTimeMillis();
        long cutoff = now - WEEK_MS;
        long recentCutoff = now - 48 * HOUR_MS;
        long maxAddon = Math.max(0, Math.min(100_000L, config.investmentMaxBuyPrice));
        long maxUnit = Math.max(1, config.investmentMaxBuyPrice);

        List<AhMarketData.Sold> sold = AhMarketData.soldForInvestment(tag, 2);
        if (sold == null || sold.isEmpty()) {
            return null;
        }
        Map<Integer, List<Long>> hourBuckets = new HashMap<>();
        Map<Long, List<long[]>> dayBuckets = new TreeMap<>();
        Map<String, Integer> nameCounts = new HashMap<>();
        List<Long> allPrices = new ArrayList<>();
        List<Long> recentPrices = new ArrayList<>();
        List<Long> olderPrices = new ArrayList<>();
        for (AhMarketData.Sold sale : sold) {
            if (sale == null || sale.price <= 0 || sale.end <= cutoff || sale.end > now) {
                continue;
            }
            if (!AhMarketData.isFastCleanForInvestment(tag, sale, maxAddon)) {
                continue;
            }
            long unit = Math.max(1, Math.round(sale.price / (double) Math.max(1, sale.count)));
            if (unit < config.investmentMinPrice || unit > maxUnit) {
                continue;
            }
            LocalDateTime time = Instant.ofEpochMilli(sale.end).atZone(ZONE).toLocalDateTime();
            int hour = time.getHour();
            hourBuckets.computeIfAbsent(hour, ignored -> new ArrayList<>()).add(unit);
            long day = time.toLocalDate().toEpochDay();
            dayBuckets.computeIfAbsent(day, ignored -> new ArrayList<>())
                    .add(new long[]{hour, unit});
            allPrices.add(unit);
            if (sale.end >= recentCutoff) {
                recentPrices.add(unit);
            } else {
                olderPrices.add(unit);
            }
            String name = ScreenUtil.cleanName(sale.itemName);
            if (!name.isBlank()) {
                nameCounts.merge(name, 1, Integer::sum);
            }
        }

        int cleanSales = allPrices.size();
        int minSales = Math.max(2, config.investmentMinSales);
        if (cleanSales < minSales) {
            return null;
        }
        allPrices.sort(null);
        recentPrices.sort(null);
        olderPrices.sort(null);
        long weeklyMedian = median(allPrices);
        long weeklyP25 = percentile(allPrices, 0.25);
        long weeklyP75 = percentile(allPrices, 0.75);
        if (weeklyMedian <= 0) {
            return null;
        }
        // Clearing price: what the item sells for right now.
        long clearing = recentPrices.size() >= 5 ? median(recentPrices) : weeklyMedian;
        double momentum = 0;
        if (recentPrices.size() >= 5 && olderPrices.size() >= 5) {
            momentum = median(recentPrices) / (double) median(olderPrices) - 1.0;
        }
        if (momentum < -0.08) {
            // Falling knife: the last 48h are clearly below the rest of the week.
            return null;
        }

        int minSamples = Math.max(1, config.investmentMinHourSamples);
        Map<Integer, HourStat> hourStats = new HashMap<>();
        for (Map.Entry<Integer, List<Long>> entry : hourBuckets.entrySet()) {
            List<Long> prices = entry.getValue();
            if (prices.size() < minSamples) {
                continue;
            }
            prices.sort(null);
            HourStat stat = new HourStat();
            stat.count = prices.size();
            stat.median = median(prices);
            stat.p25 = percentile(prices, 0.25);
            stat.p75 = percentile(prices, 0.75);
            hourStats.put(entry.getKey(), stat);
        }

        // Candidate daily pattern; only trusted when it held on most days.
        int[] buyWindow = {-1, -1};
        int[] sellWindow = {-1, -1};
        long buyHourMedian = 0;
        long sellHourMedian = 0;
        int patternDays = 0;
        int patternHits = 0;
        double uplift = 1.0;
        if (hourStats.size() >= 6) {
            int bestBuyHour = -1;
            int bestSellHour = -1;
            HourStat bestBuy = null;
            HourStat bestSell = null;
            for (Map.Entry<Integer, HourStat> entry : hourStats.entrySet()) {
                HourStat stat = entry.getValue();
                if (bestBuy == null || stat.median < bestBuy.median) {
                    bestBuy = stat;
                    bestBuyHour = entry.getKey();
                }
                if (bestSell == null || stat.median > bestSell.median) {
                    bestSell = stat;
                    bestSellHour = entry.getKey();
                }
            }
            if (bestBuy != null && bestSell != null && bestSell.median > bestBuy.median) {
                int[] candidateBuy = window(hourStats, bestBuyHour, true, minSamples);
                int[] candidateSell = window(hourStats, bestSellHour, false, minSamples);
                List<Double> ratios = new ArrayList<>();
                for (List<long[]> daySales : dayBuckets.values()) {
                    List<Long> buys = new ArrayList<>();
                    List<Long> sells = new ArrayList<>();
                    for (long[] sale : daySales) {
                        int hour = (int) sale[0];
                        if (inWindow(hour, candidateBuy[0], candidateBuy[1])) {
                            buys.add(sale[1]);
                        } else if (inWindow(hour, candidateSell[0], candidateSell[1])) {
                            sells.add(sale[1]);
                        }
                    }
                    if (buys.isEmpty() || sells.isEmpty()) {
                        continue;
                    }
                    buys.sort(null);
                    sells.sort(null);
                    ratios.add(median(sells) / (double) Math.max(1, median(buys)));
                }
                patternDays = ratios.size();
                for (double ratio : ratios) {
                    if (ratio > 1.0) {
                        patternHits++;
                    }
                }
                ratios.sort(null);
                double medianRatio = ratios.isEmpty() ? 1.0 : ratios.get(ratios.size() / 2);
                if (patternDays >= 4 && patternHits >= Math.ceil(patternDays * 0.75)
                        && medianRatio >= 1.03) {
                    buyWindow = candidateBuy;
                    sellWindow = candidateSell;
                    buyHourMedian = bestBuy.median;
                    sellHourMedian = bestSell.median;
                    uplift = Math.min(medianRatio, 1.20);
                }
            }
        }
        boolean patternValidated = buyWindow[0] >= 0;

        List<AhMarketData.ActiveBin> bins = AhMarketData.activeBins(tag);
        if (bins == null || bins.isEmpty()) {
            return null;
        }
        long bookCeiling = Math.round(Math.max(weeklyMedian, weeklyP75) * 1.35);
        long bookFloor = Math.max(1, weeklyP25 * 70 / 100);
        List<Long> book = new ArrayList<>();
        for (AhMarketData.ActiveBin bin : bins) {
            long price = bin == null ? 0 : bin.price();
            if (price < bookFloor || price > bookCeiling) {
                continue;
            }
            book.add(price);
        }
        if (book.isEmpty()) {
            return null;
        }
        book.sort(null);
        long buyPrice = book.get(0);
        if (buyPrice > maxUnit) {
            return null;
        }
        // Never pay more than the clearing price minus the required margin.
        double premium = (buyPrice - clearing) * 100.0 / clearing;
        if (premium > config.investmentMaxBuyPremiumPercent) {
            return null;
        }
        double slippage = Math.max(0, config.investmentSlippagePercent) / 100.0;
        long slipCap = Math.round(buyPrice * (1.0 + slippage));
        int available = 0;
        for (Long price : book) {
            if (price > slipCap) {
                break;
            }
            available++;
        }
        if (available <= 0) {
            return null;
        }

        double salesPerHour = cleanSales / 168.0;
        long cleanSalesPerDay = Math.max(1, Math.round(cleanSales / 7.0));
        long maxByVolume = Math.max(1, Math.round(cleanSalesPerDay * 0.25));
        long maxByBankroll = bankroll > 0
                ? Math.max(1, bankroll / Math.max(1, slipCap)) : Long.MAX_VALUE;
        long qty = Math.min(Math.min(available, maxByVolume),
                Math.min(maxByBankroll, Math.max(1, config.investmentMaxQty)));
        qty = Math.max(1, qty);
        long sum = 0;
        for (int i = 0; i < qty; i++) {
            sum += book.get(i);
        }
        long effectiveBuy = Math.max(1, Math.round(sum / (double) qty));

        // Exit: clearing price (or the validated sell-window level), but our
        // listing has to undercut whatever stays on the book after our buys.
        long exit = patternValidated
                ? Math.max(clearing, Math.min(sellHourMedian, Math.round(clearing * uplift)))
                : clearing;
        long nextBin = book.size() > qty ? book.get((int) qty) : 0;
        if (nextBin > 0) {
            exit = Math.min(exit, Math.round(nextBin * 0.995));
        }
        exit = Math.min(exit, percentile(allPrices, 0.90));
        long fee = Math.round(exit * config.ahFeePercent / 100.0);
        long netPerItem = exit - fee - effectiveBuy;
        if (netPerItem <= 0) {
            return null;
        }
        double margin = netPerItem * 100.0 / effectiveBuy;
        if (margin < config.investmentMinMarginPercent) {
            return null;
        }

        // Holding: time to sell qty items as the cheapest listing (we assume
        // half of the sales flow) and, with a pattern, the wait for the window.
        double sellHours = qty / Math.max(0.01, salesPerHour * 0.5);
        int holding = (int) Math.ceil(Math.max(1.0, sellHours));
        if (patternValidated) {
            int currentHour = LocalDateTime.now(ZONE).getHour();
            int untilWindow = 0;
            if (!inWindow(currentHour, sellWindow[0], sellWindow[1])) {
                untilWindow = sellWindow[0] - currentHour;
                if (untilWindow <= 0) {
                    untilWindow += 24;
                }
            }
            holding = Math.max(holding, untilWindow + 1);
        }
        holding = Math.max(1, Math.min(72, holding));
        long totalProfit = netPerItem * qty;
        long coinsPerHour = Math.max(1, Math.round(totalProfit / (double) holding));
        if (coinsPerHour < config.investmentMinCoinsPerHour) {
            return null;
        }

        double iqr = (weeklyP75 - weeklyP25) / (double) weeklyMedian;
        double salesScore = clamp(Math.log10(cleanSales + 1.0) / Math.log10(100.0), 0, 1);
        double stability = 1.0 - clamp(iqr / 0.6, 0, 1);
        double recency = clamp(recentPrices.size() / 10.0, 0, 1);
        double depth = 1.0 - clamp(book.size() / (double) Math.max(1, cleanSales), 0, 1);
        double confidence = 0.35 * salesScore + 0.25 * stability + 0.20 * recency
                + 0.20 * depth;
        if (confidence < 0.25) {
            return null;
        }

        InvestmentCandidate candidate = new InvestmentCandidate();
        candidate.tag = tag;
        candidate.name = mostCommon(nameCounts);
        if (candidate.name.isBlank()) {
            candidate.name = prettyTag(tag);
        }
        candidate.buyPrice = buyPrice;
        candidate.effectiveBuyPrice = effectiveBuy;
        candidate.sellTarget = exit;
        candidate.clearingPrice = clearing;
        candidate.nextBinPrice = nextBin;
        candidate.qty = qty;
        candidate.totalProfit = totalProfit;
        candidate.coinsPerHour = coinsPerHour;
        candidate.marginPercent = margin;
        candidate.confidence = confidence;
        candidate.momentumPercent = momentum * 100.0;
        candidate.trendPercentPerHour = momentum * 100.0 / 96.0;
        candidate.trendPerHour = clearing * momentum / 96.0;
        candidate.patternValidated = patternValidated;
        candidate.patternDays = patternDays;
        candidate.patternHits = patternHits;
        candidate.buyHourStart = buyWindow[0];
        candidate.buyHourEnd = buyWindow[1];
        candidate.sellHourStart = sellWindow[0];
        candidate.sellHourEnd = sellWindow[1];
        candidate.buyHourMedian = buyHourMedian;
        candidate.sellHourMedian = sellHourMedian;
        candidate.holdingHours = holding;
        candidate.cleanSales = cleanSales;
        candidate.hoursWithData = hourStats.size();
        candidate.weeklyMedian = weeklyMedian;
        candidate.bookDepth = book.size();
        for (int hour = 0; hour < 24; hour++) {
            HourStat stat = hourStats.get(hour);
            if (stat != null) {
                candidate.hourlyMedians[hour] = stat.median;
            }
        }
        candidate.note = (patternValidated
                ? "Tagesmuster " + patternHits + "/" + patternDays + " Tage"
                : "kein Tagesmuster")
                + " · Clearing " + ScreenUtil.formatCoins(clearing)
                + (nextBin > 0 ? " · nächste BIN " + ScreenUtil.formatCoins(nextBin) : "");
        return candidate;
    }

    private static double score(InvestmentCandidate candidate) {
        return candidate.coinsPerHour * (0.5 + 0.5 * candidate.confidence);
    }

    private static Set<String> bazaarProducts() {
        try {
            MarketData data = BazaarApi.fetch();
            if (data == null) {
                return Set.of();
            }
            Set<String> ids = new HashSet<>();
            for (String id : data.products.keySet()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.toUpperCase(Locale.ROOT));
                }
            }
            return ids;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static boolean inWindow(int hour, int start, int end) {
        if (start == end) {
            return hour == start;
        }
        if (start < end) {
            return hour >= start && hour <= end;
        }
        return hour >= start || hour <= end;
    }

    private static long currentBankroll() {
        long purse = ScreenUtil.readPurse();
        long configured = Math.max(0, AhFlipperMod.CONFIG.investmentBankrollCoins);
        if (purse > 0) {
            long bankroll = Math.max(0, purse - AhFlipperMod.CONFIG.reserveCoins);
            if (AhFlipperMod.CONFIG.maxBankrollCoins > 0) {
                bankroll = Math.min(bankroll, AhFlipperMod.CONFIG.maxBankrollCoins);
            }
            if (bankroll > 0) {
                return bankroll;
            }
        }
        return configured;
    }

    private static void sleepInterruptibly(long ms) {
        long end = System.currentTimeMillis() + Math.max(0, ms);
        while (running && System.currentTimeMillis() < end) {
            ScreenUtil.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    private static int[] window(Map<Integer, HourStat> stats, int bestHour,
                                boolean low, int minSamples) {
        HourStat best = stats.get(bestHour);
        if (best == null) {
            return new int[]{bestHour, bestHour};
        }
        double limit = low ? best.median * 1.05 : best.median * 0.95;
        int start = bestHour;
        int end = bestHour;
        for (int i = 1; i < 12; i++) {
            int hour = (bestHour - i + 24) % 24;
            HourStat stat = stats.get(hour);
            if (stat == null || stat.count < minSamples) {
                break;
            }
            if (low ? stat.median > limit : stat.median < limit) {
                break;
            }
            start = hour;
        }
        for (int i = 1; i < 12; i++) {
            int hour = (bestHour + i) % 24;
            HourStat stat = stats.get(hour);
            if (stat == null || stat.count < minSamples) {
                break;
            }
            if (low ? stat.median > limit : stat.median < limit) {
                break;
            }
            end = hour;
        }
        return new int[]{start, end};
    }

    private static long median(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
    }

    private static long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.round(clamp(fraction, 0, 1) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double coefficientVariation(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (long value : values) {
            sum += value;
        }
        double average = sum / values.size();
        if (average <= 0) {
            return 0;
        }
        double variance = 0;
        for (long value : values) {
            double difference = value - average;
            variance += difference * difference;
        }
        return Math.sqrt(variance / values.size()) / average;
    }

    private static String mostCommon(Map<String, Integer> counts) {
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static String prettyTag(String tag) {
        StringBuilder builder = new StringBuilder();
        for (String part : tag.split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

