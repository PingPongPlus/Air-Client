package com.coflnet.pathing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Global Baritone tuning (Pathfinder tab), saved in CoflSky/pathfinder.json. */
public final class PathfinderConfig {
    /** Jumps (ascends, high jumps). The lowballer forbids them for itself anyway. */
    public boolean allowJumps = true;
    public boolean allowParkour = true;
    public boolean allowSprint = true;
    /** Extra cost of every jump; higher = jumps only when they save a lot. */
    public double jumpPenalty = 2.0;
    /** A* heuristic weight: higher = faster search, slightly less optimal paths. */
    public double costHeuristic = 3.0;
    /** A single movement that takes longer than this is treated as failed (Baritone default 100). */
    public int movementTimeoutTicks = 100;
    /** Max time Baritone searches before it gives up on a path. */
    public long failureTimeoutMs = 3500L;
    /**
     * Extra cost (ticks) per side where the next block borders a drop of 2+
     * blocks: paths keep a gap to edges when there is room. 0 = off.
     */
    public double edgeSafetyCost = 2.0;
    /** Extra cost (ticks) per side where the next block touches a wall at body height. */
    public double wallSafetyCost = 0.5;

    /** Detect stuck situations and make Baritone avoid those blocks next time. */
    public boolean learnFromFaults = true;
    /** Cost multiplier added per fault at a block (1 fault = x(1+strength)). */
    public double faultPenaltyStrength = 3.0;
    /** Faults needed before a block is avoided at all (one-off lag is ignored). */
    public double faultMinCount = 2.0;
    /** Highest cost multiplier a learned fault can give. */
    public double faultMaxMultiplier = 10.0;
    /** Learned faults fade away with this half-life. */
    public double faultHalfLifeHours = 24.0;
    /** Write every detected fault to the chat. */
    public boolean showFaultsInChat = false;

    /** Aspect of the Void while pathing: OFF, TELEPORT, ETHERWARP or BOTH. */
    public String aotvMode = "OFF";
    /** Blocks a normal right-click teleport goes (8 + tuned transmission). */
    public int aotvTeleportBlocks = 12;
    /** Only warp when it skips at least this many path steps. */
    public int aotvMinGain = 8;

    /** Static NPCs / mobs that block the way count as obstacles. */
    public boolean avoidStandingEntities = true;
    /** After a server setback: no parkour / sprint jumps for a while and in that area. */
    public boolean setbackGuard = true;

    /** Bumped when defaults change; older files (no field = 0) get the new defaults once. */
    public int configVersion;
    static final int CURRENT_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile PathfinderConfig instance;

    public static PathfinderConfig get() {
        PathfinderConfig current = instance;
        if (current == null) {
            synchronized (PathfinderConfig.class) {
                if (instance == null) {
                    instance = load();
                }
                current = instance;
            }
        }
        return current;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("pathfinder.json");
    }

    private static PathfinderConfig load() {
        try {
            Path path = file();
            if (Files.exists(path)) {
                PathfinderConfig loaded = GSON.fromJson(Files.readString(path), PathfinderConfig.class);
                if (loaded != null) {
                    loaded.migrate();
                    loaded.clamp();
                    return loaded;
                }
            }
        } catch (Exception e) {
            System.out.println("[Pathfinder] Config nicht lesbar: " + e.getMessage());
        }
        PathfinderConfig fresh = new PathfinderConfig();
        fresh.configVersion = CURRENT_VERSION;
        return fresh;
    }

    private void migrate() {
        if (configVersion < 2) {
            // old defaults were too strict for lag (20 ticks) and kept faults too long
            movementTimeoutTicks = Math.max(movementTimeoutTicks, 100);
            faultHalfLifeHours = Math.min(faultHalfLifeHours, 24.0);
            failureTimeoutMs = 3500L;
            edgeSafetyCost = 2.0;
            wallSafetyCost = 0.5;
            faultMinCount = 2.0;
            faultMaxMultiplier = 10.0;
            avoidStandingEntities = true;
            setbackGuard = true;
            configVersion = 2;
            save();
        }
    }

    void clamp() {
        jumpPenalty = clamp(jumpPenalty, 0.0, 20.0);
        costHeuristic = clamp(costHeuristic, 1.0, 5.0);
        movementTimeoutTicks = (int) clamp(movementTimeoutTicks, 10, 200);
        failureTimeoutMs = (long) clamp(failureTimeoutMs, 1000, 10_000);
        edgeSafetyCost = clamp(edgeSafetyCost, 0.0, 10.0);
        wallSafetyCost = clamp(wallSafetyCost, 0.0, 5.0);
        faultMinCount = clamp(faultMinCount, 1.0, 5.0);
        faultMaxMultiplier = clamp(faultMaxMultiplier, 2.0, 60.0);
        faultPenaltyStrength = clamp(faultPenaltyStrength, 0.5, 20.0);
        faultHalfLifeHours = clamp(faultHalfLifeHours, 1.0, 24.0 * 30);
        aotvTeleportBlocks = (int) clamp(aotvTeleportBlocks, 4, 20);
        aotvMinGain = (int) clamp(aotvMinGain, 3, 40);
        if (aotvMode == null || aotvMode.isBlank()) {
            aotvMode = "OFF";
        }
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    public void save() {
        try {
            clamp();
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (Exception e) {
            System.out.println("[Pathfinder] Config nicht speicherbar: " + e.getMessage());
        }
    }
}
