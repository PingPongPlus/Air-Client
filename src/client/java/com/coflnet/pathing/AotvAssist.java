package com.coflnet.pathing;

import baritone.api.IBaritone;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.etherwarp.EtherwarpHelper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Uses the Aspect of the Void (or End) while Baritone walks:
 * <ul>
 *   <li><b>Etherwarp</b> (Shift + right click): onto a visible, valid block of
 *       the path up to 57 blocks ahead.</li>
 *   <li><b>Teleport</b> (right click): straight ahead (default 12 blocks) when
 *       the path runs straight, level and free.</li>
 * </ul>
 * Baritone is paused for the few ticks of a warp (own high-priority process),
 * afterwards it plans again from the new position.
 */
public final class AotvAssist {

    public enum Mode { OFF, TELEPORT, ETHERWARP, BOTH }

    private enum Step { IDLE, PREPARE, AIM, USE, WAIT }

    private record Plan(boolean etherwarp, int slot, float yaw, float pitch, int gain) {
    }

    private static final String ID_AOTE = "ASPECT_OF_THE_END";
    private static final String ID_AOTV = "ASPECT_OF_THE_VOID";

    private static Step step = Step.IDLE;
    private static Plan plan;
    private static int stepTicks;
    private static int previousSlot = -1;
    private static boolean sneakOwned;
    private static Vec3 startPos;
    private static long cooldownUntil;
    private static int evaluateCounter;
    private static IBaritone registeredOn;
    private static int warps;

    /** Holds Baritone still while a warp is in progress. */
    private static final IBaritoneProcess PAUSE = new IBaritoneProcess() {
        @Override
        public boolean isActive() {
            return step != Step.IDLE;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        @Override
        public boolean isTemporary() {
            return true;
        }

        @Override
        public void onLostControl() {
        }

        @Override
        public double priority() {
            return 1_000.0;
        }

        @Override
        public String displayName0() {
            return "AOTV warp";
        }
    };

    private AotvAssist() {
    }

    /** True while aiming/warping: other camera control must stay out. */
    public static boolean isBusy() {
        return step != Step.IDLE;
    }

    public static int warps() {
        return warps;
    }

    public static Mode mode() {
        try {
            return Mode.valueOf(PathfinderConfig.get().aotvMode.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Mode.OFF;
        }
    }

    /** Called every client tick by {@link PathfinderSystem}. */
    static void tick(Minecraft client, IBaritone baritone) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || baritone == null) {
            abort(client);
            return;
        }
        if (registeredOn != baritone) {
            baritone.getPathingControlManager().registerProcess(PAUSE);
            registeredOn = baritone;
        }
        if (step != Step.IDLE) {
            runStep(client, player, baritone);
            return;
        }
        Mode mode = mode();
        long now = System.currentTimeMillis();
        if (mode == Mode.OFF || now < cooldownUntil || client.gui.screen() != null
                || com.coflnet.lowballing.LowballingMod.isRunning()
                || !baritone.getPathingBehavior().isPathing()
                || !player.onGround()
                || ++evaluateCounter % 5 != 0) {
            return;
        }
        Plan found = findPlan(client.level, player, baritone, mode);
        if (found != null) {
            plan = found;
            step = Step.PREPARE;
            stepTicks = 0;
        }
    }

