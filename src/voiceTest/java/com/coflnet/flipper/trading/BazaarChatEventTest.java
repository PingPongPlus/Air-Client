package com.coflnet.flipper.trading;

/** Checks the chat parser with real lines from the game logs. Run: gradlew bazaarChatEventTest */
public final class BazaarChatEventTest {
    private static int failures;

    public static void main(String[] args) {
        BazaarChatEvent coins = BazaarChatEvent.parse(
                "[Bazaar] Claimed 5,040,129 coins from selling 5x Singed Powder at 1,019,495.1 each!");
        check("coin claim is no item claim", coins != null
                && coins.type == BazaarChatEvent.Type.CLAIMED_COINS);
        check("coin claim amount", coins != null && coins.coins == 5_040_129L);
        check("coin claim item", coins != null && "Singed Powder".equals(coins.item));

        BazaarChatEvent items = BazaarChatEvent.parse(
                "[Bazaar] Claimed 8x Singed Powder worth 7,000,000 coins bought for 875,000 each!");
        check("item claim", items != null && items.type == BazaarChatEvent.Type.CLAIMED_ITEMS
                && items.qty == 8 && "Singed Powder".equals(items.item));

        BazaarChatEvent sellCancel = BazaarChatEvent.parse(
                "[Bazaar] Cancelled! Refunded 5x Singed Powder from cancelling Sell Offer!");
        check("cancelled sell offer", sellCancel != null
                && sellCancel.type == BazaarChatEvent.Type.CANCELLED
                && sellCancel.side == BazaarChatEvent.Side.SELL
                && "Singed Powder".equals(sellCancel.item));

        BazaarChatEvent buyCancel = BazaarChatEvent.parse(
                "[Bazaar] Cancelled! Refunded 1,000,000 coins from cancelling Buy Order!");
        check("cancelled buy order", buyCancel != null
                && buyCancel.side == BazaarChatEvent.Side.BUY);
        check("buy cancellation coin refund", buyCancel != null && buyCancel.coins == 1_000_000L);
        check("sell cancellation is an item refund", sellCancel != null && sellCancel.coins == 0L);

        BazaarChatEvent decimalRefund = BazaarChatEvent.parse(
                "[Bazaar] Cancelled! Refunded 600,000.5 coins from cancelling Buy Order!");
        check("fractional coin refund", decimalRefund != null && decimalRefund.coins == 600_001L);

        BazaarChatEvent setup = BazaarChatEvent.parse(
                "[Bazaar] Sell Offer Setup! 5x Singed Powder for 5,040,129 coins.");
        check("sell offer setup", setup != null && setup.type == BazaarChatEvent.Type.SELL_SETUP
                && setup.qty == 5);

        // Everything else in the chat must not be taken for a Bazaar event.
        check("auction claim", BazaarChatEvent.parse(
                "You claimed Ancient Necron's Helmet from [MVP+] michaelveryshort's auction!") == null);
        check("expired auction", BazaarChatEvent.parse(
                "You claimed Calcified Thunder Boots back from your expired auction!") == null);
        check("co-op auction cancel", BazaarChatEvent.parse(
                "TestPlayer cancelled an auction for Titanium Necklace!") == null);
        check("player chat", BazaarChatEvent.parse(
                "[289] [MVP+] qbreese: 5x hype claimed and cancelled") == null);
        check("progress line", BazaarChatEvent.parse("[Bazaar] Submitting buy order...") == null);

        if (failures > 0) {
            throw new AssertionError(failures + " check(s) failed");
        }
        System.out.println("BazaarChatEventTest ok");
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + name);
        }
    }
}
