package com.coflnet.lowballing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.AimSmoothing;
import pingplus.voicechat.client.PlayerSettings;

/**
 * Frame-rate-independent aiming using the same easing path as KillAura:
 * target the hitbox centre, take the short yaw path and ease every rendered frame.
 */
public final class LowballingRotation {
    private static boolean active;
    private static Entity targetEntity;
    private static Vec3 targetPoint;
    private static long lastFrame;

    private LowballingRotation() {
    }

    public static void lookAt(Entity target) {
        if (target == null) {
            clear();
            return;
        }
        targetEntity = target;
        targetPoint = null;
        active = true;
    }

    public static void lookAt(Vec3 point) {
        if (point == null) {
            clear();
            return;
        }
        targetEntity = null;
        targetPoint = point;
        active = true;
    }

    /**
     * Called from the KillAuraFrameMixin every rendered frame. This is deliberately
     * not called from the 20 Hz client tick anymore, because that is what made the
     * old Lowballing rotation feel steppy.
     */
    public static void frame(LocalPlayer player) {
        if (!active || player == null) {
            return;
        }
        if (net.minecraft.client.Minecraft.getInstance().gui.screen() != null) {
            lastFrame = 0; // no catch-up jump after the GUI closes
            return;
        }
        Vec3 point = targetEntity != null
                ? targetEntity.getBoundingBox().getCenter() : targetPoint;
        if (point == null) {
            return;
        }
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9;
        lastFrame = now;
        Vec3 delta = point.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y,
                Math.hypot(delta.x, delta.z)));
        float speed = PlayerSettings.killAuraTurnSpeed;
        player.setYRot(AimSmoothing.step(player.getYRot(), yaw, speed, dt));
        player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), pitch, speed, dt),
                -90.0f, 90.0f));
    }

    public static boolean isAligned(LocalPlayer player, Entity target, double toleranceDegrees) {
        if (player == null || target == null) {
            return false;
        }
        Vec3 delta = target.getBoundingBox().getCenter()
                .subtract(player.getEyePosition());
        double horizontal = Math.hypot(delta.x, delta.z);
        double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        double pitch = Math.toDegrees(-Math.atan2(delta.y, horizontal));
        double yawDelta = Math.abs(wrapDegrees(yaw - player.getYRot()));
        double pitchDelta = Math.abs(pitch - player.getXRot());
        return yawDelta <= toleranceDegrees && pitchDelta <= toleranceDegrees * 1.6;
    }

    public static void clear() {
        active = false;
        targetEntity = null;
        targetPoint = null;
        lastFrame = 0;
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
