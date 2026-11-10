package com.coflnet.lowballing;

import java.util.List;

/**
 * Everything the local brain is allowed to see about one negotiation. The
 * controller builds this from the live lowballer state; it never contains
 * another player's chat lines.
 */
public record LowballingAiContext(
        String state,
        LowballingAiStage stage,
        String targetName,
        String speaker,
        boolean target,
        String itemName,
        long expectedValue,
        long firstOffer,
        long lastOffer,
        long statedPrice,
        long bankroll,
        int partialStage,
        boolean secondOffer,
        boolean finalizingSecond,
        boolean finalRetryUsed,
        boolean tradeSucceeded,
        long now,
        String message,
        List<String> messages,
        int counterCount,
        int refusalCount,
        int complaintCount,
        boolean ahAsked,
        List<String> history) {
}
