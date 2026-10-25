package com.coflnet.lowballing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

public final class LowballingRotation {
    private static boolean active;
    private static double desiredYaw;
    private static double desiredPitch;
    private static long lastTickAt;

    private LowballingRotation() {
    }

    public static void lookAt(Entity target) {
        if (target == null) {
            return;
        }
        lookAt(target.getEyePosition());
    }

    public static void lookAt(Vec3 point) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || point == null) {
            return;
        }
        Vec3 delta = point.subtract(player.getEyePosition());
        double horizontal = Math.hypot(delta.x, delta.z);
        desiredYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        desiredPitch = Math.toDegrees(-Math.atan2(delta.y, horizontal));
        desiredPitch = Math.max(-80.0, Math.min(80.0, desiredPitch));
        active = true;
    }

    public static void tick(LocalPlayer player) {
        if (!active || player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        double dt = lastTickAt == 0 ? 0.05 : Math.min(0.15, (now - lastTickAt) / 1000.0);
        lastTickAt = now;

        double yawDelta = wrapDegrees(desiredYaw - player.getYRot());
        double pitchDelta = desiredPitch - player.getXRot();
        double random = 0.85 + ThreadLocalRandom.current().nextDouble(0.3);

        double maxYaw = Math.max(1.0, 190.0 * dt * random);
        double maxPitch = Math.max(0.8, 130.0 * dt * random);
        double yawStep = Math.max(-maxYaw, Math.min(maxYaw, yawDelta * 0.42));
        double pitchStep = Math.max(-maxPitch, Math.min(maxPitch, pitchDelta * 0.36));

        if (Math.abs(yawDelta) < 0.35) {
            yawStep = yawDelta;
        }
        if (Math.abs(pitchDelta) < 0.35) {
            pitchStep = pitchDelta;
        }

        player.setYRot((float) (player.getYRot() + yawStep));
        player.setXRot((float) Math.max(-89.0, Math.min(89.0, player.getXRot() + pitchStep)));
    }

    public static boolean isAligned(LocalPlayer player, Entity target, double toleranceDegrees) {
        if (player == null || target == null) {
            return false;
        }
        Vec3 delta = target.getEyePosition().subtract(player.getEyePosition());
        double horizontal = Math.hypot(delta.x, delta.z);
        double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        double pitch = Math.toDegrees(-Math.atan2(delta.y, horizontal));
        double yawDelta = Math.abs(wrapDegrees(yaw - player.getYRot()));
        double pitchDelta = Math.abs(pitch - player.getXRot());
        return yawDelta <= toleranceDegrees && pitchDelta <= toleranceDegrees * 1.6;
    }

    public static void clear() {
        active = false;
        lastTickAt = 0;
    }

    private static double wrapDegrees(double value) {
        value %= 360.0;
        if (value > 180.0) {
            value -= 360.0;
        } else if (value < -180.0) {
            value += 360.0;
        }
        return value;
    }
}
