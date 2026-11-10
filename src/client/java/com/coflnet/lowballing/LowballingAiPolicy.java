package com.coflnet.lowballing;

import java.util.Locale;

/**
 * Pure decision table for the lowballer chat. This class never talks to
 * Minecraft: it maps a chat classification plus the current negotiation stage
 * to one safe action.
 */
public final class LowballingAiPolicy {

    private LowballingAiPolicy() {
    }

    public static LowballingAiDecision decide(LowballingAiContext ctx, LowballingAiRules rules) {
        LowballingChatClassification classification = LowballingChatClassifier.classify(
                ctx.message(), ctx.expectedValue());
        return decide(ctx, classification, rules);
    }

    public static LowballingAiDecision decide(LowballingAiContext ctx,
                                              LowballingChatClassification c,
                                              LowballingAiRules rules) {
        if (ctx.tradeSucceeded() || ctx.stage() == LowballingAiStage.LISTING) {
            return LowballingAiDecision.ignore("trade already completed");
        }
        if ("KICK_WAIT".equals(ctx.state()) || "NEXT_PLAYER".equals(ctx.state())) {
            return LowballingAiDecision.ignore("already moving to the next player");
        }

        if (c.intent() == LowballingChatIntent.AH_OFFER) {
            if (ctx.target() && ctx.ahAsked()) {
                return LowballingAiDecision.kick("kept asking us to buy from ah");
            }
            return LowballingAiDecision.replyOnly(
                    LowballingAiReply.ahRefusal(), "no ah buys, direct trade only");
        }
        if (ctx.target() && ctx.ahAsked() && c.refusal()) {
            return LowballingAiDecision.kick("refused direct trade after ah request");
        }

        if (!ctx.target()) {
            if (ctx.targetName() == null || ctx.targetName().isBlank()) {
                // No trade target selected yet (island scan): do not pretend
                // another visitor is interrupting a trade.
                if (c.pricePresent() || c.intent() == LowballingChatIntent.QUESTION) {
                    return LowballingAiDecision.replyOnly(
                            LowballingAiReply.noOffer(), "no target selected yet");
                }
                return LowballingAiDecision.ignore("no target selected yet");
            }
            if (!rules.replyToOtherPlayers()) {
                return LowballingAiDecision.ignore("message from another player");
            }
            if (c.pricePresent() || c.intent() == LowballingChatIntent.QUESTION
                    || c.intent() == LowballingChatIntent.GREETING
                    || c.intent() == LowballingChatIntent.WAIT) {
                return LowballingAiDecision.replyOnly(
                        LowballingAiReply.otherPlayer(), "other player is talking");
            }
            return LowballingAiDecision.ignore("other player smalltalk");
        }

        String item = ctx.itemName() == null ? "item" : ctx.itemName();
        long max = maxOffer(ctx);
        long allowed = maxAcceptable(ctx, rules);
        String lowerMessage = ctx.message().toLowerCase(Locale.ROOT);
        if (c.insult() && c.question() && !containsOfferVerb(lowerMessage)) {
            // "10m are u crazy?" is a reaction to our price, not an offer.
            if (rules.allowKick()) {
                return LowballingAiDecision.kick("insulting question");
            }
            return LowballingAiDecision.ignore("insulting question");
        }
        if (max <= 0) {
            if (c.insult()) {
                if (rules.allowKick()) {
                    return LowballingAiDecision.kick("insult before valuation");
                }
                return LowballingAiDecision.ignore("insult before valuation");
            }
            if (c.pricePresent() || c.intent() == LowballingChatIntent.QUESTION) {
                return LowballingAiDecision.replyOnly(
                        LowballingAiReply.noOffer(), "no offer valued yet");
            }
            return LowballingAiDecision.ignore("no offer yet");
        }
        if (rules.enforceBankroll() && ctx.bankroll() >= 0 && ctx.bankroll() < max) {
            // tickOfferReady normally kicks first; never promise a price above
            // the purse if a chat line slips in before that check.
            if (rules.allowKick()) {
                return LowballingAiDecision.kick("not enough bankroll");
            }
            return LowballingAiDecision.ignore("not enough bankroll");
        }

        if (c.pricePresent() && c.valueClaim()) {
            return LowballingAiDecision.informationReply(
                    LowballingAiReply.valueClaim(max, item), "market value claim");
        }

        if (c.pricePresent()) {
            long ask = c.price();
            if (ask > 0 && ask <= allowed) {
                return LowballingAiDecision.secondTrade(
                        LowballingAiReply.accept(ask, item), ask,
                        "ask is inside our limit");
            }
            double gap = max <= 0 ? 1.0 : (ask - max) / (double) max;
            if (ctx.stage() == LowballingAiStage.SECOND_TRADE
                    || ctx.stage() == LowballingAiStage.FINAL_RETRY) {
                if (gap <= 0.10 && !ctx.finalRetryUsed()) {
                    long price = LowballingSmartChat.chatRound(max * 1.01);
                    price = Math.min(price, allowed);
                    if (price > 0) {
                        return LowballingAiDecision.secondTrade(
                                LowballingAiReply.finalOffer(price, item), price,
                                "meet the visitor for the final trade");
                    }
                }
                if (c.insult()) {
                    return LowballingAiDecision.kick("insult after second offer");
                }
                if (rules.allowKick()) {
                    return LowballingAiDecision.kick("still too high after second offer");
                }
                return LowballingAiDecision.informationReply(
                        LowballingAiReply.finalOffer(max, item), "too high");
            }
            if (c.insult()) {
                return LowballingAiDecision.kick("insult with an impossible price");
            }
            if (gap <= rules.proactiveTradeGapPercent() / 100.0) {
                return LowballingAiDecision.secondTrade(
                        LowballingAiReply.finalOffer(max, item), max,
                        "close enough, start the final trade at our max");
            }
            if (ctx.counterCount() < rules.maxCounters()) {
                return LowballingAiDecision.counterReply(
                        LowballingAiReply.counter(ask, max, item),
                        "first counter above our limit");
            }
            if (rules.allowKick()) {
                return LowballingAiDecision.kick("too high after counter");
            }
            return LowballingAiDecision.informationReply(
                    LowballingAiReply.finalOffer(max, item), "too high");
        }

        if (c.insult()) {
            if (rules.allowKick()) {
                return LowballingAiDecision.kick("visitor is insulting us");
            }
            return LowballingAiDecision.informationReply(
                    LowballingAiReply.insult(max, item), "insult");
        }

        if (c.agreement()) {
            long price = agreementPrice(ctx, rules);
            if (price > 0) {
                return LowballingAiDecision.secondTrade(
                        LowballingAiReply.accept(price, item), price,
                        "visitor agreed");
            }
            return LowballingAiDecision.ignore("agreement without a value");
        }

        if (c.refusal()) {
            if (ctx.stage() == LowballingAiStage.PARTIAL_OFFER && ctx.partialStage() == 2) {
                return LowballingAiDecision.refusalReply(
                        LowballingAiReply.finalOffer(max, item),
                        "partial offer refused, concede to the chat offer");
            }
            if (ctx.refusalCount() < rules.maxCounters()) {
                return LowballingAiDecision.refusalReply(
                        LowballingAiReply.finalOffer(max, item),
                        "first refusal");
            }
            if (rules.allowKick()) {
                return LowballingAiDecision.kick("refused twice");
            }
            return LowballingAiDecision.informationReply(
                    LowballingAiReply.finalOffer(max, item), "refused");
        }

        if (c.valueClaim()) {
            return LowballingAiDecision.informationReply(
                    LowballingAiReply.valueClaim(max, item), "market value claim");
        }

        if (c.complaint()) {
            if (ctx.complaintCount() < rules.maxComplaints()) {
                return LowballingAiDecision.complaintReply(
                        LowballingAiReply.complaint(max, item), "first complaint");
            }
            if (rules.allowKick()) {
                return LowballingAiDecision.kick("complaint repeated");
            }
            return LowballingAiDecision.informationReply(
                    LowballingAiReply.complaint(max, item), "complaint");
        }

        if (c.question()) {
            String text = ctx.message().toLowerCase(Locale.ROOT);
            if (asksWhy(text)) {
                return LowballingAiDecision.complaintReply(
                        LowballingAiReply.complaint(max, item), "why question");
            }
            if (asksPrice(text)) {
                return LowballingAiDecision.informationReply(
                        LowballingAiReply.priceQuestion(max, item), "price question");
            }
            if (text.replace("?", "").isBlank()) {
                return LowballingAiDecision.ignore("question mark only");
            }
            return LowballingAiDecision.informationReply(
                    LowballingAiReply.priceQuestion(max, item), "question");
        }

        if (c.waiting()) {
            return LowballingAiDecision.wait(
                    LowballingAiReply.waitMessage(), rules.waitMs(),
                    "visitor asked us to wait");
        }

        return LowballingAiDecision.ignore("no actionable chat");
    }

