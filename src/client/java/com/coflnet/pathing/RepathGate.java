package com.coflnet.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * When a bot may hand Baritone a new goal for a moving target. A new path
 * started mid-jump makes the jump fail (the new path begins somewhere else),
 * and a new path for every small step of the target means constant
 * recalculation: so only once the target moved {@link #MOVE_BLOCKS} blocks,
 * the player stands (or swims / climbs) and the last request is a moment ago.
 */
public final class RepathGate {
    public static final int MOVE_BLOCKS = 3;
    public static final long MIN_INTERVAL_MS = 500L;

    private RepathGate() {
    }

    /** Not in the middle of a jump or fall. */
    public static boolean playerSettled(Player player) {
        return player.onGround() || player.isInWater() || player.isInLava()
                || player.onClimbable() || player.getAbilities().flying;
    }

    /** The target moved far enough from the goal of the current path. */
    public static boolean targetMoved(BlockPos lastGoal, BlockPos now) {
        return lastGoal == null || lastGoal.distSqr(now) >= MOVE_BLOCKS * MOVE_BLOCKS;
    }

    public static boolean mayRepath(Player player, BlockPos lastGoal, BlockPos now,
                                    long lastRequestAt, long nowMs) {
        return targetMoved(lastGoal, now) && nowMs - lastRequestAt >= MIN_INTERVAL_MS
                && playerSettled(player);
    }
}
