package pingplus.voicechat.client.etherwarp;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Etherwarp helper, following SkyHanni's approach
 * ({@code SkyBlockItemModifierUtils.hasEtherwarp} + {@code BlockUtils.raycast}):
 *
 * <ul>
 *   <li>Only active while sneaking (Shift held); otherwise no overlay at all.</li>
 *   <li>Item detection via Hypixel NBT ({@code id} + {@code ethermerge} flag inside
 *       the {@code custom_data} component), with item-lore fallback ("etherwarp").</li>
 *   <li>Voxel raycast (DDA) from the player's eye along the look vector. Every block
 *       is tested as a full voxel; blacklisted pass-through blocks (fluids, plants,
 *       torches, carpets, cobwebs, ...) are skipped. Stops at the first other block,
 *       max 57 blocks.</li>
 *   <li>Target validation is a pure blacklist: every hit block is a valid target
 *       (green) unless it is water, lava, carpet, torch, cobweb, grass/flower-like
 *       or tripwire. Slabs, stairs, fences, chests, leaves etc. are all valid.</li>
 * </ul>
 *
 * <p>Client-side only, recomputed at most every 3rd client tick. The result is
 * block-quantized and cached, so the overlay renderer never flickers.
 */
public final class EtherwarpHelper {
    /** Etherwarp range on Hypixel. */
    public static final double MAX_RANGE = 57.0;
    /** Recompute at most every Nth client tick (performance). */
    private static final int TICK_INTERVAL = 3;
    /** Hypixel internal item ids of the Aspect of the End / Void. */
    private static final String ID_AOTE = "ASPECT_OF_THE_END";
    private static final String ID_AOTV = "ASPECT_OF_THE_VOID";

    /** Cached computation for the overlay renderer (client thread only). */
    private static Result cached = Result.INACTIVE;
    private static int tickCounter;

    /** Immutable computation result. */
    public record Result(boolean active, BlockPos target, boolean valid, double distance) {
        public static final Result INACTIVE = new Result(false, null, false, -1);
    }

    /** Ray hit: first solid voxel plus entry distance. */
    public record RayHit(BlockPos pos, double distance) {}

    private EtherwarpHelper() {}

