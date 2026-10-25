package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anonymous flip stream of the Cofl web app. It uses the same websocket as
 * sky.coflnet.com/flipper, but without a login, premium account or the Cofl mod.
 */
public final class CoflFlipStream {
    private static final String WS_URL = "wss://sky.coflnet.com/skyblock";
    private static final String SETTINGS = "{"
            + "\"whitelist\":[],"
            + "\"blacklist\":[],"
            + "\"minProfit\":0,"
            + "\"minProfitPercent\":0,"
            + "\"minVolume\":0,"
            + "\"maxCost\":0,"
            + "\"onlyBin\":true,"
            + "\"lbin\":false,"
            + "\"mod\":{\"justProfit\":false,\"soundOnFlip\":false,\"shortNumbers\":true,"
            + "\"blockTenSecMsg\":false,\"chat\":false,\"countdown\":false},"
            + "\"visibility\":{\"cost\":true,\"estProfit\":true,\"lbin\":true,\"slbin\":true,"
            + "\"medPrice\":true,\"seller\":true,\"volume\":true,\"extraFields\":0,"
            + "\"profitPercent\":true,\"sellerOpenBtn\":true,\"lore\":true,"
            + "\"copySuccessMessage\":true,\"links\":true},"
            + "\"blockExport\":false"
            + "}";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final AtomicInteger MESSAGE_ID = new AtomicInteger(1);
    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();

    private static volatile boolean running;
    private static volatile Thread worker;
    private static volatile WebSocket socket;
    private static volatile String status = "aus";
    private static volatile long lastFlipAt;
    private static volatile String lastTag = "";
    private static volatile long lastProfit;
    private static volatile int flipCount;
    private static volatile int errorCount;

    private CoflFlipStream() {
    }

    static void start() {
        if (running) {
            return;
        }
        running = true;
        status = "verbinde";
        Thread thread = new Thread(CoflFlipStream::loop, "skycofl-ah-stream");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        System.out.println("[AH-Flipper] Cofl-Flip-Stream gestartet");
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
        System.out.println("[AH-Flipper] Cofl-Flip-Stream gestoppt");
    }

    static boolean isRunning() {
        return running;
    }

    static String status() {
        long age = lastFlipAt > 0 ? (System.currentTimeMillis() - lastFlipAt) / 1000 : -1;
        return (running ? "§aan" : "§caus")
                + " §7· " + status
                + " §7· Flips §f" + flipCount
                + " §7· Fehler §f" + errorCount
                + (lastTag.isBlank() ? "" : " §7· Letzter §f" + lastTag
                + " §7(+" + ScreenUtil.formatCoins(lastProfit) + ")")
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
                System.out.println("[AH-Flipper] Stream-Fehler: " + e);
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

        private Listener(CountDownLatch closed) {
            this.closed = closed;
            this.connectionMessageId = MESSAGE_ID.getAndIncrement();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            String connectionId = UUID.randomUUID().toString();
            String data = b64(quote(connectionId));
            send(webSocket, "setConId", data, connectionMessageId);
            System.out.println("[AH-Flipper] Stream verbunden, setConId gesendet");
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
            System.out.println("[AH-Flipper] Stream geschlossen: " + statusCode + " " + reason);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[AH-Flipper] Stream-Fehler: " + error);
            errorCount++;
            closed.countDown();
        }

        private void handle(String message, WebSocket webSocket) {
            try {
                JsonObject outer = JsonParser.parseString(message).getAsJsonObject();
                String type = stringValue(outer, "type");
                int mId = outer.has("mId") ? outer.get("mId").getAsInt() : -1;
                if ("ok".equals(type)) {
                    if (mId == connectionMessageId && running) {
                        int subscriptionId = MESSAGE_ID.getAndIncrement();
                        send(webSocket, "subFlipAnonym", b64(SETTINGS), subscriptionId);
                        System.out.println("[AH-Flipper] Anonyme Flip-Subscription gesendet");
                    }
                    return;
                }
                if ("error".equals(type)) {
                    errorCount++;
                    String error = outer.has("data") ? outer.get("data").toString() : message;
                    System.out.println("[AH-Flipper] Stream-Command-Fehler (mId " + mId + "): " + error);
                    if (mId == connectionMessageId) {
                        closed.countDown();
                    }
                    return;
                }
                if ("flip".equals(type)) {
                    JsonObject flip = JsonParser.parseString(stringValue(outer, "data")).getAsJsonObject();
                    acceptFlip(flip);
                }
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Stream-Nachricht nicht lesbar: " + e);
            }
        }
    }

    private static void send(WebSocket socket, String type, String data, int mId) {
        String message = "{\"type\":\"" + type + "\",\"data\":\"" + data + "\",\"mId\":" + mId + "}";
        socket.sendText(message, true);
    }

    private static void acceptFlip(JsonObject flip) {
        String id = stringValue(flip, "uuid");
        String tag = stringValue(flip, "tag");
        String name = stringValue(flip, "name");
        String seller = stringValue(flip, "sellerName");
        long cost = longValue(flip, "cost");
        long lowestBin = longValue(flip, "lowestBin");
        long profit = longValue(flip, "Profit");
        if (profit <= 0) {
            profit = lowestBin - cost;
        }
        if (id.isBlank() || profit <= 0) {
            return;
        }
        if (seenRecently(id)) {
            return;
        }
        if (!seller.isBlank()) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.getUser() != null
                    && seller.equalsIgnoreCase(client.getUser().getName())) {
                System.out.println("[AH-Flipper] Stream: eigene Auktion übersprungen " + id);
                return;
            }
        }

        lastFlipAt = System.currentTimeMillis();
        lastTag = tag;
        lastProfit = profit;
        flipCount++;
        System.out.println("[AH-Flipper] Stream-Flip: id=" + id + " tag=" + tag
                + " name=" + name + " seller=" + seller + " cost=" + cost
                + " lbin=" + lowestBin + " profit=" + profit
                + " running=" + running + " autoBuy=" + AhFlipperMod.isAutoBuyOn());

        AhFlipperMod.onStreamFlip(id, tag, name, cost, lowestBin, profit);
    }

    private static boolean seenRecently(String id) {
        long now = System.currentTimeMillis();
        Long at = SEEN.put(id, now);
        if (at != null && now - at < 10 * 60_000L) {
            return true;
        }
        if (SEEN.size() > 5000) {
            SEEN.entrySet().removeIf(entry -> now - entry.getValue() > 60 * 60_000L);
        }
        return false;
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
