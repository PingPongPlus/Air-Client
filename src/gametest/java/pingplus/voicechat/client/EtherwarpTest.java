package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.etherwarp.EtherwarpHelper;

/**
 * Etherwarp helper end-to-end: item detection, voxel raycast, target validation
 * and overlay screenshots. Run with ./gradlew runClientGameTest.
 */
public final class EtherwarpTest implements FabricClientGameTest {
    private static final double EYE_Y = 62.62;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.level != null);
            buildArena(context);
            context.runOnClient(client -> {
                assertItems();
                assertRaycast(client.level);
                assertValidation(client.level);
                assertRange(client.level);
            });
            screenshotScenario(context, "etherwarp-valid", 7.7, 0.5, 8.0, 60.0);
            screenshotScenario(context, "etherwarp-invalid", 43.7, 0.5, 44.0, 60.0);
            screenshotScenario(context, "etherwarp-through-web", 31.7, 0.5, 32.0, 60.0);
            context.runOnClient(client -> {
                PlayerSettings.etherwarpHelper = true;
            });
        }
    }

    // Compact arena near spawn: the headless client loads few chunks around the
    // player, so every feature stays within ~3 chunks of world spawn.
    private static void buildArena(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var server = client.getSingleplayerServer();
            var level = server.overworld();
            server.execute(() -> {
                // Floor strip.
                for (int x = -20; x <= 62; x++) {
                    for (int z = -2; z <= 12; z++) {
                        level.setBlock(new BlockPos(x, 60, z), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
                // Feature blocks on the z=0 lane.
                level.setBlock(new BlockPos(8, 61, 0), Blocks.CARPET.white().defaultBlockState(), 3);
                level.setBlock(new BlockPos(12, 61, 0), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(new BlockPos(16, 61, 0), Blocks.TORCH.defaultBlockState(), 3);
                level.setBlock(new BlockPos(20, 61, 0), Blocks.CHEST.defaultBlockState(), 3);
                level.setBlock(new BlockPos(24, 61, 0),
                        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
                level.setBlock(new BlockPos(28, 61, 0), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(28, 62, 0), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(28, 63, 0), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(32, 61, 0), Blocks.COBWEB.defaultBlockState(), 3);
                level.setBlock(new BlockPos(36, 61, 0), Blocks.SHORT_GRASS.defaultBlockState(), 3);
                level.setBlock(new BlockPos(37, 61, 0), Blocks.POPPY.defaultBlockState(), 3);
                level.setBlock(new BlockPos(41, 61, 0), Blocks.LAVA.defaultBlockState(), 3);
                // Tripwire stops the ray and is blacklisted (flag 2: sync, no updates).
                level.setBlock(new BlockPos(44, 61, 0), Blocks.TRIPWIRE.defaultBlockState(), 2);
                // Slabs, stairs and fences are valid warp targets.
                level.setBlock(new BlockPos(48, 61, 0), Blocks.STONE_SLAB.defaultBlockState(), 3);
                level.setBlock(new BlockPos(52, 61, 0), Blocks.OAK_STAIRS.defaultBlockState(), 3);
                level.setBlock(new BlockPos(56, 61, 0), Blocks.OAK_FENCE.defaultBlockState(), 3);
                // Range lane at z=10: wall face 34.5 blocks from x=10.5.
                for (int y = 60; y <= 65; y++) {
                    level.setBlock(new BlockPos(45, y, 10), Blocks.STONE.defaultBlockState(), 3);
                }
            });
        });
        context.waitTicks(20);
    }

    private static net.minecraft.world.item.ItemStack warpSword(String id, boolean ethermerge, String lore) {
        var stack = new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD);
        var tag = new CompoundTag();
        if (id != null) tag.putString("id", id);
        if (ethermerge) tag.putBoolean("ethermerge", true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (lore != null) {
            stack.set(DataComponents.LORE, new ItemLore(java.util.List.of(Component.literal(lore))));
        }
        return stack;
    }

    private static void assertItems() {
        var plain = new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD);
        if (EtherwarpHelper.isEtherwarpItem(plain))
            throw new AssertionError("Plain sword must not count as etherwarp item");
        if (EtherwarpHelper.isEtherwarpItem(net.minecraft.world.item.ItemStack.EMPTY))
            throw new AssertionError("Empty hand must not count as etherwarp item");
        if (EtherwarpHelper.isEtherwarpItem(warpSword("ASPECT_OF_THE_VOID", false, null)))
            throw new AssertionError("AOTV without conduit must not count");
        if (!EtherwarpHelper.isEtherwarpItem(warpSword("ASPECT_OF_THE_VOID", true, null)))
            throw new AssertionError("AOTV with ethermerge NBT must count");
        if (!EtherwarpHelper.isEtherwarpItem(warpSword("ASPECT_OF_THE_END", true, null)))
            throw new AssertionError("AOTE with ethermerge NBT must count");
        if (!EtherwarpHelper.isEtherwarpItem(warpSword("ASPECT_OF_THE_VOID", false, "Etherwarp Conduit")))
            throw new AssertionError("AOTV with etherwarp lore must count");
        if (EtherwarpHelper.isEtherwarpItem(warpSword("HYPERION", true, null)))
            throw new AssertionError("Wrong item id with conduit must not count");
    }

    private static EtherwarpHelper.Result computeAim(net.minecraft.client.multiplayer.ClientLevel level,
                                                     double eyeX, double eyeY, double eyeZ,
                                                     double aimX, double aimY, double aimZ) {
        var sword = warpSword("ASPECT_OF_THE_VOID", true, null);
        Vec3 eye = new Vec3(eyeX, eyeY, eyeZ);
        Vec3 aim = new Vec3(aimX, aimY, aimZ);
        return EtherwarpHelper.compute(level, eye, aim.subtract(eye).normalize(), sword);
    }

    // Steep look-down through the feature voxel: ignored cover resolves to the
    // stone below, solid features stop the ray inside their own voxel.
    private static void assertRaycast(net.minecraft.client.multiplayer.ClientLevel level) {
        // Carpet / water / torch / cobweb / grass / poppy / lava are passed through.
        assertTarget(computeAim(level, 8.2, 63.5, 0.5, 8.5, 60.5, 0.5), 8, 60, 0, true, "carpet");
        assertTarget(computeAim(level, 12.2, 63.5, 0.5, 12.5, 60.5, 0.5), 12, 60, 0, true, "water");
        assertTarget(computeAim(level, 16.2, 63.5, 0.5, 16.5, 60.5, 0.5), 16, 60, 0, true, "torch");
        assertTarget(computeAim(level, 32.2, 63.5, 0.5, 32.5, 60.5, 0.5), 32, 60, 0, true, "cobweb");
        assertTarget(computeAim(level, 36.2, 63.5, 0.5, 36.5, 60.5, 0.5), 36, 60, 0, true, "grass");
        assertTarget(computeAim(level, 37.2, 63.5, 0.5, 37.5, 60.5, 0.5), 37, 60, 0, true, "poppy");
        assertTarget(computeAim(level, 41.2, 63.5, 0.5, 41.5, 60.5, 0.5), 41, 60, 0, true, "lava");
        // Tripwire: blacklisted, invalid.
        assertTarget(computeAim(level, 44.2, 63.5, 0.5, 44.5, 60.5, 0.5), 44, 61, 0, false, "tripwire");
        // Slabs, stairs and fences are valid warp targets.
        assertTarget(computeAim(level, 48.2, 63.5, 0.5, 48.5, 60.5, 0.5), 48, 61, 0, true, "slab");
        assertTarget(computeAim(level, 52.2, 63.5, 0.5, 52.5, 60.5, 0.5), 52, 61, 0, true, "stairs");
        assertTarget(computeAim(level, 56.2, 63.5, 0.5, 56.5, 60.5, 0.5), 56, 61, 0, true, "fence");
    }

    private static void assertValidation(net.minecraft.client.multiplayer.ClientLevel level) {
        // Blacklist-only validation now: chests, leaves and covered blocks are valid.
        assertTarget(computeAim(level, 20.2, 63.5, 0.5, 20.5, 60.5, 0.5), 20, 61, 0, true, "chest");
        assertTarget(computeAim(level, 24.2, 63.5, 0.5, 24.5, 60.5, 0.5), 24, 61, 0, true, "leaves");
        assertTarget(computeAim(level, 26.5, EYE_Y, 0.5, 28.5, 61.5, 0.5), 28, 61, 0, true, "headroom");
    }

    private static void assertRange(net.minecraft.client.multiplayer.ClientLevel level) {
        var sword = warpSword("ASPECT_OF_THE_VOID", true, null);
        // Wall face 34.5 blocks away: a target within range.
        var near = EtherwarpHelper.compute(level, new Vec3(10.5, EYE_Y, 10.5),
                new Vec3(1, 0, 0), sword);
        if (near.target() == null || !near.target().equals(new BlockPos(45, 62, 10)))
            throw new AssertionError("Expected near wall hit, got " + near.target());
        if (near.distance() > EtherwarpHelper.MAX_RANGE)
            throw new AssertionError("Hit beyond max range: " + near.distance());
        // Same wall face, 57.5 blocks away: out of range, no target.
        var beyond = EtherwarpHelper.compute(level, new Vec3(-12.5, EYE_Y, 10.5),
                new Vec3(1, 0, 0), sword);
        if (beyond.target() != null)
            throw new AssertionError("Wall beyond 57 blocks must not be hit, got " + beyond.target());
        // Ray into the sky: no target at all.
        var sky = EtherwarpHelper.compute(level, new Vec3(10.5, EYE_Y, 10.5),
                new Vec3(1, 0.6, 0).normalize(), sword);
        if (sky.target() != null)
            throw new AssertionError("Ray into the sky must find no target, got " + sky.target());
    }

    private static void assertTarget(EtherwarpHelper.Result r, int x, int y, int z,
                                     boolean valid, String label) {
        if (!r.active()) throw new AssertionError(label + ": helper inactive");
        if (r.target() == null || !r.target().equals(new BlockPos(x, y, z)))
            throw new AssertionError(label + ": expected (" + x + "," + y + "," + z
                    + ") but got " + r.target());
        if (r.valid() != valid)
            throw new AssertionError(label + ": expected valid=" + valid);
    }

    // Teleport the real player, hold a warp sword, look at the target, screenshot.
    private static void screenshotScenario(ClientGameTestContext context, String name,
                                           double px, double pz, double tx, double ty) {
        context.runOnClient(client -> {
            var server = client.getSingleplayerServer();
            var sword = warpSword("ASPECT_OF_THE_VOID", true, null);
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(client.player.getUUID());
                for (int i = 0; i < 9; i++) sp.getInventory().setItem(i, sword.copy());
                sp.teleportTo(px, 61.0, pz);
                // Look at the target block centre.
                double dx = tx + 0.5 - px, dy = ty + 0.5 - (61.0 + 1.62), dz = 0.5 - pz;
                double horiz = Math.sqrt(dx * dx + dz * dz);
                sp.setYRot((float) (Math.atan2(-dx, dz) * 180.0 / Math.PI));
                sp.setXRot((float) (-Math.asin(dy / Math.sqrt(dy * dy + horiz * horiz)) * 180.0 / Math.PI));
            });
        });
        context.waitTicks(15);
        context.takeScreenshot(name);
    }
}
