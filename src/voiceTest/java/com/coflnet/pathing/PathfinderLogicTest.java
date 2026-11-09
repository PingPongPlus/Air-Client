package com.coflnet.pathing;

public final class PathfinderLogicTest {
    private PathfinderLogicTest() {
    }

    public static void main(String[] args) {
        jumpPhysics();
        faultDecay();
        normalWalkingIsNoFault();
        hangingInAir();
        normalJumpIsNoFault();
        wall();
        oscillation();
        setback();
        failedJumps();
        System.out.println("PASS: jump physics, fault decay/penalty, stuck detection, failed jumps");
    }

    private static void jumpPhysics() {
        double vanilla = PlayerMovementStats.maxJumpHeight(0.42, 0.08);
        check(vanilla > 1.24 && vanilla < 1.26, "vanilla jump ~1.25, got " + vanilla);
        // Jump Boost II adds 0.2 jump power
        double boost2 = PlayerMovementStats.maxJumpHeight(0.62, 0.08);
        check(boost2 > 2.4 && boost2 < 2.6, "jump boost II ~2.5, got " + boost2);
        check(PlayerMovementStats.maxJumpHeight(0, 0.08) == 0, "no jump");
        check(Math.abs(PlayerMovementStats.VANILLA.speedPercent() - 100) < 1e-9, "vanilla speed 100%");
    }

    private static void faultDecay() {
        check(FaultMemory.decay(2.0, 0, 72) == 2.0, "fresh fault keeps its count");
        double half = FaultMemory.decay(2.0, 72L * 3_600_000L, 72);
        check(Math.abs(half - 1.0) < 1e-9, "one half-life halves, got " + half);
        check(FaultMemory.multiplier(1.0, 3.0) == 4.0, "one fault x4 at strength 3");
        check(FaultMemory.multiplier(0.1, 3.0) == 1.0, "faded fault has no effect");
        check(FaultMemory.multiplier(1000, 3.0) == 60.0, "capped at x60");
        check(FaultMemory.key(1, 2, 3) != FaultMemory.key(3, 2, 1), "key depends on order");
    }

    /** Walking straight towards the goal for 10 s: never a fault. */
    private static void normalWalkingIsNoFault() {
        StuckDetector detector = new StuckDetector();
        for (int t = 0; t < 200; t++) {
            check(detector.update(walk(t * 0.2, 0, 400 - t)) == null, "walking tick " + t);
        }
    }

    private static void hangingInAir() {
        StuckDetector detector = new StuckDetector();
        FaultMemory.Type found = null;
        for (int t = 0; t < 20 && found == null; t++) {
            found = detector.update(new StuckDetector.Sample(0, 65.3, 0, 0.0, false, false, false,
                    false, false, 300));
        }
        check(found == FaultMemory.Type.AIR_STUCK, "air stuck, got " + found);
    }

    /** A real jump has 1 tick near dy=0 at the top, that is no fault. */
    private static void normalJumpIsNoFault() {
        StuckDetector detector = new StuckDetector();
        double[] dys = {0.42, 0.33, 0.25, 0.16, 0.08, 0.003, -0.07, -0.15, -0.23, -0.30};
        double y = 64;
        for (int i = 0; i < dys.length; i++) {
            y += dys[i];
            boolean ground = i == dys.length - 1;
            check(detector.update(new StuckDetector.Sample(i * 0.2, y, 0, dys[i], ground, false, false,
                    false, false, 300 - i)) == null, "jump tick " + i);
        }
    }

    private static void wall() {
        StuckDetector detector = new StuckDetector();
        FaultMemory.Type found = null;
        for (int t = 0; t < 40 && found == null; t++) {
            found = detector.update(new StuckDetector.Sample(5.0, 64, 5.0, 0, true, false, false,
                    false, true, 300));
        }
        check(found == FaultMemory.Type.WALL, "wall, got " + found);
    }

    private static void oscillation() {
        StuckDetector detector = new StuckDetector();
        FaultMemory.Type found = null;
        for (int t = 0; t < 200 && found == null; t++) {
            double x = (t % 10 < 5) ? 0.4 : -0.4;
            // goal heuristic slowly "improving" by noise must not hide the pendulum
            found = detector.update(walk(x, 0, 300 - (t % 2)));
        }
        check(found == FaultMemory.Type.OSCILLATION, "oscillation, got " + found);
    }

    private static void setback() {
        StuckDetector detector = new StuckDetector();
        detector.update(walk(0, 0, 300));
        FaultMemory.Type found = detector.update(walk(6, 0, 300));
        check(found == FaultMemory.Type.SETBACK, "setback, got " + found);
    }

    /** Two jumps against a ledge that land on the same spot = JUMP_FAILED; a good one is fine. */
    private static void failedJumps() {
        StuckDetector detector = new StuckDetector();
        FaultMemory.Type found = null;
        for (int jump = 0; jump < 2 && found == null; jump++) {
            found = bounce(detector, 64.0);
        }
        check(found == FaultMemory.Type.JUMP_FAILED, "two failed jumps, got " + found);

        StuckDetector good = new StuckDetector();
        check(climb(good) == null, "successful jump onto a ledge is no fault");
    }

    /** Jump straight up and land on the same block again. */
    private static FaultMemory.Type bounce(StuckDetector d, double y) {
        FaultMemory.Type found = d.update(new StuckDetector.Sample(0, y, 0, 0, true, false, false, false, true, 300));
        double[] dys = {0.42, 0.33, 0.25, 0.16, 0.08, -0.08, -0.16, -0.25, -0.33, -0.42};
        double cy = y;
        for (int i = 0; i < dys.length && found == null; i++) {
            cy = Math.max(y, cy + dys[i]);
            boolean ground = i == dys.length - 1;
            found = d.update(new StuckDetector.Sample(0.05, ground ? y : cy, 0, ground ? 0 : dys[i], ground,
                    false, false, false, true, 300));
        }
        return found;
    }

    /** Jump forward and land one block higher. */
    private static FaultMemory.Type climb(StuckDetector d) {
        FaultMemory.Type found = null;
        for (int step = 0; step < 3 && found == null; step++) {
            double y = 64.0 + step;
            found = d.update(new StuckDetector.Sample(step, y, 0, 0, true, false, false, false, false, 300 - step * 5));
            double[] dys = {0.42, 0.33, 0.25, 0.16, 0.08, -0.05};
            double cy = y;
            for (int i = 0; i < dys.length && found == null; i++) {
                cy += dys[i];
                found = d.update(new StuckDetector.Sample(step + 0.15 * (i + 1), cy, 0, dys[i], false,
                        false, false, false, false, 300 - step * 5 - i));
            }
            if (found == null) {
                found = d.update(new StuckDetector.Sample(step + 1, y + 1, 0, 0, true, false, false, false,
                        false, 290 - step * 5));
            }
        }
        return found;
    }

    private static StuckDetector.Sample walk(double x, double z, double goal) {
        return new StuckDetector.Sample(x, 64, z, 0, true, false, false, false, false, goal);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
