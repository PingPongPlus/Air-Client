package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Addons that hardly ever sell on this item. They used to reject the whole
 * item; now the item is valued as before and then most of the value of those
 * addons is taken off again (rareAddonDeductPercent, default 90 %). That also
 * removes them from a Cofl median that already contains them.
 */
public final class AhRareAddons {
    private AhRareAddons() {
    }

    /** One addon that sells less than addonMinSalesPerWeek times on this item. */
    public record Addon(String label, long value, int sales) {
    }

    public static List<Addon> find(AhItem item) {
        List<Addon> rare = new ArrayList<>();
        if (item == null || item.tag == null || item.tag.isBlank() || item.isRune()) {
            return rare;
        }
        if (AhMarketData.sold(item.tag).isEmpty()) {
            // no sales data at all: nothing can be judged (other filters handle it)
            return rare;
        }
        int min = Math.max(1, AhFlipperMod.CONFIG.addonMinSalesPerWeek);
        String tag = item.tag;
        if (item.isPet()) {
            if (item.petItem != null && !item.petItem.isBlank()) {
                add(rare, min, "Pet-Item " + item.petItem,
                        AhAddonValues.petItemValue(item.petItem),
                        AhMarketData.petItemSales(tag, item.petItem));
            }
            if (item.petSkin != null && !item.petSkin.isBlank()) {
                add(rare, min, "Pet-Skin " + item.petSkin,
                        AhAddonValues.cosmeticValue(item.petSkin, false),
                        AhMarketData.petSkinSales(tag, item.petSkin));
            }
            return rare;
        }
        for (Map.Entry<String, Integer> enchant : item.enchants.entrySet()) {
            String type = enchant.getKey().toLowerCase(Locale.ROOT);
            int level = enchant.getValue();
            add(rare, min, "Enchant " + type + " " + level,
                    AhAddonValues.enchantValue(type, level),
                    AhMarketData.enchantSales(tag, type, level));
        }
        if (item.reforge != null && !item.reforge.isBlank()
                && !item.reforge.equalsIgnoreCase("none")) {
            double stone = AhAddonValues.reforgeStoneValue(item.reforge);
            // only reforges with an expensive stone are checked at all
            if (stone >= AhFlipperMod.CONFIG.reforgeStoneCheckMinCoins) {
                add(rare, min, "Reforge " + item.reforge, stone,
                        AhMarketData.reforgeSales(tag, item.reforge));
            }
        }
        if (item.stars > 0) {
            add(rare, min, "Stars " + item.stars + "+",
                    AhAddonValues.masterStarValue(item.stars),
                    AhMarketData.salesWithAtLeast(tag, item.stars,
                            "upgrade_level", "dungeon_item_level"));
        }
        if (item.hpb > 0) {
            add(rare, min, "HPB " + item.hpb + "+",
                    AhAddonValues.hpbValue(item.hpb) + AhAddonValues.fumingHpbValue(item.fumingHpb),
                    AhMarketData.salesWithAtLeast(tag, item.hpb, "hpc", "hot_potato_count"));
        }
        if (item.recomb) {
            add(rare, min, "Recombobulated", AhAddonValues.recombValue(),
                    AhMarketData.salesWithAtLeast(tag, 1, "rarity_upgrades"));
        }
        if (item.artOfWar > 0) {
            add(rare, min, "Art of War", AhAddonValues.artOfWarValue(item.artOfWar),
                    AhMarketData.salesWithAtLeast(tag, 1, "art_of_war_count"));
        }
        if (item.woodSingularity > 0) {
            add(rare, min, "Wood Singularity",
                    AhAddonValues.productValue("WOOD_SINGULARITY") * item.woodSingularity,
                    AhMarketData.salesWithAtLeast(tag, 1, "wood_singularity_count"));
        }
        for (String scroll : item.abilityScrolls) {
            String id = scroll.toUpperCase(Locale.ROOT);
            if (!id.endsWith("_SCROLL")) {
                id += "_SCROLL";
            }
            add(rare, min, "Scroll " + id, AhAddonValues.productValue(id),
                    AhMarketData.scrollSales(tag, id));
        }
        if (item.skin != null && !item.skin.isBlank()) {
            add(rare, min, "Skin " + item.skin, AhAddonValues.cosmeticValue(item.skin, false),
                    AhMarketData.textSales(tag, "skin", item.skin));
        }
        if (item.dyeItem != null && !item.dyeItem.isBlank()) {
            add(rare, min, "Dye " + item.dyeItem, AhAddonValues.cosmeticValue(item.dyeItem, false),
                    AhMarketData.textSales(tag, "dye_item", item.dyeItem));
        }
        return rare;
    }

    private static void add(List<Addon> rare, int min, String label, double value, int sales) {
        long coins = Math.round(value);
        if (coins > 0 && sales < min) {
            rare.add(new Addon(label, coins, sales));
        }
    }

    public static long totalValue(List<Addon> rare) {
        long total = 0;
        for (Addon addon : rare) {
            total += Math.max(0, addon.value());
        }
        return total;
    }

    /** Coins taken off the full value for these addons. */
    public static long deduction(List<Addon> rare, double percent) {
        double share = Math.max(0, Math.min(100, percent)) / 100.0;
        return Math.round(totalValue(rare) * share);
    }

    /** The full value minus the deduction, never below 1. */
    public static long apply(long estimated, long deduction) {
        return Math.max(1, estimated - Math.max(0, deduction));
    }
}
