package com.coflnet.pathing;

import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyBlock mobs carry their name ("[Lv10] Zombie 100/100❤") on an invisible
 * armor stand above them. Bots must chase and hit the real mob, never the
 * stand: when the mob dies the stand stays for a moment (or is moved away)
 * and a bot following it walks / looks into the sky.
 */
public final class SkyblockTargets {
    /** Horizontal / vertical window in which the mob stands under its nametag. */
    static final double UNDER_RADIUS_SQ = 1.5 * 1.5;
    static final double UNDER_MIN = -0.5;
    static final double UNDER_MAX = 3.5;
    /** Health text on the nametag: "0❤", "0/100❤", "1.2k/5k❤". */
    private static final Pattern HEALTH = Pattern.compile("([0-9][0-9.,]*[kKmMbB]?)(?:/[0-9][0-9.,]*[kKmMbB]?)?\\s*❤");

    private SkyblockTargets() {
    }

    /**
     * The entity to chase for a name match: the mob under a nametag stand, the
     * entity itself otherwise. Null for a stand without a living mob under it.
     */
    public static Entity resolve(Level level, Entity entity) {
        if (entity instanceof ArmorStand stand) {
            return mobUnder(level, stand);
        }
        return entity;
    }

    /** Living, non-stand, non-player entity right under the nametag stand. */
    public static LivingEntity mobUnder(Level level, ArmorStand stand) {
        AABB box = new AABB(stand.getX() - 1.5, stand.getY() - UNDER_MAX, stand.getZ() - 1.5,
                stand.getX() + 1.5, stand.getY() - UNDER_MIN, stand.getZ() + 1.5);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.getEntities(stand, box)) {
            if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand
                    || entity instanceof Player || isDead(living)) {
                continue;
            }
            double dx = entity.getX() - stand.getX();
            double dz = entity.getZ() - stand.getZ();
            double below = stand.getY() - entity.getY();
            if (dx * dx + dz * dz > UNDER_RADIUS_SQ || below < UNDER_MIN || below > UNDER_MAX) {
                continue;
            }
            double distance = dx * dx + dz * dz + below * below;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    /**
     * True when the target is gone for good: removed, dead or dying (death
     * animation), or its SkyBlock nametag already shows 0 health.
     */
    public static boolean isGone(Level level, Entity entity) {
        if (entity == null || entity.isRemoved() || !entity.isAlive() || entity.level() != level) {
            return true;
        }
        if (entity instanceof ArmorStand) {
            return true; // never a target on its own
        }
        if (entity instanceof LivingEntity living && isDead(living)) {
            return true;
        }
        Double health = nametagHealth(level, entity);
        return health != null && health <= 0;
    }

    static boolean isDead(LivingEntity living) {
        return !living.isAlive() || living.isDeadOrDying() || living.deathTime > 0;
    }

    /** Health shown on the nametag stand above the entity, null when there is none. */
    static Double nametagHealth(Level level, Entity entity) {
        if (entity instanceof Player) {
            return null;
        }
        AABB box = new AABB(entity.getX() - 1.5, entity.getY() + UNDER_MIN, entity.getZ() - 1.5,
                entity.getX() + 1.5, entity.getY() + UNDER_MAX + 0.5, entity.getZ() + 1.5);
        Double best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (!stand.hasCustomName()) {
                continue;
            }
            double dx = stand.getX() - entity.getX();
            double dz = stand.getZ() - entity.getZ();
            double distance = dx * dx + dz * dz;
            if (distance > UNDER_RADIUS_SQ || distance >= bestDistance) {
                continue;
            }
            Double health = parseHealth(stand.getName().getString());
            if (health != null) {
                best = health;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Current health from a nametag text, null when the text has none. */
    static Double parseHealth(String text) {
        if (text == null) {
            return null;
        }
        String plain = ChatFormatting.stripFormatting(text);
        if (plain == null) {
            return null;
        }
        Matcher matcher = HEALTH.matcher(plain);
        if (!matcher.find()) {
            return null;
        }
        return parseAmount(matcher.group(1));
    }

    static Double parseAmount(String raw) {
        String value = raw.replace(",", "");
        double factor = 1;
        char last = Character.toLowerCase(value.charAt(value.length() - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            factor = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Double.parseDouble(value) * factor;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
