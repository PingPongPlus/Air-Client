package com.coflnet.flipper.trading;

public class Trade {
    public enum State {
        PLANNED, BUY_OPEN, FILLED, CLAIMED, SELL_OPEN, SOLD, DONE, CANCELLED, ABANDONED
    }

    public String productId;
    public String displayName;
    public long qty;
    public long filledQty;
    public double buyPrice;
    public double sellPrice;
    public double cost;
    public double revenue;
    public State state = State.PLANNED;
    public long createdAt;
    public long filledAt;
    public long listedAt;
    public long soldAt;
    public int outbidChecks;
    public double lastBestBid;
    public int notFoundChecks;
    public long lastRelistedAt;

    public boolean isOpen() {
        return state == State.PLANNED || state == State.BUY_OPEN || state == State.FILLED
                || state == State.CLAIMED || state == State.SELL_OPEN || state == State.SOLD;
    }
}
