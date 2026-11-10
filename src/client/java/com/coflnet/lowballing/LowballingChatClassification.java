package com.coflnet.lowballing;

/**
 * Result of the local chat understanding step. {@code price} is only the price
 * the visitor names, never our own offer.
 */
public record LowballingChatClassification(
        LowballingChatIntent intent,
        double confidence,
        long price,
        boolean pricePresent,
        boolean agreement,
        boolean refusal,
        boolean complaint,
        boolean insult,
        boolean valueClaim,
        boolean question,
        boolean waiting,
        boolean greeting,
        String text) {

    public boolean isClearPriceOffer() {
        return pricePresent && intent == LowballingChatIntent.PRICE_OFFER;
    }
}
