package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-term investment scanner for Hypixel Fire Sale cosmetics/skins.
 * Uses the full public Cofl daily price history (since the first sale, so the
 * age of a skin is known), market volume and the current BIN book. The
 * valuation itself lives in {@link AhFireSaleModel}.
 */
public final class AhFireSaleSkinTracker {
    private static final long DAY_MS = 24L * 60L * 60_000L;
    /** The full history is daily data; refreshing it every few hours is plenty. */
    private static final long HISTORY_TTL_MS = 6L * 60L * 60_000L;
    private static final long SKIN_LIST_TTL_MS = 24L * 60L * 60_000L;
    private static final String RESOURCE_URL =
            "https://api.hypixel.net/v2/resources/skyblock/items";

    public static final class SkinCandidate {
        public String tag = "";
        public String name = "";
        public long buyPrice;
        public long historicalAverage;
        public long historicalMin;
        public long historicalMax;
        public long target;
        public long qty;
        public long totalProfit;
        public long profitPerDay;
        public double roiPercent;
        public double trendPerDayPercent;
        public double weeklyTrendPerDayPercent;
        public double confidence;
        public int monthlySales;
        public int weeklySales;
        public int activeListings;
        public int historyDays;
        public int holdingDays;
        /** Days since the first AH sale of the skin. */
        public int ageDays;
        /** How far the buy price is below the fair value (%). */
        public double discountPercent;
        public long fairValue;
        public long[] graphPrices = new long[0];
        public String note = "";
    }

    public static final class Snapshot {
        public volatile boolean enabled;
        public volatile boolean scanning;
        public volatile int totalSkins;
        public volatile int processedSkins;
        public volatile String currentTag = "";
        public volatile long updatedAt;
        public volatile String error = "";
        public volatile long bankroll;
        public volatile int candidateCount;
        public volatile List<SkinCandidate> top = List.of();
    }

    record HistoryPoint(long time, long min, long max, long avg, int volume) {
    }

    /**
     * {@code full} is the complete daily history since the first sale;
     * {@code month}/{@code week} are the last 30/7 days of it.
     */
    record HistoryData(List<HistoryPoint> full, List<HistoryPoint> month,
                       List<HistoryPoint> week) {
    }

    record SkinMeta(String name, String tier, String type, long releaseGuessAt) {
    }

    private record CachedHistory(HistoryData data, long at) {
    }

    private static final Snapshot SNAPSHOT = new Snapshot();
    private static final Map<String, CachedHistory> HISTORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, SkinMeta> SKIN_NAMES = new ConcurrentHashMap<>();
    private static volatile long skinNamesAt;
    private static volatile boolean running;
    private static volatile boolean refreshRequested;
    private static volatile int generation;
    private static volatile Thread worker;

    private AhFireSaleSkinTracker() {
    }

    static void init() {
        SNAPSHOT.enabled = AhFlipperMod.CONFIG.fireSaleSkins;
        if (SNAPSHOT.enabled) {
            start();
        }
    }

