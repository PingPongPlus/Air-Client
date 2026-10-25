package com.coflnet.flipper.data;

import java.util.HashMap;
import java.util.Map;

public class MarketData {
    public long lastUpdated;
    public final Map<String, Product> products = new HashMap<>();

    public static class Product {
        public String productId;
        public double bestBid;
        public double bestBidVolume;
        public double bestBidOrders;
        public double bestBid2;
        public double bestAsk;
        public double bestAskVolume;
        public double bestAskOrders;
        public double sellMovingWeek;
        public double buyMovingWeek;
        public double avgSellPrice;
        public double avgBuyPrice;
        public double sellVolume;
        public double buyVolume;
        public double sellOrders;
        public double buyOrders;
    }
}
