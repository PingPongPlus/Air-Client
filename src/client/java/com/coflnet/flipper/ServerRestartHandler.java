package com.coflnet.flipper;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.lowballing.LowballingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Handles Hypixel's scheduled reboot warning: pause every flipper, bounce
 * island -> hub -> island every two seconds until each warp is visible, then
 * restore whatever had been running.
 */
public final class ServerRestartHandler {
    private enum Phase {
        IDLE, IS_1, HUB, IS_2
    }

    private static final long COMMAND_INTERVAL_MS = 2000L;
    private static final long PHASE_TIMEOUT_MS = 60_000L;
    private static final long RESTART_COOLDOWN_MS = 60_000L;

    private static Phase phase = Phase.IDLE;
    private static long phaseAt;
    private static long nextCommandAt;
    private static long warpSignalAt;
    private static long lastRestartAt;
    private static boolean ahWasRunning;
    private static boolean bazaarWasRunning;
    private static boolean lowballingWasRunning;
    private static boolean bidWasRunning;
    private static Vec3 lastPosition;
    private static Object lastLevel;
    private static boolean initialized;

    private ServerRestartHandler() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || message == null) {
                return;
            }
            handleChat(ChatFormatting.stripFormatting(message.getString()));
        });
        ClientTickEvents.END_CLIENT_TICK.register(ServerRestartHandler::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void handleChat(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (phase == Phase.IDLE) {
            if (lower.contains("this server will restart soon")
                    && lower.contains("scheduled reboot")) {
                trigger();
            }
            return;
        }
        if (lower.contains("sending you to")
                || lower.contains("request join for hub")
                || lower.contains("sende zum server")
                || lower.contains("warping")
                || lower.contains("you are already on this server")
                || lower.contains("already connected to this server")) {
            warpSignalAt = System.currentTimeMillis();
        }
    }

    private static void trigger() {
        long now = System.currentTimeMillis();
        if (phase != Phase.IDLE || now - lastRestartAt < RESTART_COOLDOWN_MS) {
            return;
        }
        lastRestartAt = now;
        bidWasRunning = AhBidMacro.isRunning();
        ahWasRunning = AhFlipperMod.stopForRestart();
        bazaarWasRunning = FlipperMod.pauseForRestart();
        lowballingWasRunning = LowballingMod.stopForRestart();
        if (bidWasRunning) {
            AhBidMacro.stop();
        }
        Minecraft client = Minecraft.getInstance();
        lastPosition = client.player == null ? null : client.player.position();
        lastLevel = client.level;
        phase = Phase.IS_1;
        phaseAt = now;
        nextCommandAt = now;
        warpSignalAt = 0L;
        System.out.println("[Restart] Scheduled reboot detected - beginning warp sequence");
    }

    private static void tick(Minecraft client) {
        if (phase == Phase.IDLE || client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (warpSignalAt > phaseAt
                || (lastLevel != null && client.level != lastLevel)
                || (lastPosition != null
                && client.player.position().distanceToSqr(lastPosition) >= 25.0)) {
            advance(client, now);
            return;
        }
        if (now - phaseAt >= PHASE_TIMEOUT_MS) {
            System.out.println("[Restart] Warp phase timed out: " + phase);
            advance(client, now);
            return;
        }
        if (now < nextCommandAt) {
            return;
        }
        String command = phase == Phase.HUB ? "/hub" : "/is";
        if (client.getConnection() != null) {
            client.getConnection().sendChat(command);
        }
        nextCommandAt = now + COMMAND_INTERVAL_MS;
        lastPosition = client.player.position();
        lastLevel = client.level;
        System.out.println("[Restart] Sent " + command);
    }

    private static void advance(Minecraft client, long now) {
        switch (phase) {
            case IS_1 -> {
                phase = Phase.HUB;
                phaseAt = now;
                nextCommandAt = now;
                warpSignalAt = 0L;
                lastPosition = client.player == null ? null : client.player.position();
                lastLevel = client.level;
            }
            case HUB -> {
                phase = Phase.IS_2;
                phaseAt = now;
                nextCommandAt = now;
                warpSignalAt = 0L;
                lastPosition = client.player == null ? null : client.player.position();
                lastLevel = client.level;
            }
            case IS_2 -> {
                phase = Phase.IDLE;
                restore();
            }
            default -> {
            }
        }
    }

    private static void restore() {
        System.out.println("[Restart] Warp sequence complete - restoring flippers");
        AhFlipperMod.resumeAfterRestart(ahWasRunning);
        FlipperMod.resumeAfterRestart(bazaarWasRunning);
        LowballingMod.resumeAfterRestart(lowballingWasRunning);
        if (bidWasRunning) {
            AhBidMacro.start();
        }
        ahWasRunning = false;
        bazaarWasRunning = false;
        lowballingWasRunning = false;
        bidWasRunning = false;
    }

    private static void reset() {
        phase = Phase.IDLE;
        warpSignalAt = 0L;
        lastPosition = null;
        lastLevel = null;
        ahWasRunning = false;
        bazaarWasRunning = false;
        lowballingWasRunning = false;
        bidWasRunning = false;
    }
}
