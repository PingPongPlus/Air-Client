package com.coflnet.flipper.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class BazaarApi {
    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static MarketData fetch() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (SkyCofl-BazaarFlipper)")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            MarketData data = new MarketData();
            data.lastUpdated = root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : System.currentTimeMillis();
            JsonObject products = root.getAsJsonObject("products");
            if (products == null) {
                return data;
            }
            for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
                JsonObject json = entry.getValue().getAsJsonObject();
                MarketData.Product product = new MarketData.Product();
                product.productId = entry.getKey();

                JsonArray bids = json.getAsJsonArray("sell_summary");
                if (bids != null && bids.size() > 0) {
                    JsonObject top = bids.get(0).getAsJsonObject();
                    product.bestBid = top.get("pricePerUnit").getAsDouble();
                    product.bestBidVolume = top.get("amount").getAsDouble();
                    product.bestBidOrders = top.get("orders").getAsDouble();
                    if (bids.size() > 1) {
                        JsonObject second = bids.get(1).getAsJsonObject();
                        product.bestBid2 = second.get("pricePerUnit").getAsDouble();
                    }
                }
                JsonArray asks = json.getAsJsonArray("buy_summary");
                if (asks != null && asks.size() > 0) {
                    JsonObject top = asks.get(0).getAsJsonObject();
                    product.bestAsk = top.get("pricePerUnit").getAsDouble();
                    product.bestAskVolume = top.get("amount").getAsDouble();
                    product.bestAskOrders = top.get("orders").getAsDouble();
                }

                JsonObject quick = json.getAsJsonObject("quick_status");
                if (quick != null) {
                    product.sellMovingWeek = num(quick, "sellMovingWeek");
                    product.buyMovingWeek = num(quick, "buyMovingWeek");
                    product.avgSellPrice = num(quick, "sellPrice");
                    product.avgBuyPrice = num(quick, "buyPrice");
                    product.sellVolume = num(quick, "sellVolume");
                    product.buyVolume = num(quick, "buyVolume");
                    product.sellOrders = num(quick, "sellOrders");
                    product.buyOrders = num(quick, "buyOrders");
                }

                if (product.bestBid > 0 || product.bestAsk > 0) {
                    data.products.put(product.productId, product);
                }
            }
            return data;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double num(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        return json.get(key).getAsDouble();
    }
}
