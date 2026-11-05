package com.coflnet.ahflipper;

public final class AhFlipperConfigTest {
    private AhFlipperConfigTest() {
    }

    public static void main(String[] args) {
        check("SHADOW_ASSASSIN_CHESTPLATE".equals(
                AhFlipperConfig.baseTag("starred_shadow_assassin_chestplate")),
                "baseTag starred");
        check("SHADOW_ASSASSIN_CHESTPLATE".equals(
                AhFlipperConfig.baseTag(" Starred_Shadow_Assassin_Chestplate ")),
                "baseTag mixed case");
        check("".equals(AhFlipperConfig.baseTag("")), "baseTag empty");

        AhFlipperConfig config = new AhFlipperConfig();
        boolean locked = config.toggleBlacklist("STARRED_HYPERION", "Heroic Hyperion");
        check(locked, "toggle locks");
        check(config.isBlockedItem("HYPERION", "Heroic Hyperion"),
                "variant blocked by base tag");
        check(config.isBlockedItem("STARRED_HYPERION", "Heroic Hyperion"),
                "starred variant blocked");
        boolean unlocked = config.toggleBlacklist("HYPERION", "Heroic Hyperion");
        check(!unlocked, "toggle unlocks");
        check(!config.isBlockedItem("HYPERION", "Heroic Hyperion"),
                "unlocked item not blocked");

        config.blacklist.add("Heroic Hyperion");
        check(config.isBlockedItem("HYPERION", "Heroic Hyperion"),
                "legacy text blacklist still works");

        System.out.println("PASS: baseTag, tag blacklist and legacy blacklist");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
