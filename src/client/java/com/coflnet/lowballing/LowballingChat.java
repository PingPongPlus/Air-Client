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
    private static volatile long tradeSuccessAt;
    private static volatile Visitor visitor;
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
                || lower.contains("server is full")
                || lower.contains("an exception occurred")) {
            kickAt = now;
            return;
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
            trackVisit(name, now);
        }
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
        visitor = null;
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
