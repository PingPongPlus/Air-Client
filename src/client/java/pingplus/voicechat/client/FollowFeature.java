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
    /** Where the camera should look (set per tick, applied smoothly per frame). */
    private static boolean hasLook;
    private static float lookYaw;
    private static float lookPitch;
    private static long lastFrameNanos;
    /** Degrees per second for the walking look. */
    private static final float LOOK_SPEED = 260.0f;

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
        KillAuraFeature.setForcedTarget(attack == null ? null : entity -> entity == attack,
                PlayerSettings.followCpsMin, PlayerSettings.followCpsMax);
        updateLook(client, player, baritone, current, attack != null);
    }

    /**
     * Walking: look where we go (a point a bit ahead on the path). Standing at
     * the target: look at it (or at nearby players). While KillAura has the
     * target in range it aims itself and this stays out of the way.
     */
    private static void updateLook(Minecraft client, LocalPlayer player, IBaritone baritone,
                                   Entity target, boolean attacking) {
        if (attacking && KillAuraFeature.hasTarget()) {
            hasLook = false;
            return;
        }
        net.minecraft.world.phys.Vec3 ahead = baritone.getPathingBehavior().isPathing()
                ? pathAhead(baritone, player) : null;
        if (ahead != null) {
            setLook(player, ahead);
            return;
        }
        if (!attacking && PlayerSettings.followLookAtNearbyPlayers && lookAtNearbyPlayer(client, player, target)) {
            return;
        }
        setLook(player, new net.minecraft.world.phys.Vec3(target.getX(),
                target instanceof LivingEntity living ? living.getEyeY() : target.getY(), target.getZ()));
    }

    private static void setLook(LocalPlayer player, net.minecraft.world.phys.Vec3 point) {
        double dx = point.x - player.getX();
        double dy = point.y - player.getEyeY();
        double dz = point.z - player.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.3 && Math.abs(dy) < 0.3) {
            return;
        }
        lookYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        lookPitch = Math.clamp((float) Math.toDegrees(-Math.atan2(dy, flat)), -60.0f, 60.0f);
        hasLook = true;
    }

    /** First path point at least ~1.5 blocks away, at eye height (natural, level look). */
    private static net.minecraft.world.phys.Vec3 pathAhead(IBaritone baritone, LocalPlayer player) {
        try {
            baritone.api.pathing.path.IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor == null || executor.getPath() == null) {
                return null;
            }
            List<baritone.api.utils.BetterBlockPos> positions = executor.getPath().positions();
            for (int i = Math.max(0, executor.getPosition() + 1); i < positions.size(); i++) {
                baritone.api.utils.BetterBlockPos pos = positions.get(i);
                double dx = pos.getX() + 0.5 - player.getX();
                double dz = pos.getZ() + 0.5 - player.getZ();
                if (dx * dx + dz * dz > 2.25) {
                    return new net.minecraft.world.phys.Vec3(pos.getX() + 0.5,
                            Math.max(player.getEyeY() - 0.4, pos.getY() + 1.4), pos.getZ() + 0.5);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Every rendered frame: ease the camera towards the wanted look. */
    public static void frame(Minecraft client) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        LocalPlayer player = client.player;
        if (!active || !hasLook || player == null || client.gui.screen() != null) {
            return;
        }
        player.setYRot(AimSmoothing.step(player.getYRot(), lookYaw, LOOK_SPEED, dt));
        player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), lookPitch, LOOK_SPEED, dt), -90.0f, 90.0f));
        player.setYHeadRot(player.getYRot());
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
        // The camera is steered here (walking direction) or by KillAura, never by Baritone.
        s.freeLook.value = true;
        com.coflnet.pathing.PathfinderTuning.apply(s, false);
    }

    /** "Look at nearby players" while the followed player stands next to us. */
    private static boolean lookAtNearbyPlayer(Minecraft client, LocalPlayer player, Entity target) {
        if (!(target instanceof Player) || player.distanceToSqr(target) > Math.pow(PlayerSettings.followDistance + 1.5, 2)) {
            idleLookTargetId = null;
            return false;
        }
        List<Player> nearby = client.level.players().stream()
                .filter(p -> p != player && p != target && !p.isSpectator() && p.distanceToSqr(player) <= 49.0)
                .map(p -> (Player) p)
                .toList();
        if (nearby.isEmpty()) {
            idleLookTargetId = null;
            return false;
        }
        Player look = nearby.stream().filter(p -> p.getUUID().equals(idleLookTargetId)).findFirst()
                .orElseGet(() -> nearby.get(ThreadLocalRandom.current().nextInt(nearby.size())));
        idleLookTargetId = look.getUUID();
        setLook(player, new net.minecraft.world.phys.Vec3(look.getX(), look.getEyeY(), look.getZ()));
        return true;
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
        hasLook = false;
        KillAuraFeature.setForcedTarget(null);
    }

    private static void release() {
        stopBaritone();
        followed = null;
        searchCooldown = 0;
        idleLookTargetId = null;
        lastFrameNanos = 0;
    }
}
