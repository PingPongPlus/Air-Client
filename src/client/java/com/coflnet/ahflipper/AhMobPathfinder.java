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
import pingplus.voicechat.client.AimSmoothing;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class AhMobPathfinder {

    private static final long ONE_HIT_COOLDOWN_MS = 5_000L;

    private static long nextAttackAt;
    private static BlockPos lastGoalPos;
    private static Entity currentTarget;
    private static String lastTargetName = "";
    private static long lastPathRequestAt;
    private static long lastProgressCheckAt;
    private static double lastX;
    private static double lastZ;
    private static int stuckRepaths;

    private static final Map<UUID, Long> ONE_HIT_UNTIL = new HashMap<>();
    private static double desiredYaw;
    private static double desiredPitch;
    private static boolean hasDesiredAim;
    private static long lastFrameNanos;

    private AhMobPathfinder() {
    }

    public static void onSettingsChanged() {
        AhAutomationConfig config = AhAutomationConfig.get();
        if (!config.enabled || config.targetName.isBlank()) {
            cancelPathing();
            ONE_HIT_UNTIL.clear();
        }
    }

    public static void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            hasDesiredAim = false;
            return;
        }
        AhAutomationConfig config = AhAutomationConfig.get();
        if (!config.enabled || config.targetName.isBlank()) {
            hasDesiredAim = false;
            return;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            hasDesiredAim = false;
            return;
        }
        configureBaritone(config);

        String wanted = config.targetName.toLowerCase(Locale.ROOT);
        if (!wanted.equals(lastTargetName)) {
            lastTargetName = wanted;
            currentTarget = null;
            lastGoalPos = null;
            ONE_HIT_UNTIL.clear();
            hasDesiredAim = false;
        }

        long now = System.currentTimeMillis();
        ONE_HIT_UNTIL.entrySet().removeIf(entry -> entry.getValue() <= now);
        Entity target = findTarget(client, config.targetName, now);
        if (target == null || !target.isAlive() || target.isRemoved()) {
            currentTarget = null;
            cancelPathing();
            return;
        }
        currentTarget = target;
        Player player = client.player;
        double distance = player.distanceTo(target);
        if (distance <= config.reach) {
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            lastGoalPos = null;
            setDesiredAim(player, target.getBoundingBox().getCenter());
            if (config.autoAttack) {
                maybeAttack(client, player, target, config, baritone, now);
            }
            return;
        }

        BlockPos goal = target.blockPosition();
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

        aimAlongPath(player, goal, baritone);
    }

    public static void frame(Minecraft client) {
        if (client.player == null || client.level == null) {
            hasDesiredAim = false;
            lastFrameNanos = 0;
            return;
        }
        AhAutomationConfig config = AhAutomationConfig.get();
        if (!config.enabled || !hasDesiredAim || config.targetName.isBlank()
                || pingplus.voicechat.client.PlayerSettings.killAura
                || client.gui.screen() != null || client.gui.overlay() != null
                || client.isPaused()) {
            lastFrameNanos = 0;
            return;
        }
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e9;
        lastFrameNanos = now;
        float speed = (float) config.cameraTurnSpeed;
        Player player = client.player;
        player.setYRot(AimSmoothing.step(player.getYRot(),
                (float) desiredYaw, speed, dt));
        player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(),
                (float) desiredPitch, speed, dt), -90.0f, 90.0f));
    }

    private static void setDesiredAim(Player player, Vec3 point) {
        Vec3 delta = point.subtract(player.getEyePosition());
        double horizontal = Math.hypot(delta.x, delta.z);
        desiredYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        desiredPitch = Math.toDegrees(-Math.atan2(delta.y, horizontal));
        hasDesiredAim = true;
    }

    private static void aimAlongPath(Player player, BlockPos goal, IBaritone baritone) {
        Vec3 ahead = pathAhead(baritone, player);
        if (ahead == null) {
            Vec3 velocity = player.getDeltaMovement();
            double horizontal = Math.hypot(velocity.x, velocity.z);
            if (horizontal > 0.02) {
                ahead = new Vec3(player.getX() + velocity.x / horizontal * 3.0,
                        player.getEyeY(),
                        player.getZ() + velocity.z / horizontal * 3.0);
            } else {
                ahead = new Vec3(goal.getX() + 0.5, player.getEyeY(), goal.getZ() + 0.5);
            }
        }
        setDesiredAim(player, ahead);
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

    private static Entity findTarget(Minecraft client, String name, long now) {
        String wanted = name.toLowerCase(Locale.ROOT);
        if (currentTarget != null && currentTarget.isAlive()
                && !currentTarget.isRemoved() && currentTarget != client.player
                && !isOneHitDone(currentTarget, now)
                && client.player.distanceToSqr(currentTarget) < 60 * 60) {
            return currentTarget;
        }
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || !entity.isAlive() || entity.isRemoved()
                    || isOneHitDone(entity, now)) {
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

    private static boolean isOneHitDone(Entity entity, long now) {
        Long until = ONE_HIT_UNTIL.get(entity.getUUID());
        return until != null && until > now;
    }

    private static void maybeAttack(Minecraft client, Player player, Entity target,
                                    AhAutomationConfig config, IBaritone baritone, long now) {
        if (player.getAttackStrengthScale(0.0f) < 1.0f) {
            return;
        }
        if (now < nextAttackAt || !player.hasLineOfSight(target)) {
            return;
        }
        client.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        if (config.oneHitMode) {
            ONE_HIT_UNTIL.put(target.getUUID(), now + ONE_HIT_COOLDOWN_MS);
            currentTarget = null;
            lastGoalPos = null;
            hasDesiredAim = false;
            nextAttackAt = 0;
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            return;
        }
        double cpsMin = Math.max(0.5, config.attackCpsMin);
        double cpsMax = Math.max(cpsMin, config.attackCpsMax);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double cps = cpsMin + random.nextDouble() * (cpsMax - cpsMin);
        long interval = (long) (1000.0 / cps);
        nextAttackAt = now + interval + random.nextLong(60, 220);
    }

    private static void configureBaritone(AhAutomationConfig config) {
        baritone.api.Settings settings = BaritoneAPI.getSettings();
        settings.allowSprint.value = config.allowSprint;
        settings.allowBreak.value = false;
        settings.allowPlace.value = false;
        settings.allowInventory.value = false;
        settings.autoTool.value = false;
        settings.allowParkour.value = true;
        settings.allowParkourAscend.value = true;
        settings.allowParkourPlace.value = false;
        settings.allowDiagonalDescend.value = true;
        settings.allowDiagonalAscend.value = true;
        settings.allowDownward.value = true;
        settings.allowOvershootDiagonalDescend.value = true;
        settings.allowVines.value = true;
        settings.allowWalkOnBottomSlab.value = true;
        settings.sprintAscends.value = true;
        settings.sprintInWater.value = true;
        settings.overshootTraverse.value = true;
        settings.assumeWalkOnWater.value = false;
        settings.assumeWalkOnLava.value = false;
        settings.assumeStep.value = false;
        settings.assumeSafeWalk.value = false;
        settings.allowWaterBucketFall.value = false;
        settings.freeLook.value = false;
        settings.blockFreeLook.value = false;
        settings.smoothLook.value = false;
        settings.randomLooking.value = 0.01;
        settings.antiCheatCompatibility.value = true;
        settings.considerPotionEffects.value = true;
        settings.avoidance.value = true;
        settings.rightClickContainerOnArrival.value = false;
        settings.enterPortal.value = false;
        settings.renderPath.value = false;
        settings.renderGoal.value = false;
        settings.renderSelectionBoxes.value = false;
        settings.renderCachedChunks.value = false;
        settings.chatDebug.value = false;
        settings.chatControl.value = false;
        settings.desktopNotifications.value = false;
        settings.itemSaver.value = false;
        settings.mineScanDroppedItems.value = false;
        settings.costHeuristic.value = 3.0;
        settings.jumpPenalty.value = 1.5;
        settings.walkOnWaterOnePenalty.value = 3.0;
        settings.pathingMaxChunkBorderFetch.value = 64;
        settings.planningTickLookahead.value = 150;
        settings.primaryTimeoutMS.value = 500L;
        settings.failureTimeoutMS.value = 2000L;
        settings.planAheadPrimaryTimeoutMS.value = 2000L;
        settings.planAheadFailureTimeoutMS.value = 3000L;
        settings.movementTimeoutTicks.value = 20;
        settings.maxFallHeightNoWater.value = 3;
        settings.blockReachDistance.value = 3.0f;
        settings.chunkCaching.value = true;
        settings.pruneRegionsFromRAM.value = true;
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
        lastGoalPos = null;
        hasDesiredAim = false;
        lastFrameNanos = 0;
    }
}
