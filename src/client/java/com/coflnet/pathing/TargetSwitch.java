package com.coflnet.pathing;

/**
 * Switch to another target only when it is clearly closer, and not again
 * right after a switch: two targets at about the same distance would
 * otherwise flip every tick and the path would be recalculated every tick.
 */
public final class TargetSwitch {
    /** The new one must be at least this many blocks closer ... */
    static final double MIN_GAIN_BLOCKS = 4.0;
    /** ... and at least this share closer. */
    static final double MIN_GAIN_SHARE = 0.3;
    /** Stay with a target at least this long after switching to it. */
    public static final long HOLD_MS = 2_000L;
    /** Rough walking detour for a target that can not be seen (behind a wall). */
    public static final double HIDDEN_EXTRA = 8.0;

    private TargetSwitch() {
    }

    /**
     * @param current   effective distance to the current target
     * @param candidate effective distance to the other one
     */
    public static boolean shouldSwitch(double current, double candidate, long sinceSwitchMs) {
        if (sinceSwitchMs < HOLD_MS) {
            return false;
        }
        double gain = current - candidate;
        return gain >= MIN_GAIN_BLOCKS && gain >= current * MIN_GAIN_SHARE;
    }

    /** Distance plus a detour estimate when there is no line of sight. */
    public static double effectiveDistance(double distance, boolean visible) {
        return visible ? distance : distance + HIDDEN_EXTRA;
    }
}
