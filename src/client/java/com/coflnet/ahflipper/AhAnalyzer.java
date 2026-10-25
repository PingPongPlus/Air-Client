package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AhAnalyzer {
    public static class AddonCheck {
        public String label;
        public String productId = "";
        public String attribute = "";
        public int sales;
        public double value;
        public double volume;
        public boolean supported = true;
    }

    public static class Result {
        public boolean buyable;
        public final List<String> reasons = new ArrayList<>();
        public String tag = "";
        public long price;
        public long cleanPrice;
        public long expectedValue;
        public long profit;
        public double profitPercent;
        public double addonValue;
        public double cleanScore;
        public double salesScore;
        public double volumeScore;
        public double stabilityScore;
        public double quality;
        public double score;
        public long overallSales;
        public long cleanSales;
        public double cleanCv;
        public int minAddonSales;
        public final List<AddonCheck> addons = new ArrayList<>();

        public String shortSummary() {
            return String.format(Locale.US,
                    "%s | Preis %s | Clean %s | Gewinn %s (%.0f%%) | Score %.0f (Q %.2f) | Clean-Sales %d | CV %.1f%% | Addons %d",
                    tag, fmt(price), fmt(cleanPrice), fmt(profit), profitPercent, score, quality,
                    cleanSales, cleanCv * 100, addons.size());
        }
    }

    public static Result analyze(AhItem item, long price, long expectedProfit) {
        return analyze(item, price, expectedProfit, "");
    }

    public static Result analyze(AhItem item, long price, long expectedProfit,
                                 String excludeUuid) {
        Result result = new Result();
        result.tag = item.tag;
        result.price = price;
        if (item.tag == null || item.tag.isBlank()) {
            result.reasons.add("Item-Tag unbekannt");
            result.buyable = false;
            return result;
        }
        if (price < AhFlipperMod.CONFIG.minItemCoins) {
            result.reasons.add("Preis unter " + AhFlipperMod.CONFIG.minItemCoins / 1_000_000 + "M");
        }

        boolean pet = item.isPet();
        String petFilter = pet ? item.petFilter() : "";
        if (pet && petFilter.isBlank()) {
            result.reasons.add("Pet-Level/Rarität nicht erkannt");
        }

        AhMarketData.Analysis clean;
        long cleanSales;
        if (pet) {
            AhMarketData.Analysis level1 = AhMarketData.analysis(item.tag, "&PetLevel=1");
            long level1Sales = level1 == null ? 0 : level1.totalSales;
            if (level1 == null || level1.totalSales <= AhFlipperMod.CONFIG.petLevel1MinSales) {
                result.reasons.add("Zu wenig Level-1-Verkäufe (" + level1Sales + ")");
            }
            AhMarketData.Analysis level = AhMarketData.analysis(item.tag,
                    "&PetLevel=" + item.petLevel);
            long levelSales = level == null ? 0 : level.totalSales;
            if (level == null || level.totalSales <= AhFlipperMod.CONFIG.petLevelMinSales) {
                result.reasons.add("Zu wenig Level-" + item.petLevel + "-Verkäufe ("
                        + levelSales + ")");
            }
            clean = AhMarketData.analysis(item.tag, petFilter);
            if (clean == null || clean.totalSales < 5) {
                AhMarketData.Analysis fallback = AhMarketData.analysis(item.tag,
                        "&PetLevel=" + item.petLevel);
                if (fallback != null && fallback.totalSales > (clean == null ? 0 : clean.totalSales)) {
                    clean = fallback;
                }
            }
            cleanSales = clean == null ? 0 : clean.totalSales;
        } else {
            clean = AhMarketData.analysis(item.tag, "&Clean=true");
            if (clean == null || clean.totalSales < 5) {
                AhMarketData.Analysis fallback = AhMarketData.analysis(item.tag,
                        "&Enchantment=None&Reforge=None");
                if (fallback != null && fallback.totalSales > (clean == null ? 0 : clean.totalSales)) {
                    clean = fallback;
                }
            }
            cleanSales = clean == null ? 0 : clean.totalSales;
        }
        AhMarketData.Analysis overall = AhMarketData.analysis(item.tag, "");
        List<AhMarketData.Sold> sold = AhMarketData.sold(item.tag);
        if (overall == null) {
            overall = new AhMarketData.Analysis();
            overall.totalSales = sold.size();
        }
        if (clean == null) {
            result.reasons.add(pet ? "Pet-Preisdaten nicht abrufbar" : "Clean-Preisdaten nicht abrufbar");
            result.buyable = false;
            return result;
        }
        long sevenDayAverage = clean.avgPrice > 0
                ? Math.round(clean.avgPrice) : clean.medianPrice;
        long cleanAverage = AhMarketData.lowestCleanAverage(item.tag, item.petLevel,
                item.petRarity, sevenDayAverage);
        long cleanBin = AhMarketData.binReference(item.tag, excludeUuid);
        long reference = AhMarketData.lowestReference(cleanAverage, cleanBin);
        if (reference > 0) {
            System.out.println("[AH-Flipper] Referenz " + item.tag + ": CleanAvg="
                    + cleanAverage + " BIN=" + cleanBin + " -> " + reference);
            cleanAverage = reference;
        }
        result.cleanPrice = cleanAverage;
        if (cleanAverage <= 0) {
            result.reasons.add("Keine belastbare Preisreferenz (BIN/Clean fehlt)");
        }
        result.cleanSales = cleanSales;
        result.cleanCv = clean.coeffVariation;
        AhMarketData.RobustCv robust = AhMarketData.robustCv(item.tag,
                item.petLevel, item.petRarity, !pet);
        if (robust != null) {
            result.cleanCv = robust.cv;
        }
        result.overallSales = overall.totalSales;
        result.addonValue = AhAddonValues.bazaarValue(item);

        if (!pet && overall.totalSales <= AhFlipperMod.CONFIG.minTotalSales) {
            result.reasons.add("Zu wenig Gesamt-Verkäufe (" + overall.totalSales + ")");
        }
        if (!pet
                && result.addonValue <= AhFlipperMod.CONFIG.cleanSalesAddonValueCoins
                && result.cleanSales < AhFlipperMod.CONFIG.minCleanSales) {
            result.reasons.add("Zu wenig Clean-Verkäufe (" + result.cleanSales + ")");
        }
        if (result.cleanCv > AhFlipperMod.CONFIG.maxDeviationPercent / 100.0) {
            result.reasons.add(String.format(Locale.US, "%s-Preis schwankt %.1f%% (> %.0f%%)",
                    pet ? "Pet" : "Clean",
                    result.cleanCv * 100, AhFlipperMod.CONFIG.maxDeviationPercent));
        }

        for (Map.Entry<String, Integer> enchant : item.enchants.entrySet()) {
            AddonCheck check = new AddonCheck();
            check.label = "Enchant " + enchant.getKey() + " " + enchant.getValue();
            check.productId = "ENCHANTMENT_" + enchant.getKey().toUpperCase(Locale.ROOT)
                    + "_" + enchant.getValue();
            check.attribute = "enchantments";
            check.sales = countEnchant(sold, enchant.getKey(), enchant.getValue());
            check.value = AhAddonValues.enchantValue(enchant.getKey(), enchant.getValue());
            check.volume = AhAddonValues.enchantVolume(enchant.getKey(), enchant.getValue());
            result.addons.add(check);
        }
        if (!item.reforge.isBlank() && !item.reforge.equalsIgnoreCase("none")) {
            AddonCheck check = new AddonCheck();
            check.label = "Reforge " + item.reforge;
            check.attribute = "modifier";
            check.sales = countReforge(sold, item.reforge);
            result.addons.add(check);
        }
        if (item.stars > 0) {
            AddonCheck check = new AddonCheck();
            check.label = "Stars " + item.stars + "+";
            check.attribute = "upgrade_level";
            check.sales = countStars(sold, item.stars);
            AhMarketData.AddonPrice starPrice = AhMarketData.averageForStars(item.tag, item.stars);
            if (starPrice != null && starPrice.average > cleanAverage && cleanAverage > 0
                    && starPrice.sample >= AhFlipperMod.CONFIG.addonMinSalesPerWeek) {
                check.value = starPrice.average - cleanAverage;
                result.addonValue += check.value;
            }
            result.addons.add(check);
        }
        if (item.hpb > 0) {
            AddonCheck check = new AddonCheck();
            check.label = "HPB " + item.hpb + "+";
            check.productId = "HOT_POTATO_BOOK";
            check.attribute = "hpc";
            check.sales = countHpb(sold, item.hpb);
            check.value = AhAddonValues.hpbValue(item.hpb);
            check.volume = AhAddonValues.hpbVolume();
            result.addons.add(check);
        }
        if (item.recomb) {
            AddonCheck check = new AddonCheck();
            check.label = "Recombobulated";
            check.productId = "RECOMBOBULATOR_3000";
            check.attribute = "rarity_upgrades";
            check.sales = countRecomb(sold);
            check.value = AhAddonValues.recombValue();
            check.volume = AhAddonValues.recombVolume();
            result.addons.add(check);
        }
        if (item.artOfWar > 0) {
            AddonCheck check = new AddonCheck();
            check.label = "Art of War " + item.artOfWar;
            check.productId = "THE_ART_OF_WAR";
            check.attribute = "art_of_war_count";
            check.sales = countArtOfWar(sold);
            check.value = AhAddonValues.artOfWarValue(item.artOfWar);
            check.volume = AhAddonValues.artOfWarVolume();
            result.addons.add(check);
        }
        if (item.woodSingularity > 0) {
            AddonCheck check = new AddonCheck();
            check.label = "Wood Singularity " + item.woodSingularity;
            check.productId = "WOOD_SINGULARITY";
            check.attribute = "wood_singularity_count";
            check.sales = countWood(sold);
            check.value = AhAddonValues.productValue("WOOD_SINGULARITY") * item.woodSingularity;
            check.volume = AhAddonValues.weeklyVolume("WOOD_SINGULARITY");
            result.addons.add(check);
        }
        for (String scroll : item.abilityScrolls) {
            String id = scroll.toUpperCase(Locale.ROOT);
            if (!id.endsWith("_SCROLL")) {
                id += "_SCROLL";
            }
            AddonCheck check = new AddonCheck();
            check.label = "Scroll " + scroll;
            check.productId = id;
            check.attribute = "ability_scroll";
            check.sales = countScroll(sold, id);
            check.value = AhAddonValues.productValue(id);
            check.volume = AhAddonValues.weeklyVolume(id);
            result.addons.add(check);
        }
        if (!item.petItem.isBlank()) {
            AddonCheck check = new AddonCheck();
            check.label = "Pet Item " + item.petItem;
            check.supported = false;
            result.addons.add(check);
        }

        result.minAddonSales = Integer.MAX_VALUE;
        int supportedAddons = 0;
        for (AddonCheck check : result.addons) {
            if (!check.supported) {
                continue;
            }
            supportedAddons++;
            result.minAddonSales = Math.min(result.minAddonSales, check.sales);
            if (check.volume > 0 && check.volume < AhFlipperMod.CONFIG.addonMinBazaarVolume) {
                result.reasons.add(check.label + " Bazaar-Volumen "
                        + Math.round(check.volume) + "/Woche");
            }
            if (!sold.isEmpty()
                    && check.sales < AhFlipperMod.CONFIG.addonMinSalesPerWeek) {
                result.reasons.add(check.label + " nur " + check.sales + "x/Woche auf "
                        + item.tag);
            }
        }
        if (result.minAddonSales == Integer.MAX_VALUE) {
            result.minAddonSales = 0;
        }
        if (sold.isEmpty() && supportedAddons > 0) {
            result.reasons.add("Verkaufsdaten fehlen (Addons nicht prüfbar)");
        }

        double feeFraction = AhFlipperMod.CONFIG.ahFeePercent / 100.0;
        double threshold = AhFlipperMod.CONFIG.minAddonValueMillions * 1_000_000.0;

        AhValuation.Estimate estimate = AhValuation.estimate(item, excludeUuid);
        result.addonValue = estimate.addonValue;
        result.expectedValue = estimate.estimated;
        if (estimate.estimated <= 1 || estimate.reference <= 0) {
            result.reasons.add("Keine belastbare Preisbewertung");
        }
        if (estimate.addonValueUnbacked) {
            result.reasons.add("Addon-Vergleich unbrauchbar (zu wenige belastbare "
                    + "Verk\u00e4ufe / zu viele Ausrei\u00dfer)");
        }
        if (estimate.candyPet) {
            result.reasons.add("Pet mit Candies - kein Kauf");
        }
        System.out.println("[AH-Flipper] Bewertung " + item.tag + ": " + estimate.source
                + " Referenz=" + estimate.reference + " Addons=" + estimate.addonValue
                + " AddonsAsk=" + estimate.addonValueAsk
                + " Gems=" + estimate.gemValue
                + " Slots=" + estimate.slotUnlockValue
                + " Comparable=" + estimate.comparableAverage + " ("
                + estimate.comparableSales + " Sales, "
                + estimate.comparableExcluded + " Ausrei\u00dfer) AskSafety="
                + estimate.askSafetyValue
                + " Ceiling=" + estimate.ceiling + " -> " + result.expectedValue);
        boolean addonsWorthwhile = result.addonValue >= threshold
                || estimate.estimated > Math.max(1, estimate.reference);

        result.profit = Math.round(result.expectedValue * (1.0 - feeFraction)) - price;
        result.profitPercent = price > 0 ? result.profit * 100.0 / price : -1;
        if (expectedProfit > 0 && result.profitPercent < AhFlipperMod.CONFIG.minProfitPercent) {
            result.reasons.add(String.format(Locale.US, "Gewinn %.0f%% (< %.0f%%)",
                    result.profitPercent, AhFlipperMod.CONFIG.minProfitPercent));
        }

        result.cleanScore = addonsWorthwhile && result.expectedValue > 0
                ? clamp((double) cleanAverage / result.expectedValue, 0, 1)
                : 1.0;
        result.salesScore = supportedAddons == 0 ? 1.0
                : logScore(result.minAddonSales, AhFlipperMod.CONFIG.addonMinSalesPerWeek,
                AhFlipperMod.CONFIG.scoreSalesTarget);
        result.volumeScore = logScore((int) Math.min(Integer.MAX_VALUE, overall.totalSales), 10,
                AhFlipperMod.CONFIG.scoreVolumeTarget);
        result.stabilityScore = clamp(1.0 - result.cleanCv
                / Math.max(0.0001, AhFlipperMod.CONFIG.maxDeviationPercent / 100.0), 0, 1);

        double wClean = Math.max(0, AhFlipperMod.CONFIG.scoreCleanWeight);
        double wSales = Math.max(0, AhFlipperMod.CONFIG.scoreSalesWeight);
        double wVolume = Math.max(0, AhFlipperMod.CONFIG.scoreVolumeWeight);
        double wStability = Math.max(0, AhFlipperMod.CONFIG.scoreStabilityWeight);
        double weightSum = wClean + wSales + wVolume + wStability;
        if (weightSum <= 0) {
            weightSum = 1;
        }
        result.quality = (result.cleanScore * wClean + result.salesScore * wSales
                + result.volumeScore * wVolume + result.stabilityScore * wStability) / weightSum;
        result.score = Math.max(0, result.profit) * result.quality;
        result.buyable = result.reasons.isEmpty();
        return result;
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

    private static int countReforge(List<AhMarketData.Sold> sold, String reforge) {
        String display = reforgeDisplay(reforge);
        if (display.isBlank()) {
            return 0;
        }
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
            } else if (sale.itemName.toLowerCase(Locale.ROOT)
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

    private static int countStars(List<AhMarketData.Sold> sold, int stars) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (nbtInt(sale, "upgrade_level") >= stars) {
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

    private static int countRecomb(List<AhMarketData.Sold> sold) {
        int count = 0;
        for (AhMarketData.Sold sale : sold) {
            if (nbtInt(sale, "rarity_upgrades") > 0) {
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

    private static double logScore(int value, int min, int target) {
        if (value <= 0 || target <= min) {
            return 0;
        }
        double ratio = (Math.log10(Math.max(1, value)) - Math.log10(Math.max(1, min)))
                / (Math.log10(target) - Math.log10(Math.max(1, min)));
        return clamp(ratio, 0, 1);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String fmt(long coins) {
        return com.coflnet.flipper.trading.ScreenUtil.formatCoins(coins);
    }
}
