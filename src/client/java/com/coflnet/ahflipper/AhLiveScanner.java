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

    private record SortEntry(Candidate candidate, int buy, double score, long updatedAt) {
    }

    public static final class Stats {
        public int received;
        public int belowMin;
        public int overBankroll;
        public int queued;
        public int cleanRejected;
        public int craftRejected;
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

    static Snapshot snapshot() {
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
            snapshot.stats.craftRejected = STATS.craftRejected;
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
        return (running ? "§aan" : "§caus")
                + " §7· Feed " + snapshot.feedStatus
                + " §7· " + s.received + " neu"
                + " §7· " + s.queued + " geprüft"
                + " §7· " + s.buyable + " kaufbar"
                + " §7· " + s.cleanRejected + " clean"
                + " §7· " + s.craftRejected + " craft"
                + " §7· " + s.valueRejected + " value"
                + " §7· " + s.addonRejected + " addon";
    }

    private static void onAuction(CoflAuctionFeed.Auction auction) {
        if (!running) {
            return;
        }
        if (auction.tag.isBlank() || auction.price <= 0) {
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
            boolean pet = candidate.tag.toUpperCase(Locale.ROOT).startsWith("PET_")
                    || (candidate.name != null && candidate.name.startsWith("[Lvl"));
            String petFilter = "";
            if (pet) {
                AhItem petItem = AhItem.fromAuction(details);
                candidate.petLevel = petItem.petLevel;
                candidate.petRarity = petItem.petRarity;
                petFilter = petItem.petFilter();
                if (petFilter.isBlank()) {
                    reject(candidate, "CLEAN", "Pet-Level/Rarität nicht erkannt");
                    return;
                }
                AhMarketData.Analysis level1 = AhMarketData.analysis(candidate.tag, "&PetLevel=1");
                long level1Sales = level1 == null ? 0 : level1.totalSales;
                if (level1 == null || level1.totalSales <= AhFlipperMod.CONFIG.petLevel1MinSales) {
                    reject(candidate, "CLEAN", "Pet-Level-1-Sales " + level1Sales
                            + " <= " + AhFlipperMod.CONFIG.petLevel1MinSales);
                    return;
                }
                AhMarketData.Analysis level = AhMarketData.analysis(candidate.tag,
                        "&PetLevel=" + candidate.petLevel);
                long levelSales = level == null ? 0 : level.totalSales;
                if (level == null || level.totalSales <= AhFlipperMod.CONFIG.petLevelMinSales) {
                    reject(candidate, "CLEAN", "Pet-Level-" + candidate.petLevel + "-Sales "
                            + levelSales + " <= " + AhFlipperMod.CONFIG.petLevelMinSales);
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
                if (clean == null || clean.totalSales < 5) {
                    AhMarketData.Analysis fallback = AhMarketData.analysis(candidate.tag,
                            "&Enchantment=None&Reforge=None");
                    if (fallback != null && fallback.totalSales > (clean == null ? 0 : clean.totalSales)) {
                        clean = fallback;
                    }
                }
                cleanSales = clean == null ? 0 : clean.totalSales;
            }
            if (clean == null || clean.totalSales <= 0) {
                reject(candidate, "CLEAN", "Preisdaten nicht abrufbar");
                return;
            }
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
            if (candidate.cleanCv > AhFlipperMod.CONFIG.maxDeviationPercent / 100.0) {
                reject(candidate, "CLEAN", String.format(Locale.US, "%sCV %.1f%% > %.0f%%",
                        pet ? "Pet-" : "",
                        candidate.cleanCv * 100, AhFlipperMod.CONFIG.maxDeviationPercent));
                return;
            }

            AhItem item = AhItem.fromAuction(details);
            AhMarketData.CraftCost craft = AhMarketData.craftCost(candidate.tag);
            candidate.craftCost = craft == null ? 0 : Math.max(0, craft.craftCost);

            update(candidate, "VALUE", "");
            AhValuation.Estimate estimate = AhValuation.estimate(item, candidate.uuid);
            candidate.cleanAverage = estimate.reference;
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
                    + estimate.comparableExcluded + " Ausrei\u00dfer) AskSafety="
                    + estimate.askSafetyValue
                    + " Ceiling=" + estimate.ceiling + " -> " + estimated);
            if (estimated <= 0) {
                reject(candidate, "VALUE", "Keine belastbare Bewertung");
                return;
            }
            if (estimate.addonValueUnbacked) {
                reject(candidate, "VALUE", "Addon-Vergleich unbrauchbar "
                        + "(zu wenige belastbare Verkäufe / zu viele Ausreißer)");
                return;
            }
            if (estimate.candyPet) {
                reject(candidate, "VALUE", "Pet mit Candies - kein Kauf");
                return;
            }
            AhMarketData.Breakdown breakdown = candidate.addonValue > 0
                    ? AhMarketData.breakdown(details) : null;
            candidate.estimatedValue = estimated;
            candidate.estimatedDiscount = discount(candidate.price, estimated);

            if (!pet
                    && candidate.addonValue <= AhFlipperMod.CONFIG.cleanSalesAddonValueCoins
                    && candidate.cleanSales < AhFlipperMod.CONFIG.minCleanSales) {
                reject(candidate, "CLEAN", "Clean-Sales " + candidate.cleanSales
                        + " < " + AhFlipperMod.CONFIG.minCleanSales
                        + " (Addon " + Math.round(candidate.addonValue / 1_000_000.0) + "M)");
                return;
            }

            update(candidate, "ADDONS", "");
            if (!checkBreakdown(candidate, breakdown, item, candidate.tag)) {
                return;
            }

            long fee = Math.round(estimated
                    * (AhFlipperMod.CONFIG.ahFeePercent / 100.0));
            candidate.profit = estimated - fee - candidate.price;
            candidate.profitPercent = candidate.price > 0
                    ? candidate.profit * 100.0 / candidate.price : -1;

            if (candidate.profitPercent < AhFlipperMod.CONFIG.minProfitPercent) {
                reject(candidate, "PROFIT", String.format(Locale.US, "Gewinn %.1f%% < %.0f%%",
                        candidate.profitPercent, AhFlipperMod.CONFIG.minProfitPercent));
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

    private static boolean checkBreakdown(Candidate candidate, AhMarketData.Breakdown breakdown,
                                          AhItem item, String tag) {
        if (breakdown == null || breakdown.parts.isEmpty()) {
            return true;
        }
        List<AhMarketData.Sold> sold = AhMarketData.sold(tag);
        candidate.addonParts = 0;
        candidate.addonPartsPassed = 0;
        candidate.minAddonSales = Integer.MAX_VALUE;
        for (AhMarketData.BreakdownPart part : breakdown.parts) {
            if (isBase(part, tag)) {
                continue;
            }
            candidate.addonParts++;
            if (!part.itemTag.isBlank()) {
                double volume = AhAddonValues.weeklyVolume(part.itemTag);
                if (volume > 0 && volume < AhFlipperMod.CONFIG.addonMinBazaarVolume) {
                    reject(candidate, "ADDONS", part.itemTag + " Bazaar-Volumen "
                            + Math.round(volume) + "/Woche < "
                            + AhFlipperMod.CONFIG.addonMinBazaarVolume);
                    return false;
                }
            }
            int count = countAddonSales(part, item, sold);
            if (count >= 0) {
                candidate.minAddonSales = Math.min(candidate.minAddonSales, count);
                if (count < AhFlipperMod.CONFIG.addonMinSalesPerWeek) {
                    reject(candidate, "ADDONS", part.itemTag + " nur " + count
                            + "x/Woche auf " + tag + " < "
                            + AhFlipperMod.CONFIG.addonMinSalesPerWeek);
                    return false;
                }
            }
            candidate.addonPartsPassed++;
        }
        if (candidate.minAddonSales == Integer.MAX_VALUE) {
            candidate.minAddonSales = 0;
        }
        return true;
    }

    private static boolean isBase(AhMarketData.BreakdownPart part, String tag) {
        return part.reason.toLowerCase(Locale.ROOT).contains("base item")
                || (!part.itemTag.isBlank() && part.itemTag.equalsIgnoreCase(tag)
                && part.reason.toLowerCase(Locale.ROOT).contains("base"));
    }

    private static int countAddonSales(AhMarketData.BreakdownPart part, AhItem item,
                                       List<AhMarketData.Sold> sold) {
        String product = part.itemTag.toUpperCase(Locale.ROOT);
        if (product.endsWith("_GEM")) {
            return -1;
        }
        if (product.startsWith("ENCHANTMENT_")) {
            String rest = product.substring("ENCHANTMENT_".length());
            int split = rest.lastIndexOf('_');
            if (split > 0) {
                String type = rest.substring(0, split).toLowerCase(Locale.ROOT);
                int level;
                try {
                    level = Integer.parseInt(rest.substring(split + 1));
                } catch (NumberFormatException e) {
                    return -1;
                }
                return countEnchant(sold, type, level);
            }
        }
        if (product.contains("RECOMBOBULATOR") || part.attribute.contains("rarity_upgrades")) {
            return countRecomb(sold);
        }
        if (product.contains("HOT_POTATO") || part.attribute.contains("hpc")
                || part.attribute.contains("hot_potato")) {
            return countHpb(sold, Math.max(1, item.hpb));
        }
        if (product.contains("ART_OF_WAR") || part.attribute.contains("art_of_war")) {
            return countArtOfWar(sold);
        }
        if (product.contains("WOOD_SINGULARITY") || part.attribute.contains("wood_singularity")) {
            return countWood(sold);
        }
        if (product.contains("_SCROLL") || part.attribute.contains("ability_scroll")) {
            return countScroll(sold, product);
        }
        if (product.startsWith("DYE_") || part.attribute.equals("dye_item")) {
            return countNbtText(sold, "dye_item", product);
        }
        if (part.attribute.contains("upgrade_level") || part.attribute.contains("dungeon_item_level")) {
            return countStars(sold, Math.max(1, item.stars));
        }
        if (part.attribute.contains("modifier") || part.attribute.contains("reforge")
                || part.reason.toLowerCase(Locale.ROOT).contains("reforge")) {
            return countReforge(sold, item.reforge);
        }
        return -1;
    }

    private static int countEnchant(List<AhMarketData.Sold> sold, String type, int level) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            for (AhMarketData.Enchant enchant : sale.enchants) {
                if (enchant.type.equalsIgnoreCase(type) && enchant.level == level) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static int countRecomb(List<AhMarketData.Sold> sold) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (nbtInt(sale, "rarity_upgrades") > 0) {
                count++;
            }
        }
        return count;
    }

    private static int countHpb(List<AhMarketData.Sold> sold, int hpb) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (nbtInt(sale, "hpc") >= hpb) {
                count++;
            }
        }
        return count;
    }

    private static int countArtOfWar(List<AhMarketData.Sold> sold) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (nbtInt(sale, "art_of_war_count") > 0) {
                count++;
            }
        }
        return count;
    }

    private static int countWood(List<AhMarketData.Sold> sold) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (nbtInt(sale, "wood_singularity_count") > 0) {
                count++;
            }
        }
        return count;
    }

    private static int countScroll(List<AhMarketData.Sold> sold, String product) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            String raw = nbtText(sale, "ability_scroll");
            if (!raw.isBlank()
                    && raw.toUpperCase(Locale.ROOT).contains(product)) {
                count++;
            }
        }
        return count;
    }

    private static int countReforge(List<AhMarketData.Sold> sold, String reforge) {
        if (reforge == null || reforge.isBlank()) {
            return -1;
        }
        String display = reforgeDisplay(reforge);
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            String value = nbtText(sale, "modifier");
            if (value.isBlank()) {
                value = nbtText(sale, "reforge");
            }
            if (!value.isBlank()) {
                if (value.equalsIgnoreCase(reforge)) {
                    count++;
                }
            } else if (!display.isBlank()
                    && sale.itemName.toLowerCase(Locale.ROOT)
                            .startsWith(display.toLowerCase(Locale.ROOT) + " ")) {
                count++;
            }
        }
        return count;
    }

    private static String nbtText(AhMarketData.Sold sale, String key) {
        if (!sale.nbt.has(key) || sale.nbt.get(key).isJsonNull()) {
            return "";
        }
        try {
            return sale.nbt.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String reforgeDisplay(String reforge) {
        String[] parts = reforge.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private static int countNbtText(List<AhMarketData.Sold> sold, String key, String value) {
        if (key.isBlank() || value.isBlank()) {
            return -1;
        }
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (!sale.nbt.has(key) || sale.nbt.get(key).isJsonNull()) {
                continue;
            }
            if (sale.nbt.get(key).getAsString().equalsIgnoreCase(value)) {
                count++;
            }
        }
        return count;
    }

    private static int countStars(List<AhMarketData.Sold> sold, int stars) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (nbtInt(sale, "upgrade_level") >= stars
                    || nbtInt(sale, "dungeon_item_level") >= stars) {
                count++;
            }
        }
        return count;
    }

    private static int nbtInt(AhMarketData.Sold sale, String key) {
        if (!sale.nbt.has(key) || sale.nbt.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(sale.nbt.get(key).getAsString());
        } catch (Exception ignored) {
            return 0;
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
                        if (next == null || candidate.at < next.at) {
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
                case "CRAFT" -> STATS.craftRejected++;
                case "VALUE" -> STATS.valueRejected++;
                case "ADDONS" -> STATS.addonRejected++;
                default -> {
                }
            }
        }
        update(candidate, "REJECT", reason);
        candidate.stage = "REJECT:" + stage;
    }

    private static long currentBankroll() {
        long now = System.currentTimeMillis();
        if (now - bankrollAt < 20_000L && bankroll > 0) {
            return bankroll;
        }
        long purse = ScreenUtil.readPurse();
        if (purse > 0) {
            bankroll = Math.max(0, purse - AhFlipperMod.CONFIG.reserveCoins);
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
