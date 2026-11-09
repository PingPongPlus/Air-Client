package com.coflnet.ahflipper;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Premium (team) players. Their client runs the Hypixel flippers at full speed
 * and the bid macro never outbids them; every other client waits
 * {@code nonPremiumDelayMs} before each flip action, so premium wins the race.
 */
public final class Premium {
    /** Hard-coded premium player names. */
    public static final List<String> NAMES = List.of(
            "xVoltrix_", "Gannelgang123", "pingplus", "ImmaMaster");

    private Premium() {
    }

    public static boolean isName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (String premium : NAMES) {
            if (premium.equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Name of the local player, "" if unknown. */
    public static String localName() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null ? mc.player.getName().getString() : "";
    }

    public static boolean isLocalPremium() {
        return isName(localName());
    }

    /** Premium names plus the user's own don't-outbid list. */
    public static List<String> protectedPlayers() {
        List<String> all = new ArrayList<>(NAMES);
        List<String> extra = AhFlipperMod.CONFIG.dontOutbidPlayers;
        if (extra != null) {
            for (String name : extra) {
                if (name != null && !name.isBlank()) {
                    all.add(name);
                }
            }
        }
        return all;
    }

    /**
     * Head start for premium: a non-premium client waits before each flip
     * action (AH buy, AH bid, Bazaar order); premium does not wait. Runs on the
     * flipper's own background threads, never on the render thread.
     */
    public static void applyDelay() {
        if (isLocalPremium()) {
            return;
        }
        long ms = Math.max(0, AhFlipperMod.CONFIG.nonPremiumDelayMs);
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
