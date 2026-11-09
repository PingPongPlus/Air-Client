package com.coflnet;

import net.minecraft.network.chat.Component;

/**
 * Keeps third-party branding out of user-facing text while leaving the
 * functional strings (commands, endpoints, wire formats) untouched.
 */
public final class Rebrand {
    private Rebrand() {
    }

    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text
                .replace("SkyCofl Mod", "Air Client")
                .replace("SkyCofl", "Air Client")
                .replace("CoflSky", "Air Client")
                .replace("CoflCoins", "coins")
                .replace("CoflNet", "Air Client")
                .replace("coflnet.com", "the market service")
                .replace("Cofl-LBin", "LBin")
                .replace("Cofl-Median", "Median")
                .replace("Cofl genommen", "Market genommen")
                .replace("/cofl", "/air")
                .replace("Cofl", "Air")
                .replace("COFL", "AIR")
                .replace("cofl", "air");
    }

    /** Only rebuilds plain text; styled components without branding stay intact. */
    public static Component clean(Component component) {
        if (component == null) {
            return null;
        }
        String plain = component.getString();
        String cleaned = clean(plain);
        return cleaned.equals(plain) ? component : Component.literal(cleaned);
    }
}
