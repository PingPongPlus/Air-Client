package com.coflnet.pathing;

import baritone.api.pathing.calc.CostPenalties;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks where the bot got stuck, per world. Each fault raises the cost of
 * moving into that block for the next path calculations; faults fade with a
 * half-life so a block that was only bad once (lag, a player in the way) is
 * used again later. Cheap: one map lookup per block while Baritone searches,
 * and only the blocks of the current world are handed over.
 */
public final class FaultMemory {

    public enum Type {
        /** Hangs in the air after a jump (ghost block / lag). */
        AIR_STUCK,
        /** Keeps walking into a wall without moving. */
        WALL,
        /** Moves back and forth on the same spot. */
        OSCILLATION,
        /** No progress towards the goal for a long time. */
        NO_PROGRESS,
        /** The server teleported the player back (anticheat setback). */
        SETBACK,
        /** Jumped and landed again on the same spot without getting up (bonk / edge). */
        JUMP_FAILED
    }

    public static final class Fault {
        public int x;
        public int y;
        public int z;
        public String type = "";
        public String movement = "";
        /** Number of faults, already including the decay up to {@link #lastAt}. */
        public double count;
        public long lastAt;
        /**
         * Set once the block failed often enough (see faultMinCount). A single
         * fault (lag, a player in the way) only counts once it happens again.
         */
        public boolean confirmed;
    }

    private static final int MAX_FAULTS = 3000;
    private static final double MIN_EFFECTIVE = 0.15;
    private static final double MAX_MULTIPLIER = 60.0;
    /** A repeat within about one half-life confirms a block (2 faults -> 1.5+ after decay). */
    private static final double CONFIRM_SLACK = 0.5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** world key -> (block hash -> fault) */
    private static final Map<String, Map<Long, Fault>> WORLDS = new ConcurrentHashMap<>();
    private static volatile String activeWorld = "";
    private static volatile boolean dirty;
    private static volatile boolean loaded;
    private static final java.util.concurrent.atomic.AtomicBoolean SAVING = new java.util.concurrent.atomic.AtomicBoolean();

    private FaultMemory() {
    }

    /** Hands the learned penalties of the current world (and standing entities) to Baritone. */
    static void install() {
        CostPenalties.set(sink -> {
            forEachActive(sink);
            EntityObstacles.forEach(sink);
        });
    }

    static void setActiveWorld(String key) {
        activeWorld = key == null ? "" : key;
    }

    public static String activeWorld() {
        return activeWorld;
    }

    /** Same packing as Baritone's BetterBlockPos.longHash (for a stable map key). */
    static long key(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }

    /** {@code count} after {@code ageMs} with the given half-life. */
    static double decay(double count, long ageMs, double halfLifeHours) {
        if (count <= 0) {
            return 0;
        }
        if (ageMs <= 0 || halfLifeHours <= 0) {
            return count;
        }
        double halfLives = ageMs / (halfLifeHours * 3_600_000.0);
        return count * Math.pow(0.5, halfLives);
    }

    /** Cost factor for a block with this many (decayed) faults. */
    static double multiplier(double effectiveCount, double strength) {
        return multiplier(effectiveCount, strength, MAX_MULTIPLIER);
    }

    static double multiplier(double effectiveCount, double strength, double max) {
        if (effectiveCount < MIN_EFFECTIVE) {
            return 1.0;
        }
        return Math.min(Math.min(MAX_MULTIPLIER, max), 1.0 + Math.max(0, strength) * effectiveCount);
    }

    /** True once a block failed {@code minCount} times close together. */
    static boolean confirms(double count, double minCount) {
        return count >= minCount - CONFIRM_SLACK;
    }

    /** Stores a fault and returns the new cost factor for that block. */
    static double record(String world, int x, int y, int z, Type type, String movement, long now) {
        PathfinderConfig config = PathfinderConfig.get();
        Map<Long, Fault> faults = WORLDS.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
        Fault fault = faults.computeIfAbsent(key(x, y, z), ignored -> {
            Fault created = new Fault();
            created.x = x;
            created.y = y;
            created.z = z;
            return created;
        });
        synchronized (fault) {
            fault.count = decay(fault.count, now - fault.lastAt, config.faultHalfLifeHours) + 1.0;
            fault.lastAt = now;
            fault.type = type.name();
            fault.movement = movement == null ? "" : movement;
            if (confirms(fault.count, config.faultMinCount)) {
                fault.confirmed = true;
            }
        }
        dirty = true;
        trim();
        return fault.confirmed
                ? multiplier(fault.count, config.faultPenaltyStrength, config.faultMaxMultiplier)
                : 1.0;
    }

