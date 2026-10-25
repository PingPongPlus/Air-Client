package com.coflnet.lowballing;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LowballingChat {
    public record Visitor(String name, long at) {
    }

    private static final int MAX_ENTRIES = 200;
    private static final Deque<Entry> RECENT = new ArrayDeque<>();
    private static final Pattern VISITOR = Pattern.compile(
            "(?i)([A-Za-z0-9_]{2,16})\\s+is visiting your island");
    private static volatile long warpAt;
    private static volatile long kickAt;
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
        if (lower.contains("request join for hub")
                || lower.contains("sende zum server")
                || lower.contains("sending you to")) {
            warpAt = now;
            return;
        }

        Matcher matcher = VISITOR.matcher(text);
        if (matcher.find()) {
            visitor = new Visitor(matcher.group(1), now);
        }
    }

    public static boolean consumeWarp(long since) {
        long at = warpAt;
        if (at > 0 && at >= since) {
            warpAt = 0;
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
    }

    public static void reset() {
        warpAt = 0;
        kickAt = 0;
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
