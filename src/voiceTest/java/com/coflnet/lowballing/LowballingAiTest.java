package com.coflnet.lowballing;

import java.util.List;

public final class LowballingAiTest {

    private static final long M = 1_000_000L;
    private static final LowballingAiRules RULES = new LowballingAiRules(
            true, true, true, 1, 1, 1.0, 5.0, 10_000L);

    private LowballingAiTest() {
    }

    public static void main(String[] args) {
        LowballingChatClassifier.Stats stats = LowballingChatClassifier.stats();
        check(stats.ready(), "training data loaded: " + stats);
        check(stats.examples() > 250, "enough training examples: " + stats.examples());
        check(stats.labels() >= 8, "enough intents: " + stats.labels());

        checkIntent("are u crazy?", LowballingChatIntent.INSULT);
        checkIntent("i do for 9m", LowballingChatIntent.PRICE_OFFER);
        checkIntent("9.5m", LowballingChatIntent.PRICE_OFFER);
        checkIntent("lbin is 30m", LowballingChatIntent.VALUE_CLAIM);
        checkIntent("how much", LowballingChatIntent.QUESTION);
        checkIntent("wait a sec", LowballingChatIntent.WAIT);
        checkIntent("no thanks", LowballingChatIntent.REFUSE);
        checkIntent("ok deal", LowballingChatIntent.ACCEPT);
        checkIntent("why so low", LowballingChatIntent.COMPLAINT);
        checkIntent("can you do 11m", LowballingChatIntent.PRICE_OFFER);
        checkIntent("1 sec", LowballingChatIntent.WAIT);
        LowballingChatIntent items = LowballingChatClassifier.classify("i have 3 items", 10 * M).intent();
        check(items != LowballingChatIntent.PRICE_OFFER, "item count is no price: " + items);
        LowballingChatIntent level = LowballingChatClassifier.classify("level 100", 10 * M).intent();
        check(level != LowballingChatIntent.PRICE_OFFER, "level is no price: " + level);
        LowballingChatIntent pets = LowballingChatClassifier.classify("2 pets", 10 * M).intent();
        check(pets != LowballingChatIntent.PRICE_OFFER, "pet count is no price: " + pets);
        LowballingChatIntent noise = LowballingChatClassifier.classify("lol", 10 * M).intent();
        check(noise == LowballingChatIntent.CONTEXT_LESS
                || noise == LowballingChatIntent.UNKNOWN, "lol is noise, got " + noise);

        checkPrice("i do for 9m", 9 * M);
        checkPrice("9.5m", 9_500_000L);
        checkPrice("10,5m", 10_500_000L);
        checkPrice("9500k", 9_500_000L);
        checkPrice("9", 9 * M);
        checkPrice("9 pls", 9 * M);
        checkPrice("2x 10m", 10 * M);
        checkPrice("level 100, 10m", 10 * M);
        checkPrice("10m min", 10 * M);
        checkPrice("gimme 10", 10 * M);
        checkPrice("1 sec", 0);
        checkPrice("10 sec", 0);
        checkPrice("5min", 0);
        checkPrice("i have 3 items", 0);
        checkPrice("2 pets", 0);

        LowballingAiDecision accept = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "i do for 9m", 0, 0, 0, false, false, false, 0));
        check(accept.action() == LowballingAiDecision.Action.SECOND_TRADE
                && accept.price() == 9 * M, "accept lower ask: " + accept);

        LowballingAiDecision concession = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "10.05m", 0, 0, 0, false, false, false, 0));
        check(concession.action() == LowballingAiDecision.Action.SECOND_TRADE
                        && concession.price() == 10_050_000L,
                "accept inside 1% concession: " + concession);

        LowballingAiDecision proactive = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "10.5m", 0, 0, 0, false, false, false, 0));
        check(proactive.action() == LowballingAiDecision.Action.SECOND_TRADE
                        && proactive.price() == 10 * M,
                "close ask starts final trade at last offer: " + proactive);

        LowballingAiDecision counter = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "12m", 0, 0, 0, false, false, false, 0));
        check(counter.action() == LowballingAiDecision.Action.REPLY_ONLY
                        && counter.counter() && counter.marksChatOfferSent(),
                "counter above limit: " + counter);

        LowballingAiDecision kick = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "12m", 1, 0, 0, false, false, false, 0));
        check(kick.action() == LowballingAiDecision.Action.KICK,
                "second impossible ask kicks: " + kick);

        LowballingAiDecision insult = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "are u crazy?", 0, 0, 0, false, false, false, 0));
        check(insult.action() == LowballingAiDecision.Action.KICK,
                "insult kicks: " + insult);

        LowballingAiDecision insultQuestion = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "10m are u crazy?", 0, 0, 0, false, false, false, 0));
        check(insultQuestion.action() == LowballingAiDecision.Action.KICK,
                "insulting question is no offer: " + insultQuestion);

        checkIntent("look at my ah", LowballingChatIntent.AH_OFFER);
        checkIntent("would u lb my ah", LowballingChatIntent.AH_OFFER);
        checkIntent("buy it from ah", LowballingChatIntent.AH_OFFER);
        checkIntent("./ah mavrin", LowballingChatIntent.AH_OFFER);
        checkIntent("its on ah", LowballingChatIntent.AH_OFFER);
        checkIntent("my items are on ah", LowballingChatIntent.AH_OFFER);
        checkIntent("on ah for 20m", LowballingChatIntent.VALUE_CLAIM);
        LowballingChatIntent ahNoise = LowballingChatClassifier.classify("ah ok", 10 * M).intent();
        check(ahNoise != LowballingChatIntent.AH_OFFER, "ah ok is no ah request");
        LowballingChatIntent ahInterjection = LowballingChatClassifier.classify(
                "ah look at this", 10 * M).intent();
        check(ahInterjection != LowballingChatIntent.AH_OFFER, "ah look at this is no ah request");

        LowballingAiDecision ahFirst = decide(context(LowballingAiStage.FIRST_TRADE,
                true, "seller", "look at my ah", 0, 0, 0, false, false, false, 0, false));
        check(ahFirst.action() == LowballingAiDecision.Action.REPLY_ONLY
                        && ahFirst.reply() != null
                        && ahFirst.reply().toLowerCase().contains("ah"),
                "first ah request is refused: " + ahFirst);

        LowballingAiDecision ahOther = decide(context(LowballingAiStage.FIRST_TRADE,
                false, "other", "look at my ah", 0, 0, 0, false, false, false, 0, false));
        check(ahOther.action() == LowballingAiDecision.Action.REPLY_ONLY,
                "other player ah request is refused too: " + ahOther);

        LowballingAiDecision ahNo = decide(context(LowballingAiStage.FIRST_TRADE,
                true, "seller", "no", 0, 0, 0, false, false, false, 0, true));
        check(ahNo.action() == LowballingAiDecision.Action.KICK,
                "no after ah request kicks: " + ahNo);

        LowballingAiDecision ahInsist = decide(context(LowballingAiStage.FIRST_TRADE,
                true, "seller", "buy it from ah", 0, 0, 0, false, false, false, 0, true));
        check(ahInsist.action() == LowballingAiDecision.Action.KICK,
                "insisting on ah kicks: " + ahInsist);

        LowballingAiDecision ahPrice = decide(context(LowballingAiStage.FIRST_TRADE,
                true, "seller", "10m", 0, 0, 0, false, false, false, 0, true));
        check(ahPrice.action() == LowballingAiDecision.Action.SECOND_TRADE
                        && ahPrice.price() == 10 * M,
                "direct price after ah still trades: " + ahPrice);

        LowballingAiDecision ignore = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "lol", 0, 0, 0, false, false, false, 0));
        check(ignore.action() == LowballingAiDecision.Action.IGNORE,
                "noise is ignored: " + ignore);

        LowballingAiDecision wait = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "wait a sec", 0, 0, 0, false, false, false, 0));
        check(wait.action() == LowballingAiDecision.Action.WAIT,
                "wait pauses: " + wait);

        LowballingAiDecision claim = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "lbin is 30m", 0, 0, 0, false, false, false, 0));
        check(claim.action() == LowballingAiDecision.Action.REPLY_ONLY
                        && claim.reply() != null && claim.reply().contains("10m"),
                "market claim is answered with our offer: " + claim);

        LowballingAiDecision other = decide(context(LowballingAiStage.CHAT_OFFER,
                false, "other", "9m", 0, 0, 0, false, false, false, 0));
        check(other.action() == LowballingAiDecision.Action.REPLY_ONLY,
                "other player never starts a trade: " + other);

        LowballingAiDecision done = decide(context(LowballingAiStage.CHAT_OFFER,
                true, "seller", "9m", 0, 0, 0, false, false, true, 0));
        check(done.action() == LowballingAiDecision.Action.IGNORE,
                "completed trade is left alone: " + done);

        LowballingAiDecision finalMeet = decide(context(LowballingAiStage.SECOND_TRADE,
                true, "seller", "10.5m", 0, 0, 0, true, false, false, 0));
        check(finalMeet.action() == LowballingAiDecision.Action.SECOND_TRADE
                        && finalMeet.price() == 10_100_000L,
                "final retry meets at 1%: " + finalMeet);

        LowballingAiDecision finalKick = decide(context(LowballingAiStage.SECOND_TRADE,
                true, "seller", "12m", 0, 0, 0, true, true, false, 0));
        check(finalKick.action() == LowballingAiDecision.Action.KICK,
                "final retry used then kick: " + finalKick);

        LowballingAiDecision partial = decide(context(LowballingAiStage.PARTIAL_OFFER,
                true, "seller", "no", 0, 0, 0, false, false, false, 2));
        check(partial.action() == LowballingAiDecision.Action.REPLY_ONLY
                        && partial.refusal(),
                "partial refusal concedes: " + partial);

        LowballingAiDecision partialAccept = decide(context(LowballingAiStage.PARTIAL_OFFER,
                true, "seller", "ok deal", 0, 0, 0, false, false, false, 2));
        check(partialAccept.action() == LowballingAiDecision.Action.SECOND_TRADE
                        && partialAccept.price() == 9 * M,
                "partial agreement uses the partial first offer: " + partialAccept);

        long delay = LowballingSmartChat.typingDelayMs("ok 9m", 0.5);
        check(delay >= 3_000 && delay <= 8_000, "reply delay 3-8s: " + delay);
        check(LowballingAiReply.sanitize("  /say hi\n").equals("say hi"),
                "reply sanitizer");

        System.out.println("PASS: " + stats);
    }

    private static LowballingAiContext context(LowballingAiStage stage, boolean target,
                                               String speaker, String message,
                                               int counter, int refusal, int complaint,
                                               boolean secondOffer, boolean finalRetryUsed,
                                               boolean tradeSucceeded, int partialStage) {
        long last = 10 * M;
        long first = 9 * M;
        long expected = 15 * M;
        return new LowballingAiContext(
                "CHAT_OFFER",
                stage,
                "seller",
                speaker,
                target,
                "test item",
                expected,
                first,
                last,
                0,
                100 * M,
                partialStage,
                secondOffer,
                false,
                finalRetryUsed,
                tradeSucceeded,
                System.currentTimeMillis(),
                message,
                List.of(message),
                counter,
                refusal,
                complaint,
                false,
                List.of(message));
    }

    private static LowballingAiContext context(LowballingAiStage stage, boolean target,
                                               String speaker, String message,
                                               int counter, int refusal, int complaint,
                                               boolean secondOffer, boolean finalRetryUsed,
                                               boolean tradeSucceeded, int partialStage,
                                               boolean ahAsked) {
        long last = 10 * M;
        long first = 9 * M;
        long expected = 15 * M;
        return new LowballingAiContext(
                "CHAT_OFFER",
                stage,
                "seller",
                speaker,
                target,
                "test item",
                expected,
                first,
                last,
                0,
                100 * M,
                partialStage,
                secondOffer,
                false,
                finalRetryUsed,
                tradeSucceeded,
                System.currentTimeMillis(),
                message,
                List.of(message),
                counter,
                refusal,
                complaint,
                ahAsked,
                List.of(message));
    }

    private static LowballingAiDecision decide(LowballingAiContext context) {
        return LowballingAiPolicy.decide(context, RULES);
    }

    private static void checkIntent(String text, LowballingChatIntent expected) {
        LowballingChatIntent actual = LowballingChatClassifier.classify(text, 10 * M).intent();
        check(actual == expected, "'" + text + "' -> " + actual + ", expected " + expected);
    }

    private static void checkPrice(String text, long expected) {
        long actual = LowballingChatClassifier.classify(text, 10 * M).price();
        check(actual == expected, "'" + text + "' price " + actual + ", expected " + expected);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