    /** Throttled per-tick update, called from the client tick hook. */
    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) { cached = Result.INACTIVE; return; }
        if (!pingplus.voicechat.client.PlayerSettings.etherwarpHelper) { cached = Result.INACTIVE; return; }
        if (!client.player.isShiftKeyDown()) { cached = Result.INACTIVE; return; }
        if (tickCounter++ % TICK_INTERVAL != 0) return;
        try {
            Player player = client.player;
            ItemStack held = etherwarpItem(player);
            if (held == null) { cached = Result.INACTIVE; return; }
            cached = compute(client.level, player.getEyePosition(), player.getLookAngle(), held);
        } catch (Exception ignored) {
            cached = Result.INACTIVE;
        }
    }

    public static void clear() {
        cached = Result.INACTIVE;
        tickCounter = 0;
    }

    /** Current cached state for the renderer. */
    public static Result snapshot() {
        return cached;
    }

    /**
     * Pure computation (also used by tests): item check + raycast + validation.
     * Active with a null target means "holding the item, but aiming at nothing".
     */
    public static Result compute(Level level, Vec3 eye, Vec3 look, ItemStack stack) {
        if (level == null || eye == null || look == null || stack == null || !isEtherwarpItem(stack)) {
            return Result.INACTIVE;
        }
        Optional<RayHit> hit = raycast(level, eye, look, MAX_RANGE);
        if (hit.isEmpty()) return new Result(true, null, false, -1);
        RayHit h = hit.get();
        boolean valid = h.distance() <= MAX_RANGE && isValidTarget(level, h.pos());
        return new Result(true, h.pos(), valid, h.distance());
    }

    /** Held etherwarp item (main hand first, then offhand), null when none. */
    public static ItemStack etherwarpItem(Player player) {
        ItemStack main = player.getMainHandItem();
        if (isEtherwarpItem(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (isEtherwarpItem(off)) return off;
        return null;
    }

    /**
     * True for Aspect of the End / Void with an Etherwarp Conduit applied.
     * Primary: Hypixel NBT ({@code id} + {@code ethermerge}, like SkyHanni's
     * {@code hasEtherwarp()}). Fallbacks: display name + item lore.
     */
    public static boolean isEtherwarpItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag extra = extraAttributes(stack);
        String id = extra == null ? "" : extra.getStringOr("id", "");
        boolean conduitNbt = extra != null
                && (extra.getBooleanOr("ethermerge", false)
                    || extra.getByteOr("ethermerge", (byte) 0) != 0);
        String hoverName;
        try {
            hoverName = stack.getHoverName().getString();
        } catch (Exception e) {
            hoverName = "";
        }
        boolean isAote = ID_AOTE.equals(id) || ID_AOTV.equals(id)
                || hoverName.contains("Aspect of the End") || hoverName.contains("Aspect of the Void");
        if (!isAote) return false;
        if (conduitNbt) return true;
        return loreContains(stack, "etherwarp");
    }

    /** Hypixel ExtraAttributes = root of the {@code custom_data} component (SkyHanni). */
    public static CompoundTag extraAttributes(ItemStack stack) {
        try {
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            if (custom == null) return null;
            return custom.copyTag();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean loreContains(ItemStack stack, String needle) {
        try {
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) return false;
            List<Component> lines = lore.lines();
            if (lines == null) return false;
            String lower = needle.toLowerCase(java.util.Locale.ROOT);
            for (Component line : lines) {
                if (line != null && line.getString().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * DDA voxel traversal (Amanatides &amp; Woo): visits every voxel along the ray and
     * tests each as a FULL block, stopping at the first ray-stopping block.
     */
    public static Optional<RayHit> raycast(Level level, Vec3 eye, Vec3 look, double maxDist) {
        if (level == null || eye == null || look == null || maxDist <= 0) return Optional.empty();
        double dx = look.x, dy = look.y, dz = look.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return Optional.empty();
        dx /= len; dy /= len; dz /= len;

        int x = Mth.floor(eye.x), y = Mth.floor(eye.y), z = Mth.floor(eye.z);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY
                : (dx > 0 ? (x + 1 - eye.x) : (eye.x - x)) * tDeltaX;
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY
                : (dy > 0 ? (y + 1 - eye.y) : (eye.y - y)) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY
                : (dz > 0 ? (z + 1 - eye.z) : (eye.z - z)) * tDeltaZ;

        double t = 0;
        for (int i = 0; i < 256; i++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isInWorldBounds(pos)) return Optional.empty();
            BlockState state;
            try {
                state = level.getBlockState(pos);
            } catch (Exception e) {
                return Optional.empty();
            }
            if (isRayStopper(level, pos, state)) return Optional.of(new RayHit(pos, t));
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX; t = tMaxX; tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY; t = tMaxY; tMaxY += tDeltaY;
            } else {
                z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ;
            }
            if (t > maxDist) return Optional.empty();
        }
        return Optional.empty();
    }

    /** The ray passes through this state (treated as air). */
    public static boolean isPassThrough(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        String path;
        try {
            path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        } catch (Exception e) {
            return false;
        }
        // Tripwire stops the ray (blacklisted target) despite having no collision.
        if (path.contains("tripwire")) return false;
        try {
            if (!state.getFluidState().isEmpty()) return true; // water, lava
            if (state.getCollisionShape(level, pos).isEmpty()) return true; // plants, torches, dust, rails, ...
        } catch (Exception e) {
            return true;
        }
        // Thin but colliding blocks that must not stop an etherwarp ray.
        return path.contains("carpet") || path.equals("cobweb") || path.equals("snow");
    }

    private static boolean isRayStopper(Level level, BlockPos pos, BlockState state) {
        return !isPassThrough(level, pos, state);
    }

    /**
     * Target validation: blacklisted blocks are invalid (red), everything else
     * needs 2 blocks of headroom (air or water) above the target to be valid.
     * Slabs, stairs, fences, chests, leaves etc. are all valid targets.
     */
    public static boolean isValidTarget(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockState state;
        try {
            state = level.getBlockState(pos);
        } catch (Exception e) {
            return false;
        }
        if (isBlacklisted(level, pos, state)) return false;
        // 2 blocks of headroom (air or water) for the player above the target.
        try {
            if (!isHeadroom(level, pos.above())) return false;
            if (!isHeadroom(level, pos.above(2))) return false;
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private static boolean isHeadroom(Level level, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) return false;
        BlockState state;
        try {
            state = level.getBlockState(pos);
        } catch (Exception e) {
            return false;
        }
        return state.isAir() || state.is(Blocks.WATER);
    }

    static boolean isBlacklisted(Level level, BlockPos pos, BlockState state) {
        try {
            if (!state.getFluidState().isEmpty()) return true; // water, lava
        } catch (Exception e) {
            return true;
        }
        String path;
        try {
            path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        } catch (Exception e) {
            return true;
        }
        if (path.contains("carpet")) return true;
        if (path.contains("torch")) return true;
        if (path.equals("cobweb")) return true;
        if (path.contains("tripwire")) return true;
        // Grass, flowers and similar plants: no collision shape (solid blocks
        // like slabs, stairs, fences or chests always have one and stay valid).
        try {
            if (state.getCollisionShape(level, pos).isEmpty()) return true;
        } catch (Exception e) {
            return true;
        }
        return false;
    }
}
