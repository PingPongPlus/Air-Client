package com.coflnet.lowballing;

import com.coflnet.flipper.trading.ScreenUtil;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LowballingChat {
    public record Visitor(String name, long at) {
    }

    private static final int MAX_ENTRIES = 200;
    private static final long VISIT_WINDOW_MS = 5 * 60_000L;
    private static final long VISIT_DEDUPE_MS = 30_000L;
    private static final long BLOCK_COOLDOWN_MS = 30 * 60_000L;
    private static final Deque<Entry> RECENT = new ArrayDeque<>();
    private static final Map<String, Deque<Long>> VISITS = new HashMap<>();
    private static final Map<String, Long> LAST_VISIT = new HashMap<>();
    private static final Map<String, Long> BLOCKED = new HashMap<>();
    private static final Pattern VISITOR = Pattern.compile(
            "(?i)([A-Za-z0-9_]{2,16})\\s+is visiting your island");
    private static volatile long warpAt;
    private static volatile long alreadyHereAt;
    private static volatile long kickAt;
    private static volatile long hubFullAt;
    /** "Please move away from spawn to chat!": our ad was not sent. */
    private static volatile long spawnChatBlockedAt;
    private static volatile long warpFailedAt;
    private static volatile long tradeSuccessAt;
    private static volatile Visitor visitor;
    private static volatile long tradeRequestSentAt;
    /** Last chat line per player (lower-case name), for the island negotiation. */
    private static final Map<String, PlayerLine> PLAYER_LINES = new HashMap<>();
    /** Last 10 chat lines per player (lower-case name), guarded by PLAYER_LINES. */
    private static final Map<String, Deque<PlayerLine>> PLAYER_HISTORY = new HashMap<>();
    // "[289] [✌] [MVP+] qbreese: my best offer rn is 190" -> name + message.
    // Also "[110] ᛝ [✌] [MVP+] atqz: ..." : SkyBlock emblems (any symbol that is
    // not a letter/digit/bracket) may stand between level, icons and rank.
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:\\[[^\\]]*\\]|[^A-Za-z0-9_\\s\\[\\]:]+)\\s*)*"
                    + "([A-Za-z0-9_]{2,16})\\s*:\\s*(.+)$");

    /** {name, message} of a player chat line, null for anything else. */
    static String[] splitPlayerChat(String text) {
        Matcher matcher = PLAYER_CHAT.matcher(text == null ? "" : text);
        return matcher.find() ? new String[]{matcher.group(1), matcher.group(2).trim()} : null;
    }

    public record PlayerLine(String name, String text, long at) {
    }
    private static boolean initialized;

    private record Entry(long at, String text) {
    }

    private LowballingChat() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                String text = ChatFormatting.stripFormatting(message.getString());
                if (text == null || text.isBlank()) {
                    return;
                }
                handle(text.trim());
            } catch (Exception ignored) {
            }
        });
    }

    private static void handle(String text) {
        long now = System.currentTimeMillis();
        synchronized (RECENT) {
            RECENT.addLast(new Entry(now, text));
            while (RECENT.size() > MAX_ENTRIES) {
                RECENT.removeFirst();
            }
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("you were kicked")
                || lower.contains("kicked while joining")
                || lower.contains("couldn't connect")
                || lower.contains("could not connect")
                || lower.contains("an exception occurred")) {
            kickAt = now;
            return;
        }
        // "This server is full!" / "Couldn't warp you! (SERVER_FULL)" is the
        // answer to joining a full hub, not a kick: the bot should try the next
        // hub instead of restarting the whole sequence.
        if (lower.contains("please move away from spawn to chat")) {
            spawnChatBlockedAt = now;
            return;
        }
        if (lower.contains("server is full") || lower.contains("server_full")) {
            hubFullAt = now;
            return;
        }
        if (lower.contains("couldn't warp you") || lower.contains("could not warp you")) {
            warpFailedAt = now;
        }
        if (lower.contains("you are already on this server")
                || lower.contains("already connected to this server")) {
            alreadyHereAt = now;
            return;
        }
        if (lower.contains("request join for hub")
                || lower.contains("sende zum server")
                || lower.contains("sending you to")) {
            warpAt = now;
            return;
        }

        if (lower.contains("you have sent a trade request")
                || lower.contains("already have an outgoing /trade request")) {
            tradeRequestSentAt = now;
        }
        if (lower.contains("trade request") && lower.contains("expired")) {
            tradeRequestSentAt = 0;
        }
        Matcher chat = PLAYER_CHAT.matcher(text);
        if (chat.find()) {
            String name = chat.group(1);
            String own = ownName();
            if (own.isBlank() || !own.equalsIgnoreCase(name)) {
                PlayerLine line = new PlayerLine(name, chat.group(2).trim(), now);
                synchronized (PLAYER_LINES) {
                    String key = name.toLowerCase(Locale.ROOT);
                    PLAYER_LINES.put(key, line);
                    Deque<PlayerLine> history = PLAYER_HISTORY.computeIfAbsent(key,
                            ignored -> new ArrayDeque<>());
                    history.addLast(line);
                    while (history.size() > 10) {
                        history.removeFirst();
                    }
                    if (PLAYER_HISTORY.size() > 200) {
                        PLAYER_HISTORY.clear();
                    }
                }
            }
            // What a player writes is never a system event: "trade me when done"
            // used to count as a successful trade.
            return;
        }

        if (lower.contains("trade")
                && (lower.contains("completed") || lower.contains("success")
                || lower.contains("done")
                || (lower.contains("accepted") && !lower.contains("request")))) {
            tradeSuccessAt = now;
        }

        Matcher matcher = VISITOR.matcher(text);
        if (matcher.find()) {
            String name = matcher.group(1);
            visitor = new Visitor(name, now);
            synchronized (RECENT_VISITORS) {
                RECENT_VISITORS.put(name.toLowerCase(Locale.ROOT), now);
                RECENT_VISITORS.values().removeIf(at -> now - at > 10 * 60_000L);
            }
            trackVisit(name, now);
        }
    }

    /**
     * Every "X is visiting your island" of the last minutes, independent of
     * {@link #consumeVisitor}: a visitor that arrives while the bot trades with
     * someone else (or just leaves for the hub) must not get lost.
     */
    private static final Map<String, Long> RECENT_VISITORS = new HashMap<>();

    public static boolean visitedSince(String name, long since) {
        if (name == null || name.isBlank()) {
            return false;
        }
        synchronized (RECENT_VISITORS) {
            Long at = RECENT_VISITORS.get(name.toLowerCase(Locale.ROOT));
            return at != null && at >= since;
        }
    }

    /** Time of the visit message of {@code name} (0 = none). */
    public static long visitAt(String name) {
        if (name == null) {
            return 0;
        }
        synchronized (RECENT_VISITORS) {
            Long at = RECENT_VISITORS.get(name.toLowerCase(Locale.ROOT));
            return at == null ? 0 : at;
        }
    }

    public static void clearVisitor() {
        visitor = null;
    }

    /** Time of the newest visitor message (0 = none). */
    public static long lastVisitorAt() {
        synchronized (RECENT_VISITORS) {
            long newest = 0;
            for (long at : RECENT_VISITORS.values()) {
                newest = Math.max(newest, at);
            }
            return newest;
        }
    }

    /** Names of visitors announced since {@code since}. */
    public static java.util.List<String> visitorsSince(long since) {
        java.util.List<String> names = new java.util.ArrayList<>();
        synchronized (RECENT_VISITORS) {
            for (Map.Entry<String, Long> entry : RECENT_VISITORS.entrySet()) {
                if (entry.getValue() >= since) {
                    names.add(entry.getKey());
                }
            }
        }
        return names;
    }

    private static void trackVisit(String name, long now) {
        if (!LowballingMod.isRunning()) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (VISITS) {
            Long last = LAST_VISIT.get(key);
            if (last != null && now - last < VISIT_DEDUPE_MS) {
                return;
            }
            LAST_VISIT.put(key, now);
            Deque<Long> visits = VISITS.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            visits.addLast(now);
            while (!visits.isEmpty() && now - visits.peekFirst() > VISIT_WINDOW_MS) {
                visits.removeFirst();
            }
            if (visits.size() < 3) {
                return;
            }
            Long blockedAt = BLOCKED.get(key);
            if (blockedAt != null && now - blockedAt < BLOCK_COOLDOWN_MS) {
                return;
            }
            BLOCKED.put(key, now);
            visits.clear();
        }
        ScreenUtil.onClient(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                client.getConnection().sendChat("/block add " + name);
            }
        });
    }

    private static String ownName() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "" : client.player.getName().getString();
    }

    /**
     * Everything {@code name} wrote after {@code sinceExclusive}, oldest first.
     * Only the last line used to be kept, so "20m" followed by "for both" lost
     * the price.
     */
    public static java.util.List<PlayerLine> linesFrom(String name, long sinceExclusive) {
        if (name == null || name.isBlank()) {
            return java.util.List.of();
        }
        synchronized (PLAYER_LINES) {
            Deque<PlayerLine> history = PLAYER_HISTORY.get(name.toLowerCase(Locale.ROOT));
            if (history == null) {
                return java.util.List.of();
            }
            java.util.List<PlayerLine> result = new java.util.ArrayList<>();
            for (PlayerLine line : history) {
                if (line.at() > sinceExclusive) {
                    result.add(line);
                }
            }
            return result;
        }
    }

    /** Last thing {@code name} wrote in chat since {@code since}, or null. */
    public static PlayerLine lastLineFrom(String name, long since) {
        if (name == null || name.isBlank()) {
            return null;
        }
        synchronized (PLAYER_LINES) {
            PlayerLine line = PLAYER_LINES.get(name.toLowerCase(Locale.ROOT));
            return line != null && line.at() >= since ? line : null;
        }
    }

    /** True while an own /trade request is still pending (they expire after ~10 s). */
    public static boolean tradeRequestPending(long now) {
        long at = tradeRequestSentAt;
        return at > 0 && now - at < 10_500L;
    }

    public static void clearTradeRequest() {
        tradeRequestSentAt = 0;
    }

    public static boolean consumeSpawnChatBlocked(long since) {
        long at = spawnChatBlockedAt;
        if (at > 0 && at >= since) {
            spawnChatBlockedAt = 0;
            return true;
        }
        return false;
    }

    public static boolean consumeHubFull(long since) {
        long at = hubFullAt;
        if (at > 0 && at >= since) {
            hubFullAt = 0;
            return true;
        }
        return false;
    }

    /** "Couldn't warp you! Try again later." (cooldown etc.) since {@code since}. */
    public static boolean warpFailedSince(long since) {
        long at = warpFailedAt;
        return at > 0 && at >= since;
    }

    public static boolean consumeWarp(long since) {
        long at = warpAt;
        if (at > 0 && at >= since) {
            warpAt = 0;
            return true;
        }
        return false;
    }

    public static boolean consumeAlreadyHere(long since) {
        long at = alreadyHereAt;
        if (at > 0 && at >= since) {
            alreadyHereAt = 0;
            return true;
        }
        return false;
    }

    public static Visitor consumeVisitor(long since) {
        Visitor current = visitor;
        if (current != null && current.at() >= since) {
            visitor = null;
            return current;
        }
        return null;
    }

    public static boolean consumeTradeSuccess(long since) {
        long at = tradeSuccessAt;
        if (at > 0 && at >= since) {
            tradeSuccessAt = 0;
            return true;
        }
        return false;
    }

    public static boolean consumeKick(long since) {
        long at = kickAt;
        if (at > 0 && at >= since) {
            kickAt = 0;
            return true;
        }
        return false;
    }

    public static void clearWarp() {
        warpAt = 0;
        alreadyHereAt = 0;
    }

    public static void reset() {
        warpAt = 0;
        alreadyHereAt = 0;
        kickAt = 0;
        tradeSuccessAt = 0;
        tradeRequestSentAt = 0;
        Visitor current = visitor;
        if (current == null || System.currentTimeMillis() - current.at() > 30_000L) {
            // A visitor of the last 30 s survives the reset (e.g. the one that
            // arrived just as the bot sent /hub).
            visitor = null;
        }
        synchronized (PLAYER_LINES) {
            PLAYER_LINES.clear();
            PLAYER_HISTORY.clear();
        }
    }

    public static String recentText() {
        StringBuilder builder = new StringBuilder();
        synchronized (RECENT) {
            for (Entry entry : RECENT) {
                if (!builder.isEmpty()) {
                    builder.append(" | ");
                }
                builder.append(entry.text());
            }
        }
        return builder.toString();
    }
}
