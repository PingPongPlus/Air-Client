package pingplus.voicechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Automatically aims at one nearby entity and attacks when the normal cooldown is ready.
 *
 * Two separate update paths are intentional:
 * - tick() selects targets and attempts attacks at the client's game-tick rate.
 * - frame() eases the visible camera toward the target every rendered frame.
 * This keeps attack decisions tied to game ticks while making aiming smoother than
 * moving the camera only 20 times per second. Both callbacks run on the client thread,
 * so these shared fields do not need locks. This class does not rotate the camera secretly
 * or extend the player's normal attack reach.
 */
public final class KillAuraFeature {
    // Keep the current target between updates instead of switching to whichever entity
    // happens to be closest each frame. Reacquire only when this target becomes invalid.
    private static LivingEntity target;
    // Monotonic timestamp for frame-rate-independent rotation. Zero means no previous frame.
    private static long lastFrame;
    // Extra fully charged ticks to wait, sampled once after each attack.
    private static int extraCooldownTicks;
    // Set by Follow ("attack target"): only this entity is attacked, also while
    // the KillAura toggle itself is off. Null = normal KillAura behaviour.
    private static volatile java.util.function.Predicate<LivingEntity> forcedTarget;

    // Follow's own click speed for the forced target; 0 = normal attack cooldown.
    private static volatile double forcedCpsMin;
    private static volatile double forcedCpsMax;
    private static long nextForcedAttackAt;

    /** Restrict KillAura to one target (Follow), or null to go back to normal. */
    public static void setForcedTarget(java.util.function.Predicate<LivingEntity> filter) {
        setForcedTarget(filter, 0, 0);
    }

    /** Same with a click speed (attacks per second, random between min and max). */
    public static void setForcedTarget(java.util.function.Predicate<LivingEntity> filter,
                                       double cpsMin, double cpsMax) {
        if (filter == null && forcedTarget != null) {
            clear();
        }
        forcedCpsMin = cpsMin;
        forcedCpsMax = Math.max(cpsMin, cpsMax);
        forcedTarget = filter;
    }

    /** True while KillAura has an entity it aims at (Follow then leaves the camera alone). */
    public static boolean hasTarget() {
        return target != null;
    }

    /** Check the player and game state before accessing world data or taking control. */
    private static boolean active(Minecraft client) {
        var screen = client.gui.screen();
        // The toggle must be on, and the player, world and interaction controller must exist.
        // Those objects can be absent during startup, disconnects and world changes.
        return (PlayerSettings.killAura || forcedTarget != null) && client.player != null && client.level != null && client.gameMode != null
            // Chat and the player inventory do not suspend gameplay for KillAura.
            && (screen == null || screen instanceof ChatScreen || screen instanceof InventoryScreen)
            && client.gui.overlay() == null && !client.isPaused()
                // Dead players and spectators cannot fight. Keep aiming and attacking while blocking,
                // but pause for other item use so eating, drinking or charging a bow is not interrupted.
                && client.player.isAlive() && !client.player.isSpectator()
                && (!client.player.isUsingItem() || client.player.isBlocking());
    }

    /** Return the configured reach, limited to the player's actual interaction range. */
    private static double range(Minecraft client) {
        float configured = PlayerSettings.killAuraRange;
        // Reject NaN/infinity, use 3 blocks as the fallback, and constrain normal values to 1–4.
        // The outer minimum prevents a setting from granting extra reach. The held item's
        // own attack-range restrictions are checked separately in valid().
        return Math.min(Float.isFinite(configured) ? Math.clamp(configured, 1, 4) : 3,
                client.player.entityInteractionRange());
    }

    /** Measure from the player's eyes to the nearest point on the entity's hitbox. */
    private static double distanceSquared(Minecraft client, LivingEntity entity) {
        var eye = client.player.getEyePosition();
        var box = entity.getBoundingBox();
        // Clamping each coordinate to the box finds its closest point. Measuring to the
        // entity's center instead would unfairly reject large entities whose surface is in reach.
        // Keep distances squared: comparisons and sorting do not need a square root.
        return eye.distanceToSqr(new Vec3(Math.clamp(eye.x, box.minX, box.maxX),
                Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(eye.z, box.minZ, box.maxZ)));
    }

