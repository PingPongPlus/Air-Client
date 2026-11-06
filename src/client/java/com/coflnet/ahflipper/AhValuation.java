package com.coflnet.ahflipper;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public class AhValuation {

    private static final long CACHE_TTL_MS = 45_000L;
    private static final Map<String, CachedEstimate> CACHE = new ConcurrentHashMap<>();

    private record CachedEstimate(Estimate estimate, long at) {
    }

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
        public long comparableBin;
        public boolean comparableFromBin;
        public boolean comparableAttempted;
        public boolean comparableBelowReference;
        public String comparableNote = "";
        public long askSafetyValue;
        public long ceiling;
        public long estimated;
        public long coflMed;
        public long coflLbin;
        public long coflChosen;
        public int coflLbinCount = -1;
        public String coflPick = "";
        public long coflCap;
        public String source = "clean";
        public boolean addonValueUnbacked;
        public boolean coflFallback;
        public boolean candyPet;
    }

    public static Estimate peek(AhItem item, String excludeUuid) {
        String key = fingerprint(item, excludeUuid);
        if (key.isEmpty()) {
            return null;
        }
        CachedEstimate cached = CACHE.get(key);
        if (cached == null) {
            return null;
        }
        if (System.currentTimeMillis() - cached.at() > CACHE_TTL_MS) {
            CACHE.remove(key);
            return null;
        }
        return cached.estimate();
    }

    public static Estimate estimate(AhItem item, String excludeUuid) {
        String key = fingerprint(item, excludeUuid);
        if (key.isEmpty()) {
            return compute(item, excludeUuid);
        }
        long now = System.currentTimeMillis();
        CachedEstimate cached = CACHE.get(key);
        if (cached != null && now - cached.at() <= CACHE_TTL_MS) {
            return cached.estimate();
        }
        Estimate estimate = compute(item, excludeUuid);
        CACHE.put(key, new CachedEstimate(estimate, now));
        if (CACHE.size() > 800) {
            CACHE.entrySet().removeIf(entry -> now - entry.getValue().at() > CACHE_TTL_MS * 2);
        }
        return estimate;
    }

    public static String fingerprint(AhItem item, String excludeUuid) {
        if (item == null || item.tag == null || item.tag.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(item.tag);
        builder.append("|count:").append(item.count)
                .append("|hpb:").append(item.hpb)
                .append("|fuming:").append(item.fumingHpb)
                .append("|recomb:").append(item.recomb)
                .append("|stars:").append(item.stars)
                .append("|aow:").append(item.artOfWar)
                .append("|wood:").append(item.woodSingularity)
                .append("|ethermerge:").append(item.ethermerge)
                .append("|tuned:").append(item.tunedTransmission)
                .append("|dye:").append(normalize(item.dyeItem))
                .append("|skin:").append(normalize(item.skin))
                .append("|petSkin:").append(normalize(item.petSkin))
                .append("|ref:").append(normalize(item.reforge))
                .append("|rune:").append(item.runeLevel)
                .append("|kills:").append(item.killKey).append(':').append(item.kills)
                .append("|coins:").append(item.coinValue)
                .append("|pet:").append(item.petLevel).append(':')
                .append(normalize(item.petRarity))
                .append("|petItem:").append(normalize(item.petItem))
                .append("|candies:").append(item.petCandies)
                .append("|coflmed:").append(item.coflMed)
                .append("|cofllbin:").append(item.coflLbin)
                .append("|coflpick:").append(item.coflPick)
                .append("|cofllbincount:").append(item.coflLbinCount);
        new TreeMap<>(item.enchants).forEach((key, level) ->
                builder.append("|ench:").append(key.toLowerCase(Locale.ROOT))
                        .append(':').append(level));
        new TreeSet<>(item.abilityScrolls).forEach(scroll ->
                builder.append("|scroll:").append(normalize(scroll)));
        new TreeMap<>(item.gemstones).forEach((gem, count) ->
                builder.append("|gem:").append(normalize(gem)).append(':').append(count));
        new TreeSet<>(item.unlockedGemSlots).forEach(slot ->
                builder.append("|slot:").append(normalize(slot)));
        builder.append("|ex:").append(excludeUuid == null
                ? "" : excludeUuid.replace("-", "").toLowerCase(Locale.ROOT));
        return builder.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Estimate compute(AhItem item, String excludeUuid) {
        Estimate est = new Estimate();
        if (item == null || item.tag == null || item.tag.isBlank()) {
            est.source = "no-tag";
            return est;
        }
        AhMarketData.ensureLbinCount(item);
        est.coflMed = item.coflMed;
        est.coflLbin = item.coflLbin;
        est.coflChosen = item.coflValue();
        est.coflLbinCount = item.coflLbinCount;
        est.coflPick = item.coflPick;
        if (item.isRune()) {
            String runeTag = item.tag;
            if (runeTag == null || runeTag.isBlank() || runeTag.equalsIgnoreCase("RUNE")) {
                String derived = AhItem.runeMarketTag(item.displayName);
                if (!derived.isBlank()) {
                    runeTag = derived;
                }
            }
            AhMarketData.RuneStats rune = AhMarketData.runeStats(runeTag, item.runeLevel);
            if (rune == null && runeTag != null && runeTag.startsWith("RUNE_")) {
                rune = AhMarketData.runeStats("UNIQUE_" + runeTag, item.runeLevel);
            }
            if (rune == null || rune.sales <= 0 || rune.median <= 0) {
                est.source = "rune-lvl-" + item.runeLevel + " no-data";
                est.estimated = blendCofl(item, 0, est);
                return est;
            }
            est.cleanAverage = rune.median;
            est.reference = rune.median;
            est.source = "rune-lvl-" + item.runeLevel;
            est.estimated = blendCofl(item, rune.median, est);
            if (item.coflValue() > 0) {
                est.estimated = Math.min(est.estimated, item.coflValue());
            }
            return est;
        }
        boolean pet = item.isPet();
        boolean aspectOfTheVoid = item.isAspectOfTheVoid();

        AhMarketData.RuneStats cleanRef = AhMarketData.referenceMedian(item);
        if (cleanRef == null || cleanRef.median <= 0) {
            est.source = "no-clean-data";
            est.estimated = blendCofl(item, 0, est);
            return est;
        }
        long cleanAverage = cleanRef.median;
        long bin = pet ? 0 : AhMarketData.binReference(item.tag, excludeUuid);
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
            est.comparableAttempted = addonValue > 0;
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
                    est.comparableNote = "keine Vergleichsverk\u00e4ufe";
                }
                long comparableBin = AhMarketData.lowestComparableBin(item, excludeUuid,
                        AhFlipperMod.CONFIG.comparableAddonMinCoins,
                        comparableAverage > 0 ? comparableAverage : 0);
                est.comparableBin = comparableBin;
                if (comparableBin > 0
                        && (comparableAverage <= 0 || comparableBin < comparableAverage)) {
                    comparableAverage = comparableBin;
                    est.comparableFromBin = true;
                    est.addonValueUnbacked = false;
                }
            } else if (addonValue > 0) {
                est.comparableNote = "Addon nicht dominant";
            }
        }
        long coflCap = Math.max(reference + addonValue, bin);
        est.coflCap = coflCap;
        long coflMedian = item.coflMed;
        long coflValue = item.coflValue();
        est.coflMed = coflMedian;
        est.coflLbin = item.coflLbin;
        est.coflPick = item.coflPick;
        if (!pet && comparableAverage <= 0 && coflValue > 0) {
            comparableAverage = coflValue;
            comparableSales = 1;
            est.addonValueUnbacked = false;
            est.coflFallback = true;
            est.comparableNote = "Cofl-" + item.coflPick + " statt Addon-Vergleich";
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
            if (refPlusAddons <= 0 || comparableAverage <= refPlusAddons) {
                estimated = comparableAverage;
                est.source = "comparable-100";
            } else if (comparableSales >= AhFlipperMod.CONFIG.comparableTrustSales) {
                double weight = AhFlipperMod.CONFIG.comparableWeightPercent / 100.0;
                estimated = Math.round(comparableAverage * weight
                        + refPlusAddons * (1.0 - weight));
                est.source = "comparable-" + Math.round(weight * 100)
                        + "/" + Math.round((1.0 - weight) * 100);
            } else if (refPlusAddons < comparableAverage) {
                long refPlusAddonsAsk = reference + addonValueAsk;
                if (refPlusAddons <= 0) {
                    estimated = comparableAverage;
                    est.source = "comparable";
                } else {
                    double difference = (comparableAverage - refPlusAddons)
                            / (double) refPlusAddons;
                    double steepness = Math.max(0.1,
                            AhFlipperMod.CONFIG.comparableWeightCurve);
                    double weight = 0.5 * Math.exp(-steepness * (difference - 0.25));
                    weight = Math.max(0.05, Math.min(0.95, weight));
                    estimated = Math.round(comparableAverage * weight
                            + refPlusAddons * (1.0 - weight));
                    est.source = "comparable-exp-" + Math.round(weight * 100)
                            + "/" + Math.round((1.0 - weight) * 100);
                    if (refPlusAddonsAsk > refPlusAddons) {
                        est.askSafetyValue = refPlusAddonsAsk;
                    }
                }
            } else {
                estimated = comparableAverage;
                est.source = "comparable";
            }
            // No extra safety deduction here: the tooltip shows the market value,
            // the flipper/lowballer apply their own margins on top of it.
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
        estimated = blendCofl(item, estimated, est);
        if (item.coflValue() > 0) {
            estimated = Math.min(estimated, item.coflValue());
        }
        if (estimated <= 0) {
            est.source = est.source + " (wertlos)";
        }
        est.estimated = Math.max(1, estimated);
        return est;
    }

    private static long blendCofl(AhItem item, long ourValue, Estimate est) {
        if (item == null) {
            return ourValue;
        }
        long cofl = item.coflValue();
        if (cofl <= 0) {
            return ourValue;
        }
        est.coflMed = item.coflMed;
        est.coflLbin = item.coflLbin;
        est.coflChosen = cofl;
        est.coflLbinCount = item.coflLbinCount;
        est.coflPick = item.coflPick;
        if (est.coflFallback) {
            return ourValue;
        }
        if (ourValue <= 0) {
            est.source = est.source + "+cofl";
            return cofl;
        }
        double difference = (cofl - ourValue) / (double) ourValue;
        double distance = Math.abs(difference);
        double weight = 0.5 * Math.exp(6.43 * (distance - 0.10));
        weight = difference < 0
                ? Math.min(0.95, weight)
                : Math.min(0.60, weight);
        weight = Math.max(0.05, Math.min(0.95, weight));
        long blended = Math.round(ourValue * (1.0 - weight)
                + cofl * weight);
        est.source = est.source + "+cofl-" + Math.round(weight * 100) + "/"
                + Math.round((1.0 - weight) * 100);
        return Math.max(1, blended);
    }
}
