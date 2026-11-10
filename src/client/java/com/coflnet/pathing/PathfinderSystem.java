package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;

/**
 * Pathfinder watchdog: while Baritone walks, one cheap check per tick looks for
 * typical failures. A failure is stored at the block the current movement was
 * heading to, Baritone avoids that block from now on (cost multiplier, see
 * {@link FaultMemory}) and the current path is recalculated.
 */
public final class PathfinderSystem {
    private static final StuckDetector DETECTOR = new StuckDetector();
    private static boolean initialized;
    private static int tickCounter;
    private static long cooldownUntil;
    private static long lastSaveAt;
    private static int sessionFaults;
    private static String lastFault = "";
    private static int skippedFaults;
    private static String lastWorld = "";

    private PathfinderSystem() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        PathfinderConfig.get();
        FaultMemory.load();
        FaultMemory.install();
        ClientTickEvents.END_CLIENT_TICK.register(PathfinderSystem::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FaultMemory.saveNow());
    }

    public static int sessionFaults() {
        return sessionFaults;
    }

    public static String lastFault() {
        return lastFault;
    }

    /** Faults seen but not learned (lag, an entity in the way). */
    public static int skippedFaults() {
        return skippedFaults;
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            DETECTOR.reset();
            LagMonitor.reset();
            return;
        }
        long now = System.currentTimeMillis();
        LagMonitor.onClientTick(now);
        EntityObstacles.tick(client, now);
        if (++tickCounter % 20 == 0) {
            PathfinderTuning.updateStats(PlayerMovementStats.read(player));
            String world = worldKey(client);
            if (!world.equals(lastWorld)) {
                lastWorld = world;
                LagMonitor.reset(); // the time packet gap across a world change is no lag
                SetbackGuard.clear();
            }
            FaultMemory.setActiveWorld(world);
            if (now - lastSaveAt > 60_000L) {
                lastSaveAt = now;
                FaultMemory.saveIfDirty();
            }
        }
        IBaritone baritone;
        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return;
        }
        AotvAssist.tick(client, baritone);
        if (AotvAssist.isBusy()) {
            DETECTOR.reset(); // the warp itself looks like a setback
            return;
        }
        if (!PathfinderConfig.get().learnFromFaults) {
            DETECTOR.reset();
            return;
        }
        if (baritone == null || !baritone.getPathingBehavior().isPathing()) {
            DETECTOR.reset();
            return;
        }
        Goal goal = baritone.getPathingBehavior().getGoal();
        double heuristic = goal == null ? 0 : goal.heuristic(player.blockPosition());
        StuckDetector.Sample sample = new StuckDetector.Sample(player.getX(), player.getY(), player.getZ(),
                player.getDeltaMovement().y, player.onGround(),
                player.isInWater() || player.isInLava(), player.onClimbable(),
                player.getAbilities().flying, player.horizontalCollision, heuristic);
        FaultMemory.Type type = DETECTOR.update(sample);
        if (type == null || now < cooldownUntil) {
            return;
        }
        cooldownUntil = now + 2_000L;
        IMovement movement = currentMovement(baritone);
        BlockPos at;
        if (type == FaultMemory.Type.SETBACK) {
            // where the server sent us back from, not where the movement was heading
            at = BlockPos.containing(DETECTOR.faultX(), DETECTOR.faultY(), DETECTOR.faultZ());
            SetbackGuard.record(DETECTOR.faultX(), DETECTOR.faultY(), DETECTOR.faultZ(), now);
        } else {
            at = movement != null ? movement.getDest() : player.blockPosition();
        }
        String movementName = movement == null ? "" : movement.getClass().getSimpleName();
        String where = type.name() + " @ " + at.getX() + "," + at.getY() + "," + at.getZ()
                + (movementName.isEmpty() ? "" : " (" + movementName + ")");
        String skipReason = skipReason(client, player, at, type);
        String text;
        if (skipReason != null) {
            skippedFaults++;
            text = "[Pathfinder] " + where + " -> nicht gelernt (" + skipReason + "), plane neu";
        } else {
            double factor = FaultMemory.record(FaultMemory.activeWorld(), at.getX(), at.getY(), at.getZ(),
                    type, movementName, now);
            sessionFaults++;
            lastFault = where;
            text = factor > 1.0
                    ? String.format(Locale.ROOT, "[Pathfinder] %s -> Kosten x%.1f, plane neu", where, factor)
                    : "[Pathfinder] " + where + " -> gemerkt (erst bei Wiederholung gemieden), plane neu";
        }
        System.out.println(text);
        if (PathfinderConfig.get().showFaultsInChat) {
            com.coflnet.ahflipper.AhFlipperMod.message("§8" + text);
        }
        try {
            baritone.getPathingBehavior().forceCancel();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Reasons not to blame the block: the game lagged, or someone stands in
     * the way (that is no property of the block). Setbacks are always learned.
     */
    private static String skipReason(Minecraft client, LocalPlayer player, BlockPos at, FaultMemory.Type type) {
        if (type == FaultMemory.Type.SETBACK) {
            return null;
        }
        if (LagMonitor.isLagging()) {
            return String.format(Locale.ROOT, "Lag, %.0f TPS", LagMonitor.tps());
        }
        AABB around = player.getBoundingBox().inflate(1.2, 0.5, 1.2)
                .minmax(new AABB(at).inflate(0.6, 0.0, 0.6));
        for (Entity entity : client.level.getEntities(player, around)) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity && !(entity instanceof ArmorStand)
                    && entity.isAlive() && entity.isPushable()) {
                return "Entity im Weg: " + entity.getName().getString();
            }
        }
        return null;
    }

    private static IMovement currentMovement(IBaritone baritone) {
        try {
            IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor == null || executor.getPath() == null) {
                return null;
            }
            List<IMovement> movements = executor.getPath().movements();
            int position = executor.getPosition();
            return position >= 0 && position < movements.size() ? movements.get(position) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Learned faults belong to one world layout. Hub servers share their spawn,
     * an island has its own: dimension + world spawn tells them apart without
     * reading the scoreboard.
     */
    static String worldKey(Minecraft client) {
        try {
            BlockPos spawn = client.level.getRespawnData().pos();
            return client.level.dimension().identifier() + "@"
                    + spawn.getX() + "," + spawn.getY() + "," + spawn.getZ();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
