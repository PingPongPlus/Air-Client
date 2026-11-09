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
    /** A single movement that takes longer than this is treated as failed. */
    public int movementTimeoutTicks = 20;

    /** Detect stuck situations and make Baritone avoid those blocks next time. */
    public boolean learnFromFaults = true;
    /** Cost multiplier added per fault at a block (1 fault = x(1+strength)). */
    public double faultPenaltyStrength = 3.0;
    /** Learned faults fade away with this half-life. */
    public double faultHalfLifeHours = 72.0;
    /** Write every detected fault to the chat. */
    public boolean showFaultsInChat = false;

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
                    loaded.clamp();
                    return loaded;
                }
            }
        } catch (Exception e) {
            System.out.println("[Pathfinder] Config nicht lesbar: " + e.getMessage());
        }
        return new PathfinderConfig();
    }

    void clamp() {
        jumpPenalty = clamp(jumpPenalty, 0.0, 20.0);
        costHeuristic = clamp(costHeuristic, 1.0, 5.0);
        movementTimeoutTicks = (int) clamp(movementTimeoutTicks, 5, 100);
        faultPenaltyStrength = clamp(faultPenaltyStrength, 0.5, 20.0);
        faultHalfLifeHours = clamp(faultHalfLifeHours, 1.0, 24.0 * 30);
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
