package com.coflnet.flipper.trading;

public class SignInput {
    public static volatile String pendingText;
    public static volatile long pendingAt;

    public static void set(String text) {
        pendingText = text;
        pendingAt = System.currentTimeMillis();
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

    public static void clear() {
        pendingText = null;
    }
}
