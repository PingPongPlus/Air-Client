package com.coflnet.lowballing;

/** The negotiation step the lowballer is currently in. */
public enum LowballingAiStage {
    FIRST_TRADE,
    CHAT_OFFER,
    SECOND_TRADE,
    FINAL_RETRY,
    PARTIAL_OFFER,
    LISTING,
    IDLE
}
