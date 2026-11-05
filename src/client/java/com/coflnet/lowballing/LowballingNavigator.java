package com.coflnet.lowballing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class LowballingNavigator {
    private static Entity currentTarget;
    private static BlockPos lastGoal;
    private static long lastPathAt;
    private static long lastProgressAt;
    private static double lastX;
    private static double lastZ;
    private static int stuckRepaths;
    private static int detourSide = 1;

    private LowballingNavigator() {
    }

    public static void tick(Entity target) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null || target == null || !target.isAlive() || target.isRemoved()) {
            stop();
            return;
        }
        if (currentTarget != target) {
            currentTarget = target;
            lastGoal = null;
            lastPathAt = 0;
            lastProgressAt = 0;
            stuckRepaths = 0;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            return;
        }
        configureBaritone();

        double distance = player.distanceTo(target);
        if (distance <= LowballingMod.CONFIG.interactReach) {
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            lastGoal = null;
            LowballingRotation.lookAt(target);
            return;
        }

        BlockPos goal = target.blockPosition();
        long now = System.currentTimeMillis();
        boolean pathing = baritone.getPathingBehavior().isPathing();
        boolean goalMoved = lastGoal != null && goal.distSqr(lastGoal) > 36;
        boolean repathAllowed = now - lastPathAt > 1000;
        if (lastGoal == null || goalMoved || (!pathing && repathAllowed)) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 2));
            lastGoal = goal;
            lastPathAt = now;
        }

        if (now - lastProgressAt > 2000) {
            double movedDistance = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
            if (pathing && movedDistance < 0.35
                    && distance > LowballingMod.CONFIG.interactReach + 1.0) {
                stuckRepaths++;
                if (stuckRepaths >= 2) {
                    baritone.getPathingBehavior().cancelEverything();
                    BlockPos detour = detourGoal(player, goal);
                    BlockPos next = detour != null ? detour : goal;
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(next, 1));
                    lastGoal = next;
                    lastPathAt = now;
                    stuckRepaths = 0;
                    detourSide = -detourSide;
                }
            } else {
                stuckRepaths = 0;
            }
            lastX = player.getX();
            lastZ = player.getZ();
            lastProgressAt = now;
        }

        if (distance <= 9.0) {
            LowballingRotation.lookAt(target);
        } else {
            Vec3 ahead = pathAhead(baritone, player);
            LowballingRotation.lookAt(ahead != null ? ahead : target.getEyePosition());
        }
    }

    public static void stop() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
            }
            BaritoneAPI.getSettings().freeLook.value = false;
        } catch (Exception ignored) {
        }
        currentTarget = null;
        lastGoal = null;
        lastPathAt = 0;
        lastProgressAt = 0;
        stuckRepaths = 0;
    }

    public static boolean isPathing() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        return baritone != null && baritone.getPathingBehavior().isPathing();
    }

    private static void configureBaritone() {
        baritone.api.Settings s = BaritoneAPI.getSettings();
        s.allowSprint.value = true;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.autoTool.value = false;
        s.allowParkour.value = true;
        s.allowParkourAscend.value = true;
        s.allowParkourPlace.value = false;
        s.allowDiagonalDescend.value = true;
        s.allowDiagonalAscend.value = true;
        s.allowDownward.value = true;
        s.allowOvershootDiagonalDescend.value = true;
        s.allowVines.value = true;
        s.allowWalkOnBottomSlab.value = true;
        s.sprintAscends.value = true;
        s.sprintInWater.value = true;
        s.overshootTraverse.value = true;
        s.assumeWalkOnWater.value = false;
        s.assumeWalkOnLava.value = false;
        s.assumeStep.value = false;
        s.assumeSafeWalk.value = false;
        s.allowWaterBucketFall.value = false;
        s.freeLook.value = true;
        s.blockFreeLook.value = false;
        s.smoothLook.value = false;
        s.randomLooking.value = 0.01;
        s.antiCheatCompatibility.value = true;
        s.considerPotionEffects.value = true;
        s.avoidance.value = true;
        s.rightClickContainerOnArrival.value = false;
        s.enterPortal.value = false;
        s.renderPath.value = false;
        s.renderGoal.value = false;
        s.renderSelectionBoxes.value = false;
        s.renderCachedChunks.value = false;
        s.chatDebug.value = false;
        s.chatControl.value = false;
        s.desktopNotifications.value = false;
        s.itemSaver.value = false;
        s.mineScanDroppedItems.value = false;
        s.costHeuristic.value = 3.0;
        s.jumpPenalty.value = 2.0;
        s.walkOnWaterOnePenalty.value = 3.0;
        s.pathingMaxChunkBorderFetch.value = 64;
        s.planningTickLookahead.value = 150;
        s.primaryTimeoutMS.value = 500L;
        s.failureTimeoutMS.value = 2000L;
        s.planAheadPrimaryTimeoutMS.value = 2000L;
        s.planAheadFailureTimeoutMS.value = 3000L;
        s.movementTimeoutTicks.value = 20;
        s.maxFallHeightNoWater.value = 3;
        s.blockReachDistance.value = 3.0f;
        s.chunkCaching.value = true;
        s.pruneRegionsFromRAM.value = true;
    }

    private static Vec3 pathAhead(IBaritone baritone, Player player) {
        try {
            baritone.api.pathing.path.IPathExecutor executor =
                    baritone.getPathingBehavior().getCurrent();
            if (executor == null) {
                return null;
            }
            baritone.api.pathing.calc.IPath path = executor.getPath();
            if (path == null) {
                return null;
            }
            int start = Math.max(0, executor.getPosition() + 2);
            java.util.List<baritone.api.utils.BetterBlockPos> positions = path.positions();
            for (int i = start; i < positions.size(); i++) {
                BlockPos pos = positions.get(i);
                double dx = pos.getX() + 0.5 - player.getX();
                double dz = pos.getZ() + 0.5 - player.getZ();
                if (dx * dx + dz * dz > 1.44) {
                    return new Vec3(pos.getX() + 0.5, player.getEyeY(), pos.getZ() + 0.5);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static BlockPos detourGoal(Player player, BlockPos goal) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        double dx = goal.getX() + 0.5 - player.getX();
        double dz = goal.getZ() + 0.5 - player.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 0.1) {
            return null;
        }
        double px = -dz / length * detourSide;
        double pz = dx / length * detourSide;
        BlockPos base = player.blockPosition();
        for (int distance = 3; distance >= 2; distance--) {
            BlockPos candidate = base.offset(
                    (int) Math.round(px * distance), 0, (int) Math.round(pz * distance));
            if (client.level.getBlockState(candidate).isAir()
                    && client.level.getBlockState(candidate.above()).isAir()
                    && !client.level.getBlockState(candidate.below()).isAir()) {
                return candidate;
            }
        }
        return null;
    }
}