    static long maxOffer(LowballingAiContext ctx) {
        return ctx.lastOffer() > 0 ? ctx.lastOffer() : ctx.firstOffer();
    }

    static long maxAcceptable(LowballingAiContext ctx, LowballingAiRules rules) {
        long max = maxOffer(ctx);
        if (max <= 0) {
            return 0;
        }
        long allowed = max;
        if (rules.maxConcessionPercent() > 0) {
            allowed = LowballingSmartChat.chatRound(
                    max * (1.0 + rules.maxConcessionPercent() / 100.0));
        }
        if (rules.enforceBankroll() && ctx.bankroll() >= 0) {
            allowed = Math.min(allowed, ctx.bankroll());
        }
        return Math.max(0, allowed);
    }

    private static long agreementPrice(LowballingAiContext ctx, LowballingAiRules rules) {
        long allowed = maxAcceptable(ctx, rules);
        if (!ctx.secondOffer() && ctx.partialStage() == 2 && ctx.firstOffer() > 0) {
            return Math.min(ctx.firstOffer(), allowed);
        }
        if (ctx.statedPrice() > 0 && ctx.statedPrice() <= allowed) {
            return ctx.statedPrice();
        }
        return Math.min(maxOffer(ctx), allowed);
    }

    private static boolean asksPrice(String text) {
        return text.contains("how much") || text.contains("offer") || text.contains("price")
                || text.contains("max") || text.contains("best") || text.contains("coins")
                || text.contains("what can you");
    }

    private static boolean asksWhy(String text) {
        return text.contains("why") || text.contains("how come");
    }

    private static boolean containsOfferVerb(String text) {
        for (String token : text.split("[^a-z]+")) {
            if (token.equals("do") || token.equals("give") || token.equals("take")
                    || token.equals("pay") || token.equals("offer") || token.equals("sell")
                    || token.equals("buy") || token.equals("deal") || token.equals("works")
                    || token.equals("work") || token.equals("send") || token.equals("trade")
                    || token.equals("for")) {
                return true;
            }
        }
        return false;
    }
}
