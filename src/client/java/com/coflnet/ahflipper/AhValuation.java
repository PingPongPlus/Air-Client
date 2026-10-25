package com.coflnet.ahflipper;

public class AhValuation {

    public static final class Estimate {
        public long cleanAverage;
        public long reference;
        public long addonValue;
        public long addonValueAsk;
        public long gemValue;
        public long slotUnlockValue;
        public long comparableAverage;
        public int comparableSales;
        public int comparableExcluded;
        public long askSafetyValue;
        public long ceiling;
        public long estimated;
        public String source = "clean";
        public boolean addonValueUnbacked;
        public boolean candyPet;
    }

    public static Estimate estimate(AhItem item, String excludeUuid) {
        Estimate est = new Estimate();
        if (item == null || item.tag == null || item.tag.isBlank()) {
            est.source = "no-tag";
            return est;
        }
        boolean pet = item.isPet();
        boolean aspectOfTheVoid = item.isAspectOfTheVoid();
        String petFilter = pet && !item.petFilter().isBlank() ? item.petFilter() : "";

        AhMarketData.Analysis clean = pet
                ? AhMarketData.analysis(item.tag, petFilter)
                : AhMarketData.analysis(item.tag, "&Clean=true");
        if (clean == null || clean.totalSales < 5) {
            AhMarketData.Analysis fallback = pet
                    ? AhMarketData.analysis(item.tag, "&PetLevel=" + item.petLevel)
                    : AhMarketData.analysis(item.tag, "&Enchantment=None&Reforge=None");
            if (fallback != null
                    && fallback.totalSales > (clean == null ? 0 : clean.totalSales)) {
                clean = fallback;
            }
        }
        if (clean == null) {
            est.source = "no-data";
            return est;
        }
        long sevenDayAverage = clean.avgPrice > 0
                ? Math.round(clean.avgPrice) : clean.medianPrice;
        long cleanAverage = AhMarketData.lowestCleanAverage(item.tag, item.petLevel,
                item.petRarity, sevenDayAverage);
        if (cleanAverage <= 0) {
            cleanAverage = sevenDayAverage > 0 ? sevenDayAverage : clean.medianPrice;
        }
        long bin = AhMarketData.binReference(item.tag, excludeUuid);
        long reference = AhMarketData.lowestReference(cleanAverage, bin);
        if (reference <= 0) {
            reference = cleanAverage;
        }
        est.cleanAverage = cleanAverage;
        est.reference = reference;

        long addonValue = 0;
        long addonValueAsk = 0;
        long comparableAverage = 0;
        int comparableSales = 0;
        if (!pet) {
            long starPremium = AhMarketData.cleanStarPremium(item.tag, item,
                    cleanAverage > 0 ? cleanAverage : reference);
            long gemValue = Math.round(AhAddonValues.gemstoneValue(item, false));
            long slotValue = Math.round(
                    AhAddonValues.slotUnlockValue(item.tag, item.unlockedGemSlots, false));
            addonValue = Math.round(AhAddonValues.bazaarValue(item)) + starPremium;
            addonValueAsk = Math.round(AhAddonValues.bazaarValueAsk(item)) + starPremium;
            addonValueAsk = Math.max(addonValue, addonValueAsk);
            est.gemValue = gemValue;
            est.slotUnlockValue = slotValue;
            long shareBase = cleanAverage > 0 ? cleanAverage : reference;
            boolean addonDominant = shareBase > 0
                    && addonValue * 100.0 > shareBase
                    * AhFlipperMod.CONFIG.comparableAddonSharePercent;
            if (!aspectOfTheVoid && addonValue > 0 && addonDominant) {
                AhMarketData.AddonPrice comparable = AhMarketData.averageForComparable(item.tag,
                        item, AhFlipperMod.CONFIG.comparableAddonMinCoins);
                if (comparable != null && comparable.average > 0
                        && comparable.sample >= Math.max(1,
                        AhFlipperMod.CONFIG.comparableMinSalesPerWeek)
                        && comparable.excluded <= comparable.sample) {
                    comparableAverage = comparable.average;
                    comparableSales = comparable.sample;
                    est.comparableExcluded = comparable.excluded;
                } else {
                    est.addonValueUnbacked = true;
                }
            }
        }
        est.addonValue = addonValue;
        est.addonValueAsk = addonValueAsk;
        est.comparableAverage = comparableAverage;
        est.comparableSales = comparableSales;
        est.ceiling = 0;
        if (pet) {
            est.candyPet = item.petCandies > 0;
            String petItem = item.petItem == null ? "" : item.petItem.trim();
            if (!petItem.isBlank()) {
                double petItemValue = AhAddonValues.productValue(
                        petItem.toUpperCase(java.util.Locale.ROOT));
                if (petItemValue > 5_000_000.0) {
                    AhMarketData.AddonPrice comparable = AhMarketData.averageForPetItem(
                            item.tag, item.petLevel, item.petRarity, petItem);
                    if (comparable != null && comparable.average > 0
                            && comparable.sample >= 2) {
                        comparableAverage = comparable.average;
                        comparableSales = comparable.sample;
                    } else {
                        est.addonValueUnbacked = true;
                    }
                }
            }
        }

        boolean addonsWorthwhile = addonValue
                >= AhFlipperMod.CONFIG.minAddonValueMillions * 1_000_000.0;
        long estimated;
        if (pet && comparableAverage > 0) {
            estimated = comparableAverage;
            est.source = "pet-item";
        } else if (comparableAverage > 0) {
            long refPlusAddons = reference + addonValue;
            if (comparableSales >= AhFlipperMod.CONFIG.comparableTrustSales) {
                double weight = AhFlipperMod.CONFIG.comparableWeightPercent / 100.0;
                estimated = Math.round(comparableAverage * weight
                        + refPlusAddons * (1.0 - weight));
                est.source = "comparable-" + Math.round(weight * 100)
                        + "/" + Math.round((1.0 - weight) * 100);
            } else if (refPlusAddons < comparableAverage) {
                long refPlusAddonsAsk = reference + addonValueAsk;
                if (comparableAverage > refPlusAddonsAsk) {
                    estimated = Math.round((refPlusAddonsAsk + refPlusAddons) / 2.0);
                    est.askSafetyValue = refPlusAddonsAsk;
                    est.source = "comparable-ask-50/50";
                } else {
                    estimated = Math.round((refPlusAddons + comparableAverage) / 2.0);
                    est.source = "comparable-50/50";
                }
            } else {
                estimated = comparableAverage;
                est.source = "comparable";
            }
            estimated = Math.round(estimated
                    * (1.0 - AhFlipperMod.CONFIG.comparableLossPercent / 100.0));
        } else {
            estimated = reference;
            if (!pet && (addonsWorthwhile || aspectOfTheVoid)) {
                estimated += addonValue;
            }
            if (pet) {
                est.source = "pet";
            } else if (aspectOfTheVoid) {
                est.source = "aotv-ref+addons";
            } else {
                est.source = "clean+addons";
            }
        }
        if (estimated <= 0) {
            est.source = est.source + " (wertlos)";
        }
        est.estimated = Math.max(1, estimated);
        return est;
    }
}
