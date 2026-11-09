package com.coflnet.ahflipper;

import java.util.List;

public final class AhProtectedBiddersTest {
    private AhProtectedBiddersTest() {
    }

    public static void main(String[] args) {
        // real chat lines from the log (colour codes already stripped)
        check("helitisy".equals(AhProtectedBidders.outbidder(
                "[Auction] helitisy outbid you by 100,000 coins for Squash Helmet CLICK")), "plain");
        check("ChonkyBoi9".equals(AhProtectedBidders.outbidder(
                "[Auction] ChonkyBoi9 outbid you by 5,000 coins for  Waxed Bone Necklace CLICK")),
                "name with digits");
        check("AarZGaming".equals(AhProtectedBidders.outbidder(
                "[Auction] [MVP+] AarZGaming outbid you by 50,000 coins for Last Breath CLICK")),
                "with rank");
        check(AhProtectedBidders.outbidder("Bid of 12,000 coins placed for Squash Helmet!").isEmpty(),
                "own bid line is no outbid");

        List<String> players = List.of("helitisy", "DaftDeer4");
        check(AhProtectedBidders.isProtectedName("Helitisy", players), "case ignored");
        check(AhProtectedBidders.isProtectedName("daftdeer4", players), "second player");
        check(!AhProtectedBidders.isProtectedName("ayoxis", players), "other player");
        check(!AhProtectedBidders.isProtectedName("", players), "empty name");

        // hard-coded premium players are always protected
        check(Premium.isName("ImmaMaster") && Premium.isName("xvoltrix_"), "premium names");
        check(!Premium.isName("ayoxis"), "non-premium name");
        check(AhProtectedBidders.isProtectedName("pingplus"), "premium is protected everywhere");
        check(Premium.protectedPlayers().containsAll(Premium.NAMES), "premium in protected list");
        System.out.println("PASS: dont-outbid chat names and player list");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
