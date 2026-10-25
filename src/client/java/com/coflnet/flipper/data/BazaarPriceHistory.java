package com.coflnet.flipper.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BazaarPriceHistory {
    public static class Snapshot {
        public double min;
        public double median;
        public double mean;
        public double mode;
        public double volume;
        public double max;

        public double referencePrice() {
            if (mode > 0) {
                return mode;
            }
            return median > 0 ? median : mean;
        }
    }

    private static final String BASE = "https://sky.coflnet.com/api/item/price/";
    private static final long CACHE_MS = 30 * 60 * 1000L;
    private static final long FAIL_CACHE_MS = 60 * 1000L;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static class Cached {
        final Snapshot snapshot;
        final long at;

        Cached(Snapshot snapshot, long at) {
            this.snapshot = snapshot;
            this.at = at;
        }
    }

    public static Snapshot lookup(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String key = tag.toUpperCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(key);
        if (cached != null) {
            long ttl = cached.snapshot == null ? FAIL_CACHE_MS : CACHE_MS;
            if (now - cached.at < ttl) {
                return cached.snapshot;
            }
        }
        Snapshot snapshot = fetch(key);
        CACHE.put(key, new Cached(snapshot, now));
        return snapshot;
    }

    private static Snapshot fetch(String tag) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + tag))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (SkyCofl-BazaarFlipper)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            Snapshot snapshot = new Snapshot();
            snapshot.min = num(json, "min");
            snapshot.median = num(json, "median");
            snapshot.mean = num(json, "mean");
            snapshot.mode = num(json, "mode");
            snapshot.volume = num(json, "volume");
            snapshot.max = num(json, "max");
            return snapshot.referencePrice() > 0 ? snapshot : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double num(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return json.get(key).getAsDouble();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