    /** Baritone's calculation thread: penalties of the current world only. */
    static void forEachActive(CostPenalties.Sink sink) {
        PathfinderConfig config = PathfinderConfig.get();
        if (!config.learnFromFaults) {
            return;
        }
        Map<Long, Fault> faults = WORLDS.get(activeWorld);
        if (faults == null || faults.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Fault fault : faults.values()) {
            if (!fault.confirmed && !confirms(fault.count, config.faultMinCount)) {
                continue;
            }
            double effective = decay(fault.count, now - fault.lastAt, config.faultHalfLifeHours);
            double factor = multiplier(effective, config.faultPenaltyStrength, config.faultMaxMultiplier);
            if (factor > 1.0) {
                sink.put(fault.x, fault.y, fault.z, factor);
            }
        }
    }

    public static int size() {
        int total = 0;
        for (Map<Long, Fault> faults : WORLDS.values()) {
            total += faults.size();
        }
        return total;
    }

    public static int activeSize() {
        Map<Long, Fault> faults = WORLDS.get(activeWorld);
        return faults == null ? 0 : faults.size();
    }

    public static void clear() {
        WORLDS.clear();
        dirty = true;
        saveIfDirty();
    }

    /** Drops faded faults and, above the limit, the oldest ones. */
    private static void trim() {
        if (size() <= MAX_FAULTS) {
            return;
        }
        PathfinderConfig config = PathfinderConfig.get();
        long now = System.currentTimeMillis();
        List<Map.Entry<Map<Long, Fault>, Map.Entry<Long, Fault>>> all = new ArrayList<>();
        for (Map<Long, Fault> faults : WORLDS.values()) {
            faults.entrySet().removeIf(entry -> decay(entry.getValue().count,
                    now - entry.getValue().lastAt, config.faultHalfLifeHours) < MIN_EFFECTIVE);
            for (Map.Entry<Long, Fault> entry : faults.entrySet()) {
                all.add(Map.entry(faults, entry));
            }
        }
        if (all.size() <= MAX_FAULTS) {
            return;
        }
        all.sort(Comparator.comparingLong(entry -> entry.getValue().getValue().lastAt));
        for (int i = 0; i < all.size() - MAX_FAULTS; i++) {
            all.get(i).getKey().remove(all.get(i).getValue().getKey());
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("pathfinder-faults.json");
    }

    static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            Map<String, List<Fault>> stored = GSON.fromJson(Files.readString(path),
                    new TypeToken<Map<String, List<Fault>>>() {
                    }.getType());
            if (stored == null) {
                return;
            }
            for (Map.Entry<String, List<Fault>> world : stored.entrySet()) {
                Map<Long, Fault> faults = new ConcurrentHashMap<>();
                for (Fault fault : world.getValue()) {
                    faults.put(key(fault.x, fault.y, fault.z), fault);
                }
                WORLDS.put(world.getKey(), faults);
            }
        } catch (Exception e) {
            System.out.println("[Pathfinder] Fehler-Speicher nicht lesbar: " + e.getMessage());
        }
    }

    /** Writes on a background thread so the game never waits for the disk. */
    static void saveIfDirty() {
        if (!dirty || !SAVING.compareAndSet(false, true)) {
            return;
        }
        dirty = false;
        Thread.startVirtualThread(() -> {
            try {
                save();
            } finally {
                SAVING.set(false);
            }
        });
    }

    /** Synchronous save for shutdown. */
    static void saveNow() {
        if (dirty) {
            dirty = false;
            save();
        }
    }

    private static void save() {
        try {
            Map<String, List<Fault>> stored = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Map<Long, Fault>> world : WORLDS.entrySet()) {
                if (!world.getValue().isEmpty()) {
                    stored.put(world.getKey(), new ArrayList<>(world.getValue().values()));
                }
            }
            Path path = file();
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(stored));
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dirty = true;
            System.out.println("[Pathfinder] Fehler-Speicher nicht speicherbar: " + e.getMessage());
        }
    }
}
