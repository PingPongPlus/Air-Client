package com.coflnet.pathing;

import baritone.api.pathing.calc.CostPenalties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NPCs and idle mobs push the player away, but Baritone plans straight through
 * them (it only knows blocks), which ends in "walking into a wall". Entities
 * that have not moved for a few seconds are handed to Baritone as expensive
 * blocks, so the path goes around them. Moving entities (players walking by,
 * mobs) are left out: they are gone again before the bot gets there.
 */
public final class EntityObstacles {
    /** An entity counts as standing after this long without moving. */
    static final long STILL_MS = 3_000L;
    static final double STILL_DISTANCE_SQ = 0.15 * 0.15;
    static final double RANGE = 48.0;
    /** Strong, but not a ban: if there is no other way the bot still squeezes past. */
    static final double MULTIPLIER = 25.0;
    private static final int UPDATE_EVERY_TICKS = 10;

    private record Seen(double x, double y, double z, long since) {
    }

    private static final Map<Integer, Seen> SEEN = new HashMap<>();
    /** Snapshot read by Baritone's calculation thread: x, y, z triples. */
    private static volatile int[] blocks = new int[0];
    private static volatile int ignoredId = Integer.MIN_VALUE;
    private static int tickCounter;

    private EntityObstacles() {
    }

    /** The bot's own target is never an obstacle. Call every tick with the current target (or null). */
    public static void setTarget(Entity target) {
        ignoredId = target == null ? Integer.MIN_VALUE : target.getId();
    }

    static void tick(Minecraft client, long now) {
        if (++tickCounter % UPDATE_EVERY_TICKS != 0) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || !PathfinderConfig.get().avoidStandingEntities) {
            SEEN.clear();
            blocks = new int[0];
            return;
        }
        int ignored = ignoredId;
        Set<Integer> alive = new HashSet<>();
        List<int[]> found = new ArrayList<>();
        AABB area = player.getBoundingBox().inflate(RANGE);
        for (Entity entity : client.level.getEntities(player, area)) {
            if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand
                    || !living.isAlive() || !entity.isPushable() || entity.getId() == ignored) {
                continue;
            }
            int id = entity.getId();
            alive.add(id);
            Seen seen = SEEN.get(id);
            double dx = seen == null ? 0 : entity.getX() - seen.x();
            double dy = seen == null ? 0 : entity.getY() - seen.y();
            double dz = seen == null ? 0 : entity.getZ() - seen.z();
            if (seen == null || dx * dx + dy * dy + dz * dz > STILL_DISTANCE_SQ) {
                SEEN.put(id, new Seen(entity.getX(), entity.getY(), entity.getZ(), now));
                continue;
            }
            if (now - seen.since() >= STILL_MS) {
                addFootprint(entity.getBoundingBox(), found);
            }
        }
        SEEN.keySet().retainAll(alive);
        int[] packed = new int[found.size() * 3];
        for (int i = 0; i < found.size(); i++) {
            int[] block = found.get(i);
            packed[i * 3] = block[0];
            packed[i * 3 + 1] = block[1];
            packed[i * 3 + 2] = block[2];
        }
        blocks = packed;
    }

    /** Blocks the entity's box stands in: walking into them means walking into the entity. */
    private static void addFootprint(AABB box, List<int[]> out) {
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX - 1.0E-4);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4);
        int feet = Mth.floor(box.minY + 1.0E-4);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                out.add(new int[]{x, feet, z});
            }
        }
    }

    /** Baritone's calculation thread. */
    static void forEach(CostPenalties.Sink sink) {
        if (!PathfinderConfig.get().avoidStandingEntities) {
            return;
        }
        int[] snapshot = blocks;
        for (int i = 0; i + 2 < snapshot.length; i += 3) {
            sink.put(snapshot[i], snapshot[i + 1], snapshot[i + 2], MULTIPLIER);
        }
    }

    static int count() {
        return blocks.length / 3;
    }
}