    public static synchronized void start() {
        if (!AhFlipperMod.CONFIG.fireSaleSkins) {
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
        Thread thread = new Thread(() -> loop(run), "skycofl-firesale-invest");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        System.out.println("[AH-Invest] Fire-Sale-Skin-Tracker gestartet");
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
        System.out.println("[AH-Invest] Fire-Sale-Skin-Tracker gestoppt");
    }

    public static void requestRefresh() {
        if (!AhFlipperMod.CONFIG.fireSaleSkins) {
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
                System.out.println("[AH-Invest] Fire-Sale-Scan-Fehler: " + e);
            } finally {
                if (generation == run) {
                    SNAPSHOT.scanning = false;
                }
            }
            if (!running || generation != run) {
                break;
            }
            long wait = Math.max(5, AhFlipperMod.CONFIG.fireSaleRefreshMinutes) * 60_000L;
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
        SNAPSHOT.processedSkins = 0;
        SNAPSHOT.totalSkins = 0;
        SNAPSHOT.currentTag = "";
        long started = System.currentTimeMillis();

        Map<String, SkinMeta> skins = fireSaleSkins();
        if (skins.isEmpty()) {
            SNAPSHOT.error = "Fire-Sale-Skin-Liste nicht abrufbar";
            return;
        }
        Map<String, AhMarketData.MarketEntry> market = AhMarketData.market();
        if (market.isEmpty()) {
            SNAPSHOT.error = "Marktdaten nicht abrufbar";
            return;
        }
        AhFlipperConfig config = AhFlipperMod.CONFIG;
        long minPrice = Math.max(0, config.fireSaleMinPrice);
        long maxPrice = config.fireSaleMaxPrice > 0 ? config.fireSaleMaxPrice : Long.MAX_VALUE;

        List<String> tags = new ArrayList<>();
        for (Map.Entry<String, AhMarketData.MarketEntry> entry : market.entrySet()) {
            String tag = entry.getKey();
            if (tag == null || tag.isBlank() || entry.getValue() == null) {
                continue;
            }
            String upper = tag.toUpperCase(Locale.ROOT);
            if (!skins.containsKey(upper)) {
                continue;
            }
            AhMarketData.MarketEntry value = entry.getValue();
            long price = value.now > 0 ? value.now
                    : value.recent > 0 ? value.recent : value.monthly;
            if (price < minPrice || price > maxPrice) {
                continue;
            }
            if (config.isBlockedItem(upper, "")) {
                continue;
            }
            tags.add(upper);
        }
        tags.sort(Comparator.comparingDouble(
                (String tag) -> marketVolume(market, tag)).reversed());
        int limit = Math.max(5, Math.min(300, config.fireSaleMaxTags));
        if (tags.size() > limit) {
            tags = new ArrayList<>(tags.subList(0, limit));
        }

        SNAPSHOT.totalSkins = tags.size();
        long bankroll = currentBankroll();
        SNAPSHOT.bankroll = bankroll;
        List<SkinCandidate> candidates = new ArrayList<>();
        for (String tag : tags) {
            if (!running || generation != run) {
                return;
            }
            SNAPSHOT.currentTag = tag;
            try {
                SkinCandidate candidate = analyzeSkin(tag, skins.get(tag),
                        market.get(tag), bankroll);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            } catch (Exception e) {
                System.out.println("[AH-Invest] Skin " + tag + " Fehler: " + e.getMessage());
            }
            SNAPSHOT.processedSkins++;
            sleepInterruptibly(Math.max(0, config.fireSaleTagDelayMs));
        }
        candidates.sort((first, second) -> Double.compare(score(second), score(first)));
        int topCount = Math.max(1, Math.min(10, config.fireSaleTopCount));
        List<SkinCandidate> top = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SkinCandidate candidate : candidates) {
            if (seen.add(candidate.tag)) {
                top.add(candidate);
            }
            if (top.size() >= topCount) {
                break;
            }
        }
        AhFireSaleHistoryStore.saveIfDue(true);
        if (generation != run) {
            return;
        }
        SNAPSHOT.candidateCount = candidates.size();
        SNAPSHOT.top = List.copyOf(top);
        SNAPSHOT.updatedAt = System.currentTimeMillis();
        SNAPSHOT.scanning = false;
        System.out.println("[AH-Invest] Fire-Sale-Scan fertig: " + tags.size()
                + " Skins, " + candidates.size() + " Kandidaten, Top " + top.size()
                + " in " + ((System.currentTimeMillis() - started) / 1000) + "s");
        for (int index = 0; index < top.size(); index++) {
            SkinCandidate candidate = top.get(index);
            System.out.println("[AH-Invest] Skin #" + (index + 1) + " " + candidate.name
                    + " [" + candidate.tag + "] buy=" + candidate.buyPrice
                    + " target=" + candidate.target + " qty=" + candidate.qty
                    + " hold=" + candidate.holdingDays + "d"
                    + " age=" + candidate.ageDays + "d fair=" + candidate.fairValue
                    + String.format(Locale.US, " disc=%.1f%%", candidate.discountPercent)
                    + String.format(Locale.US, " roi=%.1f%%", candidate.roiPercent)
                    + String.format(Locale.US, " trend=%.2f%%/d", candidate.trendPerDayPercent)
                    + String.format(Locale.US, " conf=%.0f%%", candidate.confidence * 100));
        }
        AhFireSaleBacktest.runAsync();
        AhFireSaleComps.runAsync();
    }

