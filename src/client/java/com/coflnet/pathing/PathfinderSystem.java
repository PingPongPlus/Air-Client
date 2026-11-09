package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

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
    }

    public static int sessionFaults() {
        return sessionFaults;
    }

    public static String lastFault() {
        return lastFault;
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            DETECTOR.reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (++tickCounter % 20 == 0) {
            PathfinderTuning.updateStats(PlayerMovementStats.read(player));
            FaultMemory.setActiveWorld(worldKey(client));
            if (now - lastSaveAt > 60_000L) {
                lastSaveAt = now;
                FaultMemory.saveIfDirty();
            }
        }
        if (!PathfinderConfig.get().learnFromFaults) {
            DETECTOR.reset();
            return;
        }
        IBaritone baritone;
        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
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
        BlockPos at = movement != null ? movement.getDest() : player.blockPosition();
        String movementName = movement == null ? "" : movement.getClass().getSimpleName();
        double factor = FaultMemory.record(FaultMemory.activeWorld(), at.getX(), at.getY(), at.getZ(),
                type, movementName, now);
        sessionFaults++;
        lastFault = type.name() + " @ " + at.getX() + "," + at.getY() + "," + at.getZ()
                + (movementName.isEmpty() ? "" : " (" + movementName + ")");
        String text = String.format(Locale.ROOT, "[Pathfinder] %s -> Kosten x%.1f, plane neu", lastFault, factor);
        System.out.println(text);
        if (PathfinderConfig.get().showFaultsInChat) {
            com.coflnet.ahflipper.AhFlipperMod.message("§8" + text);
        }
        try {
            baritone.getPathingBehavior().forceCancel();
        } catch (RuntimeException ignored) {
        }
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