    private static Plan findPlan(Level level, LocalPlayer player, IBaritone baritone, Mode mode) {
        IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null || executor.getPath() == null) {
            return null;
        }
        List<BetterBlockPos> positions = executor.getPath().positions();
        int from = Math.max(0, executor.getPosition());
        PathfinderConfig config = PathfinderConfig.get();
        Plan ether = null;
        Plan tele = null;
        if (mode == Mode.ETHERWARP || mode == Mode.BOTH) {
            int slot = hotbarSlot(player, true);
            if (slot >= 0) {
                ether = etherwarpPlan(level, player, positions, from, config.aotvMinGain, slot);
            }
        }
        if (mode == Mode.TELEPORT || mode == Mode.BOTH) {
            int slot = hotbarSlot(player, false);
            if (slot >= 0) {
                tele = teleportPlan(level, player, positions, from, config, slot);
            }
        }
        if (ether == null) {
            return tele;
        }
        if (tele == null) {
            return ether;
        }
        return ether.gain() >= tele.gain() ? ether : tele;
    }

    /** Farthest path point whose standing block can be seen and etherwarped onto. */
    private static Plan etherwarpPlan(Level level, LocalPlayer player, List<BetterBlockPos> positions,
                                      int from, int minGain, int slot) {
        Vec3 eye = player.getEyePosition();
        double maxSq = EtherwarpHelper.MAX_RANGE * EtherwarpHelper.MAX_RANGE;
        for (int i = positions.size() - 1; i >= from + minGain; i--) {
            BlockPos stand = positions.get(i).below();
            Vec3 aim = new Vec3(stand.getX() + 0.5, stand.getY() + 0.95, stand.getZ() + 0.5);
            if (aim.distanceToSqr(eye) > maxSq) {
                continue;
            }
            Vec3 look = aim.subtract(eye);
            Optional<EtherwarpHelper.RayHit> hit = EtherwarpHelper.raycast(level, eye, look, EtherwarpHelper.MAX_RANGE);
            if (hit.isEmpty() || !hit.get().pos().equals(stand) || !EtherwarpHelper.isValidTarget(level, stand)) {
                continue;
            }
            return new Plan(true, slot, yaw(look), pitch(look), i - from);
        }
        return null;
    }

    /** Straight, level and free part of the path about one teleport long. */
    private static Plan teleportPlan(Level level, LocalPlayer player, List<BetterBlockPos> positions,
                                     int from, PathfinderConfig config, int slot) {
        double reach = config.aotvTeleportBlocks;
        int feetY = player.blockPosition().getY();
        Vec3 start = player.position();
        for (int j = from + 1; j < positions.size(); j++) {
            BetterBlockPos p = positions.get(j);
            if (p.getY() != feetY) {
                return null; // path changes height before a full teleport
            }
            double dx = p.getX() + 0.5 - start.x;
            double dz = p.getZ() + 0.5 - start.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < reach - 1.0) {
                continue;
            }
            if (dist > reach + 0.6 || j - from < config.aotvMinGain) {
                return null;
            }
            // every path point up to here must lie on the straight line
            for (int k = from; k <= j; k++) {
                BetterBlockPos q = positions.get(k);
                if (distanceToLine(q.getX() + 0.5 - start.x, q.getZ() + 0.5 - start.z, dx / dist, dz / dist) > 0.8) {
                    return null;
                }
            }
            if (!lineFree(level, start, dx / dist, dz / dist, dist, feetY)) {
                return null;
            }
            BlockPos landing = BlockPos.containing(start.x + dx, feetY, start.z + dz);
            if (level.getBlockState(landing.below()).getCollisionShape(level, landing.below()).isEmpty()) {
                return null; // nothing to stand on after the teleport
            }
            Vec3 look = new Vec3(dx, 0, dz);
            return new Plan(false, slot, yaw(look), 0.0f, j - from);
        }
        return null;
    }

    private static double distanceToLine(double x, double z, double ux, double uz) {
        return Math.abs(x * uz - z * ux);
    }

    /** Feet and head blocks along the teleport line have no collision. */
    private static boolean lineFree(Level level, Vec3 start, double ux, double uz, double dist, int feetY) {
        for (double t = 0.5; t <= dist + 0.5; t += 0.5) {
            int x = (int) Math.floor(start.x + ux * t);
            int z = (int) Math.floor(start.z + uz * t);
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = new BlockPos(x, feetY + dy, z);
                if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void runStep(Minecraft client, LocalPlayer player, IBaritone baritone) {
        stepTicks++;
        switch (step) {
            case PREPARE -> {
                if (stepTicks == 1) {
                    previousSlot = player.getInventory().getSelectedSlot();
                    player.getInventory().setSelectedSlot(plan.slot());
                    if (plan.etherwarp()) {
                        client.options.keyShift.setDown(true);
                        sneakOwned = true;
                    }
                }
                // server needs a moment to see the new slot and the sneaking
                if (stepTicks >= 3) {
                    next(Step.AIM);
                }
            }
            case AIM -> {
                aim(player);
                if (stepTicks >= 2) {
                    next(Step.USE);
                }
            }
            case USE -> {
                aim(player);
                startPos = player.position();
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                next(Step.WAIT);
            }
            case WAIT -> {
                boolean moved = startPos != null && player.position().distanceTo(startPos) > 3.0;
                if (moved || stepTicks >= 12) {
                    finish(client, player, baritone, moved);
                }
            }
            default -> {
            }
        }
    }

    private static void aim(LocalPlayer player) {
        player.setYRot(plan.yaw());
        player.setXRot(plan.pitch());
        player.setYHeadRot(plan.yaw());
    }

    private static void next(Step nextStep) {
        step = nextStep;
        stepTicks = 0;
    }

    private static void finish(Minecraft client, LocalPlayer player, IBaritone baritone, boolean success) {
        restore(client, player);
        step = Step.IDLE;
        plan = null;
        long now = System.currentTimeMillis();
        if (success) {
            warps++;
            cooldownUntil = now + 1_200L;
        } else {
            // no teleport: no mana, wrong item, blocked - do not spam it
            cooldownUntil = now + 6_000L;
            System.out.println("[Pathfinder] AOTV ohne Wirkung - Pause 6s");
        }
        try {
            baritone.getPathingBehavior().forceCancel(); // plan again from the new position
        } catch (RuntimeException ignored) {
        }
    }

    private static void restore(Minecraft client, LocalPlayer player) {
        if (sneakOwned) {
            client.options.keyShift.setDown(false);
            sneakOwned = false;
            KeyMapping.setAll();
        }
        if (player != null && previousSlot >= 0) {
            player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    private static void abort(Minecraft client) {
        if (step != Step.IDLE) {
            restore(client, client.player);
            step = Step.IDLE;
            plan = null;
        }
    }

    /** Hotbar slot of an Aspect of the End/Void (with Etherwarp if {@code etherwarp}). */
    static int hotbarSlot(LocalPlayer player, boolean etherwarp) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (etherwarp ? EtherwarpHelper.isEtherwarpItem(stack) : isAspect(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAspect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = custom == null ? null : custom.copyTag();
            String id = tag == null ? "" : tag.getStringOr("id", "");
            return ID_AOTV.equals(id) || ID_AOTE.equals(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static float yaw(Vec3 look) {
        return (float) (Math.toDegrees(Math.atan2(look.z, look.x)) - 90.0);
    }

    private static float pitch(Vec3 look) {
        double flat = Math.sqrt(look.x * look.x + look.z * look.z);
        return (float) Math.toDegrees(-Math.atan2(look.y, flat));
    }
}
