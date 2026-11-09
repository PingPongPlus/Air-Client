package pingplus.voicechat.client;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IFollowProcess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Follows the player or entity named in {@link PlayerSettings#followTarget}
 * with Baritone's follow process (real pathing, keeps
 * {@link PlayerSettings#followDistance} blocks distance).
 *
 * <p>The name may be a player name, part of an entity's name ("Zealot",
 * also SkyBlock mobs whose name is on a nametag armor stand) or an entity type
 * ("zombie", "minecraft:zombie"). With {@link PlayerSettings#followAttack} the
 * followed target is attacked through KillAura, restricted to that one target.
 */
public final class FollowFeature {
    private static final double MAX_DISTANCE = 96.0;
    private static Entity followed;
    private static Entity issued;
    private static boolean active;
    private static int searchCooldown;
    private static UUID idleLookTargetId;
    private static long lastLookAt;

    private FollowFeature() {
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        String name = PlayerSettings.followTarget == null ? "" : PlayerSettings.followTarget.trim();
        if (player == null || client.level == null || name.isEmpty() || otherBotUsesBaritone()) {
            release();
            return;
        }
        if (!valid(followed, player)) {
            followed = null;
            if (searchCooldown-- > 0) {
                return;
            }
            searchCooldown = 10;
            followed = findTarget(client, player, name);
            if (followed == null) {
                stopBaritone();
                return;
            }
        }
        IBaritone baritone = primary();
        if (baritone == null) {
            return;
        }
        configure();
        IFollowProcess follow = baritone.getFollowProcess();
        Entity current = followed;
        if (issued != current || !follow.isActive()) {
            follow.follow(entity -> entity == current);
            issued = current;
        }
        active = true;

        LivingEntity attack = PlayerSettings.followAttack ? attackable(current) : null;
        KillAuraFeature.setForcedTarget(attack == null ? null : entity -> entity == attack);
        if (attack == null && PlayerSettings.followLookAtNearbyPlayers) {
            lookAtNearbyPlayer(client, player, current);
        }
    }

    /** Player name first, then entity name / nametag / type, nearest one. */
    static Entity findTarget(Minecraft client, LocalPlayer player, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Player other : client.level.players()) {
            if (other != player && !other.isSpectator()
                    && other.getGameProfile().name().equalsIgnoreCase(name)) {
                return other;
            }
        }
        Entity best = null;
        double bestDistance = MAX_DISTANCE * MAX_DISTANCE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == player || !entity.isAlive() || entity.isRemoved()) {
                continue;
            }
            if (!nameMatches(entity, wanted)) {
                continue;
            }
            double distance = player.distanceToSqr(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best instanceof ArmorStand stand ? mobUnderNametag(client, stand) : best;
    }

    static boolean nameMatches(Entity entity, String wanted) {
        String shown = ChatFormatting.stripFormatting(entity.getName().getString());
        if (shown != null && shown.toLowerCase(Locale.ROOT).contains(wanted)) {
            return true;
        }
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        return type.equals(wanted) || ("minecraft:" + type).equals(wanted);
    }

    /**
     * SkyBlock shows mob names on an invisible armor stand above the mob. Follow
     * (and attack) the real mob under it; without one, the stand itself.
     */
    private static Entity mobUnderNametag(Minecraft client, ArmorStand stand) {
        Entity best = stand;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand
                    || entity instanceof Player || !living.isAlive()) {
                continue;
            }
            double dx = entity.getX() - stand.getX();
            double dz = entity.getZ() - stand.getZ();
            double below = stand.getY() - entity.getY();
            if (dx * dx + dz * dz > 2.25 || below < -0.5 || below > 3.5) {
                continue;
            }
            double distance = dx * dx + dz * dz + below * below;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private static LivingEntity attackable(Entity entity) {
        return entity instanceof LivingEntity living && !(entity instanceof ArmorStand) ? living : null;
    }

    private static boolean valid(Entity entity, LocalPlayer player) {
        return entity != null && entity.isAlive() && !entity.isRemoved()
                && entity.level() == player.level()
                && player.distanceToSqr(entity) < MAX_DISTANCE * MAX_DISTANCE;
    }

    /** The lowballer and the mob pathfinder steer Baritone themselves. */
    private static boolean otherBotUsesBaritone() {
        if (com.coflnet.lowballing.LowballingMod.isRunning()) {
            return true;
        }
        com.coflnet.ahflipper.AhAutomationConfig mob = com.coflnet.ahflipper.AhAutomationConfig.get();
        return mob.enabled && !mob.targetName.isBlank();
    }

    private static void configure() {
        baritone.api.Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.antiCheatCompatibility.value = true;
        s.followRadius.value = Math.clamp(PlayerSettings.followDistance, 1, 16);
        s.followOffsetDistance.value = 0.0;
        // While attacking KillAura aims the camera, Baritone must not fight it.
        s.freeLook.value = PlayerSettings.followAttack;
        com.coflnet.pathing.PathfinderTuning.apply(s, false);
    }

    /** Old "look at nearby players" while the followed player stands next to us. */
    private static void lookAtNearbyPlayer(Minecraft client, LocalPlayer player, Entity target) {
        if (!(target instanceof Player) || player.distanceToSqr(target) > Math.pow(PlayerSettings.followDistance + 1.5, 2)) {
            idleLookTargetId = null;
            return;
        }
        List<Player> nearby = client.level.players().stream()
                .filter(p -> p != player && p != target && !p.isSpectator() && p.distanceToSqr(player) <= 49.0)
                .map(p -> (Player) p)
                .toList();
        if (nearby.isEmpty()) {
            idleLookTargetId = null;
            return;
        }
        Player look = nearby.stream().filter(p -> p.getUUID().equals(idleLookTargetId)).findFirst()
                .orElseGet(() -> nearby.get(ThreadLocalRandom.current().nextInt(nearby.size())));
        idleLookTargetId = look.getUUID();
        double dx = look.getX() - player.getX();
        double dy = look.getEyeY() - player.getEyeY();
        double dz = look.getZ() - player.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        long now = System.currentTimeMillis();
        double dt = lastLookAt == 0 ? 0.05 : Math.min(0.05, (now - lastLookAt) / 1000.0);
        lastLookAt = now;
        player.setYRot(AimSmoothing.step(player.getYRot(), yaw, 240.0f, dt));
        player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), pitch, 240.0f, dt), -90.0f, 90.0f));
        player.setYHeadRot(player.getYRot());
    }

    private static IBaritone primary() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void stopBaritone() {
        if (!active) {
            return;
        }
        IBaritone baritone = primary();
        if (baritone != null && issued != null) {
            baritone.getFollowProcess().cancel();
        }
        issued = null;
        active = false;
        KillAuraFeature.setForcedTarget(null);
    }

    private static void release() {
        stopBaritone();
        followed = null;
        searchCooldown = 0;
        idleLookTargetId = null;
        lastLookAt = 0;
    }
}
