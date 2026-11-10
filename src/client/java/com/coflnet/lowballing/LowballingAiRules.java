package com.coflnet.lowballing;

/** Tunables for the pure policy engine (also used by the tests). */
public record LowballingAiRules(
        boolean allowKick,
        boolean replyToOtherPlayers,
        boolean enforceBankroll,
        int maxCounters,
        int maxComplaints,
        double maxConcessionPercent,
        double proactiveTradeGapPercent,
        long waitMs) {
}
