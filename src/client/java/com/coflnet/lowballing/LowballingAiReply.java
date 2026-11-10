package com.coflnet.lowballing;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Builds short, casual replies for the local chat brain. All prices are
 * inserted from the validated context, never from free text.
 */
public final class LowballingAiReply {

    private static final Pattern UNSAFE = Pattern.compile("[\\r\\n\\t]");
    private static final int MAX_LENGTH = 200;

    private static final List<String> ACCEPT = List.of(
            "ok {price} works, send it",
            "alr {price} deal, accept the trade",
            "sure {price} for the {item}",
            "{price} is fine, trade me",
            "deal, {price} it is",
            "ok {price}, send me a trade");

    private static final List<String> COUNTER = List.of(
            "cant do {ask}, {last} is my max",
            "{ask} is too much for me, {last} is the best i can do",
            "i can go {last} max for the {item}",
            "sorry, {last} is my max",
            "nah {last} is my best",
            "{ask} wont work, {last} is my final",
            "i cant go higher than {last}");

    private static final List<String> FINAL = List.of(
            "{last} final offer, take it or leave it",
            "last one: {last}",
            "ok {last} is my final",
            "take it or leave it, {last}",
            "{last} and we trade, final");

    private static final List<String> COMPLAINT = List.of(
            "i know its low but {last} is what i can do",
            "it sells for less after ah tax, {last} is my best",
            "{last} is my final for the {item}",
            "gotta make a bit on it, {last} is the max",
            "thats the listing price not the sale price, {last} is what i can do");

    private static final List<String> VALUE_CLAIM = List.of(
            "lbin isnt what they actually sell for, {last} is my best for the {item}",
            "ik the lbin but they dont sell at that, {last} is the most i can do",
            "i checked the recent sales, {last} is my max for the {item}",
            "thats the listing price not the sale price, {last} is what i can do",
            "they sit on ah for ages at that price, {last} is my offer");

    private static final List<String> PRICE_QUESTION = List.of(
            "{last} is my offer for the {item}",
            "i can do {last}",
            "my best is {last}",
            "{last} for the {item}",
            "i got {last} for it");

    private static final List<String> OTHER_PLAYER = List.of(
            "one sec, finishing a trade",
            "in a trade rn, give me a min",
            "wait a sec pls",
            "one moment, then i can check");

    private static final List<String> WAIT = List.of(
            "ok waiting",
            "np take your time",
            "alr lmk",
            "sure, take your time");

    private static final List<String> INSULT = List.of(
            "ok gl then",
            "alright, gl",
            "no need to be like that, {last} is my offer");

    private static final List<String> NO_OFFER = List.of(
            "one sec, checking the items",
            "let me see the items first",
            "send the trade and i'll check",
            "gimme a sec, i'll value them");

    private static final List<String> AH_REFUSAL = List.of(
            "sry i dont buy from ah, give them to me",
            "i only trade directly, put the items in a trade",
            "cant buy from ah, trade me the items",
            "no ah buys, send them in a trade",
            "i dont buy from ah, trade me the items");

    private static final java.util.Map<String, java.util.Deque<Integer>> LAST =
            new java.util.HashMap<>();

    private LowballingAiReply() {
    }

    public static String accept(long price, String item) {
        return fill(pick("accept", ACCEPT), 0, price, item);
    }

    public static String counter(long ask, long last, String item) {
        return fill(pick("counter", COUNTER), ask, last, item);
    }

    public static String finalOffer(long last, String item) {
        return fill(pick("final", FINAL), 0, last, item);
    }

    public static String complaint(long last, String item) {
        return fill(pick("complaint", COMPLAINT), 0, last, item);
    }

    public static String valueClaim(long last, String item) {
        return fill(pick("value", VALUE_CLAIM), 0, last, item);
    }

    public static String priceQuestion(long last, String item) {
        return fill(pick("price", PRICE_QUESTION), 0, last, item);
    }

    public static String otherPlayer() {
        return fill(pick("other", OTHER_PLAYER), 0, 0, null);
    }

    public static String waitMessage() {
        return fill(pick("wait", WAIT), 0, 0, null);
    }

    public static String insult(long last, String item) {
        return fill(pick("insult", INSULT), 0, last, item);
    }

    public static String noOffer() {
        return fill(pick("nooffer", NO_OFFER), 0, 0, null);
    }

    public static String ahRefusal() {
        return fill(pick("ah", AH_REFUSAL), 0, 0, null);
    }

    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String clean = UNSAFE.matcher(text).replaceAll(" ")
                .replace('\u00a7', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.startsWith("/")) {
            clean = clean.substring(1).trim();
        }
        if (clean.length() > MAX_LENGTH) {
            clean = clean.substring(0, MAX_LENGTH).trim();
        }
        return clean.isBlank() ? null : clean;
    }

    private static String pick(String key, List<String> templates) {
        java.util.Deque<Integer> recent = LAST.computeIfAbsent(key,
                ignored -> new java.util.ArrayDeque<>());
        int attempts = Math.min(templates.size(), 4);
        int index = ThreadLocalRandom.current().nextInt(templates.size());
        for (int attempt = 0; attempt < attempts && recent.contains(index); attempt++) {
            index = (index + 1) % templates.size();
        }
        recent.addLast(index);
        while (recent.size() > 3) {
            recent.removeFirst();
        }
        return templates.get(index);
    }

    private static String fill(String template, long ask, long price, String item) {
        String shortItem = item == null || item.isBlank() ? "item"
                : item.toLowerCase(Locale.ROOT);
        return template
                .replace("{ask}", LowballingSmartChat.format(ask))
                .replace("{price}", LowballingSmartChat.format(price))
                .replace("{last}", LowballingSmartChat.format(price))
                .replace("{item}", shortItem);
    }
}
