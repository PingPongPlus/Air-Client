package com.coflnet.flipper.trading;

public class SignInput {
    private static final long DEFAULT_CLOSE_DELAY_MS = 200L;

    public static volatile String pendingText;
    public static volatile long pendingAt;
    public static volatile long pendingCloseDelayMs = DEFAULT_CLOSE_DELAY_MS;

    public static void set(String text) {
        set(text, DEFAULT_CLOSE_DELAY_MS);
    }

    public static void set(String text, long closeDelayMs) {
        pendingText = text;
        pendingAt = System.currentTimeMillis();
        pendingCloseDelayMs = Math.max(100L, closeDelayMs);
    }

    public static String takeIfFresh(long maxAgeMs) {
        String text = pendingText;
        if (text == null) {
            return null;
        }
        if (System.currentTimeMillis() - pendingAt > maxAgeMs) {
            pendingText = null;
            return null;
        }
        pendingText = null;
        return text;
    }

    public static long closeDelayMs() {
        return pendingCloseDelayMs;
    }

    public static void clear() {
        pendingText = null;
        pendingCloseDelayMs = DEFAULT_CLOSE_DELAY_MS;
    }
}
