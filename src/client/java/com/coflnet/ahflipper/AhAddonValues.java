package com.coflnet.ahflipper;

import com.coflnet.flipper.data.BazaarApi;
import com.coflnet.flipper.data.MarketData;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AhAddonValues {
    private static MarketData cached;
    private static long cachedAt;

    public static double bazaarValue(AhItem item) {
        return bazaarValue(item, false);
    }

    public static double bazaarValueAsk(AhItem item) {
        return bazaarValue(item, true);
    }

    private static double bazaarValue(AhItem item, boolean ask) {
        if (item == null) {
            return 0;
        }
        double total = Math.max(0, item.coinValue);
        MarketData data = bazaar();
        if (data == null) {
            return total;
        }
        for (Map.Entry<String, Integer> entry : item.enchants.entrySet()) {
            String id = "ENCHANTMENT_" + entry.getKey().toUpperCase(Locale.ROOT) + "_" + entry.getValue();
            total += value(data, id, ask);
        }
        if (item.hpb > 0) {
            total += item.hpb * value(data, "HOT_POTATO_BOOK", ask);
        }
        if (item.fumingHpb > 0) {
            total += item.fumingHpb * value(data, "FUMING_POTATO_BOOK", ask);
        }
        total += cosmeticValue(item.dyeItem, ask);
        total += cosmeticValue(item.skin, ask);
        total += cosmeticValue(item.petSkin, ask);
        if (item.recomb) {
            total += value(data, "RECOMBOBULATOR_3000", ask);
        }
        if (item.artOfWar > 0) {
            total += item.artOfWar * value(data, "THE_ART_OF_WAR", ask);
        }
        if (item.woodSingularity > 0) {
            total += item.woodSingularity * value(data, "WOOD_SINGULARITY", ask);
        }
        for (String scroll : item.abilityScrolls) {
            String id = scroll.toUpperCase(Locale.ROOT);
            total += value(data, id.endsWith("_SCROLL") ? id : id + "_SCROLL", ask);
        }
        for (Map.Entry<String, Integer> gem : item.gemstones.entrySet()) {
            total += gem.getValue() * value(data, gem.getKey(), ask);
        }
        if (!item.unlockedGemSlots.isEmpty()) {
            total += slotUnlockValue(item.tag, item.unlockedGemSlots, ask);
        }
        if (item.isAspectOfTheVoid()) {
            if (item.ethermerge) {
                total += etherwarpConduitValue();
            }
            if (item.tunedTransmission > 0) {
                total += item.tunedTransmission * value(data, "TRANSMISSION_TUNER", ask);
            }
        }
        return total;
    }

    public static double etherwarpConduitValue() {
        AhMarketData.MarketEntry entry = AhMarketData.market().get("ETHERWARP_CONDUIT");
        if (entry != null) {
            long value = entry.value();
            if (value > 0) {
                return value;
            }
        }
        return AhMarketData.binReference("ETHERWARP_CONDUIT", "");
    }

    public static double gemstoneChamberValue() {
        AhMarketData.MarketEntry entry = AhMarketData.market().get("GEMSTONE_CHAMBER");
        if (entry != null) {
            long value = entry.value();
            if (value > 0) {
                return value;
            }
        }
        return AhMarketData.binReference("GEMSTONE_CHAMBER", "");
    }

    public static double gemstoneValue(AhItem item, boolean ask) {
        MarketData data = bazaar();
        if (data == null || item == null || item.gemstones.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (Map.Entry<String, Integer> gem : item.gemstones.entrySet()) {
            total += gem.getValue() * value(data, gem.getKey(), ask);
        }
        return total;
    }

    public static double slotUnlockValue(String tag, Collection<String> unlockedSlots,
                                         boolean ask) {
        if (tag == null || unlockedSlots == null || unlockedSlots.isEmpty()) {
            return 0;
        }
        List<AhGemstoneCosts.SlotCost> costs = AhGemstoneCosts.forItem(tag);
        if (costs.isEmpty()) {
            return 0;
        }
        MarketData data = bazaar();
        if (data == null) {
            return 0;
        }
        double total = 0;
        for (String slot : unlockedSlots) {
            String upper = slot.toUpperCase(Locale.ROOT);
            for (AhGemstoneCosts.SlotCost cost : costs) {
                if (upper.startsWith(cost.slotType)) {
                    total += cost.coins;
                    for (AhGemstoneCosts.ItemCost itemCost : cost.items) {
                        total += itemCost.amount
                                * productValue(data, itemCost.itemId, ask);
                    }
                    break;
                }
            }
        }
        return total;
    }

    private static double productValue(MarketData data, String productId, boolean ask) {
        if ("GEMSTONE_CHAMBER".equalsIgnoreCase(productId)) {
            return gemstoneChamberValue();
        }
        return value(data, productId, ask);
    }

    public static double productValue(String productId) {
        MarketData data = bazaar();
        return data == null ? 0 : sellValue(data, productId);
    }

    public static double instantBuyProductValue(String productId) {
        MarketData data = bazaar();
        return data == null ? 0 : instantBuyValue(data, productId);
    }

    public static double instantBuyEnchantValue(String type, int level) {
        return instantBuyProductValue("ENCHANTMENT_"
                + type.toUpperCase(Locale.ROOT) + "_" + level);
    }

    public static double weeklyVolume(String productId) {
        MarketData data = bazaar();
        if (data == null) {
            return 0;
        }
        MarketData.Product product = data.products.get(productId);
        if (product == null) {
            return 0;
        }
        return Math.max(product.sellMovingWeek, product.buyMovingWeek);
    }

    public static double enchantValue(String type, int level) {
        return productValue("ENCHANTMENT_" + type.toUpperCase(Locale.ROOT) + "_" + level);
    }

    public static double enchantVolume(String type, int level) {
        return weeklyVolume("ENCHANTMENT_" + type.toUpperCase(Locale.ROOT) + "_" + level);
    }

    public static double hpbValue(int count) {
        return count * productValue("HOT_POTATO_BOOK");
    }

    public static double fumingHpbValue(int count) {
        return count * productValue("FUMING_POTATO_BOOK");
    }

    public static double cosmeticValue(String id, boolean ask) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        String key = id.trim().toUpperCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        candidates.add(key);
        if (!key.startsWith("DYE_")) {
            candidates.add("DYE_" + key);
        }
        if (!key.endsWith("_SKIN")) {
            candidates.add(key + "_SKIN");
        }
        // petInfo stores the skin as "ENDER_DRAGON_BABY_BLUE", the item is
        // PET_SKIN_ENDER_DRAGON_BABY_BLUE; pet skins used to be worth 0.
        if (!key.startsWith("PET_SKIN_")) {
            candidates.add("PET_SKIN_" + key);
        }
        for (String candidate : candidates) {
            AhMarketData.MarketEntry entry = AhMarketData.market().get(candidate);
            if (entry != null && entry.value() > 0) {
                return entry.value();
            }
        }
        for (String candidate : candidates) {
            long bin = AhMarketData.binReference(candidate, "");
            if (bin > 0) {
                return bin;
            }
        }
        return 0;
    }

    /** Value of a held pet item: Bazaar first, else AH market, else lowest BIN. */
    public static double petItemValue(String petItem) {
        return itemValue(petItem);
    }

    /** Bazaar sell value, else AH market value, else lowest BIN of any item id. */
    public static double itemValue(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0;
        }
        String id = itemId.trim().toUpperCase(Locale.ROOT);
        double bazaar = productValue(id);
        if (bazaar > 0) {
            return bazaar;
        }
        AhMarketData.MarketEntry entry = AhMarketData.market().get(id);
        if (entry != null && entry.value() > 0) {
            return entry.value();
        }
        return Math.max(0, AhMarketData.binReference(id, ""));
    }

    /**
     * Reforge (NBT "modifier") -> reforge stone. Reforges that are not in here
     * come from the blacksmith (Wise, Heroic ...) and cost almost nothing.
     */
    private static final Map<String, String> REFORGE_STONES = Map.ofEntries(
            Map.entry("ancient", "PRECURSOR_GEAR"),
            Map.entry("withered", "WITHER_BLOOD"),
            Map.entry("fabled", "DRAGON_CLAW"),
            Map.entry("giant", "GIANT_TOOTH"),
            Map.entry("necrotic", "NECROMANCER_BROOCH"),
            Map.entry("renowned", "DRAGON_HORN"),
            Map.entry("spiritual", "SPIRIT_DECOY"),
            Map.entry("submerged", "DEEP_SEA_ORB"),
            Map.entry("jaded", "JADERALD"),
            Map.entry("loving", "RED_SCARF"),
            Map.entry("shaded", "DARK_ORB"),
            Map.entry("mossy", "OVERGROWN_GRASS"),
            Map.entry("festive", "FROZEN_BAUBLE"),
            Map.entry("bustling", "SKYMART_BROCHURE"),
            Map.entry("blooming", "FLOWERING_BOUQUET"),
            Map.entry("rooted", "BURROWING_SPORES"),
            Map.entry("auspicious", "ROCK_GEMSTONE"),
            Map.entry("fleet", "DIAMONITE"),
            Map.entry("glistening", "SHINY_PRISM"),
            Map.entry("mithraic", "PURE_MITHRIL"),
            Map.entry("lustrous", "GLEAMING_CRYSTAL"),
            Map.entry("refined", "REFINED_AMBER"),
            Map.entry("blessed", "BLESSED_FRUIT"),
            Map.entry("bountiful", "GOLDEN_BALL"),
            Map.entry("squeaky", "SQUEAKY_TOY"),
            Map.entry("empowered", "SADAN_BROOCH"),
            Map.entry("warped", "AOTE_STONE"),
            Map.entry("toil", "TOIL_LOG"),
            Map.entry("gilded", "MIDAS_JEWEL"),
            Map.entry("reinforced", "RARE_DIAMOND"),
            Map.entry("perfect", "DIAMOND_ATOM"),
            Map.entry("ridiculous", "RED_NOSE"),
            Map.entry("treacherous", "RUSTY_ANCHOR"),
            Map.entry("stiff", "HARDENED_WOOD"),
            Map.entry("lucky", "LUCKY_DICE"),
            Map.entry("strengthened", "SEARING_STONE"),
            Map.entry("fortified", "METEOR_SHARD"),
            Map.entry("waxed", "BLAZE_WAX"),
            Map.entry("cubic", "MOLTEN_CUBE"),
            Map.entry("hyper", "ENDSTONE_GEODE"),
            Map.entry("undead", "PREMIUM_FLESH"),
            Map.entry("spiked", "DRAGON_SCALE"),
            Map.entry("suspicious", "SUSPICIOUS_VIAL"),
            Map.entry("salty", "SALT_CUBE"),
            Map.entry("pitchin", "PITCHIN_KOI"),
            Map.entry("stellar", "PETRIFIED_STARFALL"),
            Map.entry("heated", "HOT_STUFF"),
            Map.entry("ambered", "AMBER_MATERIAL"),
            Map.entry("dirty", "DIRT_BOTTLE"),
            Map.entry("fruitful", "ONYX"),
            Map.entry("magnetic", "LAPIS_CRYSTAL"),
            Map.entry("chomp", "KUUDRA_MANDIBLE"),
            Map.entry("groovy", "MANGROVE_GEM"),
            Map.entry("moil", "MOIL_LOG"),
            Map.entry("precise", "OPTICAL_LENS"),
            Map.entry("bulky", "BULKY_STONE"),
            Map.entry("candied", "CANDY_CORN"),
            Map.entry("snowy", "TERRY_SNOWGLOBE"),
            Map.entry("dimensional", "TITANIUM_TESSERACT"),
            Map.entry("bloody", "BEATING_HEART"));

    /** Item id of the reforge stone, "" for blacksmith reforges. */
    public static String reforgeStone(String reforge) {
        if (reforge == null || reforge.isBlank()) {
            return "";
        }
        return REFORGE_STONES.getOrDefault(reforge.trim().toLowerCase(Locale.ROOT), "");
    }

    /** Price of the reforge stone, 0 for blacksmith reforges. */
    public static double reforgeStoneValue(String reforge) {
        String stone = reforgeStone(reforge);
        return stone.isEmpty() ? 0 : itemValue(stone);
    }

    public static double masterStarValue(int stars) {
        String[] names = {"FIRST", "SECOND", "THIRD", "FOURTH", "FIFTH"};
        double total = 0;
        for (int star = 6; star <= Math.min(10, stars); star++) {
            total += productValue(names[star - 6] + "_MASTER_STAR");
        }
        return total;
    }

    public static double hpbVolume() {
        return weeklyVolume("HOT_POTATO_BOOK");
    }

    public static double recombValue() {
        return productValue("RECOMBOBULATOR_3000");
    }

    public static double recombVolume() {
        return weeklyVolume("RECOMBOBULATOR_3000");
    }

    public static double artOfWarValue(int count) {
        return count * productValue("THE_ART_OF_WAR");
    }

    public static double artOfWarVolume() {
        return weeklyVolume("THE_ART_OF_WAR");
    }

    private static double value(MarketData data, String productId, boolean ask) {
        if (!ask) {
            return sellValue(data, productId);
        }
        double instantBuy = instantBuyValue(data, productId);
        return instantBuy > 0 ? instantBuy : sellValue(data, productId);
    }

    private static double sellValue(MarketData data, String productId) {
        MarketData.Product product = data.products.get(productId);
        if (product == null) {
            return 0;
        }
        return product.bestBid > 0 ? product.bestBid : product.bestAsk;
    }

    private static double instantBuyValue(MarketData data, String productId) {
        MarketData.Product product = data.products.get(productId);
        if (product == null) {
            return 0;
        }
        return product.bestAsk > 0 ? product.bestAsk : 0;
    }

    private static MarketData bazaar() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < 5 * 60_000L) {
            return cached;
        }
        MarketData data = BazaarApi.fetch();
        if (data != null) {
            cached = data;
            cachedAt = now;
        }
        return cached;
    }
}
