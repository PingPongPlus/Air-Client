package pingplus.voicechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Walks toward and looks at the player named in {@link PlayerSettings#followTarget}.
 * The target is re-resolved every tick, so the follow stays dynamic as they move.
 */
public final class FollowFeature {
    private static boolean following;
    private static UUID trackedTargetId;
    private static UUID idleLookTargetId;
    private static double lastTargetX;
    private static double lastTargetY;
    private static double lastTargetZ;
    private static long lastLookAt;

    public static void tick(Minecraft client) {
        var player = client.player;
        if (player == null || client.level == null) { release(client); return; }
        String name = PlayerSettings.followTarget.trim();
        var target = name.isEmpty() ? null : client.level.players().stream()
                .filter(p -> p != player && !p.isSpectator()
                    && p.getGameProfile().name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (target == null) { release(client); return; }

        boolean targetMoved = false;
        UUID targetId = target.getUUID();
        if (targetId.equals(trackedTargetId)) {
            double targetMoveX = target.getX() - lastTargetX;
            double targetMoveY = target.getY() - lastTargetY;
            double targetMoveZ = target.getZ() - lastTargetZ;
            targetMoved = targetMoveX * targetMoveX + targetMoveY * targetMoveY + targetMoveZ * targetMoveZ > 1.0E-4;
        }
        trackedTargetId = targetId;
        lastTargetX = target.getX();
        lastTargetY = target.getY();
        lastTargetZ = target.getZ();

        AbstractClientPlayer lookTarget = target;
        boolean shouldLook = targetMoved;
        if (PlayerSettings.followLookAtNearbyPlayers && !targetMoved) {
            var nearbyPlayers = client.level.players().stream()
                    .filter(p -> p != player && p != target && !p.isSpectator()
                            && p.distanceToSqr(player) <= 49.0)
                    .toList();
            if (!nearbyPlayers.isEmpty()) {
                lookTarget = nearbyPlayers.stream()
                        .filter(p -> p.getUUID().equals(idleLookTargetId))
                        .findFirst()
                        .orElseGet(() -> nearbyPlayers.get(ThreadLocalRandom.current().nextInt(nearbyPlayers.size())));
                idleLookTargetId = lookTarget.getUUID();
                shouldLook = true;
            } else {
                idleLookTargetId = null;
            }
        } else {
            idleLookTargetId = null;
        }

        double dx = lookTarget.getX() - player.getX();
        double dy = lookTarget.getEyeY() - player.getEyeY();
        double dz = lookTarget.getZ() - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        if (shouldLook) {
            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float targetPitch = (float) Math.toDegrees(-Math.atan2(dy, distXZ));
            long now = System.currentTimeMillis();
            double dt = lastLookAt == 0 ? 0.05
                    : Math.min(0.05, (now - lastLookAt) / 1000.0);
            lastLookAt = now;
            player.setYRot(AimSmoothing.step(player.getYRot(), targetYaw, 240.0f, dt));
            player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(),
                    targetPitch, 240.0f, dt), -90.0f, 90.0f));
            player.setYHeadRot(player.getYRot());
        }

        boolean forward = distXZ > 2.5;
        boolean sprint = forward && distXZ > 8.0;
        boolean jump = player.horizontalCollision
                || (target.getY() - player.getY() > 0.6 && player.onGround() && player.tickCount % 12 == 0);
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(sprint);
        client.options.keyJump.setDown(jump);
        following = true;
    }

    /** Releases the injected keys once, then resyncs the real keyboard state. */
    private static void release(Minecraft client) {
        trackedTargetId = null;
        idleLookTargetId = null;
        lastLookAt = 0;
        if (!following) return;
        following = false;
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        net.minecraft.client.KeyMapping.setAll();
    }

    private FollowFeature() {}
}
