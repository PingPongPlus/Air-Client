package com.coflnet.pathing;

/**
 * Applies the player's real movement values and the Pathfinder tab settings to
 * Baritone. Called by every bot that paths (mob pathfinder, lowballer, follow)
 * after it set its own task specific settings.
 */
public final class PathfinderTuning {
    /** Baritone's ActionCosts.COST_INF: movements at or above it are never used. */
    public static final double NO_JUMP = 1_000_000.0;

    private static volatile PlayerMovementStats stats = PlayerMovementStats.VANILLA;

    private PathfinderTuning() {
    }

    /** Refreshed by the watchdog once per second. */
    static void updateStats(PlayerMovementStats fresh) {
        if (fresh != null) {
            stats = fresh;
        }
    }

    public static PlayerMovementStats stats() {
        return stats;
    }

    /**
     * @param forbidJumps true for bots that must never jump (lowballer), whatever
     *                    the Pathfinder tab says
     */
    public static void apply(baritone.api.Settings s, boolean forbidJumps) {
        PathfinderConfig config = PathfinderConfig.get();
        PlayerMovementStats current = stats;
        // Keep a higher value a bot set itself (e.g. a configured jump boost).
        s.jumpHeight.value = Math.max(s.jumpHeight.value, Math.max(1.0, current.jumpHeight()));
        // Step height >= 1 block: walking up a full block needs no jump.
        s.assumeStep.value = current.stepHeight() >= 1.0;
        s.allowSprint.value = s.allowSprint.value && config.allowSprint;
        s.costHeuristic.value = config.costHeuristic;
        s.movementTimeoutTicks.value = config.movementTimeoutTicks;
        s.failureTimeoutMS.value = config.failureTimeoutMs;
        s.planAheadFailureTimeoutMS.value = Math.max(config.failureTimeoutMs, 4000L);
        // Keep a gap to edges / walls when there is room (patched Baritone, EdgeSafety).
        s.edgeSafetyCost.value = config.edgeSafetyCost;
        s.wallSafetyCost.value = config.wallSafetyCost;
        // Mob avoidance puts a cost sphere around every mob on each path calculation:
        // slow in mob-heavy SkyBlock areas and it even made the target mob expensive.
        s.avoidance.value = false;
        // Baritone caches chunks per server address + dimension, so all Hypixel
        // islands would share one cache and plan through blocks of another island.
        // Plan with the loaded chunks only.
        s.chunkCaching.value = false;

        boolean jumps = config.allowJumps && !forbidJumps;
        if (jumps) {
            s.jumpPenalty.value = config.jumpPenalty;
            s.allowParkour.value = s.allowParkour.value && config.allowParkour;
            s.allowParkourAscend.value = s.allowParkourAscend.value && config.allowParkour;
            if (config.setbackGuard && SetbackGuard.active()) {
                // Watchdog set us back recently / here: no risky jumps for a while.
                s.allowParkour.value = false;
                s.allowParkourAscend.value = false;
                s.sprintAscends.value = false;
            }
        } else {
            s.jumpPenalty.value = NO_JUMP;
            s.allowParkour.value = false;
            s.allowParkourAscend.value = false;
            s.allowDiagonalAscend.value = false;
            s.sprintAscends.value = false;
        }
    }
}
