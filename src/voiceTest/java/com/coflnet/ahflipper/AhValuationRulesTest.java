package com.coflnet.ahflipper;

import java.util.ArrayList;
import java.util.List;

public final class AhValuationRulesTest {
    private AhValuationRulesTest() {
    }

    public static void main(String[] args) {
        cvIgnoresVariantOutliers();
        cvStillCatchesRealSpread();
        petLevels();
        rareAddonDeduction();
        reforgeStones();
        durations();
        System.out.println("PASS: robust CV, pet level rule, rare addon deduction, reforge stones,"
                + " listing durations");
    }

    /** New Year Cake / Crab Hat case: one tag, a few variants at very different prices. */
    private static void cvIgnoresVariantOutliers() {
        List<Long> cake = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cake.add(1_350_000L + i * 5_000L); // normal years ~1.35M-1.45M
        }
        cake.add(25_000_000L); // old year
        cake.add(60_000_000L); // very old year
        cake.add(40_000L);     // troll sale
        double raw = rawCv(cake);
        double robust = AhMarketData.robustCvOf(cake, 0.5);
        // the old (raw) value rejected this item: far above the 100 % limit
        check(raw > 1.0, "raw CV above 100 % (" + pct(raw) + ")");
        check(robust < 0.10, "robust CV small, got " + pct(robust));

        List<Long> hats = new ArrayList<>(List.of(15_000_000L, 16_191_246L, 16_186_476L,
                16_539_522L, 16_920_968L, 16_416_436L, 16_616_826L, 15_500_000L,
                80_000_000L, 140_000_000L));
        check(rawCv(hats) > 1.0, "hat raw CV > 100 %");
        check(AhMarketData.robustCvOf(hats, 0.5) < 0.10, "hat robust CV small");
    }

    /** Real spread inside the normal price range must still show up. */
    private static void cvStillCatchesRealSpread() {
        List<Long> wild = List.of(5_000_000L, 7_000_000L, 9_000_000L, 11_000_000L,
                13_000_000L, 15_000_000L);
        double robust = AhMarketData.robustCvOf(wild, 0.5);
        check(robust > 0.25, "real spread stays visible, got " + pct(robust));
        check(AhMarketData.robustCvOf(List.of(1L), 0.5) == 0, "single sale -> 0");
    }

    private static void petLevels() {
        check(AhItem.petCompareLevel(1) == 1, "lvl 1");
        check(AhItem.petCompareLevel(50) == 1, "lvl 50 counts as 1");
        check(AhItem.petCompareLevel(94) == 1, "lvl 94 counts as 1");
        check(AhItem.petCompareLevel(95) == 95, "lvl 95 exact");
        check(AhItem.petCompareLevel(100) == 100, "lvl 100 exact");
        check(AhItem.petCompareLevel(0) == 0, "no level");

        List<AhMarketData.Sold> sales = List.of(
                sale(1, "LEGENDARY"), sale(1, "EPIC"), sale(100, "LEGENDARY"),
                sale(60, "LEGENDARY"));
        check(AhMarketData.petReferenceLevel(sales, 80, "LEGENDARY") == 1,
                "lvl 80 legendary -> compared with lvl 1");
        check(AhMarketData.petReferenceLevel(sales, 100, "LEGENDARY") == 100,
                "lvl 100 -> exact");
        check(AhMarketData.petReferenceLevel(sales, 97, "LEGENDARY") == 97,
                "lvl 97 -> exact even without sales");

        List<AhMarketData.Sold> noLevelOne = List.of(
                sale(10, "LEGENDARY"), sale(50, "LEGENDARY"), sale(90, "LEGENDARY"));
        check(AhMarketData.petReferenceLevel(noLevelOne, 80, "LEGENDARY") == 10,
                "no lvl-1 sale -> lowest level up to own level");
        check(AhMarketData.petReferenceLevel(List.of(sale(90, "LEGENDARY")), 50, "LEGENDARY") == 0,
                "only higher levels sold -> nothing to compare");

        AhItem pet = new AhItem();
        pet.tag = "PET_ENDER_DRAGON";
        pet.petLevel = 80;
        pet.petRarity = "LEGENDARY";
        check("&PetLevel=1&Rarity=LEGENDARY".equals(pet.petFilter()), "pet filter uses lvl 1");
        pet.petLevel = 100;
        check("&PetLevel=100&Rarity=LEGENDARY".equals(pet.petFilter()), "pet filter lvl 100");
    }

    private static void rareAddonDeduction() {
        List<AhRareAddons.Addon> rare = List.of(
                new AhRareAddons.Addon("Enchant critical 7", 10_000_000L, 2),
                new AhRareAddons.Addon("Reforge ancient", 2_000_000L, 0));
        check(AhRareAddons.totalValue(rare) == 12_000_000L, "total rare value");
        long deduction = AhRareAddons.deduction(rare, 90.0);
        check(deduction == 10_800_000L, "90 % of 12M, got " + deduction);
        // full value (e.g. Cofl median that contains the addons) minus 90 %
        check(AhRareAddons.apply(50_000_000L, deduction) == 39_200_000L, "50M - 10.8M");
        check(AhRareAddons.apply(5_000_000L, deduction) == 1, "never below 1");
        check(AhRareAddons.deduction(List.of(), 90.0) == 0, "nothing rare -> no deduction");
    }

    private static void reforgeStones() {
        check("PRECURSOR_GEAR".equals(AhAddonValues.reforgeStone("ancient")), "ancient stone");
        check("PRECURSOR_GEAR".equals(AhAddonValues.reforgeStone("Ancient")), "case-insensitive");
        check("WITHER_BLOOD".equals(AhAddonValues.reforgeStone("withered")), "withered stone");
        check(AhAddonValues.reforgeStone("wise").isEmpty(), "blacksmith reforge has no stone");
        check(AhAddonValues.reforgeStone("").isEmpty(), "no reforge");
    }

    /** The create menu shows "Duration: 2 Days"; config says "2 days". */
    private static void durations() {
        check(AhScreenUtil.durationMatches("2 days", "2 days"), "same text");
        check(AhScreenUtil.durationMatches("2 Days", "48 hours"), "2 days == 48 hours");
        check(AhScreenUtil.durationMatches("48 Hours", "2d"), "48 hours == 2d");
        // the old fuzzy check took "Duration: 6 Hours" for "48 hours"
        check(!AhScreenUtil.durationMatches("6 hours", "48 hours"), "6 hours != 48 hours");
        check(!AhScreenUtil.durationMatches("6 hours", "2 days"), "6 hours != 2 days");
        check(!AhScreenUtil.durationMatches("", "2 days"), "unreadable");
        check("2880".equals(AhScreenUtil.normalizeDuration("2 Days")), "2 days in minutes");
        check("30".equals(AhScreenUtil.normalizeDuration("30 Minutes")), "minutes");
    }

    private static AhMarketData.Sold sale(int level, String rarity) {
        AhMarketData.Sold sale = new AhMarketData.Sold();
        sale.price = 1_000_000L;
        sale.petLevel = level;
        sale.petRarity = rarity;
        return sale;
    }

    private static double rawCv(List<Long> prices) {
        double sum = 0;
        for (long price : prices) {
            sum += price;
        }
        double mean = sum / prices.size();
        double variance = 0;
        for (long price : prices) {
            variance += (price - mean) * (price - mean);
        }
        return Math.sqrt(variance / prices.size()) / mean;
    }

    private static String pct(double value) {
        return String.format(java.util.Locale.US, "%.1f%%", value * 100);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
