package com.coflnet.pathing;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Get close to the target AND see it". GoalNear alone accepts any block
 * within the radius, also the one on the wrong side of a wall, where the bot
 * then stands and can not hit. The standing spots are computed once on the
 * client thread (line of sight needs the world); Baritone's thread only does
 * set lookups.
 */
public final class VisibleGoal implements Goal {
    private static final double EYE = 1.62;

    private final GoalNear near;
    private final LongOpenHashSet spots;
    private final BlockPos center;

    private VisibleGoal(BlockPos center, int radius, LongOpenHashSet spots) {
        this.center = center;
        this.near = new GoalNear(center, radius);
        this.spots = spots;
    }

    /**
     * Goal for {@code target}: standable blocks within {@code radius} that see
     * it. Falls back to a plain GoalNear when no such block exists (target in
     * a hole, unloaded area ...), so the bot at least gets close.
     */
    public static Goal of(Level level, Entity target, int radius) {
        BlockPos center = target.blockPosition();
        Vec3 aim = target.getBoundingBox().getCenter();
        LongOpenHashSet spots = new LongOpenHashSet();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (standable(level, pos) && sees(level, pos, aim, target)) {
                        spots.add(BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ()));
                    }
                }
            }
        }
        if (spots.isEmpty()) {
            return new GoalNear(center, radius);
        }
        return new VisibleGoal(center, radius, spots);
    }

    static boolean standable(Level level, BlockPos feet) {
        if (!level.isLoaded(feet)) {
            return false;
        }
        BlockPos floor = feet.below();
        return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    static boolean sees(Level level, BlockPos feet, Vec3 aim, Entity target) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE, feet.getZ() + 0.5);
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, target));
        return hit.getType() == HitResult.Type.MISS;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return spots.contains(BetterBlockPos.longHash(x, y, z));
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return near.heuristic(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VisibleGoal other && other.center.equals(center) && other.spots.equals(spots);
    }

    @Override
    public int hashCode() {
        return center.hashCode() * 31 + spots.size();
    }

    @Override
    public String toString() {
        return "VisibleGoal{" + center.toShortString() + ", spots=" + spots.size() + "}";
    }
}
