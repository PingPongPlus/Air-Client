package com.coflnet.lowballing;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small local chat understanding model.
 *
 * <p>Clear cases are handled by deterministic patterns (price, yes, no, wait,
 * market-value claim ...). A multinomial Naive Bayes classifier, trained at
 * startup from the bundled {@code chat_training.tsv}, handles the rest. There
 * is no network call, no external model and no global state outside the game
 * process.</p>
 */
public final class LowballingChatClassifier {

    public record Stats(int examples, int labels, int features, boolean ready) {
    }

    private static final Pattern TOKEN = Pattern.compile("[a-z']+|[?!]");
    private static final Pattern NUMBER = Pattern.compile(
            "\\d+(?:[.,]\\d+)*\\s*(k|m|mil|mill|million|b|bil|billion)?");
    private static final Pattern SUFFIX_PRICE = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)*)\\s*(k|m|mil|mill|million|mio|b|bil|billion)\\b");
    private static final Pattern FULL_COINS = Pattern.compile(
            "\\b\\d{1,3}(?:[.,]\\d{3}){2,}\\b|\\b\\d{7,}\\b");
    private static final Pattern BARE_NUMBER = Pattern.compile("\\b\\d+(?:[.,]\\d+)*\\b");
    private static final Pattern COUNT_X = Pattern.compile("(?i)\\b\\d+\\s*x\\b");

    private static final Set<String> STAT_WORDS = Set.of(
            "item", "items", "pet", "pets", "star", "stars", "level", "levels", "lvl",
            "piece", "pieces", "stack", "stacks", "day", "days", "hour", "hours");
    private static final Set<String> OFFER_WORDS = Set.of(
            "do", "does", "give", "take", "pay", "offer", "sell", "buy", "want", "need",
            "deal", "works", "work", "send", "trade", "for", "price", "max", "best",
            "pls", "please", "ok", "okay", "ill", "can", "could", "would", "will",
            "firm", "final", "lowest", "highest", "maximum", "minimum", "about",
            "much", "gimme");

    private static final Set<String> INSULT_WORDS = Set.of(
            "crazy", "idiot", "stupid", "dumb", "moron", "clown", "loser", "broke",
            "poor", "trash", "dogshit", "braindead", "delusional", "clueless",
            "nonsense", "insane", "wtf", "mad", "dumbass", "retard");
    private static final List<String> INSULT_PHRASES = List.of(
            "are you serious", "are u serious", "what is wrong with you", "youre a joke",
            "this is a joke", "what a joke", "you must be joking", "youre joking",
            "shut up", "screw you", "get lost", "actually insane", "insane offer",
            "worst offer", "horrible offer", "terrible offer");
    private static final Set<String> WAIT_WORDS = Set.of(
            "wait", "sec", "secs", "second", "seconds", "brb", "afk", "moment",
            "hold", "gimme", "minute", "min");
    private static final Set<String> GREETING_WORDS = Set.of(
            "hi", "hey", "hello", "yo", "sup", "howdy", "heya", "greetings");
    private static final Set<String> QUESTION_START = Set.of(
            "how", "what", "whats", "why", "when", "where", "who", "do", "does",
            "can", "could", "would", "will", "are", "is", "u");

    private static final double ALPHA = 1.0;
    private static final Map<String, Map<String, Integer>> COUNTS = new HashMap<>();
    private static final Map<String, Integer> TOTAL_FEATURES = new HashMap<>();
    private static final Map<String, Integer> LABEL_EXAMPLES = new HashMap<>();
    private static final Set<String> VOCABULARY = new HashSet<>();
    private static int examples;
    private static boolean loaded;
    private static boolean ready;

    private LowballingChatClassifier() {
    }

    public static Stats stats() {
        ensureLoaded();
        return new Stats(examples, LABEL_EXAMPLES.size(), VOCABULARY.size(), ready);
    }

    public static LowballingChatClassification classify(String text, long itemValue) {
        ensureLoaded();
        String raw = text == null ? "" : text.trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        long price = parsePrice(raw, itemValue, lower);
        boolean pricePresent = price > 0;
        boolean valueClaim = LowballingSmartChat.isValueClaim(raw);
        boolean agreement = LowballingSmartChat.isAgreement(raw);
        boolean refusal = LowballingSmartChat.isRefusal(raw);
        boolean complaint = LowballingSmartChat.isComplaint(raw);
        boolean insult = isInsult(lower);
        boolean question = isQuestion(lower);
        boolean wait = isWait(lower);
        boolean greeting = isGreeting(lower);
        boolean ahRequest = isAhRequest(lower);

        if (ahRequest) {
            return result(LowballingChatIntent.AH_OFFER, 1.0, price, pricePresent,
                    agreement, refusal, complaint, insult, valueClaim, question, wait,
                    greeting, raw);
        }
        // A market claim ("lbin is 30m") is not an offer to sell for that
        // number, so it must win over the price path.
        if (pricePresent && valueClaim) {
            return result(LowballingChatIntent.VALUE_CLAIM, 1.0, price, pricePresent,
                    agreement, refusal, complaint, insult, valueClaim, question, wait,
                    greeting, raw);
        }
        if (pricePresent) {
            return result(LowballingChatIntent.PRICE_OFFER, 1.0, price, true,
                    agreement, refusal, complaint, insult, false, question, wait,
                    greeting, raw);
        }
        if (agreement) {
            return result(LowballingChatIntent.ACCEPT, 1.0, 0, false,
                    true, false, complaint, insult, valueClaim, question, wait,
                    greeting, raw);
        }
        if (refusal) {
            return result(LowballingChatIntent.REFUSE, 1.0, 0, false,
                    false, true, complaint, insult, valueClaim, question, wait,
                    greeting, raw);
        }
        if (wait) {
            return result(LowballingChatIntent.WAIT, 1.0, 0, false,
                    false, false, complaint, insult, valueClaim, question, true,
                    greeting, raw);
        }
        if (insult) {
            return result(LowballingChatIntent.INSULT, 0.95, 0, false,
                    false, false, complaint, true, valueClaim, question, false,
                    greeting, raw);
        }
        if (valueClaim) {
            return result(LowballingChatIntent.COMPLAINT, 0.9, 0, false,
                    false, false, true, insult, true, question, false, greeting, raw);
        }
        if (complaint) {
            return result(LowballingChatIntent.COMPLAINT, 0.9, 0, false,
                    false, false, true, insult, false, question, false, greeting, raw);
        }
        if (question) {
            return result(LowballingChatIntent.QUESTION, 0.85, 0, false,
                    false, false, complaint, insult, false, true, false, greeting, raw);
        }
        if (greeting) {
            return result(LowballingChatIntent.GREETING, 0.9, 0, false,
                    false, false, complaint, insult, false, question, false, true, raw);
        }

        String label = null;
        double confidence = 0;
        if (ready && !lower.isBlank()) {
            Prediction prediction = predict(features(raw));
            if (prediction != null) {
                label = prediction.label;
                confidence = prediction.confidence;
            }
        }
        LowballingChatIntent intent = toIntent(label);
        if (intent == LowballingChatIntent.AH_OFFER) {
            // The seed data has many "ah ..." lines; an interjection like
            // "ah look at this" must not become an AH request by accident.
            intent = LowballingChatIntent.CONTEXT_LESS;
        }
        if (intent == LowballingChatIntent.PRICE_OFFER) {
            // The rules found no number, so a model price label is only noise.
            intent = LowballingChatIntent.CONTEXT_LESS;
        }
        if ((intent == LowballingChatIntent.ACCEPT || intent == LowballingChatIntent.REFUSE
                || intent == LowballingChatIntent.WAIT) && confidence < 0.55) {
            intent = LowballingChatIntent.CONTEXT_LESS;
        }
        if ((intent == LowballingChatIntent.COMPLAINT || intent == LowballingChatIntent.INSULT
                || intent == LowballingChatIntent.QUESTION || intent == LowballingChatIntent.GREETING)
                && confidence < 0.35) {
            intent = LowballingChatIntent.CONTEXT_LESS;
        }
        if (intent == LowballingChatIntent.VALUE_CLAIM) {
            intent = LowballingChatIntent.COMPLAINT;
            valueClaim = true;
        }
        if (intent == null) {
            intent = LowballingChatIntent.UNKNOWN;
        }
        return result(intent, confidence, 0, false, false, false,
                complaint || intent == LowballingChatIntent.COMPLAINT,
                insult || intent == LowballingChatIntent.INSULT,
                valueClaim, question || intent == LowballingChatIntent.QUESTION,
                wait || intent == LowballingChatIntent.WAIT,
                greeting || intent == LowballingChatIntent.GREETING, raw);
    }

    private static LowballingChatClassification result(
            LowballingChatIntent intent, double confidence, long price,
            boolean pricePresent, boolean agreement, boolean refusal, boolean complaint,
            boolean insult, boolean valueClaim, boolean question, boolean waiting,
            boolean greeting, String text) {
        return new LowballingChatClassification(intent, confidence, price, pricePresent,
                agreement, refusal, complaint, insult, valueClaim, question, waiting,
                greeting, text);
    }

    private static LowballingChatIntent toIntent(String label) {
        if (label == null) {
            return null;
        }
        try {
            return LowballingChatIntent.valueOf(label);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isInsult(String lower) {
        if (hasWord(lower, INSULT_WORDS)) {
            return true;
        }
        for (String phrase : INSULT_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWait(String lower) {
        return hasWord(lower, WAIT_WORDS);
    }

    private static boolean isGreeting(String lower) {
        String text = lower.trim();
        String first = text.split("[^a-z]+", 2)[0];
        return GREETING_WORDS.contains(first)
                || text.startsWith("good morning") || text.startsWith("good evening");
    }

    private static boolean isQuestion(String lower) {
        if (lower.contains("?")) {
            return true;
        }
        String text = lower.trim();
        String first = text.split("[^a-z]+", 2)[0];
        if (QUESTION_START.contains(first)) {
            return true;
        }
        return lower.contains("how much") || lower.contains("whats your")
                || lower.contains("what is your") || lower.contains("is that your best")
                || lower.contains("u there") || lower.contains("you there");
    }

    /**
     * "look at my ah", "buy it from ah", "would u lb my ah" ... We only trade
     * directly. A pure market claim like "on ah for 20m" is not a request.
     */
    private static boolean isAhRequest(String lower) {
        String text = lower.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (text.startsWith("/ah") || text.startsWith("./ah") || text.startsWith(".ah")) {
            return true;
        }
        boolean hasAh = text.matches(".*\\bah\\b.*") || text.contains("auction");
        if (!hasAh) {
            return false;
        }
        boolean valueClaim = LowballingSmartChat.isValueClaim(text);
        boolean direct = text.contains("my ah") || text.contains("my auction")
                || text.contains("auction house") || text.contains("from ah")
                || text.contains("buy ah") || text.contains("buy it")
                || text.contains("buy from") || text.contains("buy my")
                || text.contains("look at my ah") || text.contains("look at the ah")
                || text.contains("look at the auction") || text.contains("look on my ah")
                || text.contains("check my ah") || text.contains("check my auction")
                || text.contains("check the ah") || text.contains("check the auction")
                || text.contains("check ah") || text.contains("ah items")
                || text.contains("ah stuff") || text.contains("ah has")
                || text.contains("listed") || text.contains("put it on ah")
                || text.contains("put them on ah") || text.contains("selling on ah")
                || text.contains("sell on ah") || text.contains("its on ah")
                || text.contains("it is on ah");
        if (valueClaim && !(text.contains("my ah") || text.contains("my auction")
                || text.contains("my items") || text.contains("my stuff")
                || text.contains("buy") || text.contains("look") || text.contains("check")
                || text.contains("listed") || text.contains("put") || text.contains("sell")
                || text.contains("its on ah") || text.contains("it is on ah"))) {
            return false;
        }
        return direct || text.contains("on ah");
    }

    private static boolean hasWord(String text, Set<String> words) {
        for (String token : text.split("[^a-z]+")) {
            if (words.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The generic {@link LowballingSmartChat#parseAsk} treats every bare number
     * as millions. In a chat brain that would turn "1 sec" or "3 items" into a
     * price, so only accept numbers that really look like an offer.
     */
    static long parsePrice(String text, long itemValue, String lower) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher suffix = SUFFIX_PRICE.matcher(text);
        while (suffix.find()) {
            long coins = LowballingSmartChat.toCoins(suffix.group(1), suffix.group(2));
            if (validPrice(coins, itemValue)) {
                return coins;
            }
        }
        Matcher full = FULL_COINS.matcher(text);
        while (full.find()) {
            long coins = LowballingSmartChat.toCoins(full.group(), null);
            if (validPrice(coins, itemValue)) {
                return coins;
            }
        }
        // "1 sec" / "10 min" are no prices, but "10m min" and "gimme 10" are.
        if (!isPriceContext(lower)) {
            return 0;
        }
        Matcher bare = BARE_NUMBER.matcher(text);
        while (bare.find()) {
            long coins = LowballingSmartChat.toCoins(bare.group(), null);
            if (validPrice(coins, itemValue)) {
                return coins;
            }
        }
        return 0;
    }

    private static boolean validPrice(long coins, long itemValue) {
        if (coins <= 0) {
            return false;
        }
        return itemValue <= 0
                || (coins >= itemValue * 0.2 && coins <= itemValue * 5.0);
    }

    private static boolean isPriceContext(String lower) {
        if (COUNT_X.matcher(lower).find() || hasWord(lower, STAT_WORDS)) {
            return false;
        }
        String without = BARE_NUMBER.matcher(lower).replaceAll(" ").trim();
        String filler = without.replaceAll("[^a-z? ]", " ")
                .replaceAll("\\s+", " ").trim();
        if (filler.isEmpty() || filler.matches(
                "(ok|pls|please|then|now|lol|xd|bro|man|ty|thx|thanks|\\?)+")) {
            return true;
        }
        for (String token : filler.split(" ")) {
            if (OFFER_WORDS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (LowballingChatClassifier.class) {
            if (loaded) {
                return;
            }
            loaded = true;
            try (InputStream stream = openTrainingData()) {
                if (stream == null) {
                    System.out.println("[Lowballing] AI training data missing - rules only.");
                    return;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isBlank() || line.startsWith("#")) {
                            continue;
                        }
                        int separator = line.indexOf('|');
                        if (separator <= 0 || separator >= line.length() - 1) {
                            continue;
                        }
                        String label = line.substring(0, separator).trim();
                        String text = line.substring(separator + 1).trim();
                        if (label.isBlank() || text.isBlank()) {
                            continue;
                        }
                        addExample(label, text);
                    }
                }
                ready = examples > 0 && LABEL_EXAMPLES.size() > 1;
                System.out.println("[Lowballing] AI trained: " + examples + " examples, "
                        + LABEL_EXAMPLES.size() + " intents, " + VOCABULARY.size()
                        + " features" + (ready ? "" : " (rules only)"));
            } catch (Exception e) {
                System.out.println("[Lowballing] AI training failed: " + e.getMessage());
            }
        }
    }

    private static InputStream openTrainingData() {
        InputStream stream = LowballingChatClassifier.class.getResourceAsStream(
                "/assets/coflmod/lowballing/chat_training.tsv");
        if (stream != null) {
            return stream;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? null
                : loader.getResourceAsStream("assets/coflmod/lowballing/chat_training.tsv");
    }

    private static void addExample(String label, String text) {
        List<String> features = features(text);
        if (features.isEmpty()) {
            return;
        }
        Map<String, Integer> counts = COUNTS.computeIfAbsent(label,
                ignored -> new HashMap<>());
        for (String feature : features) {
            counts.merge(feature, 1, Integer::sum);
            VOCABULARY.add(feature);
        }
        TOTAL_FEATURES.merge(label, features.size(), Integer::sum);
        LABEL_EXAMPLES.merge(label, 1, Integer::sum);
        examples++;
    }

    private record Prediction(String label, double confidence) {
    }

    private static Prediction predict(List<String> features) {
        if (features.isEmpty() || VOCABULARY.isEmpty()) {
            return null;
        }
        int vocabulary = VOCABULARY.size();
        String bestLabel = null;
        String secondLabel = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double secondScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Integer> label : LABEL_EXAMPLES.entrySet()) {
            if (label.getValue() <= 0) {
                continue;
            }
            // Uniform prior: price examples are over-represented in the seed
            // data, and the explicit price rules already handle real offers.
            double score = 0.0;
            Map<String, Integer> counts = COUNTS.getOrDefault(label.getKey(), Map.of());
            double total = TOTAL_FEATURES.getOrDefault(label.getKey(), 0);
            for (String feature : features) {
                int count = counts.getOrDefault(feature, 0);
                score += Math.log((count + ALPHA) / (total + ALPHA * vocabulary));
            }
            if (score > bestScore) {
                secondScore = bestScore;
                secondLabel = bestLabel;
                bestScore = score;
                bestLabel = label.getKey();
            } else if (score > secondScore) {
                secondScore = score;
                secondLabel = label.getKey();
            }
        }
        if (bestLabel == null) {
            return null;
        }
        double margin = secondLabel == null ? 9.0 : bestScore - secondScore;
        double confidence = Math.max(0, Math.min(1, 1.0 - Math.exp(-margin / 2.0)));
        return new Prediction(bestLabel, confidence);
    }

    /**
     * Numbers are replaced by "num" so 10m and 25m are the same feature; the
     * actual amount is extracted separately by the price parser.
     */
    static List<String> features(String text) {
        String prepared = NUMBER.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" num ");
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(prepared);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        if (tokens.isEmpty()) {
            return tokens;
        }
        List<String> features = new ArrayList<>(tokens);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            features.add(tokens.get(i) + "_" + tokens.get(i + 1));
        }
        if (tokens.size() <= 2) {
            features.add("short");
        }
        if (tokens.contains("?")) {
            features.add("ask_mark");
        }
        return features;
    }
}
