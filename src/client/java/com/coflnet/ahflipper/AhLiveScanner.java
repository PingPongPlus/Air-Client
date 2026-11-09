package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AhLiveScanner {
    public static final class Candidate {
        public String uuid = "";
        public String tag = "";
        public String name = "";
        public int petLevel;
        public String petRarity = "";
        public String stage = "QUEUED";
        public String reason = "";
        public long price;
        public long cleanAverage;
        public long referenceValue;
        public long cleanSales;
        public double cleanCv;
        public long craftCost;
        public long estimatedValue;
        public long breakdownValue;
        public long addonValue;
        public long profit;
        public double profitPercent;
        public double craftDiscount;
        public double estimatedDiscount;
        public double score;
        public double quality;
        public int addonParts;
        public int addonPartsPassed;
        public int minAddonSales;
        public long at = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();
    }

    public record CandidateView(String uuid, String tag, String name, long price,
                                long estimatedValue, long profit, double profitPercent,
                                long updatedAt) {
    }

    public static List<CandidateView> buyableView() {
        long now = System.currentTimeMillis();
        List<CandidateView> result = new ArrayList<>();
        for (Candidate candidate : CANDIDATES.values()) {
            if (!"BUY".equals(candidate.stage) || now - candidate.updatedAt > 90_000L) {
                continue;
            }
            if (AhFlipperMod.CONFIG.isBlockedItem(candidate.tag, candidate.name)) {
                continue;
            }
            result.add(new CandidateView(candidate.uuid, candidate.tag,
                    candidate.name, candidate.price, candidate.estimatedValue,
                    candidate.profit, candidate.profitPercent, candidate.updatedAt));
        }
        result.sort((first, second) ->
                Long.compare(second.profit(), first.profit()));
        if (result.size() > 5) {
            return new ArrayList<>(result.subList(0, 5));
        }
        return result;
    }

    private record SortEntry(Candidate candidate, int buy, double score, long updatedAt) {
    }

    public static final class Stats {
        public int received;
        public int belowMin;
        public int overBankroll;
        public int queued;
        public int cleanRejected;
        public int valueRejected;
        public int addonRejected;
        public int buyable;
        public int errors;
    }

    public static final class Snapshot {
        public final Stats stats = new Stats();
        public final List<Candidate> candidates = new ArrayList<>();
        public boolean running;
        public String feedStatus = "";
        public long bankroll;
        public long updatedAt;
    }

    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();
    private static final Map<String, Candidate> CANDIDATES = new ConcurrentHashMap<>();
    private static final Map<String, Candidate> BUYABLE = new ConcurrentHashMap<>();
    private static final Stats STATS = new Stats();
    private static final Object BUY_LOCK = new Object();

    private static volatile boolean running;
    private static volatile long bankroll;
    private static volatile long bankrollAt;
    private static volatile ExecutorService workers;
    private static volatile Thread selector;
    private static volatile long lastCandidateAt;

    private AhLiveScanner() {
    }

    static void start() {
        if (running) {
            return;
        }
        running = true;
        bankroll = 0;
        bankrollAt = 0;
        workers = Executors.newFixedThreadPool(8, r -> {
            Thread thread = new Thread(r, "skycofl-ah-live");
            thread.setDaemon(true);
            return thread;
        });
        Thread thread = new Thread(AhLiveScanner::selectorLoop, "skycofl-ah-selector");
        thread.setDaemon(true);
        selector = thread;
        thread.start();
        CoflAuctionFeed.start(AhLiveScanner::onAuction);
        System.out.println("[AH-Flipper] Live-Scanner gestartet");
    }

    static void stop() {
        running = false;
        CoflAuctionFeed.stop();
        ExecutorService pool = workers;
        if (pool != null) {
            pool.shutdownNow();
        }
        Thread thread = selector;
        if (thread != null) {
            thread.interrupt();
        }
        synchronized (BUY_LOCK) {
            BUYABLE.clear();
        }
        System.out.println("[AH-Flipper] Live-Scanner gestoppt");
    }

    static boolean isRunning() {
        return running;
    }

    public static Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.running = running;
        snapshot.feedStatus = CoflAuctionFeed.status();
        snapshot.bankroll = currentBankroll();
        snapshot.updatedAt = lastCandidateAt;
        synchronized (STATS) {
            snapshot.stats.received = STATS.received;
            snapshot.stats.belowMin = STATS.belowMin;
            snapshot.stats.overBankroll = STATS.overBankroll;
            snapshot.stats.queued = STATS.queued;
            snapshot.stats.cleanRejected = STATS.cleanRejected;
            snapshot.stats.valueRejected = STATS.valueRejected;
            snapshot.stats.addonRejected = STATS.addonRejected;
            snapshot.stats.buyable = STATS.buyable;
            snapshot.stats.errors = STATS.errors;
        }
        List<SortEntry> sorted = new ArrayList<>(CANDIDATES.size());
        for (Candidate candidate : CANDIDATES.values()) {
            sorted.add(new SortEntry(candidate,
                    "BUY".equals(candidate.stage) ? 1 : 0,
                    Double.isFinite(candidate.score) ? candidate.score : 0.0,
                    candidate.updatedAt));
        }
        sorted.sort((first, second) -> {
            int compared = Integer.compare(second.buy, first.buy);
            if (compared != 0) {
                return compared;
            }
            compared = Double.compare(second.score, first.score);
            if (compared != 0) {
                return compared;
            }
            return Long.compare(second.updatedAt, first.updatedAt);
        });
        int limit = Math.max(1, AhFlipperMod.CONFIG.dashboardMaxRows);
        for (int i = 0; i < sorted.size() && i < limit; i++) {
            snapshot.candidates.add(sorted.get(i).candidate);
        }
        return snapshot;
    }

    static String status() {
        Snapshot snapshot = snapshot();
        Stats s = snapshot.stats;
        return (running ? "\u00a7aan" : "\u00a7caus")
                + " \u00a77\u00b7 Feed " + snapshot.feedStatus
                + " \u00a77\u00b7 " + s.received + " neu"
                + " \u00a77\u00b7 " + s.queued + " gepr\u00fcft"
                + " \u00a77\u00b7 " + s.buyable + " kaufbar"
                + " \u00a77\u00b7 " + s.cleanRejected + " clean"
                + " \u00a77\u00b7 " + s.valueRejected + " value"
                + " \u00a77\u00b7 " + s.addonRejected + " addon";
    }

    private static void onAuction(CoflAuctionFeed.Auction auction) {
        if (!running) {
            return;
        }
        if (auction.tag.isBlank() || auction.price <= 0) {
            return;
        }
        if (!AhFlipperMod.CONFIG.petsEnabled && (AhItem.isPetTag(auction.tag)
                || (auction.itemName != null && auction.itemName.startsWith("[Lvl")))) {
            return;
        }
        if (AhFlipperMod.isOwnSeller(auction.seller, auction.playerName)) {
            System.out.println("[AH-Flipper] Eigene Auktion im Feed ignoriert: "
                    + auction.uuid + " (" + auction.playerName + ")");
            return;
        }
        if (seenRecently(auction.uuid)) {
            return;
        }
        synchronized (STATS) {
            STATS.received++;
        }
        if (!auction.bin) {
            return;
        }
        if (AhFlipperMod.CONFIG.isBlockedItem(auction.tag, auction.itemName)) {
            Candidate candidate = candidate(auction);
            candidate.stage = "REJECT:BLOCKED";
            candidate.reason = "Blocked item";
            put(candidate);
            return;
        }
        if (auction.price < AhFlipperMod.CONFIG.liveMinPrice) {
            synchronized (STATS) {
                STATS.belowMin++;
            }
            return;
        }
        long bank = currentBankroll();
        long maxPrice = bank > 0
                ? Math.round(bank * AhFlipperMod.CONFIG.liveMaxBankrollFraction) : -1;
        if (maxPrice > 0 && auction.price > maxPrice) {
            synchronized (STATS) {
                STATS.overBankroll++;
            }
            Candidate candidate = candidate(auction);
            candidate.stage = "REJECT";
            candidate.reason = "> " + Math.round(AhFlipperMod.CONFIG.liveMaxBankrollFraction * 100)
                    + "% Bankroll";
            put(candidate);
            return;
        }
        synchronized (STATS) {
            STATS.queued++;
        }
        Candidate candidate = candidate(auction);
        put(candidate);
        ExecutorService pool = workers;
        if (pool == null || pool.isShutdown()) {
            return;
        }
        pool.submit(() -> process(candidate));
    }

    private static Candidate candidate(CoflAuctionFeed.Auction auction) {
        Candidate candidate = new Candidate();
        candidate.uuid = auction.uuid;
        candidate.tag = auction.tag;
        candidate.name = auction.itemName;
        candidate.price = auction.price;
        candidate.at = System.currentTimeMillis();
        candidate.updatedAt = candidate.at;
        return candidate;
    }

    private static void put(Candidate candidate) {
        CANDIDATES.put(candidate.uuid, candidate);
        lastCandidateAt = System.currentTimeMillis();
        if (CANDIDATES.size() > 300) {
            long cutoff = System.currentTimeMillis() - 10 * 60_000L;
            CANDIDATES.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);
        }
    }

    private static void process(Candidate candidate) {
        try {
            update(candidate, "DETAILS", "");
            AhMarketData.AuctionDetails details = AhMarketData.auction(candidate.uuid);
            if (details == null || details.tag.isBlank()) {
                reject(candidate, "DETAILS", "Auktion nicht mehr abrufbar");
                return;
            }
            if (!details.bin) {
                reject(candidate, "DETAILS", "kein BIN");
                return;
            }
            if (details.end > 0 && details.end < System.currentTimeMillis()) {
                reject(candidate, "DETAILS", "Auktion bereits beendet");
                return;
            }
            if (details.startingBid > 0) {
                candidate.price = details.startingBid;
            }
            if (candidate.price < AhFlipperMod.CONFIG.liveMinPrice) {
                reject(candidate, "DETAILS", "Preis unter Live-Minimum");
                return;
            }
            long liveBank = currentBankroll();
            long liveMax = liveBank > 0
                    ? Math.round(liveBank * AhFlipperMod.CONFIG.liveMaxBankrollFraction) : -1;
            if (liveMax > 0 && candidate.price > liveMax) {
                reject(candidate, "DETAILS", "über Bankroll-Limit");
                return;
            }
            update(candidate, "CLEAN", "");
            AhItem item = AhItem.fromAuction(details);
            boolean rune = item.isRune();
            boolean pet = AhItem.isPetTag(candidate.tag)
                    || (candidate.name != null && candidate.name.startsWith("[Lvl"));
            String petFilter = "";
            if (!rune) {
            if (pet) {
                AhItem petItem = AhItem.fromAuction(details);
                candidate.petLevel = petItem.petLevel;
                candidate.petRarity = petItem.petRarity;
                petFilter = petItem.petFilter();
                if (petFilter.isBlank()) {
                    reject(candidate, "CLEAN", "Pet-Level/Rarität nicht erkannt");
                    return;
                }
                // Pets only need any sales at all (any level).
                AhMarketData.Analysis petSales = AhMarketData.analysis(candidate.tag, "");
                if (petSales == null || petSales.totalSales <= 0) {
                    reject(candidate, "CLEAN", "Keine Pet-Verkäufe");
                    return;
                }
            } else {
                AhMarketData.Analysis overall = AhMarketData.analysis(candidate.tag, "");
                int totalSales = overall == null ? 0 : (int) Math.min(Integer.MAX_VALUE, overall.totalSales);
                if (overall == null || totalSales <= AhFlipperMod.CONFIG.minTotalSales) {
                    reject(candidate, "CLEAN", "Total-Sales " + totalSales
                            + " <= " + AhFlipperMod.CONFIG.minTotalSales);
                    return;
                }
            }
            AhMarketData.Analysis clean;
            long cleanSales;
            if (pet) {
                clean = AhMarketData.analysis(candidate.tag, petFilter);
                if (clean == null || clean.totalSales < 5) {
                    AhMarketData.Analysis fallback = AhMarketData.analysis(candidate.tag,
                            "&PetLevel=" + candidate.petLevel);
                    if (fallback != null && fallback.totalSales > (clean == null ? 0 : clean.totalSales)) {
                        clean = fallback;
                    }
                }
                cleanSales = clean == null ? 0 : clean.totalSales;
            } else {
                clean = AhMarketData.analysis(candidate.tag, "&Clean=true");
                cleanSales = AhMarketData.cleanSaleCount(candidate.tag);
            }
            AhMarketData.RuneStats cleanRef = AhMarketData.referenceMedian(item);
            if (clean == null || clean.totalSales <= 0) {
                if (cleanRef == null || cleanRef.median <= 0) {
                    reject(candidate, "CLEAN", "Preisdaten nicht abrufbar");
                    return;
                }
                candidate.cleanAverage = cleanRef.median;
                candidate.cleanSales = cleanRef.sales;
                candidate.cleanCv = cleanRef.coeffVariation;
            } else {
                long sevenDayAverage = clean.avgPrice > 0
                        ? Math.round(clean.avgPrice) : clean.medianPrice;
                long cleanAverage = AhMarketData.lowestCleanAverage(candidate.tag,
                        candidate.petLevel, candidate.petRarity, sevenDayAverage);
                candidate.cleanAverage = cleanAverage;
                candidate.cleanSales = cleanSales;
                candidate.cleanCv = clean.coeffVariation;
                AhMarketData.RobustCv robust = AhMarketData.robustCv(candidate.tag,
                        candidate.petLevel, candidate.petRarity, !pet);
                if (robust != null) {
                    candidate.cleanCv = robust.cv;
                }
                if (cleanRef != null && cleanRef.median > 0) {
                    candidate.cleanAverage = cleanRef.median;
                    candidate.cleanSales = Math.max(candidate.cleanSales, cleanRef.sales);
                    candidate.cleanCv = cleanRef.coeffVariation;
                }
            }
            if (candidate.cleanCv > AhFlipperMod.CONFIG.maxDeviationPercent / 100.0) {
                reject(candidate, "CLEAN", String.format(Locale.US, "%sCV %.1f%% > %.0f%%",
                        pet ? "Pet-" : "",
                        candidate.cleanCv * 100, AhFlipperMod.CONFIG.maxDeviationPercent));
                return;
            }
            }

            if (item.isPet() && !AhFlipperMod.CONFIG.petsEnabled) {
                reject(candidate, "PET", "Pets im AH-Flipper deaktiviert");
                return;
            }
            if (AhFlipperMod.CONFIG.isBlockedItem(item.tag, item.displayName)) {
                reject(candidate, "BLOCKED", "Blocked item");
                return;
            }
            AhMarketData.CraftCost craft = AhMarketData.craftCost(candidate.tag);
            candidate.craftCost = craft == null ? 0 : Math.max(0, craft.craftCost);

            update(candidate, "VALUE", "");
            AhValuation.Estimate estimate = AhValuation.estimate(item, candidate.uuid);
            candidate.referenceValue = estimate.reference;
            candidate.breakdownValue = 0;
            candidate.addonValue = estimate.addonValue;
            candidate.craftDiscount = discount(candidate.price, candidate.craftCost);
            long estimated = estimate.estimated;
            System.out.println("[AH-Flipper] Bewertung " + candidate.tag + ": "
                    + estimate.source + " Referenz=" + estimate.reference
                    + " Addons=" + estimate.addonValue
                    + " AddonsAsk=" + estimate.addonValueAsk
                    + " Gems=" + estimate.gemValue
                    + " Slots=" + estimate.slotUnlockValue
                    + " Comparable=" + estimate.comparableAverage + " ("
                    + estimate.comparableSales + " Sales, "
                    + estimate.comparableExcluded + " Ausrei\u00dfer) Bin="
                    + estimate.comparableBin + " AskSafety="
                    + estimate.askSafetyValue
                    + " Ceiling=" + estimate.ceiling
                    + (estimate.rareAddonDeduction > 0 ? " Selten=" + estimate.rareAddonValue
                    + " Abzug=" + estimate.rareAddonDeduction : "")
                    + (estimate.candyDiscount > 0 ? " Candy=-" + estimate.candyDiscount : "")
                    + " -> " + estimated);
            // Candies and rarely sold addons/skins/dyes are already priced into
            // the estimate (discount / 90 % deduction) instead of rejecting.
            if (estimated <= 1) {
                reject(candidate, "VALUE", "Keine belastbare Bewertung");
                return;
            }
            candidate.estimatedValue = estimated;
            candidate.estimatedDiscount = discount(candidate.price, estimated);

            if (!rune && !pet
                    && candidate.addonValue <= AhFlipperMod.CONFIG.cleanSalesAddonValueCoins
                    && candidate.cleanSales < AhFlipperMod.CONFIG.minCleanSales) {
                reject(candidate, "CLEAN", "Clean-Sales " + candidate.cleanSales
                        + " < " + AhFlipperMod.CONFIG.minCleanSales
                        + " (Addon " + Math.round(candidate.addonValue / 1_000_000.0) + "M)");
                return;
            }

            long fee = Math.round(estimated
                    * (AhFlipperMod.CONFIG.ahFeePercent / 100.0));
            candidate.profit = estimated - fee - candidate.price;
            candidate.profitPercent = candidate.price > 0
                    ? candidate.profit * 100.0 / candidate.price : -1;

            double lowPrice = Math.max(1, AhFlipperMod.CONFIG.liveMinPrice);
            double highPrice = bankroll > 0
                    ? bankroll * AhFlipperMod.CONFIG.maxBankFractionPerOrder
                    : Math.max(lowPrice, candidate.price);
            double priceRatio = highPrice > lowPrice
                    ? Math.max(0, Math.min(1,
                    (candidate.price - lowPrice) / (highPrice - lowPrice)))
                    : 1.0;
            double preselectProfit = 15.0 + priceRatio * (-5.0 - 15.0);
            if (candidate.profitPercent < preselectProfit) {
                reject(candidate, "PROFIT", String.format(Locale.US,
                        "Vorauswahl-Gewinn %.1f%% < %.1f%%",
                        candidate.profitPercent, preselectProfit));
                return;
            }
            if (candidate.estimatedDiscount * 100.0 < AhFlipperMod.CONFIG.minDiscountPercent) {
                reject(candidate, "VALUE", String.format(Locale.US,
                        "Estimated-Rabatt %.1f%% < %.0f%%",
                        candidate.estimatedDiscount * 100, AhFlipperMod.CONFIG.minDiscountPercent));
                return;
            }

            candidate.quality = 1.0;
            candidate.score = Math.max(0, candidate.profit);
            update(candidate, "BUY", "");
            System.out.println("[AH-Flipper] Live kaufbar (BUYABLE): id=" + candidate.uuid
                    + " tag=" + candidate.tag + " name=\"" + candidate.name + "\""
                    + (candidate.petLevel > 0 ? " pet=" + candidate.petRarity + " L" + candidate.petLevel : "")
                    + " preis=" + candidate.price
                    + " wert=" + candidate.estimatedValue + " craft=" + candidate.craftCost
                    + " profit=" + candidate.profit
                    + " pct=" + String.format(Locale.US, "%.1f", candidate.profitPercent)
                    + " at=" + System.currentTimeMillis());
            synchronized (STATS) {
                STATS.buyable++;
            }
            synchronized (BUY_LOCK) {
                BUYABLE.put(candidate.uuid, candidate);
            }
            if (AhFlipperMod.enqueueLive(candidate.uuid, candidate.estimatedValue, candidate.profit)) {
                synchronized (BUY_LOCK) {
                    BUYABLE.remove(candidate.uuid);
                }
            }
        } catch (Exception e) {
            synchronized (STATS) {
                STATS.errors++;
            }
            System.out.println("[AH-Flipper] Live-Fehler für " + candidate.tag + ": " + e);
            reject(candidate, "ERROR", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void selectorLoop() {
        while (running) {
            try {
                Thread.sleep(Math.max(10, AhFlipperMod.CONFIG.selectorIntervalMs));
                if (!running || !AhFlipperMod.isAutoBuyOn()) {
                    continue;
                }
                Candidate next = null;
                long cutoff = System.currentTimeMillis() - 5 * 60_000L;
                synchronized (BUY_LOCK) {
                    BUYABLE.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);
                    for (Candidate candidate : BUYABLE.values()) {
                        if (next == null || candidate.at > next.at) {
                            next = candidate;
                        }
                    }
                }
                if (next != null
                        && AhFlipperMod.enqueueLive(next.uuid, next.estimatedValue, next.profit)) {
                    synchronized (BUY_LOCK) {
                        BUYABLE.remove(next.uuid);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Live-Selector Fehler: " + e);
            }
        }
    }

    private static void update(Candidate candidate, String stage, String reason) {
        candidate.stage = stage;
        candidate.reason = reason;
        candidate.updatedAt = System.currentTimeMillis();
        put(candidate);
    }

    private static void reject(Candidate candidate, String stage, String reason) {
        synchronized (STATS) {
            switch (stage) {
                case "CLEAN" -> STATS.cleanRejected++;
                case "VALUE" -> STATS.valueRejected++;
                case "ADDONS" -> STATS.addonRejected++;
                default -> {
                }
            }
        }
        candidate.stage = "REJECT:" + stage;
        candidate.reason = reason == null ? "" : reason;
        candidate.updatedAt = System.currentTimeMillis();
        put(candidate);
    }

    private static long currentBankroll() {
        long now = System.currentTimeMillis();
        if (now - bankrollAt < 20_000L && bankroll > 0) {
            return bankroll;
        }
        long purse = ScreenUtil.readPurse();
        if (purse > 0) {
            bankroll = Math.max(0, purse - AhFlipperMod.CONFIG.reserveCoins);
            if (AhFlipperMod.CONFIG.maxBankrollCoins > 0) {
                bankroll = Math.min(bankroll, AhFlipperMod.CONFIG.maxBankrollCoins);
            }
            bankrollAt = now;
        }
        return bankroll;
    }

    private static double discount(long price, long value) {
        return value > 0 ? (value - price) * 1.0 / value : 0;
    }

    private static boolean seenRecently(String uuid) {
        long now = System.currentTimeMillis();
        Long at = SEEN.put(uuid, now);
        if (at != null && now - at < 30 * 60_000L) {
            return true;
        }
        if (SEEN.size() > 50_000) {
            SEEN.entrySet().removeIf(entry -> now - entry.getValue() > 60 * 60_000L);
        }
        return false;
    }
}


