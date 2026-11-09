package com.coflnet.ahflipper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Don't outbid player": players from {@code dontOutbidPlayers} are never
 * outbid by the bid macro. Hypixel names them in the outbid chat line; the
 * auction API only has the bidder's UUID, so the names are turned into UUIDs
 * once (Mojang profile lookup) and cached.
 */
public final class AhProtectedBidders {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final long CACHE_MS = 6 * 60 * 60_000L;
    private static final long MISS_CACHE_MS = 10 * 60_000L;
    /** "[Auction] helitisy outbid you by 100,000 coins for Squash Helmet CLICK" */
    private static final Pattern OUTBID_BY = Pattern.compile(
            "(?i)^\\s*(?:\\[auction\\]\\s*)?(?:\\[[^\\]]*\\]\\s*)*([A-Za-z0-9_]{2,16})\\s+outbid you\\b");

    private record CachedId(String uuid, long at) {
    }

    private static final Map<String, CachedId> UUIDS = new ConcurrentHashMap<>();

    private AhProtectedBidders() {
    }

    /** Name of the player in an outbid chat line, "" if it is none. */
    public static String outbidder(String cleanLine) {
        if (cleanLine == null) {
            return "";
        }
        Matcher matcher = OUTBID_BY.matcher(cleanLine);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** True if {@code name} is in the don't-outbid list (case ignored). */
    public static boolean isProtectedName(String name) {
        return PriorityAccess.isName(name) || isProtectedName(name, PriorityAccess.protectedPlayers());
    }

    static boolean isProtectedName(String name, List<String> players) {
        if (name == null || name.isBlank() || players == null) {
            return false;
        }
        for (String player : players) {
            if (player != null && player.trim().equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    /** True if the bidder UUID belongs to a player of the don't-outbid list. */
    public static boolean isProtectedBidder(String bidderUuid) {
        if (bidderUuid == null || bidderUuid.isBlank()) {
            return false;
        }
        List<String> players = PriorityAccess.protectedPlayers();
        String wanted = bidderUuid.replace("-", "").toLowerCase(Locale.ROOT);
        for (String player : players) {
            String uuid = uuidOf(player);
            if (!uuid.isEmpty() && uuid.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** UUID without dashes for a player name, "" if unknown. Cached. */
    static String uuidOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CachedId cached = UUIDS.get(key);
        if (cached != null && now - cached.at() < (cached.uuid().isEmpty() ? MISS_CACHE_MS : CACHE_MS)) {
            return cached.uuid();
        }
        String uuid = "";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://api.mojang.com/users/profiles/minecraft/" + key))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("id")) {
                    uuid = json.get("id").getAsString().replace("-", "").toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] UUID von " + name + " nicht abrufbar: " + e.getMessage());
            if (cached != null) {
                return cached.uuid();
            }
        }
        UUIDS.put(key, new CachedId(uuid, now));
        return uuid;
    }

    /** Resolve all names in the background so the bid thread never waits. */
    public static void warmUp() {
        List<String> copy = List.copyOf(PriorityAccess.protectedPlayers());
        Thread.startVirtualThread(() -> copy.forEach(AhProtectedBidders::uuidOf));
    }
}
