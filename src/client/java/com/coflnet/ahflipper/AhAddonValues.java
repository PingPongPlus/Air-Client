package com.coflnet.ahflipper;

import com.coflnet.flipper.data.BazaarApi;
import com.coflnet.flipper.data.MarketData;

import java.util.Collection;
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
        MarketData data = bazaar();
        if (data == null) {
            return 0;
        }
        double total = 0;
        for (Map.Entry<String, Integer> entry : item.enchants.entrySet()) {
            String id = "ENCHANTMENT_" + entry.getKey().toUpperCase(Locale.ROOT) + "_" + entry.getValue();
            total += value(data, id, ask);
        }
        if (item.hpb > 0) {
            total += item.hpb * value(data, "HOT_POTATO_BOOK", ask);
        }
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