    /** Shared eligibility checks for acquiring, retaining and aiming at a target. */
    private static boolean valid(Minecraft client, LivingEntity entity) {
        // Reject missing targets, ourselves, stale entities from a previous world and dead entities.
        if (entity == null || entity == client.player || entity.level() != client.level || !entity.isAlive()
                // Removed entities no longer participate in the world. Pickable means Minecraft
                // allows the entity to be hit-tested; attackable is a separate eligibility flag.
                // Invisible entities are deliberately excluded from automatic targeting.
                || entity.isRemoved() || !entity.isPickable() || !entity.attackable() || entity.isInvisible()
                // Respect Minecraft's ally/team relationship and avoid entities sharing our vehicle.
                || entity.isAlliedTo(client.player) || entity.isPassengerOfSameVehicle(client.player)) return false;
        var forced = forcedTarget;
        if (forced != null) {
            // Follow target only; the player/mob toggles do not apply to it.
            if (!forced.test(entity)) return false;
            if (entity instanceof Player player && (player.isSpectator() || player.isCreative())) return false;
        } else if (entity instanceof Player player) {
            // Players have their own toggle; spectators and creative players are excluded.
            if (!PlayerSettings.killAuraPlayers || player.isSpectator() || player.isCreative()) return false;
        // Only Mob subclasses qualify for the mob toggle. This includes passive animals,
        // but excludes other living entities such as armor stands.
        } else if (!(entity instanceof Mob) || !PlayerSettings.killAuraMobs) return false;
        double reach = range(client);
        // All three conditions must hold: configured reach, the held item's actual attack range
        // (with zero extra allowance), and Minecraft's visibility check. tick() also traces the
        // exact attack ray, because visibility alone does not mean our current aim is unobstructed.
        return distanceSquared(client, entity) <= reach * reach
                && client.player.isWithinAttackRange(client.player.getMainHandItem(), entity.getBoundingBox(), 0)
                && client.player.hasLineOfSight(entity);
    }

