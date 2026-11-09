package com.coflnet.ahflipper;

import java.util.List;

public final class AhLbinMatchTest {
    private AhLbinMatchTest() {
    }

    public static void main(String[] args) {
        // exact hit, two auctions at the LBin
        AhMarketData.LbinMatch exact = AhMarketData.matchLbin(
                List.of(2_750_000L, 2_750_000L, 3_100_000L), 2_750_000L, 1.0, 5.0);
        check(exact.price() == 2_750_000L && exact.count() == 2, "exact match");

        // Cofl shows a rounded 2.8M, the real BINs are at 2.75M -> found below
        AhMarketData.LbinMatch below = AhMarketData.matchLbin(
                List.of(2_749_000L, 2_750_000L, 3_500_000L), 2_800_000L, 1.0, 5.0);
        check(below.price() == 2_749_000L && below.count() == 2, "rounded up -> search below");

        // Cofl shows 2.7M, real BIN 2.74M -> found above
        AhMarketData.LbinMatch above = AhMarketData.matchLbin(
                List.of(2_740_000L, 2_745_000L), 2_700_000L, 1.0, 5.0);
        check(above.price() == 2_740_000L && above.count() == 2, "rounded down -> search above");

        // nothing within 5 %: no LBin
        AhMarketData.LbinMatch none = AhMarketData.matchLbin(
                List.of(3_500_000L), 2_750_000L, 1.0, 5.0);
        check(none.price() == 0 && none.count() == 0, "nothing near");

        // tie between below/above -> the cheaper one
        AhMarketData.LbinMatch tie = AhMarketData.matchLbin(
                List.of(2_800_000L, 2_700_000L), 2_750_000L, 1.0, 5.0);
        check(tie.price() == 2_700_000L && tie.count() == 1, "tie prefers cheaper");

        // real case Last Breath: 4.9M, 4.999M, 5.0M used to be "1 Auktion"
        AhMarketData.LbinMatch lastBreath = AhMarketData.matchLbin(
                List.of(4_900_000L, 4_999_999L, 5_000_000L, 5_295_000L, 5_300_000L),
                4_900_000L, 5.0, 5.0);
        check(lastBreath.price() == 4_900_000L && lastBreath.count() == 3,
                "Last Breath counts the BINs up to +5 %, got " + lastBreath);

        // real case Fiery Burst Rune: other levels far below, LBin group at 2.7M
        AhMarketData.LbinMatch rune = AhMarketData.matchLbin(
                List.of(266_000L, 1_000_000L, 1_800_000L, 2_400_000L, 2_700_000L,
                        2_750_000L, 2_780_000L, 2_799_998L), 2_700_000L, 5.0, 5.0);
        check(rune.price() == 2_700_000L && rune.count() == 4, "rune group, got " + rune);

        // one single cheap BIN, the next one far above: still only 1 -> median
        AhMarketData.LbinMatch single = AhMarketData.matchLbin(
                List.of(2_000_000L, 2_600_000L), 2_000_000L, 5.0, 5.0);
        check(single.count() == 1, "single cheap BIN stays 1");

        // addon comparison items: only BINs at the exact Cofl LBin price count
        AhMarketData.LbinMatch exactOld = AhMarketData.matchLbinExact(
                List.of(4_900_000L, 4_999_999L, 5_000_000L), 4_900_000L, 1.0);
        check(exactOld.count() == 1 && exactOld.price() == 4_900_000L,
                "exact: 4.999M/5M do not count, got " + exactOld);
        AhMarketData.LbinMatch exactTwo = AhMarketData.matchLbinExact(
                List.of(8_200_000L, 8_200_000L, 8_250_000L), 8_200_000L, 1.0);
        check(exactTwo.count() == 3, "exact: within +-1 % counts, got " + exactTwo);
        AhMarketData.LbinMatch exactNone = AhMarketData.matchLbinExact(
                List.of(8_300_000L), 8_200_000L, 1.0);
        check(exactNone.count() == 0 && exactNone.price() == 0, "exact: nothing at the price");

        AhItem pet = new AhItem();
        pet.tag = "PET_GOLDEN_DRAGON";
        pet.petLevel = 200;
        pet.petRarity = "LEGENDARY";
        check(AhMarketData.lbinFilters(pet).equals(List.of(
                "&query%5BPetLevel%5D=200&query%5BRarity%5D=LEGENDARY",
                "&query%5BRarity%5D=LEGENDARY", "")), "pet filters " + AhMarketData.lbinFilters(pet));
        AhItem runeItem = new AhItem();
        runeItem.tag = "RUNE_FIERY_BURST";
        runeItem.runeLevel = 3;
        check(AhMarketData.lbinFilters(runeItem).equals(List.of(
                "&query%5BFieryBurstRune%5D=3", "")), "rune filter " + AhMarketData.lbinFilters(runeItem));
        AhItem boots = new AhItem();
        boots.tag = "STARRED_SHADOW_ASSASSIN_BOOTS";
        boots.stars = 5;
        check(AhMarketData.lbinFilters(boots).equals(List.of("&query%5BStars%5D=5", "")),
                "stars filter");
        AhItem plain = new AhItem();
        plain.tag = "LAST_BREATH";
        check(AhMarketData.lbinFilters(plain).equals(List.of("")), "no filter");

        check(AhItemValueTooltip.isOwnLine("Cofl-LBin: 2.8M"), "own LBin line");
        check(AhItemValueTooltip.isOwnLine("Lowest Addon-BIN: 5M"), "own addon bin line");
        check(AhItemValueTooltip.isOwnLine("[AH-Flipper] Wert: 2.75M (clean)"), "own value line");
        check(!AhItemValueTooltip.isOwnLine("Lbin: 2.75M"), "Cofl line is not ours");

        AhItem.CoflValues values = AhItem.parseCoflValues(List.of(
                "Lbin: 2.75M", "Med: 3.1M", "Cofl-LBin: 2.8M", "Cofl-Median: 3.2M"));
        check(values.lbin == 2_750_000L, "own rounded LBin ignored, got " + values.lbin);
        check(values.med == 3_100_000L, "own rounded median ignored, got " + values.med);

        check(AhItem.sameTag("RUNE", "RUNE_FIERY_BURST"), "old rune tag matches");
        check(AhItem.sameTag("PET_LION", "PET"), "old pet tag matches");
        check(!AhItem.sameTag("RUNE_MUSIC", "RUNE_FIERY_BURST"), "different runes");
        check(!AhItem.sameTag("PET_LION", "PET_TIGER"), "different pets");
        check(AhItem.isPetTag("PET_LION"), "pet tag");
        check(!AhItem.isPetTag("PET_SKIN_MITHRIL_GOLEM_GHOST"), "pet skin is no pet");
        check(!AhItem.isPetTag("PET_ITEM_TIER_BOOST"), "pet item is no pet");

        check("2.75M".equals(AhItemValueTooltip.coins(2_750_000L)), "coins 2.75M");
        check("76.6M".equals(AhItemValueTooltip.coins(76_600_000L)), "coins 76.6M");
        check("1.5B".equals(AhItemValueTooltip.coins(1_500_000_000L)), "coins 1.5B");
        check("950".equals(AhItemValueTooltip.coins(950L)), "coins small");

        System.out.println("PASS: LBin match, own tooltip lines, pet tags, coin format");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