    private static SkinCandidate analyzeSkin(String tag, SkinMeta meta,
                                              AhMarketData.MarketEntry marketEntry,
                                              long bankroll) {
        AhFlipperConfig config = AhFlipperMod.CONFIG;
        List<AhMarketData.ActiveBin> bins = AhMarketData.activeBins(tag);
        List<Long> book = new ArrayList<>();
        if (bins != null) {
            for (AhMarketData.ActiveBin bin : bins) {
                if (bin == null || bin.price() <= 0) {
                    continue;
                }
                book.add(bin.price());
            }
        }
        book.sort(null);

        HistoryData history = historyData(tag);
        List<HistoryPoint> full = history.full();
        List<HistoryPoint> month = history.month();
        List<HistoryPoint> week = history.week();
        int monthlySales = totalVolume(month);
        int weeklySales = totalVolume(week);
        long marketNow = marketEntry == null ? 0 : marketEntry.now;
        long marketMonthly = marketEntry == null ? 0 : marketEntry.monthly;
        double marketVolume = marketEntry == null ? 0 : marketEntry.volume;
        long snapshotPrice = book.isEmpty() ? Math.max(marketNow, marketMonthly)
                : book.get(0);
        long firstSaleAt = full.isEmpty() ? 0 : full.get(0).time();
        if (snapshotPrice > 0) {
            AhFireSaleHistoryStore.Snapshot snapshot = new AhFireSaleHistoryStore.Snapshot();
            snapshot.at = System.currentTimeMillis();
            snapshot.price = snapshotPrice;
            snapshot.marketNow = marketNow;
            snapshot.marketMonthly = marketMonthly;
            snapshot.volume = marketVolume;
            snapshot.listings = book.size();
            snapshot.monthSales = monthlySales;
            snapshot.weekSales = weeklySales;
            // The first AH sale is a much better release date than the texture
            // timestamp (that is when the texture was made, often months earlier).
            long release = firstSaleAt > 0 ? firstSaleAt
                    : meta == null ? 0 : meta.releaseGuessAt();
            AhFireSaleHistoryStore.record(tag,
                    meta == null ? tag : meta.name(),
                    meta == null ? "" : meta.tier(),
                    meta == null ? "" : meta.type(),
                    release,
                    snapshot);
        }

        if (book.isEmpty() || full.isEmpty()) {
            return null;
        }
        long cheapest = book.get(0);
        if (cheapest < config.fireSaleMinPrice
                || (config.fireSaleMaxPrice > 0 && cheapest > config.fireSaleMaxPrice)) {
            return null;
        }
        long now = System.currentTimeMillis();
        int ageDays = (int) Math.max(0, (now - firstSaleAt) / DAY_MS);
        int minAge = Math.max(30, config.fireSaleMinAgeDays);
        if (ageDays < minAge) {
            // Fresh Fire Sale skins keep falling for months; see AhFireSaleModel.
            return null;
        }
        int minimumSales = Math.max(1, config.fireSaleMinSales);
        if (monthlySales < minimumSales) {
            return null;
        }

        // Quantity first, so the ROI is computed on the real average entry price.
        double slippage = Math.max(0, config.fireSaleSlippagePercent) / 100.0;
        long slipCap = Math.round(cheapest * (1.0 + slippage));
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
        long maxByBankroll = bankroll > 0
                ? Math.max(1, bankroll / Math.max(1, slipCap)) : Long.MAX_VALUE;
        // Selling much more than ~1/6 of the monthly volume within the holding
        // period would push the price down by itself.
        long maxByVolume = Math.max(1, monthlySales / 6);
        long qty = Math.min(Math.min(available, maxByBankroll),
                Math.min(maxByVolume, Math.max(1, config.fireSaleMaxQty)));
        qty = Math.max(1, qty);
        long sum = 0;
        for (int i = 0; i < qty; i++) {
            sum += book.get(i);
        }
        long buyPrice = Math.max(1, Math.round(sum / (double) qty));

        int holdDays = Math.max(7, Math.min(180, config.fireSaleHoldDays));
        double fee = Math.max(0, Math.min(100, config.ahFeePercent)) / 100.0;
        AhFireSaleModel.Projection projection =
                AhFireSaleModel.project(full, now, buyPrice, holdDays, fee);
        if (!projection.dataProblem.isEmpty()) {
            return null;
        }
        if (projection.roiPercent < config.fireSaleMinRoiPercent) {
            return null;
        }
        // Oversupply: many more listings than monthly sales => slow exit.
        double supply = 1.0 - clamp(book.size() / (double) Math.max(1, monthlySales * 2),
                0, 0.5);
        double confidence = clamp(projection.confidence * supply, 0, 1);
        if (confidence < config.fireSaleMinConfidence) {
            return null;
        }

        long totalProfit = Math.round((projection.target * (1.0 - fee) - buyPrice) * qty);
        long profitPerDay = Math.max(1, Math.round(totalProfit / (double) holdDays));
        long historicalMin = Long.MAX_VALUE;
        long historicalMax = 0;
        for (HistoryPoint point : AhFireSaleModel.window(full, now, 90)) {
            historicalMin = Math.min(historicalMin, point.min());
            historicalMax = Math.max(historicalMax, point.max());
        }
        if (historicalMin == Long.MAX_VALUE) {
            historicalMin = projection.median90;
        }

        SkinCandidate candidate = new SkinCandidate();
        candidate.tag = tag;
        candidate.name = meta == null || meta.name() == null || meta.name().isBlank()
                ? prettyTag(tag) : meta.name();
        candidate.buyPrice = buyPrice;
        candidate.historicalAverage = projection.median90;
        candidate.historicalMin = historicalMin;
        candidate.historicalMax = Math.max(historicalMax, projection.median90);
        candidate.fairValue = projection.fair;
        candidate.target = projection.target;
        candidate.qty = qty;
        candidate.totalProfit = totalProfit;
        candidate.profitPerDay = profitPerDay;
        candidate.roiPercent = projection.roiPercent;
        candidate.discountPercent = projection.discountPercent;
        candidate.trendPerDayPercent = projection.trend30PercentPerDay;
        candidate.weeklyTrendPerDayPercent = AhFireSaleModel.logSlopePerDay(week) * 100.0;
        candidate.confidence = confidence;
        candidate.monthlySales = monthlySales;
        candidate.weeklySales = weeklySales;
        candidate.activeListings = book.size();
        candidate.ageDays = ageDays;
        candidate.historyDays = ageDays;
        candidate.holdingDays = holdDays;
        candidate.graphPrices = graphPrices(full, now);
        candidate.note = String.format(Locale.US,
                "%dd alt · %.0f%% unter Fair %s · %d Sales/30d · %d Listings",
                ageDays, projection.discountPercent, ScreenUtil.formatCoins(projection.fair),
                monthlySales, book.size());
        return candidate;
    }

