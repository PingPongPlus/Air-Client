package com.coflnet.ahflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class CoflAuctionFeed {
    private static final String WS_URL = "wss://sky.coflnet.com/skyblock";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final AtomicInteger MESSAGE_ID = new AtomicInteger(1);
    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();

    private static volatile boolean running;
    private static volatile Thread worker;
    private static volatile WebSocket socket;
    private static volatile Consumer<Auction> consumer;
    private static volatile String status = "aus";
    private static volatile long lastPollAt;
    private static volatile int lastPollCount;
    private static volatile int totalSeen;
    private static volatile int errorCount;
    private static volatile long lastErrorLogAt;

    private CoflAuctionFeed() {
    }

    public static class Auction {
        public String uuid = "";
        public String tag = "";
        public String itemName = "";
        public String seller = "";
        public String playerName = "";
        public long price;
        public long end;
        public boolean bin;
    }

    static void start(Consumer<Auction> callback) {
        if (running) {
            return;
        }
        consumer = callback;
        running = true;
        status = "verbinde";
        Thread thread = new Thread(CoflAuctionFeed::loop, "skycofl-ah-feed");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        System.out.println("[AH-Flipper] Cofl-NewAuctions-Feed gestartet");
    }

    static void stop() {
        running = false;
        status = "aus";
        WebSocket current = socket;
        if (current != null) {
            current.abort();
            socket = null;
        }
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        System.out.println("[AH-Flipper] Cofl-NewAuctions-Feed gestoppt");
    }

    static boolean isRunning() {
        return running;
    }

    static String status() {
        long age = lastPollAt > 0 ? (System.currentTimeMillis() - lastPollAt) / 1000 : -1;
        return (running ? "§aan" : "§caus")
                + " §7· " + status
                + " §7· letzter Poll §f" + lastPollCount
                + " §7· gesamt §f" + totalSeen
                + " §7· Fehler §f" + errorCount
                + (age >= 0 ? " §7· vor §f" + age + "s" : "");
    }

    private static void loop() {
        while (running) {
            CountDownLatch closed = new CountDownLatch(1);
            try {
                status = "verbinde";
                WebSocket connected = CLIENT.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(WS_URL), new Listener(closed))
                        .join();
                socket = connected;
                status = "verbunden";
                while (running && closed.getCount() > 0) {
                    if (closed.await(1, TimeUnit.SECONDS)) {
                        break;
                    }
                }
                if (socket == connected) {
                    socket = null;
                }
                connected.abort();
            } catch (Exception e) {
                status = "Fehler";
                errorCount++;
                System.out.println("[AH-Flipper] Feed-Fehler: " + e);
            }
            if (!running) {
                break;
            }
            status = "getrennt";
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        worker = null;
        status = "aus";
    }

    private static final class Listener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final StringBuilder buffer = new StringBuilder();
        private final int connectionMessageId;
        private volatile ScheduledExecutorService poller;
        private volatile ScheduledFuture<?> pollTask;
        private volatile WebSocket webSocket;
        private volatile long nextPollAt;
        private volatile int rateLimitHits;
        private volatile long lastRateLimitLogAt;

        private Listener(CountDownLatch closed) {
            this.closed = closed;
            this.connectionMessageId = MESSAGE_ID.getAndIncrement();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            webSocket.request(1);
            String connectionId = UUID.randomUUID().toString();
            send(webSocket, "setConId", b64(quote(connectionId)), connectionMessageId);
            System.out.println("[AH-Flipper] Feed verbunden, setConId gesendet");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handle(message, webSocket);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[AH-Flipper] Feed geschlossen: " + statusCode + " " + reason);
            stopPolling();
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[AH-Flipper] Feed-Fehler: " + error);
            errorCount++;
            stopPolling();
            closed.countDown();
        }

        private void handle(String message, WebSocket webSocket) {
            try {
                JsonObject outer = JsonParser.parseString(message).getAsJsonObject();
                String type = stringValue(outer, "type");
                int mId = outer.has("mId") ? outer.get("mId").getAsInt() : -1;
                if ("ok".equals(type)) {
                    if (mId == connectionMessageId && running) {
                        startPolling(webSocket);
                    }
                    return;
                }
                if ("error".equals(type)) {
                    errorCount++;
                    String error = outer.has("data") ? outer.get("data").toString() : message;
                    boolean rateLimited = isRateLimit(error);
                    if (rateLimited) {
                        slowDown();
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastErrorLogAt > 5000) {
                        lastErrorLogAt = now;
                        System.out.println("[AH-Flipper] Feed-Command-Fehler (mId " + mId + "): " + error);
                    }
                    if (mId == connectionMessageId) {
                        closed.countDown();
                    }
                    return;
                }
                if ("newAuctions".equals(type)) {
                    JsonElement payload = outer.get("data");
                    String raw = payload != null && payload.isJsonPrimitive()
                            ? payload.getAsString() : String.valueOf(payload);
                    JsonArray auctions = JsonParser.parseString(raw).getAsJsonArray();
                    int count = 0;
                    int fresh = 0;
                    for (JsonElement element : auctions) {
                        Auction auction = parse(element.getAsJsonObject());
                        if (auction.uuid.isBlank()) {
                            continue;
                        }
                        count++;
                        if (seenRecently(auction.uuid)) {
                            continue;
                        }
                        fresh++;
                        Consumer<Auction> callback = consumer;
                        if (callback != null) {
                            callback.accept(auction);
                        }
                    }
                    lastPollCount = count;
                    totalSeen += fresh;
                    lastPollAt = System.currentTimeMillis();
                    rateLimitHits = 0;
                }
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Feed-Nachricht nicht lesbar: " + e);
            }
        }

        private void startPolling(WebSocket webSocket) {
            if (poller != null) {
                return;
            }
            poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "skycofl-ah-feed-poll");
                thread.setDaemon(true);
                return thread;
            });
            pollTask = poller.scheduleAtFixedRate(() -> {
                if (!running || webSocket != this.webSocket) {
                    stopPolling();
                    return;
                }
                try {
                    long now = System.currentTimeMillis();
                    if (now < nextPollAt) {
                        return;
                    }
                    long interval = Math.max(250L, AhFlipperMod.CONFIG.livePollMs);
                    if (rateLimitHits > 0) {
                        interval = Math.min(30_000L, interval * (1L << Math.min(rateLimitHits, 4)));
                    }
                    nextPollAt = now + interval;
                    int id = MESSAGE_ID.getAndIncrement();
                    send(webSocket, "newAuctions", b64("\"\""), id);
                } catch (Exception e) {
                    System.out.println("[AH-Flipper] Feed-Poll Fehler: " + e);
                }
            }, 0, 50, TimeUnit.MILLISECONDS);
            System.out.println("[AH-Flipper] newAuctions-Polling alle "
                    + Math.max(250, AhFlipperMod.CONFIG.livePollMs) + "ms (adaptiv)");
        }

        private boolean isRateLimit(String error) {
            String lower = error.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("stop_it") || lower.contains("too many requests")
                    || lower.contains("slow down");
        }

        private void slowDown() {
            rateLimitHits = Math.min(4, rateLimitHits + 1);
            long pause = Math.min(30_000L, 2000L * rateLimitHits);
            nextPollAt = System.currentTimeMillis() + pause;
            long now = System.currentTimeMillis();
            if (now - lastRateLimitLogAt > 10_000) {
                lastRateLimitLogAt = now;
                System.out.println("[AH-Flipper] Feed-Rate-Limit: pausiere Polling "
                        + pause + "ms (Stufe " + rateLimitHits + ")");
            }
        }

        private void stopPolling() {
            ScheduledFuture<?> task = pollTask;
            if (task != null) {
                task.cancel(false);
                pollTask = null;
            }
            ScheduledExecutorService executor = poller;
            if (executor != null) {
                executor.shutdownNow();
                poller = null;
            }
        }
    }

    private static Auction parse(JsonObject json) {
        Auction auction = new Auction();
        auction.uuid = stringValue(json, "uuid");
        auction.tag = stringValue(json, "tag");
        auction.itemName = stringValue(json, "itemName");
        auction.seller = stringValue(json, "seller");
        auction.playerName = stringValue(json, "playerName");
        auction.price = Math.max(longValue(json, "startingBid"), longValue(json, "highestBid"));
        auction.end = parseTime(stringValue(json, "end"));
        auction.bin = json.has("bin") && json.get("bin").getAsBoolean();
        return auction;
    }

    private static boolean seenRecently(String uuid) {
        long now = System.currentTimeMillis();
        Long at = SEEN.put(uuid, now);
        if (at != null && now - at < 10 * 60_000L) {
            return true;
        }
        if (SEEN.size() > 20_000) {
            SEEN.entrySet().removeIf(entry -> now - entry.getValue() > 60 * 60_000L);
        }
        return false;
    }

    private static long parseTime(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return java.time.Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (Exception ignoredAgain) {
                return 0;
            }
        }
    }

    private static void send(WebSocket socket, String type, String data, int mId) {
        socket.sendText("{\"type\":\"" + type + "\",\"data\":\"" + data + "\",\"mId\":" + mId + "}", true);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String stringValue(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long longValue(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return json.get(key).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }
}