    /** Called from the client end-tick callback to acquire a target and attempt one attack. */
    public static void tick(Minecraft client) {
        // Clear both target and timing when suspended so later updates cannot use stale state.
        if (!active(client)) { clear(); return; }
        if (!valid(client, target)) {
            double reach = range(client);
            // The expanded player box is a cheap broad search area. valid() then performs the
            // more precise hitbox-distance, target-type, weapon-range and visibility checks.
            target = client.level.getEntitiesOfClass(LivingEntity.class, client.player.getBoundingBox().inflate(reach),
                    entity -> valid(client, entity)).stream()
                    // Choose the nearest eligible entity. Entity ID breaks equal-distance ties
                    // deterministically rather than depending on the world's iteration order.
                    .min(Comparator.comparingDouble((LivingEntity entity) -> distanceSquared(client, entity))
                            .thenComparingInt(LivingEntity::getId)).orElse(null);
        }
        // A strength of 1 means fully recharged. Passing 0 samples the current tick without
        // adding a partial-tick prediction. Waiting avoids repeated low-strength attacks.
        boolean cpsMode = forcedTarget != null && forcedCpsMin > 0;
        if (cpsMode) {
            // Follow's configured click speed instead of the vanilla cooldown.
            if (System.currentTimeMillis() < nextForcedAttackAt) return;
        } else {
            if (client.player.getAttackStrengthScale(0) < 1f) return;
            // Add 50–100 ms at 20 TPS after the normal cooldown, even when switching targets.
            if (extraCooldownTicks > 0) { extraCooldownTicks--; return; }
        }
        if (target == null) return;
        Vec3 eye = client.player.getEyePosition();
        // Build a finite ray from the eyes along the CURRENT camera direction, not directly
        // toward the target. Aiming must actually reach the target before an attack is allowed.
        Vec3 end = eye.add(Vec3.directionFromRotation(client.player.getXRot(), client.player.getYRot()).scale(range(client)));
        // clip() returns the ray segment's intersection with the target's bounding box.
        // An empty result means we are still turning, aiming past it, or cannot reach it.
        var hit = target.getBoundingBox().clip(eye, end);
        if (hit.isEmpty()) return;
        double distance = eye.distanceToSqr(hit.get());
        // Trace collision shapes along the same ray. Ignore fluids, which should not act
        // like solid walls, and pass the player as the collision-shape context.
        var block = client.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        // A solid block at or before the entity intersection blocks the attack.
        if (block.getType() != HitResult.Type.MISS && eye.distanceToSqr(block.getLocation()) <= distance) return;
        // Search a padded box along the ray for other pickable entities. Padding broadens
        // the candidate search only; it does not increase the attack ray's length or hitbox size.
        for (var entity : client.level.getEntities(client.player, client.player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1),
                entity -> entity != target && entity.isPickable())) {
            var box = entity.getBoundingBox();
            // If another entity contains our eyes or intersects the ray nearer than the target,
            // it is in the way. Do not swing through it to reach the selected entity behind it.
            if (box.contains(eye) || box.clip(eye, end).filter(point -> eye.distanceToSqr(point) < distance).isPresent()) return;
        }
        // Use Minecraft's regular interaction controller so normal attack handling and
        // networking apply. A request does not guarantee damage: the server still decides.
        client.gameMode.attack(client.player, target);
        // Play the normal main-hand swing as well; the attack request and swing are separate calls.
        client.player.swing(InteractionHand.MAIN_HAND);
        extraCooldownTicks = ThreadLocalRandom.current().nextInt(1, 3);
        if (cpsMode) {
            double cps = forcedCpsMin + ThreadLocalRandom.current().nextDouble()
                    * Math.max(0, forcedCpsMax - forcedCpsMin);
            nextForcedAttackAt = System.currentTimeMillis() + Math.round(1000.0 / Math.max(0.5, cps));
        }
    }

    /** Called by KillAuraFrameMixin every rendered frame to smoothly update visible aiming. */
    public static void frame(Minecraft client) {
        // nanoTime measures elapsed time without being affected by system-clock adjustments.
        long now = System.nanoTime();
        // Convert nanoseconds to seconds. The first frame gets zero elapsed time to prevent
        // a large initial jump. AimSmoothing also caps long frame intervals after a stall.
        double dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9;
        lastFrame = now;
        // State can change between ticks (for example, opening a menu or losing a target).
        // Recheck before moving the camera. Target acquisition remains tick()'s responsibility.
        if (!active(client) || !valid(client, target)) { target = null; return; }
        // Aim at the center of the hitbox for a stable reference point. Subtracting our eye
        // position turns that world-space point into a direction relative to the player.
        Vec3 delta = target.getBoundingBox().getCenter().subtract(client.player.getEyePosition());
        // atan2 gives the horizontal heading in radians. Convert to degrees, then subtract
        // 90 to match Minecraft's yaw convention (yaw 0 faces positive Z).
        float yaw = (float)Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        // Pitch compares vertical displacement with horizontal distance. hypot computes
        // sqrt(x*x + z*z). Negate the angle because Minecraft uses negative pitch to look up.
        float pitch = (float)-Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        // The configured speed is a maximum number of degrees per second on each axis.
        float speed = PlayerSettings.killAuraTurnSpeed;
        // Ease toward the desired angles rather than snapping. AimSmoothing takes the short
        // path across the yaw wraparound and slows down near the target without overshooting.
        client.player.setYRot(AimSmoothing.step(client.player.getYRot(), yaw, speed, dt));
        // Clamp vertical rotation to Minecraft's valid looking range: straight up to straight down.
        client.player.setXRot(Math.clamp(AimSmoothing.step(client.player.getXRot(), pitch, speed, dt), -90, 90));
    }

    // Forget the selected entity and timing history, but leave the camera where it is.
    // This is also called immediately when the user switches the feature off in the GUI.
    public static void clear() { target = null; lastFrame = 0; extraCooldownTicks = 0; }

    // All state and entry points are static; prevent creating unused feature instances.
    private KillAuraFeature() {}
}
