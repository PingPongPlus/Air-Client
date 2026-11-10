package com.coflnet.pathing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Remembers server setbacks (Watchdog teleporting the player back). Right
 * after one, and near places where they happened more than once, Baritone
 * plans without parkour and sprint jumps, the moves that trigger them most.
 */
public final class SetbackGuard {
    /** No risky jumps for this long after any setback. */
    static final long COOLDOWN_MS = 30_000L;
    /** Setbacks older than this are forgotten. */
    static final long MEMORY_MS = 10L * 60_000L;
    /** Setbacks closer than this belong to the same spot. */
    static final double SPOT_RADIUS = 12.0;
    /** The player counts as near a bad spot within this distance. */
    static final double NEAR_RADIUS = 16.0;
    static final int SPOT_COUNT = 2;
    private static final int MAX_ENTRIES = 64;

    private record Setback(double x, double y, double z, long at) {
    }

    private static final Deque<Setback> SETBACKS = new ArrayDeque<>();
    private static volatile long lastAt;

    private SetbackGuard() {
    }

    static synchronized void record(double x, double y, double z, long now) {
        SETBACKS.addLast(new Setback(x, y, z, now));
        while (SETBACKS.size() > MAX_ENTRIES) {
            SETBACKS.removeFirst();
        }
        lastAt = now;
    }

    /** True right after a setback or near a spot with repeated setbacks. */
    public static boolean active() {
        long now = System.currentTimeMillis();
        if (now - lastAt < COOLDOWN_MS) {
            return true;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && nearBadSpot(player.getX(), player.getY(), player.getZ(), now);
    }

    static synchronized boolean nearBadSpot(double x, double y, double z, long now) {
        SETBACKS.removeIf(setback -> now - setback.at() > MEMORY_MS);
        for (Setback a : SETBACKS) {
            if (distSq(a, x, y, z) > NEAR_RADIUS * NEAR_RADIUS) {
                continue;
            }
            int count = 0;
            for (Setback b : SETBACKS) {
                if (distSq(b, a.x(), a.y(), a.z()) <= SPOT_RADIUS * SPOT_RADIUS) {
                    count++;
                }
            }
            if (count >= SPOT_COUNT) {
                return true;
            }
        }
        return false;
    }

    static synchronized void clear() {
        SETBACKS.clear();
        lastAt = 0;
    }

    private static double distSq(Setback s, double x, double y, double z) {
        double dx = s.x() - x;
        double dy = s.y() - y;
        double dz = s.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
