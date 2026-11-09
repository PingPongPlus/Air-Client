package com.coflnet.pathing;

/**
 * Recognises the typical ways a path run goes wrong, from one cheap sample per
 * tick (no world access, a fixed-size ring buffer): hanging in the air, walking
 * into a wall, moving back and forth, no progress and server setbacks.
 */
final class StuckDetector {

    /** One tick of the player while Baritone is pathing. */
    record Sample(double x, double y, double z, double dy, boolean onGround, boolean inLiquid,
                  boolean climbing, boolean flying, boolean horizontalCollision,
                  double goalHeuristic) {
    }

    static final int AIR_TICKS = 8;
    static final int WALL_TICKS = 20;
    static final int WINDOW = 100;
    static final int NO_PROGRESS_TICKS = 160;
    static final double OSCILLATION_RADIUS = 1.0;
    static final double SETBACK_DISTANCE = 2.5;
    /** Below this Baritone heuristic the goal is considered reached / too close to judge. */
    static final double NEAR_GOAL = 10.0;
    /** Failed jumps (landed on the same spot, no height gained) that make a fault. */
    static final int FAILED_JUMPS = 2;
    static final int FAILED_JUMP_WINDOW = 120;

    private final double[] xs = new double[WINDOW];
    private final double[] zs = new double[WINDOW];
    private int index;
    private int filled;
    private int ticks;
    private int airTicks;
    private int wallTicks;
    private int noProgressTicks;
    private double bestGoal = Double.MAX_VALUE;
    private boolean hasLast;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean lastOnGround = true;
    private boolean airborne;
    private double jumpX;
    private double jumpY;
    private double jumpZ;
    private int failedJumps;
    private int lastFailTick;

    void reset() {
        index = 0;
        filled = 0;
        ticks = 0;
        airTicks = 0;
        wallTicks = 0;
        noProgressTicks = 0;
        bestGoal = Double.MAX_VALUE;
        hasLast = false;
        lastOnGround = true;
        airborne = false;
        failedJumps = 0;
        lastFailTick = 0;
    }

    /** @return the fault seen in this tick, or null */
    FaultMemory.Type update(Sample s) {
        ticks++;
        FaultMemory.Type found = null;
        if (hasLast) {
            double dx = s.x() - lastX;
            double dy = s.y() - lastY;
            double dz = s.z() - lastZ;
            double jump = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (jump > SETBACK_DISTANCE) {
                found = FaultMemory.Type.SETBACK;
            }
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            wallTicks = s.horizontalCollision() && horizontal < 0.03 ? wallTicks + 1 : 0;
        }
        found = found != null ? found : checkJump(s);
        boolean hanging = !s.onGround() && !s.inLiquid() && !s.climbing() && !s.flying()
                && Math.abs(s.dy()) < 0.005;
        airTicks = hanging ? airTicks + 1 : 0;

        xs[index] = s.x();
        zs[index] = s.z();
        index = (index + 1) % WINDOW;
        filled = Math.min(WINDOW, filled + 1);

        boolean farFromGoal = s.goalHeuristic() > NEAR_GOAL;
        if (s.goalHeuristic() < bestGoal - 1.0) {
            bestGoal = s.goalHeuristic();
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (found == null && airTicks >= AIR_TICKS) {
            found = FaultMemory.Type.AIR_STUCK;
        } else if (found == null && wallTicks >= WALL_TICKS) {
            found = FaultMemory.Type.WALL;
        } else if (found == null && farFromGoal && filled == WINDOW && spread() < OSCILLATION_RADIUS) {
            found = FaultMemory.Type.OSCILLATION;
        } else if (found == null && farFromGoal && noProgressTicks >= NO_PROGRESS_TICKS) {
            found = FaultMemory.Type.NO_PROGRESS;
        }

        lastX = s.x();
        lastY = s.y();
        lastZ = s.z();
        lastOnGround = s.onGround();
        hasLast = true;
        if (found != null) {
            // start over after a fault; the path gets recalculated anyway
            double x = lastX;
            double y = lastY;
            double z = lastZ;
            reset();
            lastX = x;
            lastY = y;
            lastZ = z;
            hasLast = true;
        }
        return found;
    }

    /**
     * A jump that lands (almost) where it started without gaining height is a
     * failed jump: hit the edge of the ledge, bonked the head, slid off. Two of
     * them close together mean this jump does not work here.
     */
    private FaultMemory.Type checkJump(Sample s) {
        if (s.inLiquid() || s.climbing() || s.flying()) {
            airborne = false;
            return null;
        }
        if (lastOnGround && !s.onGround() && s.dy() > 0.2) {
            airborne = true;
            jumpX = s.x();
            jumpY = s.y();
            jumpZ = s.z();
            return null;
        }
        if (airborne && s.onGround()) {
            airborne = false;
            double dx = s.x() - jumpX;
            double dz = s.z() - jumpZ;
            boolean noHeight = s.y() <= jumpY + 0.3;
            boolean samePlace = dx * dx + dz * dz < 0.7 * 0.7;
            if (noHeight && samePlace) {
                if (ticks - lastFailTick > FAILED_JUMP_WINDOW) {
                    failedJumps = 0;
                }
                failedJumps++;
                lastFailTick = ticks;
                if (failedJumps >= FAILED_JUMPS) {
                    failedJumps = 0;
                    return FaultMemory.Type.JUMP_FAILED;
                }
            }
        }
        return null;
    }

    /** Largest distance of the last WINDOW positions from their centre. */
    private double spread() {
        double cx = 0;
        double cz = 0;
        for (int i = 0; i < filled; i++) {
            cx += xs[i];
            cz += zs[i];
        }
        cx /= filled;
        cz /= filled;
        double max = 0;
        for (int i = 0; i < filled; i++) {
            double dx = xs[i] - cx;
            double dz = zs[i] - cz;
            max = Math.max(max, Math.sqrt(dx * dx + dz * dz));
        }
        return max;
    }
}
