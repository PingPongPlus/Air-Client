package com.coflnet.pathing;

/**
 * Tells whether the game is lagging right now, so a stuck moment caused by lag
 * is not learned as a bad block.
 *
 * <p>Server lag: the server sends its world time about once per second
 * (every 20 server ticks). A packet that arrives much later than that, or that
 * covers clearly fewer than 20 ticks per second, means the server or the
 * connection lags. Client lag: a client tick that took far longer than 50 ms.
 */
public final class LagMonitor {
    /** Normal gap between two time packets is ~1000 ms. */
    static final long PACKET_GAP_LAG_MS = 1_600L;
    /** Gaps longer than this mean the server just does not send them regularly. */
    static final long PACKET_GAP_IGNORE_MS = 10_000L;
    static final double LOW_TPS = 15.0;
    static final long CLIENT_TICK_LAG_MS = 250L;
    /** Faults are not learned for this long after lag was seen. */
    static final long HOLD_MS = 3_000L;

    private static volatile long lastPacketAt;
    private static volatile long lastGameTime = Long.MIN_VALUE;
    private static volatile long lagUntil;
    private static volatile double tps = 20.0;
    private static long lastClientTickAt;

    private LagMonitor() {
    }

    /** Network thread: a world time packet arrived. */
    public static void onTimePacket(long gameTime) {
        long now = System.currentTimeMillis();
        long previousAt = lastPacketAt;
        long previousTime = lastGameTime;
        lastPacketAt = now;
        lastGameTime = gameTime;
        if (previousAt == 0 || previousTime == Long.MIN_VALUE) {
            return;
        }
        long gap = now - previousAt;
        if (gap <= 0 || gap > PACKET_GAP_IGNORE_MS) {
            return;
        }
        long ticks = gameTime - previousTime;
        if (ticks > 0) {
            tps = Math.min(20.0, ticks * 1000.0 / gap);
        }
        if (isLagGap(gap, ticks)) {
            lagUntil = now + HOLD_MS;
        }
    }

    static boolean isLagGap(long gapMs, long ticks) {
        if (gapMs > PACKET_GAP_LAG_MS) {
            return true;
        }
        // Only judge TPS on a normal ~1 s interval; bursts of packets are no lag.
        return gapMs >= 800 && ticks > 0 && ticks * 1000.0 / gapMs < LOW_TPS;
    }

    /** Client thread, once per client tick. */
    static void onClientTick(long now) {
        long previous = lastClientTickAt;
        lastClientTickAt = now;
        if (previous != 0 && now - previous > CLIENT_TICK_LAG_MS && now - previous < 10_000L) {
            lagUntil = Math.max(lagUntil, now + HOLD_MS);
        }
    }

    /** World change / disconnect: the next packet gap means nothing. */
    static void reset() {
        lastPacketAt = 0;
        lastGameTime = Long.MIN_VALUE;
        lastClientTickAt = 0;
    }

    public static boolean isLagging() {
        return System.currentTimeMillis() < lagUntil;
    }

    public static double tps() {
        return tps;
    }
}
