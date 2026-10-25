package com.coflnet.ahflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AhMarketData {
    private static final String BASE = "https://sky.coflnet.com/api";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Map<String, SoldCache> SOLD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, AnalysisCache> ANALYSIS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ActiveCache> ACTIVE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, AuctionCache> AUCTION_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CraftCache> CRAFT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BreakdownCache> BREAKDOWN_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> BREAKDOWN_SUPPRESSED = new ConcurrentHashMap<>();
    private static final Object HTTP_LOCK = new Object();
    private static long lastRequestAt;
    private static volatile MarketCache marketCache;

    public static class Sold {
        public long price;
        public long end;
        public String itemName = "";
        public boolean bin;
        public int petLevel;
        public String petRarity = "";
        public String petItem = "";
        public final List<Enchant> enchants = new ArrayList<>();
        public final JsonObject nbt = new JsonObject();
    }

    public static class RobustCv {
        public double cv;
        public long median;
        public int sample;
        public int excluded;
    }

    public static class Enchant {
        public String type = "";
        public int level;
    }

    public static class Analysis {
        public long totalSales;
        public long medianPrice;
        public double avgPrice;
        public double coeffVariation;
        public long minPrice;
        public long maxPrice;
    }

    public static class AddonPrice {
        public long average;
        public int sample;
        public int excluded;
    }

    @FunctionalInterface
    public interface SaleFilter {
        boolean matches(Sold sale);
    }

    public static class MarketEntry {
        public String tag = "";
        public long now;
        public long recent;
        public long monthly;
        public double volume;

        public long value() {
            long estimated = Math.max(recent, monthly);
            return estimated > 0 ? estimated : now;
        }
    }

    public record ActiveBin(String uuid, String seller, String playerName, long price) {
    }

    public static class AuctionDetails {
        public String uuid = "";
        public String tag = "";
        public String itemName = "";
        public String reforge = "";
        public String tier = "";
        public int count = 1;
        public long startingBid;
        public long end;
        public boolean bin;
        public final Map<String, Integer> enchants = new LinkedHashMap<>();
        public final JsonObject flatNbt = new JsonObject();
    }

    public static class CraftCost {
        public long craftCost;
        public long directBuyCost;
        public boolean craftable;
    }

    public static class BreakdownPart {
        public long price;
        public long count;
        public String itemTag = "";
        public String attribute = "";
        public String reason = "";
    }

    public static class Breakdown {
        public long total;
        public final List<BreakdownPart> parts = new ArrayList<>();
    }

    private record SoldCache(List<Sold> sold, long at) {
    }

    private record AuctionCache(AuctionDetails details, long at) {
    }

    private record CraftCache(CraftCost cost, long at) {
    }

    private record BreakdownCache(Breakdown breakdown, long at) {
    }

    private record AnalysisCache(Analysis analysis, long at) {
    }

    private record ActiveCache(List<ActiveBin> bins, long at) {
    }

    private record MarketCache(Map<String, MarketEntry> entries, long at) {
    }

    public static AuctionDetails auction(String uuid) {
        long now = System.currentTimeMillis();
        AuctionCache cache = AUCTION_CACHE.get(uuid);
        if (cache != null && now - cache.at < 15_000L) {
            return cache.details;
        }
        String body = get(BASE + "/auction/" + enc(uuid));
        if (body == null) {
            return cache != null ? cache.details : null;
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            AuctionDetails details = new AuctionDetails();
            details.uuid = stringValue(json, "uuid");
            details.tag = stringValue(json, "tag");
            details.itemName = stringValue(json, "itemName");
            details.reforge = stringValue(json, "reforge");
            details.tier = stringValue(json, "tier");
            details.count = json.has("count") ? json.get("count").getAsInt() : 1;
            details.startingBid = Math.max(longValue(json, "startingBid"),
                    longValue(json, "highestBidAmount"));
            details.bin = json.has("bin") && json.get("bin").getAsBoolean();
            details.end = parseTime(stringValue(json, "end"));
            JsonArray enchants = json.getAsJsonArray("enchantments");
            if (enchants != null) {
                for (JsonElement element : enchants) {
                    JsonObject enchant = element.getAsJsonObject();
                    String type = stringValue(enchant, "type");
                    int level = enchant.has("level") ? enchant.get("level").getAsInt() : 0;
                    if (!type.isBlank() && level > 0) {
                        details.enchants.merge(type.toLowerCase(java.util.Locale.ROOT), level, Math::max);
                    }
                }
            }
            JsonObject flatNbt = json.getAsJsonObject("flatNbt");
            if (flatNbt != null) {
                for (Map.Entry<String, JsonElement> entry : flatNbt.entrySet()) {
                    details.flatNbt.add(entry.getKey(), entry.getValue());
                }
            }
            AUCTION_CACHE.put(uuid, new AuctionCache(details, now));
            return details;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Auktionsdetails Fehler für " + uuid + ": " + e.getMessage());
            return cache != null ? cache.details : null;
        }
    }

    public static CraftCost craftCost(String tag) {
        long now = System.currentTimeMillis();
        CraftCache cache = CRAFT_CACHE.get(tag);
        if (cache != null && now - cache.at < 2 * 60_000L) {
            return cache.cost;
        }
        String body = get(BASE + "/craft/acquisition/" + enc(tag)
                + "?quantity=1&forceCraft=true");
        if (body == null) {
            return cache != null ? cache.cost : null;
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            CraftCost cost = new CraftCost();
            cost.craftCost = longValue(json, "craftCost");
            cost.directBuyCost = longValue(json, "directBuyCost");
            cost.craftable = "craft".equalsIgnoreCase(stringValue(json, "method"));
            if (cost.craftCost <= 0 && cost.craftable) {
                cost.craftCost = longValue(json, "cost");
            }
            CRAFT_CACHE.put(tag, new CraftCache(cost, now));
            return cost;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Craft-Cost Fehler für " + tag + ": " + e.getMessage());
            return cache != null ? cache.cost : null;
        }
    }

    public static Breakdown breakdown(AuctionDetails details) {
        if (details == null || details.uuid.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        BreakdownCache cache = BREAKDOWN_CACHE.get(details.uuid);
        if (cache != null && now - cache.at < 2 * 60_000L) {
            return cache.breakdown;
        }
        Long suppressed = BREAKDOWN_SUPPRESSED.get(details.uuid);
        if (suppressed != null && now < suppressed) {
            return cache != null ? cache.breakdown : null;
        }
        JsonObject item = new JsonObject();
        item.addProperty("tag", details.tag);
        item.addProperty("count", details.count);
        item.addProperty("itemName", details.itemName);
        item.add("extraAttributes", details.flatNbt);
        JsonObject enchants = new JsonObject();
        for (Map.Entry<String, Integer> enchant : details.enchants.entrySet()) {
            enchants.addProperty(enchant.getKey(), enchant.getValue());
        }
        item.add("enchantments", enchants);
        JsonArray request = new JsonArray();
        request.add(item);
        String body = post(BASE + "/mod/pricing/breakdown", request.toString());
        if (body == null) {
            BREAKDOWN_SUPPRESSED.put(details.uuid, now + 10 * 60_000L);
            return cache != null ? cache.breakdown : null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            JsonObject first;
            if (parsed.isJsonArray()) {
                JsonArray response = parsed.getAsJsonArray();
                if (response.isEmpty()) {
                    return null;
                }
                first = response.get(0).getAsJsonObject();
            } else if (parsed.isJsonObject()) {
                first = parsed.getAsJsonObject();
            } else {
                return null;
            }
            JsonArray prices = first.getAsJsonArray("craftPrice");
            Breakdown breakdown = new Breakdown();
            if (prices != null) {
                for (JsonElement element : prices) {
                    JsonObject part = element.getAsJsonObject();
                    BreakdownPart breakdownPart = new BreakdownPart();
                    breakdownPart.price = longValue(part, "price");
                    breakdownPart.count = longValue(part, "count");
                    breakdownPart.itemTag = stringValue(part, "itemTag");
                    breakdownPart.attribute = stringValue(part, "attribute");
                    breakdownPart.reason = stringValue(part, "formattedReson");
                    if (breakdownPart.reason.isBlank()) {
                        breakdownPart.reason = stringValue(part, "formattedReason");
                    }
                    breakdown.total += breakdownPart.price;
                    breakdown.parts.add(breakdownPart);
                }
            }
            BREAKDOWN_CACHE.put(details.uuid, new BreakdownCache(breakdown, now));
            return breakdown;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Breakdown Fehler fǬr " + details.uuid + ": " + e.getMessage());
            BREAKDOWN_SUPPRESSED.put(details.uuid, System.currentTimeMillis() + 10 * 60_000L);
            return cache != null ? cache.breakdown : null;
        }
    }

    public static List<ActiveBin> activeBins(String tag) {
        long now = System.currentTimeMillis();
        long ttl = Math.max(0, AhFlipperMod.CONFIG.scanActiveCacheSeconds) * 1000L;
        ActiveCache cache = ACTIVE_CACHE.get(tag);
        if (cache != null && ttl > 0 && now - cache.at < ttl) {
            return cache.bins;
        }
        List<ActiveBin> result = fetchActiveBins(tag);
        if (result != null) {
            ACTIVE_CACHE.put(tag, new ActiveCache(result, now));
            return result;
        }
        return cache != null ? cache.bins : List.of();
    }

    private static List<ActiveBin> fetchActiveBins(String tag) {
        String body = get(BASE + "/auctions/tag/" + enc(tag)
                + "/active/overview?query%5BBin%5D=true");
        if (body == null) {
            return null;
        }
        try {
            JsonArray array = JsonParser.parseString(body).getAsJsonArray();
            List<ActiveBin> bins = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject json = element.getAsJsonObject();
                String uuid = json.has("uuid") && !json.get("uuid").isJsonNull()
                        ? json.get("uuid").getAsString() : "";
                String seller = stringValue(json, "seller");
                String playerName = stringValue(json, "playerName");
                long price = longValue(json, "price");
                if (!uuid.isBlank() && price > 0) {
                    bins.add(new ActiveBin(uuid, seller, playerName, price));
                }
            }
            bins.sort((a, b) -> Long.compare(a.price, b.price));
            return bins;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Active-Bins Fehler für " + tag + ": " + e.getMessage());
            return null;
        }
    }

    public static Map<String, MarketEntry> market() {
        long now = System.currentTimeMillis();
        MarketCache cache = marketCache;
        long ttl = Math.max(1, AhFlipperMod.CONFIG.scanPriceRefreshMinutes) * 60_000L;
        if (cache != null && now - cache.at < ttl) {
            return cache.entries;
        }
        String body = get(BASE + "/prices/change");
        if (body == null) {
            return cache != null ? cache.entries : Map.of();
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            Map<String, MarketEntry> entries = new ConcurrentHashMap<>();
            for (Map.Entry<String, JsonElement> property : json.entrySet()) {
                if (!property.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject value = property.getValue().getAsJsonObject();
                MarketEntry entry = new MarketEntry();
                entry.tag = property.getKey();
                entry.now = longValue(value, "now");
                entry.recent = longValue(value, "recent");
                entry.monthly = Math.round(doubleValue(value, "monthly"));
                entry.volume = doubleValue(value, "volume");
                if (entry.value() > 0 || entry.volume > 0) {
                    entries.put(entry.tag, entry);
                }
            }
            marketCache = new MarketCache(entries, now);
            return entries;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Marktpreis-Fehler: " + e.getMessage());
            return cache != null ? cache.entries : Map.of();
        }
    }

    public static List<Sold> sold(String tag) {
        long now = System.currentTimeMillis();
        SoldCache cache = SOLD_CACHE.get(tag);
        if (cache != null && now - cache.at < AhFlipperMod.CONFIG.analysisCacheMinutes * 60_000L) {
            return cache.sold;
        }
        List<Sold> result = fetchSold(tag);
        if (result != null) {
            SOLD_CACHE.put(tag, new SoldCache(result, now));
            return result;
        }
        return cache != null ? cache.sold : List.of();
    }

    private static List<Sold> fetchSold(String tag) {
        List<Sold> all = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        try {
            for (int page = 0; page < Math.max(1, AhFlipperMod.CONFIG.maxSoldPages); page++) {
                String url = BASE + "/auctions/tag/" + enc(tag) + "/sold?page=" + page + "&pageSize=1000";
                String body = get(url);
                if (body == null) {
                    break;
                }
                JsonArray array = JsonParser.parseString(body).getAsJsonArray();
                if (array.isEmpty()) {
                    break;
                }
                boolean reachedOld = false;
                for (JsonElement element : array) {
                    Sold sold = parseSold(element.getAsJsonObject());
                    if (sold.end > 0 && sold.end < cutoff) {
                        reachedOld = true;
                        continue;
                    }
                    all.add(sold);
                }
                if (reachedOld || array.size() < 1000) {
                    break;
                }
            }
            return all;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Sold-Daten Fehler für " + tag + ": " + e.getMessage());
            return null;
        }
    }

    private static Sold parseSold(JsonObject json) {
        Sold sold = new Sold();
        sold.price = longValue(json, "highestBidAmount");
        if (sold.price <= 0) {
            sold.price = longValue(json, "price");
        }
        if (sold.price <= 0) {
            sold.price = longValue(json, "startingBid");
        }
        sold.itemName = json.has("itemName") && !json.get("itemName").isJsonNull()
                ? json.get("itemName").getAsString() : "";
        sold.end = parseTime(json.has("end") && !json.get("end").isJsonNull()
                ? json.get("end").getAsString() : "");
        sold.bin = json.has("bin") && json.get("bin").getAsBoolean();
        sold.petLevel = AhItem.petLevel(sold.itemName);
        sold.petRarity = stringValue(json, "tier").toUpperCase(java.util.Locale.ROOT);
        JsonArray enchants = json.getAsJsonArray("enchantments");
        if (enchants != null) {
            for (JsonElement element : enchants) {
                JsonObject e = element.getAsJsonObject();
                Enchant enchant = new Enchant();
                enchant.type = e.has("type") ? e.get("type").getAsString() : "";
                enchant.level = e.has("level") ? e.get("level").getAsInt() : 0;
                if (!enchant.type.isBlank()) {
                    sold.enchants.add(enchant);
                }
            }
        }
        JsonObject nbt = json.getAsJsonObject("flattenedNbt");
        if (nbt == null) {
            nbt = json.getAsJsonObject("flatNbt");
        }
        if (nbt != null) {
            for (Map.Entry<String, JsonElement> entry : nbt.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && !value.isJsonNull()) {
                    sold.nbt.addProperty(entry.getKey(), value.getAsString());
                }
            }
        }
        if (sold.petRarity.isBlank() && sold.nbt.has("tier")
                && !sold.nbt.get("tier").isJsonNull()) {
            sold.petRarity = sold.nbt.get("tier").getAsString().toUpperCase(Locale.ROOT);
        }
        if (sold.nbt.has("petInfo") && !sold.nbt.get("petInfo").isJsonNull()) {
            try {
                JsonObject petJson = JsonParser.parseString(
                        sold.nbt.get("petInfo").getAsString()).getAsJsonObject();
                if (petJson.has("heldItem") && !petJson.get("heldItem").isJsonNull()) {
                    sold.petItem = petJson.get("heldItem").getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        return sold;
    }

    private static long parseTime(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return java.time.Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (Exception ignoredAgain) {
                return 0;
            }
        }
    }

    public static Analysis analysis(String tag, String extraQuery) {
        String cacheKey = tag + "|" + extraQuery;
        long now = System.currentTimeMillis();
        AnalysisCache cache = ANALYSIS_CACHE.get(cacheKey);
        if (cache != null && now - cache.at < AhFlipperMod.CONFIG.analysisCacheMinutes * 60_000L) {
            return cache.analysis;
        }
        String url = BASE + "/item/price/" + enc(tag) + "/analysis?days=7" + extraQuery;
        String body = get(url);
        if (body == null) {
            return cache != null ? cache.analysis : null;
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            Analysis analysis = new Analysis();
            analysis.totalSales = longValue(json, "totalSales");
            analysis.medianPrice = longValue(json, "medianPrice");
            analysis.avgPrice = doubleValue(json, "avgPrice");
            analysis.coeffVariation = doubleValue(json, "priceCoeffVariation");
            analysis.minPrice = longValue(json, "minPrice");
            analysis.maxPrice = longValue(json, "maxPrice");
            ANALYSIS_CACHE.put(cacheKey, new AnalysisCache(analysis, now));
            return analysis;
        } catch (Exception e) {
            return cache != null ? cache.analysis : null;
        }
    }

    public static long medianPrice(String tag) {
        Analysis analysis = analysis(tag, "");
        return analysis == null ? 0 : analysis.medianPrice;
    }

    public static long lowestCleanAverage(String tag, int petLevel, String petRarity,
                                          long sevenDayAverage) {
        long lowest = sevenDayAverage > 0 ? sevenDayAverage : Long.MAX_VALUE;
        long day = cleanAverageWindow(tag, petLevel, petRarity, 24L * 60 * 60 * 1000);
        if (day > 0) {
            lowest = Math.min(lowest, day);
        }
        long hour = cleanAverageWindow(tag, petLevel, petRarity, 60L * 60 * 1000);
        if (hour > 0) {
            lowest = Math.min(lowest, hour);
        }
        return lowest == Long.MAX_VALUE ? 0 : lowest;
    }

    public static long binReference(String tag, String excludeUuid) {
        List<ActiveBin> bins = activeBins(tag);
        for (ActiveBin bin : bins) {
            if (bin.price() <= 0) {
                continue;
            }
            if (!excludeUuid.isBlank() && bin.uuid().equalsIgnoreCase(excludeUuid)) {
                continue;
            }
            if (AhFlipperMod.isOwnSeller(bin.seller(), bin.playerName())) {
                continue;
            }
            return bin.price();
        }
        return 0;
    }

    public static long lowestReference(long cleanAverage, long lowestBin) {
        long reference = Long.MAX_VALUE;
        if (cleanAverage > 0) {
            reference = Math.min(reference, cleanAverage);
        }
        if (lowestBin > 0) {
            reference = Math.min(reference, lowestBin);
        }
        return reference == Long.MAX_VALUE ? 0 : reference;
    }

    private static long cleanAverageWindow(String tag, int petLevel, String petRarity,
                                           long windowMs) {
        List<Sold> all = sold(tag);
        if (all.isEmpty()) {
            return 0;
        }
        boolean pet = petLevel > 0 && petRarity != null && !petRarity.isBlank();
        long cutoff = System.currentTimeMillis() - windowMs;
        List<Long> prices = new ArrayList<>();
        for (Sold sale : all) {
            if (sale.price <= 0 || sale.end <= 0 || sale.end < cutoff) {
                continue;
            }
            if (pet) {
                if (sale.petLevel != petLevel || !petRarity.equalsIgnoreCase(sale.petRarity)) {
                    continue;
                }
            } else if (!isCleanSale(sale)) {
                continue;
            }
            prices.add(sale.price);
        }
        int minSales = Math.max(1, AhFlipperMod.CONFIG.cleanShortWindowMinSales);
        if (prices.size() < minSales) {
            return 0;
        }
        long sum = 0;
        for (long price : prices) {
            sum += price;
        }
        return Math.round((double) sum / prices.size());
    }

    public static long lowestBin(String tag) {
        List<ActiveBin> bins = activeBins(tag);
        return bins.isEmpty() ? 0 : bins.get(0).price();
    }

    public static AddonPrice addonAverage(String tag, SaleFilter filter) {
        if (filter == null) {
            return null;
        }
        List<Long> prices = new ArrayList<>();
        for (Sold sale : sold(tag)) {
            if (sale.price > 0 && filter.matches(sale)) {
                prices.add(sale.price);
            }
        }
        if (prices.isEmpty()) {
            return null;
        }
        prices.sort(null);
        long baseline = median(prices);
        if (baseline <= 0) {
            return null;
        }
        long low = Math.round(baseline * 0.80);
        long high = Math.round(baseline * 1.20);
        long sum = 0;
        int kept = 0;
        for (Long price : prices) {
            if (price >= low && price <= high) {
                sum += price;
                kept++;
            }
        }
        if (kept == 0) {
            return null;
        }
        AddonPrice result = new AddonPrice();
        result.average = Math.round((double) sum / kept);
        result.sample = kept;
        result.excluded = prices.size() - kept;
        return result;
    }

    public static AddonPrice averageForEnchant(String tag, String type, int level) {
        return addonAverage(tag, sale -> hasEnchant(sale, type, level));
    }

    public static AddonPrice averageForHpb(String tag, int hpb) {
        int wanted = Math.max(1, hpb);
        return addonAverage(tag, sale -> nbtInt(sale, "hpc") >= wanted);
    }

    public static AddonPrice averageForStars(String tag, int stars) {
        int wanted = Math.max(1, stars);
        return addonAverage(tag, sale -> nbtInt(sale, "upgrade_level") >= wanted
                || nbtInt(sale, "dungeon_item_level") >= wanted);
    }

    public static long cleanStarPremium(String tag, AhItem item, long cleanBaseline) {
        if (item == null || item.stars <= 0 || cleanBaseline <= 0) {
            return 0;
        }
        int wanted = Math.max(1, item.stars);
        AddonPrice price = addonAverage(tag, sale -> isCleanSale(sale)
                && (nbtInt(sale, "upgrade_level") >= wanted
                || nbtInt(sale, "dungeon_item_level") >= wanted));
        if (price == null || price.average <= cleanBaseline
                || price.sample < AhFlipperMod.CONFIG.addonMinSalesPerWeek) {
            return 0;
        }
        return price.average - cleanBaseline;
    }

    public static AddonPrice averageForReforge(String tag, String reforge) {
        return addonAverage(tag, sale -> matchesReforge(sale, reforge));
    }

    public static AddonPrice averageForPetItem(String tag, int petLevel, String petRarity,
                                               String petItem) {
        return addonAverage(tag, sale -> sale.petLevel == petLevel
                && petRarity != null && !petRarity.isBlank()
                && petRarity.equalsIgnoreCase(sale.petRarity)
                && petItem != null && !petItem.isBlank()
                && petItem.equalsIgnoreCase(sale.petItem));
    }

    public static AddonPrice averageForProduct(String tag, String productId,
                                               String attribute, AhItem item) {
        String product = productId == null ? "" : productId.toUpperCase(java.util.Locale.ROOT);
        String attr = attribute == null ? "" : attribute.toLowerCase(java.util.Locale.ROOT);
        if (product.endsWith("_GEM")) {
            return null;
        }
        if (product.startsWith("ENCHANTMENT_")) {
            String rest = product.substring("ENCHANTMENT_".length());
            int split = rest.lastIndexOf('_');
            if (split > 0) {
                try {
                    return averageForEnchant(tag, rest.substring(0, split).toLowerCase(java.util.Locale.ROOT),
                            Integer.parseInt(rest.substring(split + 1)));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        if (product.contains("RECOMBOBULATOR") || attr.contains("rarity_upgrades")) {
            return addonAverage(tag, sale -> nbtInt(sale, "rarity_upgrades") > 0);
        }
        if (product.contains("HOT_POTATO") || attr.contains("hpc") || attr.contains("hot_potato")) {
            return averageForHpb(tag, item == null ? 1 : item.hpb);
        }
        if (product.contains("ART_OF_WAR") || attr.contains("art_of_war")) {
            return addonAverage(tag, sale -> nbtInt(sale, "art_of_war_count") > 0);
        }
        if (product.contains("WOOD_SINGULARITY") || attr.contains("wood_singularity")) {
            return addonAverage(tag, sale -> nbtInt(sale, "wood_singularity_count") > 0);
        }
        if (product.contains("_SCROLL") || attr.contains("ability_scroll")) {
            return addonAverage(tag, sale -> hasScroll(sale, product));
        }
        if (product.startsWith("DYE_") || attr.equals("dye_item")) {
            return addonAverage(tag, sale -> matchesNbtText(sale, "dye_item", product));
        }
        if (attr.contains("upgrade_level") || attr.contains("dungeon_item_level")) {
            return averageForStars(tag, item == null ? 1 : item.stars);
        }
        if (attr.contains("modifier") || attr.contains("reforge")) {
            return averageForReforge(tag, item == null ? "" : item.reforge);
        }
        return null;
    }

    public static AddonPrice averageForComparable(String tag, AhItem item, long minAddonValue) {
        if (item == null) {
            return null;
        }
        long minValue = Math.max(0, minAddonValue);
        List<Long> prices = new ArrayList<>();
        int matched = 0;
        for (Sold sale : sold(tag)) {
            if (sale.price <= 0 || !matchesComparable(sale, item, minValue)) {
                continue;
            }
            matched++;
            double extra = extraAddonBuyValue(sale, item, minValue);
            if (extra >= sale.price * 0.15) {
                continue;
            }
            long adjusted = Math.round(sale.price - extra);
            if (adjusted > 0) {
                prices.add(adjusted);
            }
        }
        if (prices.isEmpty()) {
            return null;
        }
        prices.sort(null);
        long median = median(prices);
        if (median <= 0) {
            return null;
        }
        double outlier = Math.max(0.01,
                AhFlipperMod.CONFIG.comparableOutlierPercent / 100.0);
        long low = Math.round(median * (1.0 - outlier));
        long high = Math.round(median * (1.0 + outlier));
        long sum = 0;
        int kept = 0;
        for (Long price : prices) {
            if (price >= low && price <= high) {
                sum += price;
                kept++;
            }
        }
        if (kept == 0) {
            return null;
        }
        AddonPrice result = new AddonPrice();
        result.average = Math.round((double) sum / kept);
        result.sample = kept;
        result.excluded = matched - kept;
        return result;
    }

    private static boolean matchesComparable(Sold sale, AhItem item, long minValue) {
        if (hasCosmetic(sale)) {
            return false;
        }
        Map<String, Integer> soldGems = soldGemstones(sale);
        for (Map.Entry<String, Integer> gem : item.gemstones.entrySet()) {
            if (AhAddonValues.productValue(gem.getKey()) <= minValue) {
                continue;
            }
            if (soldGems.getOrDefault(gem.getKey(), 0) < gem.getValue()) {
                return false;
            }
        }
        if (!item.unlockedGemSlots.isEmpty()
                && AhAddonValues.slotUnlockValue(item.tag, item.unlockedGemSlots, false) > minValue
                && !soldUnlockedSlots(sale).containsAll(item.unlockedGemSlots)) {
            return false;
        }
        if (item.petLevel > 0) {
            if (sale.petLevel != item.petLevel) {
                return false;
            }
            if (item.petRarity != null && !item.petRarity.isBlank()
                    && !item.petRarity.equalsIgnoreCase(sale.petRarity)) {
                return false;
            }
        }
        Map<String, Integer> soldEnchants = soldEnchants(sale);
        for (Map.Entry<String, Integer> enchant : item.enchants.entrySet()) {
            if (enchant.getValue() <= 0) {
                continue;
            }
            if (AhAddonValues.enchantValue(enchant.getKey(), enchant.getValue()) <= minValue) {
                continue;
            }
            if (soldEnchants.getOrDefault(enchant.getKey().toLowerCase(Locale.ROOT), 0)
                    < enchant.getValue()) {
                return false;
            }
        }
        if (AhAddonValues.hpbValue(item.hpb) > minValue && saleHpb(sale) < item.hpb) {
            return false;
        }
        if (item.recomb && AhAddonValues.recombValue() > minValue
                && nbtInt(sale, "rarity_upgrades") <= 0) {
            return false;
        }
        if (AhAddonValues.artOfWarValue(item.artOfWar) > minValue
                && nbtInt(sale, "art_of_war_count") < item.artOfWar) {
            return false;
        }
        if (AhAddonValues.productValue("WOOD_SINGULARITY") * item.woodSingularity > minValue
                && nbtInt(sale, "wood_singularity_count") < item.woodSingularity) {
            return false;
        }
        if (!sameReforge(sale, item.reforge)) {
            return false;
        }
        if (saleStars(sale) != item.stars) {
            return false;
        }
        if (item.killKey != null && !item.killKey.isBlank()) {
            int itemKills = Math.max(0, item.kills);
            int saleKills = nbtInt(sale, item.killKey);
            if (itemKills <= 0) {
                if (saleKills > 500) {
                    return false;
                }
            } else {
                long low = itemKills / 2L;
                long high = itemKills * 2L;
                if (saleKills < low || saleKills > high) {
                    return false;
                }
            }
        }
        return containsAllScrolls(sale, item, minValue);
    }

    private static double extraAddonBuyValue(Sold sale, AhItem item, long minValue) {
        double extra = 0;
        for (Map.Entry<String, Integer> enchant : soldEnchants(sale).entrySet()) {
            int ours = item.enchants.getOrDefault(enchant.getKey(), 0);
            if (enchant.getValue() <= ours) {
                continue;
            }
            double value = AhAddonValues.instantBuyEnchantValue(enchant.getKey(), enchant.getValue());
            if (ours > 0) {
                value -= AhAddonValues.instantBuyEnchantValue(enchant.getKey(), ours);
            }
            if (value > minValue) {
                extra += value;
            }
        }
        int soldHpb = saleHpb(sale);
        if (soldHpb > item.hpb) {
            double value = AhAddonValues.instantBuyProductValue("HOT_POTATO_BOOK")
                    * (soldHpb - item.hpb);
            if (value > minValue) {
                extra += value;
            }
        }
        if (!item.recomb && nbtInt(sale, "rarity_upgrades") > 0) {
            double value = AhAddonValues.instantBuyProductValue("RECOMBOBULATOR_3000");
            if (value > minValue) {
                extra += value;
            }
        }
        int soldArtOfWar = nbtInt(sale, "art_of_war_count");
        if (soldArtOfWar > item.artOfWar) {
            double value = AhAddonValues.instantBuyProductValue("THE_ART_OF_WAR")
                    * (soldArtOfWar - item.artOfWar);
            if (value > minValue) {
                extra += value;
            }
        }
        int soldWood = nbtInt(sale, "wood_singularity_count");
        if (soldWood > item.woodSingularity) {
            double value = AhAddonValues.instantBuyProductValue("WOOD_SINGULARITY")
                    * (soldWood - item.woodSingularity);
            if (value > minValue) {
                extra += value;
            }
        }
        Set<String> ourScrolls = normalizedScrolls(item.abilityScrolls);
        for (String scroll : soldScrolls(sale)) {
            if (!ourScrolls.contains(scroll)) {
                double value = AhAddonValues.instantBuyProductValue(scroll);
                if (value > minValue) {
                    extra += value;
                }
            }
        }
        Map<String, Integer> soldGems = soldGemstones(sale);
        for (Map.Entry<String, Integer> gem : soldGems.entrySet()) {
            int ours = item.gemstones.getOrDefault(gem.getKey(), 0);
            if (gem.getValue() <= ours) {
                continue;
            }
            double value = AhAddonValues.instantBuyProductValue(gem.getKey());
            if (value > minValue) {
                extra += value * (gem.getValue() - ours);
            }
        }
        Set<String> extraSlots = new LinkedHashSet<>(soldUnlockedSlots(sale));
        extraSlots.removeAll(item.unlockedGemSlots);
        if (!extraSlots.isEmpty()) {
            extra += AhAddonValues.slotUnlockValue(item.tag, extraSlots, true);
        }
        return Math.max(0, extra);
    }

    private static Map<String, Integer> soldGemstones(Sold sale) {
        Map<String, Integer> gems = new LinkedHashMap<>();
        AhItem.parseFlatGems(sale.nbt, gems, null);
        return gems;
    }

    private static Set<String> soldUnlockedSlots(Sold sale) {
        Set<String> slots = new LinkedHashSet<>();
        AhItem.parseFlatGems(sale.nbt, null, slots);
        return slots;
    }

    private static boolean hasCosmetic(Sold sale) {
        if (hasNbt(sale, "dye_item") || hasNbt(sale, "skin")) {
            return true;
        }
        String petInfo = nbtText(sale, "petInfo");
        return !petInfo.isBlank()
                && petInfo.toLowerCase(Locale.ROOT).contains("\"skin\"");
    }

    private static Map<String, Integer> soldEnchants(Sold sale) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Enchant enchant : sale.enchants) {
            if (enchant == null || enchant.type == null || enchant.type.isBlank()
                    || enchant.level <= 0) {
                continue;
            }
            result.merge(enchant.type.toLowerCase(Locale.ROOT), enchant.level, Math::max);
        }
        return result;
    }

    private static int saleHpb(Sold sale) {
        return Math.max(nbtInt(sale, "hpc"), nbtInt(sale, "hot_potato_count"));
    }

    private static int saleStars(Sold sale) {
        return Math.max(nbtInt(sale, "upgrade_level"),
                nbtInt(sale, "dungeon_item_level"));
    }

    private static boolean sameReforge(Sold sale, String reforge) {
        boolean none = reforge == null || reforge.isBlank()
                || reforge.equalsIgnoreCase("none");
        String value = nbtText(sale, "modifier");
        if (value.isBlank()) {
            value = nbtText(sale, "reforge");
        }
        if (none) {
            return value.isBlank() || value.equalsIgnoreCase("none");
        }
        if (!value.isBlank()) {
            return value.equalsIgnoreCase(reforge);
        }
        return matchesReforge(sale, reforge);
    }

    private static boolean containsAllScrolls(Sold sale, AhItem item, long minValue) {
        Set<String> actual = soldScrolls(sale);
        for (String scroll : normalizedScrolls(item.abilityScrolls)) {
            if (AhAddonValues.productValue(scroll) <= minValue) {
                continue;
            }
            if (!actual.contains(scroll)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> soldScrolls(Sold sale) {
        Set<String> result = new LinkedHashSet<>();
        String raw = nbtText(sale, "ability_scroll").toUpperCase(Locale.ROOT);
        for (String token : raw.split("[\\s,\\[\\]\"]+")) {
            String scroll = normalizeScroll(token);
            if (!scroll.isBlank()) {
                result.add(scroll);
            }
        }
        return result;
    }

    private static Set<String> normalizedScrolls(Iterable<String> scrolls) {
        Set<String> result = new LinkedHashSet<>();
        for (String scroll : scrolls) {
            String normalized = normalizeScroll(scroll);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String normalizeScroll(String scroll) {
        if (scroll == null) {
            return "";
        }
        String id = scroll.trim().toUpperCase(Locale.ROOT);
        if (id.isBlank()) {
            return "";
        }
        return id.endsWith("_SCROLL") ? id : id + "_SCROLL";
    }

    private static boolean hasScroll(Sold sale, String product) {
        String raw = nbtText(sale, "ability_scroll");
        return !raw.isBlank()
                && raw.toUpperCase(Locale.ROOT).contains(product);
    }

    private static boolean hasEnchant(Sold sale, String type, int level) {
        for (Enchant enchant : sale.enchants) {
            if (enchant.type.equalsIgnoreCase(type) && enchant.level == level) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesReforge(Sold sale, String reforge) {
        if (reforge == null || reforge.isBlank()) {
            return false;
        }
        String value = nbtText(sale, "modifier");
        if (value.isBlank()) {
            value = nbtText(sale, "reforge");
        }
        if (!value.isBlank()) {
            return value.equalsIgnoreCase(reforge);
        }
        String display = reforgeDisplay(reforge);
        return !display.isBlank()
                && sale.itemName.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(display.toLowerCase(java.util.Locale.ROOT) + " ");
    }

    private static boolean matchesNbtText(Sold sale, String key, String value) {
        String actual = nbtText(sale, key);
        return !actual.isBlank() && actual.equalsIgnoreCase(value);
    }

    private static String nbtText(Sold sale, String key) {
        if (sale == null || !sale.nbt.has(key) || sale.nbt.get(key).isJsonNull()) {
            return "";
        }
        try {
            JsonElement element = sale.nbt.get(key);
            if (element.isJsonArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonElement part : element.getAsJsonArray()) {
                    if (part != null && !part.isJsonNull()) {
                        if (builder.length() > 0) {
                            builder.append(' ');
                        }
                        builder.append(part.getAsString());
                    }
                }
                return builder.toString();
            }
            return element.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int nbtInt(Sold sale, String key) {
        if (sale == null || !sale.nbt.has(key) || sale.nbt.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(sale.nbt.get(key).getAsString());
        } catch (Exception ignored) {
            return 0;
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
            if (part.equalsIgnoreCase("renowed")) {
                builder.append("Renowned");
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return builder.toString();
    }

    public static RobustCv robustCv(String tag, int petLevel, String petRarity, boolean cleanOnly) {
        List<Sold> sold = sold(tag);
        if (sold.isEmpty()) {
            return null;
        }
        boolean pet = petLevel > 0 && petRarity != null && !petRarity.isBlank();
        if (petLevel > 0 && !pet) {
            return null;
        }
        List<Long> prices = new ArrayList<>();
        for (Sold sale : sold) {
            if (sale.price <= 0) {
                continue;
            }
            if (pet) {
                if (sale.petLevel != petLevel || !petRarity.equalsIgnoreCase(sale.petRarity)) {
                    continue;
                }
            } else if (cleanOnly && !isCleanSale(sale)) {
                continue;
            }
            prices.add(sale.price);
        }
        if (prices.size() < 5) {
            return null;
        }
        prices.sort(null);
        long baseline = median(prices);
        if (baseline <= 0) {
            return null;
        }
        double outlier = Math.max(1.0, AhFlipperMod.CONFIG.cvOutlierPercent) / 100.0;
        long cutoff = Math.round(baseline * (1.0 + outlier));
        List<Long> kept = new ArrayList<>();
        for (Long price : prices) {
            if (price <= cutoff) {
                kept.add(price);
            }
        }
        if (kept.size() < 5) {
            return null;
        }
        long robustMedian = median(kept);
        if (robustMedian <= 0) {
            return null;
        }
        double sumSquares = 0;
        for (Long price : kept) {
            double deviation = price - robustMedian;
            sumSquares += deviation * deviation;
        }
        RobustCv result = new RobustCv();
        result.cv = Math.sqrt(sumSquares / kept.size()) / robustMedian;
        result.median = robustMedian;
        result.sample = kept.size();
        result.excluded = prices.size() - kept.size();
        return result;
    }

    private static boolean isCleanSale(Sold sale) {
        if (!sale.enchants.isEmpty()) {
            return false;
        }
        String[] addonKeys = {
                "rarity_upgrades", "hpc", "hot_potato_count", "art_of_war_count",
                "wood_singularity_count", "dye_item", "upgrade_level", "dungeon_item_level",
                "ability_scroll", "modifier", "reforge", "unlocked_slots",
                "farming_for_dummies_count", "mana_disintegrator_count",
                "ethermerge", "tuned_transmission", "power_ability_scroll"
        };
        for (String key : addonKeys) {
            if (!hasNbt(sale, key)) {
                continue;
            }
            String value = sale.nbt.get(key).getAsString();
            if (!value.equalsIgnoreCase("none")) {
                return false;
            }
        }
        for (Map.Entry<String, JsonElement> entry : sale.nbt.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            String value = entry.getValue().getAsString();
            if (value.equalsIgnoreCase("rough") || value.equalsIgnoreCase("flawed")
                    || value.equalsIgnoreCase("fine") || value.equalsIgnoreCase("flawless")
                    || value.equalsIgnoreCase("perfect")) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNbt(Sold sale, String key) {
        if (!sale.nbt.has(key) || sale.nbt.get(key).isJsonNull()) {
            return false;
        }
        String value = sale.nbt.get(key).getAsString();
        return !value.isBlank() && !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(middle);
        }
        return Math.round((values.get(middle - 1) + values.get(middle)) / 2.0);
    }

    private static String stringValue(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long longValue(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        return json.get(key).getAsLong();
    }

    private static double doubleValue(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        return json.get(key).getAsDouble();
    }

    private static String get(String url) {
        try {
            synchronized (HTTP_LOCK) {
                long wait = 50 - (System.currentTimeMillis() - lastRequestAt);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastRequestAt = System.currentTimeMillis();
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (SkyCofl-AHFlipper)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("[AH-Flipper] HTTP " + response.statusCode() + " für " + url);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            System.out.println("[AH-Flipper] HTTP Fehler für " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static String post(String url, String body) {
        try {
            synchronized (HTTP_LOCK) {
                long wait = 50 - (System.currentTimeMillis() - lastRequestAt);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastRequestAt = System.currentTimeMillis();
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (SkyCofl-AHFlipper)")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("[AH-Flipper] HTTP " + response.statusCode() + " für " + url);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            System.out.println("[AH-Flipper] HTTP-Fehler für " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String getUrl(String url) {
        return get(url);
    }
}

