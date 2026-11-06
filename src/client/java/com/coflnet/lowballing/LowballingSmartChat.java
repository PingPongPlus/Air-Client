package com.coflnet.lowballing;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Island negotiation chat: reads what the visitor wrote and answers in a short,
 * casual way (accept a price we can pay, explain why a counter is too high,
 * answer "too low" complaints). Only used on the island, never for the hub ads.
 */
public final class LowballingSmartChat {

    private static final Pattern NUMBER = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)*)\\s*(k|m|mil|mill|million|b|bil|billion)?\\b");

    private static final Set<String> AGREE = Set.of(
            "ok", "okay", "okey", "k", "kk", "sure", "deal", "fine", "yes", "yeah", "ye", "yep",
            "yup", "alr", "alright", "bet", "send", "done", "accept", "accepted", "ight");
    /**
     * Words that turn an "ok"/"send" into a question or a no ("can you go
     * higher", "dont send"). Tokens are split on non-letters, so "don't" is "don".
     */
    private static final Set<String> NEGATION = Set.of(
            "not", "dont", "don", "cant", "wont", "won", "didnt", "didn", "isnt", "isn",
            "higher", "more", "raise", "but", "if", "what", "how", "why", "wait");
    private static final Set<String> REFUSE = Set.of(
            "no", "nah", "nope", "never", "pass", "naw", "nty", "noo", "nooo");
    private static final List<String> COMPLAINT_WORDS = List.of(
            "low", "lowball", "insult", "worth", "more", "higher", "troll", "joke", "scam",
            "cheap", "lbin", "lowest bin");

    private static final List<String> ACCEPT = List.of(
            "{ask} works, trading you",
            "ok {ask} deal, send it",
            "sure {ask} for the {item}",
            "alr {ask} it is",
            "{ask} is fine, accept the trade");
    private static final List<String> TOO_HIGH = List.of(
            "cant do {ask}, {offer} is my max for the {item}",
            "{ask} is too much for me, {offer} is the best i can do",
            "i can go {offer} max, it sells for less after tax",
            "{ask} wont work, {offer} is my final");
    private static final List<String> COMPLAINT = List.of(
            "i know its low but {offer} is what i can do for the {item}",
            "it sells for less after ah tax, {offer} is my best",
            "{offer} is my final for the {item}",
            "gotta make a bit on it, {offer} is the max");

    private static final List<String> ACCEPT_FINAL = List.of(
            "ok thats fine, {ask} it is",
            "fine with me, {ask} - trading you again",
            "alr {ask} works, accept this time",
            "ok {ask}, sending trade");
    private static final List<String> MEET_FINAL = List.of(
            "take it or leave it, {ask} final offer",
            "{ask} final offer, take it or leave it",
            "ill meet you a bit, {ask} is my final offer for the {item}",
            "last one: {ask}, take it or leave it");

    private static final int[] LAST = {-1, -1, -1, -1, -1};

    private LowballingSmartChat() {
    }

    /**
     * Price the visitor asks for, in coins, or 0 when there is none. Plain numbers
     * below 1000 count as millions ("190" -> 190m). Numbers far away from the item
     * value (stars, levels, typos) are ignored.
     */
    public static long parseAsk(String text, long itemValue) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = NUMBER.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            long coins = toCoins(matcher.group(1), matcher.group(2));
            if (coins <= 0) {
                continue;
            }
            if (itemValue <= 0 || (coins >= itemValue * 0.2 && coins <= itemValue * 5.0)) {
                return coins;
            }
        }
        return 0;
    }

    private static long toCoins(String number, String suffix) {
        String raw = number;
        double multiplier;
        if (suffix == null || suffix.isBlank()) {
            // A bare number while negotiating always means millions: "12" = 12m,
            // "12.5" / "12,5" / "12.500" = 12.5m. Only a full coin amount with at
            // least two thousands groups ("14,500,000") or 7+ plain digits is
            // taken literally.
            if (raw.matches("\\d{1,3}([.,]\\d{3}){2,}")) {
                try {
                    return Long.parseLong(raw.replace(",", "").replace(".", ""));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            if (raw.matches("\\d{7,}")) {
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            String decimal = raw;
            int separators = raw.replaceAll("[^.,]", "").length();
            if (separators > 1) {
                return 0;
            }
            try {
                return Math.round(Double.parseDouble(decimal.replace(',', '.')) * 1_000_000.0);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        multiplier = switch (suffix.toLowerCase(Locale.ROOT).charAt(0)) {
            case 'k' -> 1_000.0;
            case 'b' -> 1_000_000_000.0;
            default -> 1_000_000.0;
        };
        try {
            return Math.round(Double.parseDouble(raw.replace(',', '.')) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isAgreement(String text) {
        // "190k" must not count as "k" (= ok). "go" is no longer an agreement word
        // ("can you go higher" started a trade request right away); questions,
        // negations and complaints are never an agreement.
        return text != null && !text.matches(".*\\d.*") && !text.contains("?")
                && hasWord(text, AGREE) && !hasWord(text, REFUSE)
                && !hasWord(text, NEGATION) && !isComplaint(text);
    }

    public static boolean isRefusal(String text) {
        return hasWord(text, REFUSE);
    }

    public static boolean isComplaint(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : COMPLAINT_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public static String acceptAsk(long ask, String item) {
        return fill(pick(ACCEPT, 0), ask, 0, item);
    }

    public static String counterTooHigh(long ask, long offer, String item) {
        return fill(pick(TOO_HIGH, 1), ask, offer, item);
    }

    public static String acceptFinal(long price, String item) {
        return fill(pick(ACCEPT_FINAL, 3), price, 0, item);
    }

    public static String meetFinal(long price, String item) {
        return fill(pick(MEET_FINAL, 4), price, 0, item);
    }

    public static String answerComplaint(long offer, String item) {
        return fill(pick(COMPLAINT, 2), 0, offer, item);
    }

    private static boolean hasWord(String text, Set<String> words) {
        if (text == null) {
            return false;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (words.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Random template, never the same one twice in a row. */
    private static String pick(List<String> templates, int slot) {
        int index = ThreadLocalRandom.current().nextInt(templates.size());
        if (templates.size() > 1 && index == LAST[slot]) {
            index = (index + 1) % templates.size();
        }
        LAST[slot] = index;
        return templates.get(index);
    }

    private static String fill(String template, long ask, long offer, String item) {
        return template.replace("{ask}", format(ask))
                .replace("{offer}", format(offer))
                .replace("{item}", item == null || item.isBlank() ? "item" : item);
    }

    /**
     * Rounds one of OUR offers DOWN to a clean chat number (10m steps above 1b,
     * 100k above 1m, 1k below). The coins put into the trade are exactly this
     * value, so what we write in chat and what we pay is always the same.
     */
    public static long chatRound(double coins) {
        if (coins <= 0) {
            return 0;
        }
        long unit = coins >= 1_000_000_000L ? 10_000_000L
                : coins >= 1_000_000L ? 100_000L : 1_000L;
        return (long) Math.floor(coins / unit) * unit;
    }

    /**
     * Chat text for a coin amount. Never rounds up and shows enough decimals
     * that a visitor's own ask (e.g. 12.35m) is repeated exactly; our own
     * offers are already {@link #chatRound rounded}.
     */
    static String format(long coins) {
        if (coins >= 1_000_000_000L) {
            long millions = coins / 1_000_000L;
            return trim(String.format(Locale.US, "%.3f", millions / 1_000.0)) + "b";
        }
        if (coins >= 1_000_000L) {
            long tenThousands = coins / 10_000L;
            return trim(String.format(Locale.US, "%.2f", tenThousands / 100.0)) + "m";
        }
        long hundreds = coins / 100L;
        return trim(String.format(Locale.US, "%.1f", hundreds / 10.0)) + "k";
    }

    private static String trim(String number) {
        if (!number.contains(".")) {
            return number;
        }
        number = number.replaceAll("0+$", "");
        return number.endsWith(".") ? number.substring(0, number.length() - 1) : number;
    }
}