    /**
     * Ranking: expected ROI weighted by confidence and liquidity. Momentum is
     * deliberately not rewarded (it was a negative signal in the data).
     */
    private static double score(SkinCandidate candidate) {
        double liquidity = clamp(candidate.monthlySales / 15.0, 0.3, 1.0);
        return candidate.roiPercent * candidate.confidence * liquidity;
    }

    static Map<String, SkinMeta> fireSaleSkins() {
        long now = System.currentTimeMillis();
        if (!SKIN_NAMES.isEmpty() && now - skinNamesAt < SKIN_LIST_TTL_MS) {
            return SKIN_NAMES;
        }
        try {
            String body = AhMarketData.getUrl(RESOURCE_URL);
            if (body == null) {
                return SKIN_NAMES;
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray items = root.getAsJsonArray("items");
            if (items == null) {
                return SKIN_NAMES;
            }
            Map<String, SkinMeta> parsed = new ConcurrentHashMap<>();
            for (JsonElement element : items) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String id = string(item, "id").toUpperCase(Locale.ROOT);
                if (id.isBlank() || !isFireSaleSkin(id, item)) {
                    continue;
                }
                String name = string(item, "name");
                String tier = string(item, "tier").toUpperCase(Locale.ROOT);
                String type = id.startsWith("PET_SKIN_") ? "PET" : "ITEM";
                long releaseGuess = 0;
                if (item.has("skin") && item.get("skin").isJsonObject()) {
                    releaseGuess = AhFireSaleHistoryStore.textureTimestamp(
                            string(item.getAsJsonObject("skin"), "value"));
                }
                parsed.put(id, new SkinMeta(
                        name.isBlank() ? prettyTag(id) : name, tier, type, releaseGuess));
            }
            if (!parsed.isEmpty()) {
                SKIN_NAMES.clear();
                SKIN_NAMES.putAll(parsed);
                skinNamesAt = now;
            }
        } catch (Exception e) {
            System.out.println("[AH-Invest] Skin-Liste Fehler: " + e.getMessage());
        }
        return SKIN_NAMES;
    }

