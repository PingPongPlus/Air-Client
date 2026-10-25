package com.coflnet.ahflipper;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class AhMobPathfinder {

    private static long nextAttackAt;
    private static BlockPos lastGoalPos;
    private static Entity currentTarget;
    private static String lastTargetName = "";
    private static double smoothX;
    private static double smoothZ;
    private static long lastPathRequestAt;
    private static long lastProgressCheckAt;
    private static double lastX;
    private static double lastZ;
    private static int stuckRepaths;

    private AhMobPathfinder() {
    }

    public static void onSettingsChanged() {
        AhAutomationConfig config = AhAutomationConfig.get();
        if (!config.enabled || config.targetName.isBlank()) {
            cancelPathing();
        }
    }

    public static void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        AhAutomationConfig config = AhAutomationConfig.get();
        if (!config.enabled || config.targetName.isBlank()) {
            return;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            return;
        }
        if (config.allowSprint) {
            BaritoneAPI.getSettings().allowSprint.value = true;
        }
        BaritoneAPI.getSettings().allowBreak.value = false;
        BaritoneAPI.getSettings().allowPlace.value = false;

        String wanted = config.targetName.toLowerCase(Locale.ROOT);
        if (!wanted.equals(lastTargetName)) {
            lastTargetName = wanted;
            currentTarget = null;
            lastGoalPos = null;
        }

        Entity target = findTarget(client, config.targetName);
        if (target == null || !target.isAlive()) {
            currentTarget = null;
            cancelPathing();
            lastGoalPos = null;
            return;
        }
        currentTarget = target;
        Player player = client.player;
        double distance = player.distanceTo(target);
        if (distance <= config.reach) {
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            lastGoalPos = null;
            faceEntity(player, target, 15.0, 10.0);
            if (config.autoAttack) {
                maybeAttack(client, player, target, config);
            }
            return;
        }

        BlockPos goal = target.blockPosition();
        long now = System.currentTimeMillis();
        boolean pathing = baritone.getPathingBehavior().isPathing();
        boolean goalMoved = lastGoalPos != null && goal.distSqr(lastGoalPos) > 4;
        boolean repathAllowed = now - lastPathRequestAt > 500;
        boolean needsPath = lastGoalPos == null || goalMoved || (!pathing && repathAllowed);
        if (needsPath) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 2));
            lastGoalPos = goal;
            lastPathRequestAt = now;
        }

        if (now - lastProgressCheckAt > 2000) {
            double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
            if (pathing && moved < 0.5 && distance > config.reach + 1.0) {
                stuckRepaths++;
                if (stuckRepaths >= 2) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 2));
                    lastGoalPos = goal;
                    lastPathRequestAt = now;
                    stuckRepaths = 0;
                }
            } else {
                stuckRepaths = 0;
            }
            lastX = player.getX();
            lastZ = player.getZ();
            lastProgressCheckAt = now;
        }

        faceMovementDirection(player, goal, baritone);
    }

    private static void faceMovementDirection(Player player, BlockPos goal, IBaritone baritone) {
        Vec3 ahead = pathAhead(baritone, player);
        Vec3 velocity = player.getDeltaMovement();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (ahead != null) {
            double dx = ahead.x - player.getX();
            double dz = ahead.z - player.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.1) {
                smoothX += (dx / len - smoothX) * 0.35;
                smoothZ += (dz / len - smoothZ) * 0.35;
            }
        } else if (horizontal > 0.02) {
            smoothX += (velocity.x / horizontal - smoothX) * 0.25;
            smoothZ += (velocity.z / horizontal - smoothZ) * 0.25;
        } else {
            double dx = goal.getX() + 0.5 - player.getX();
            double dz = goal.getZ() + 0.5 - player.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.1) {
                smoothX += (dx / len - smoothX) * 0.10;
                smoothZ += (dz / len - smoothZ) * 0.10;
            }
        }
        double yaw = Math.toDegrees(Math.atan2(-smoothX, smoothZ));
        player.setYRot((float) rotateToward(player.getYRot(), yaw, 12.0));
        player.setXRot((float) rotateToward(player.getXRot(), 0, 8.0));
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
                    return new Vec3(pos.getX() + 0.5, player.getEyeY(),
                            pos.getZ() + 0.5);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Entity findTarget(Minecraft client, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        if (currentTarget != null && currentTarget.isAlive()
                && !currentTarget.isRemoved() && currentTarget != client.player
                && client.player.distanceToSqr(currentTarget) < 60 * 60) {
            return currentTarget;
        }
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || !entity.isAlive()) {
                continue;
            }
            String entityName = entity.getName().getString().toLowerCase(Locale.ROOT);
            if (!entityName.contains(wanted)) {
                continue;
            }
            double distance = client.player.distanceToSqr(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private static void maybeAttack(Minecraft client, Player player, Entity target,
                                    AhAutomationConfig config) {
        long now = System.currentTimeMillis();
        if (now < nextAttackAt) {
            return;
        }
        client.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        double cpsMin = Math.max(0.5, config.attackCpsMin);
        double cpsMax = Math.max(cpsMin, config.attackCpsMax);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double cps = cpsMin + random.nextDouble() * (cpsMax - cpsMin);
        long interval = (long) (1000.0 / cps);
        nextAttackAt = now + interval + random.nextLong(60, 220);
    }

    private static void faceEntity(Player player, Entity target, double yawSpeed,
                                   double pitchSpeed) {
        Vec3 eye = target.getEyePosition();
        Vec3 delta = eye.subtract(player.getEyePosition());
        double targetYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double targetPitch = Math.toDegrees(-Math.atan2(delta.y, horizontal));
        player.setYRot((float) rotateToward(player.getYRot(), targetYaw, yawSpeed));
        player.setXRot((float) rotateToward(player.getXRot(), targetPitch, pitchSpeed));
    }

    private static double rotateToward(double current, double target, double maxDegrees) {
        double delta = wrapDegrees(target - current);
        double step = Math.max(-maxDegrees, Math.min(maxDegrees, delta));
        return current + step;
    }

    private static double wrapDegrees(double value) {
        value = value % 360.0;
        if (value > 180.0) {
            value -= 360.0;
        } else if (value < -180.0) {
            value += 360.0;
        }
        return value;
    }

    private static void cancelPathing() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
            }
        } catch (Exception ignored) {
        }
        smoothX = 0;
        smoothZ = 0;
        lastGoalPos = null;
    }
}
