package com.coflnet.lowballing;

/** What the chat brain wants the lowballer to do next. */
public record LowballingAiDecision(
        Action action,
        String reply,
        long price,
        String reason,
        boolean counter,
        boolean refusal,
        boolean complaint,
        boolean marksChatOfferSent,
        boolean resetChatPhase,
        long waitMs) {

    public enum Action {
        IGNORE,
        REPLY_ONLY,
        SECOND_TRADE,
        KICK,
        WAIT
    }

    public static LowballingAiDecision ignore(String reason) {
        return new LowballingAiDecision(Action.IGNORE, null, 0, reason,
                false, false, false, false, false, 0);
    }

    public static LowballingAiDecision replyOnly(String reply, String reason) {
        return new LowballingAiDecision(Action.REPLY_ONLY, reply, 0, reason,
                false, false, false, false, false, 0);
    }

    public static LowballingAiDecision secondTrade(String reply, long price, String reason) {
        return new LowballingAiDecision(Action.SECOND_TRADE, reply, price, reason,
                false, false, false, false, false, 0);
    }

    public static LowballingAiDecision kick(String reason) {
        return new LowballingAiDecision(Action.KICK, null, 0, reason,
                false, false, false, false, false, 0);
    }

    public static LowballingAiDecision wait(String reply, long waitMs, String reason) {
        return new LowballingAiDecision(Action.WAIT, reply, 0, reason,
                false, false, false, false, false, waitMs);
    }

    public static LowballingAiDecision counterReply(String reply, String reason) {
        return new LowballingAiDecision(Action.REPLY_ONLY, reply, 0, reason,
                true, false, false, true, true, 0);
    }

    public static LowballingAiDecision refusalReply(String reply, String reason) {
        return new LowballingAiDecision(Action.REPLY_ONLY, reply, 0, reason,
                false, true, false, true, true, 0);
    }

    public static LowballingAiDecision complaintReply(String reply, String reason) {
        return new LowballingAiDecision(Action.REPLY_ONLY, reply, 0, reason,
                false, false, true, true, true, 0);
    }

    public static LowballingAiDecision informationReply(String reply, String reason) {
        return new LowballingAiDecision(Action.REPLY_ONLY, reply, 0, reason,
                false, false, false, true, true, 0);
    }
}