    private static boolean isFireSaleSkin(String id, JsonObject item) {
        String category = string(item, "category");
        String material = string(item, "material").toUpperCase(Locale.ROOT);
        if (!"COSMETIC".equalsIgnoreCase(category)) {
            return false;
        }
        if (id.endsWith("_PERSONALITY")
                || id.endsWith("_BARN_SKIN")
                || id.endsWith("_GREENHOUSE_SKIN")
                || id.equals("RUNE") || id.equals("UNIQUE_RUNE")
                || id.contains("STATUE") || id.contains("DISPLAY")
                || id.equals("BOOK_OF_STATS")) {
            return false;
        }
        return "SKULL_ITEM".equals(material) || id.startsWith("PET_SKIN_");
    }

    static HistoryData cachedHistory(String tag) {
        CachedHistory cached = HISTORY_CACHE.get(tag);
        if (cached == null) {
            return null;
        }
        if (System.currentTimeMillis() - cached.at() > HISTORY_TTL_MS) {
            return null;
        }
        return cached.data();
    }

    static synchronized HistoryData historyData(String tag) {
        long now = System.currentTimeMillis();
        CachedHistory cached = HISTORY_CACHE.get(tag);
        if (cached != null && now - cached.at() < HISTORY_TTL_MS) {
            return cached.data();
        }
        List<HistoryPoint> full = fetchHistory(tag, "full");
        if (full.isEmpty()) {
            // Fallback for tags the full endpoint does not know (yet).
            full = fetchHistory(tag, "month");
        }
        if (full.isEmpty() && cached != null) {
            // Keep the last good data on a temporary API error.
            return cached.data();
        }
        HistoryData data = new HistoryData(full,
                AhFireSaleModel.window(full, now, 30),
                AhFireSaleModel.window(full, now, 7));
        HISTORY_CACHE.put(tag, new CachedHistory(data, now));
        if (HISTORY_CACHE.size() > 500) {
            HISTORY_CACHE.entrySet().removeIf(
                    entry -> now - entry.getValue().at() > HISTORY_TTL_MS * 2);
        }
        return data;
    }

    private static List<HistoryPoint> fetchHistory(String tag, String period) {
        String body = AhMarketData.getUrl("https://sky.coflnet.com/api/item/price/"
                + tag + "/history/" + period);
        if (body == null) {
            return List.of();
        }
        try {
            JsonArray array = JsonParser.parseString(body).getAsJsonArray();
            List<HistoryPoint> points = new ArrayList<>();
            for (JsonElement element : array) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                long time = parseTime(string(object, "time"));
                long min = Math.round(doubleValue(object, "min"));
                long max = Math.round(doubleValue(object, "max"));
                long avg = Math.round(doubleValue(object, "avg"));
                int volume = (int) Math.max(0, doubleValue(object, "volume"));
                if (time > 0 && avg > 0) {
                    points.add(new HistoryPoint(time, min, max, avg, volume));
                }
            }
            points.sort(Comparator.comparingLong(HistoryPoint::time));
            return points;
        } catch (Exception e) {
            System.out.println("[AH-Invest] Skin-History Fehler " + tag + "/"
                    + period + ": " + e.getMessage());
            return List.of();
        }
    }

    /** Last 90 days of daily averages for the sparkline. */
    private static long[] graphPrices(List<HistoryPoint> full, long now) {
        List<HistoryPoint> points = AhFireSaleModel.window(full, now, 90);
        if (points.isEmpty()) {
            return new long[0];
        }
        long[] values = new long[points.size()];
        for (int i = 0; i < points.size(); i++) {
            values[i] = points.get(i).avg();
        }
        return values;
    }

    private static int totalVolume(List<HistoryPoint> points) {
        int total = 0;
        for (HistoryPoint point : points) {
            total += point.volume();
        }
        return total;
    }

    private static double marketVolume(Map<String, AhMarketData.MarketEntry> market, String tag) {
        AhMarketData.MarketEntry entry = market.get(tag);
        return entry == null ? 0 : entry.volume;
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

    private static long parseTime(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static long doubleValue(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return Math.round(object.get(key).getAsDouble());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
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
        return builder.length() == 0 ? tag : builder.toString();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

