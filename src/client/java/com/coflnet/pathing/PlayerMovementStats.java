package com.coflnet.pathing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Locale;

/**
 * Movement values of the local player that matter for pathing. Read from the
 * real attributes (jump strength, Jump Boost, gravity, step height, speed), so
 * Baritone plans with what the player can actually do right now.
 */
public record PlayerMovementStats(double jumpVelocity, double gravity, double jumpHeight,
                                  double stepHeight, double speed) {

    /** Plain vanilla player: jump 0.42, gravity 0.08, step 0.6, speed 0.1. */
    public static final PlayerMovementStats VANILLA = of(0.42, 0.08, 0.6, 0.1);

    public static PlayerMovementStats of(double jumpVelocity, double gravity,
                                         double stepHeight, double speed) {
        return new PlayerMovementStats(jumpVelocity, gravity,
                maxJumpHeight(jumpVelocity, gravity), stepHeight, speed);
    }

    /**
     * Highest point of a jump, simulated like vanilla physics:
     * each tick {@code y += v; v = (v - gravity) * 0.98}. Vanilla gives ~1.25.
     */
    public static double maxJumpHeight(double velocity, double gravity) {
        if (velocity <= 0) {
            return 0;
        }
        double g = gravity <= 0 ? 0.08 : gravity;
        double y = 0;
        double v = velocity;
        for (int tick = 0; tick < 200 && v > 0; tick++) {
            y += v;
            v = (v - g) * 0.98;
        }
        return y;
    }

    public static PlayerMovementStats read(LocalPlayer player) {
        if (player == null) {
            return VANILLA;
        }
        try {
            double jump = player.getAttributeValue(Attributes.JUMP_STRENGTH) + player.getJumpBoostPower();
            double gravity = player.getAttributeValue(Attributes.GRAVITY);
            double step = player.getAttributeValue(Attributes.STEP_HEIGHT);
            double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
            return of(jump, gravity, step, speed);
        } catch (RuntimeException e) {
            return VANILLA;
        }
    }

    /** Speed in percent of a vanilla player (0.1 = 100 %). */
    public double speedPercent() {
        return speed / 0.1 * 100.0;
    }

    public String summary() {
        return String.format(Locale.ROOT, "Jump %.2f Step %.1f %.0f%%",
                jumpHeight, stepHeight, speedPercent());
    }
}
