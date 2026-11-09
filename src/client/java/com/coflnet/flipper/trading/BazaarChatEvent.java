package com.coflnet.flipper.trading;

public class BazaarChatEvent {
    public enum Type {
        BUY_SETUP, SELL_SETUP, BUY_FILLED, SELL_FILLED, FLIPPED, CLAIMED_COINS, CLAIMED_ITEMS,
        CANCELLED, EXPIRED, ORDER_LIMIT, INVENTORY_FULL, OTHER
    }

    /** Which kind of order a cancel/expire line is about. */
    public enum Side {
        BUY, SELL, UNKNOWN
    }

    public Type type;
    public Side side = Side.UNKNOWN;
    public String item;
    public long qty;
    public double price;
    public long coins;
    public String raw;

    public static BazaarChatEvent parse(String stripped) {
        if (stripped == null) {
            return null;
        }
        BazaarChatEvent event = new BazaarChatEvent();
        event.raw = stripped;
        try {
            if (!isBazaarLine(stripped)) {
                // All chat comes through here. Without the prefix, only the limit and
                // inventory messages count: "You claimed X from Y's auction!" was taken
                // for a claimed order, and a player writing "5x hype claimed" as well.
                return stripped.contains(": ") ? null : parseWithoutPrefix(event, stripped);
            }
            event.side = stripped.contains("Sell Offer") ? Side.SELL
                    : stripped.contains("Buy Order") ? Side.BUY : Side.UNKNOWN;
            if (stripped.contains("Buy Order Setup!")) {
                event.type = Type.BUY_SETUP;
                parseSetup(event, stripped);
                return event;
            }
            if (stripped.contains("Sell Offer Setup!")) {
                event.type = Type.SELL_SETUP;
                parseSetup(event, stripped);
                return event;
            }
            if (stripped.contains("Order Flipped!")) {
                event.type = Type.FLIPPED;
                parseSetup(event, stripped);
                return event;
            }
            if (stripped.contains("was filled!")) {
                event.type = stripped.contains("Sell Offer") ? Type.SELL_FILLED : Type.BUY_FILLED;
                parseFilled(event, stripped);
                return event;
            }
            // "Claimed 5,040,000 coins from selling 5x Singed Powder at 1,008,000 each!"
            // also contains "5x ...", so it used to be read as a claim of 5 items.
            java.util.regex.Matcher coinsMatcher = java.util.regex.Pattern
                    .compile("Claimed ([\\d,.]+) coins from selling (?:([\\d,]+)x )?(.+?) at ")
                    .matcher(stripped);
            if (coinsMatcher.find()) {
                event.type = Type.CLAIMED_COINS;
                event.coins = Math.round(parseDouble(coinsMatcher.group(1)));
                event.qty = coinsMatcher.group(2) == null ? 0 : parseLong(coinsMatcher.group(2));
                event.item = coinsMatcher.group(3).trim();
                return event;
            }
            if (stripped.contains("Claimed") || stripped.contains("claimed")) {
                java.util.regex.Matcher itemMatcher = java.util.regex.Pattern
                        .compile("([\\d,]+)x (.+?)(?: worth| from|,|$)").matcher(stripped);
                if (itemMatcher.find()) {
                    event.type = Type.CLAIMED_ITEMS;
                    event.qty = parseLong(itemMatcher.group(1));
                    event.item = itemMatcher.group(2).trim();
                    return event;
                }
                if (stripped.contains("coins")) {
                    event.type = Type.CLAIMED_COINS;
                    event.coins = firstNumber(stripped);
                    event.item = extractItemAfterClaim(stripped);
                    return event;
                }
                event.type = Type.CLAIMED_ITEMS;
                event.item = extractItemAfterClaim(stripped);
                return event;
            }
            if (stripped.contains("cancelled") || stripped.contains("Cancelled")) {
                event.type = Type.CANCELLED;
                event.item = extractItemAfterClaim(stripped);
                event.qty = firstNumber(stripped);
                event.price = firstDecimal(stripped);
                return event;
            }
            if (stripped.contains("expired") || stripped.contains("Expired")) {
                event.type = Type.EXPIRED;
                event.item = extractItemAfterClaim(stripped);
                event.qty = firstNumber(stripped);
                return event;
            }
            return parseWithoutPrefix(event, stripped);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Hypixel's own Bazaar lines, as opposed to everything else in the chat. */
    public static boolean isBazaarLine(String stripped) {
        return stripped != null && stripped.trim().startsWith("[Bazaar]");
    }

    private static BazaarChatEvent parseWithoutPrefix(BazaarChatEvent event, String stripped) {
        String lower = stripped.toLowerCase();
        if (lower.contains("inventory") && (lower.contains("full") || lower.contains("space"))) {
            event.type = Type.INVENTORY_FULL;
            return event;
        }
        if (lower.contains("order") || lower.contains("offer")) {
            boolean limitSignal = lower.contains("too many")
                    || lower.contains("at once") || lower.contains("concurrent")
                    || lower.contains("order limit") || lower.contains("offer limit")
                    || lower.contains("maximum amount") || lower.contains("max amount")
                    || lower.contains("reached your maximum") || lower.contains("maximum of ")
                    || lower.contains("cancel some");
            if (limitSignal) {
                event.type = Type.ORDER_LIMIT;
                return event;
            }
        }
        return null;
    }

    private static void parseSetup(BazaarChatEvent event, String stripped) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\d,]+)x (.+?) for ([\\d,]+(?:\\.\\d+)?) coins")
                .matcher(stripped);
        if (matcher.find()) {
            event.qty = parseLong(matcher.group(1));
            event.item = matcher.group(2).trim();
            event.price = parseDouble(matcher.group(3));
        }
    }

    private static void parseFilled(BazaarChatEvent event, String stripped) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\d,]+)x (.+?) was filled!")
                .matcher(stripped);
        if (matcher.find()) {
            event.qty = parseLong(matcher.group(1));
            event.item = matcher.group(2).trim();
        }
    }

    private static String extractItemAfterClaim(String stripped) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\d,]+)x (.+?)(?: from|,|$)")
                .matcher(stripped);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return "";
    }

    private static long firstNumber(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\d,]{2,}").matcher(text);
        if (matcher.find()) {
            return parseLong(matcher.group());
        }
        return 0;
    }

    private static double firstDecimal(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\d,]+(?:\\.\\d+)?").matcher(text);
        if (matcher.find()) {
            return parseDouble(matcher.group());
        }
        return 0;
    }

    private static long parseLong(String value) {
        return Long.parseLong(value.replace(",", "").trim());
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value.replace(",", "").trim());
    }
}
