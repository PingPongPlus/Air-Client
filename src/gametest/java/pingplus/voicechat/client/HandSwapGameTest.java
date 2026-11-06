package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.HumanoidArm;

/** Covers preference restoration with a missing player, reconnects and lifecycle cleanup. */
public final class HandSwapGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        boolean[] enabled = new boolean[1];
        int[] interval = new int[1];
        HumanoidArm[] original = new HumanoidArm[1];
        context.runOnClient(client -> {
            enabled[0] = PlayerSettings.handSwap;
            interval[0] = PlayerSettings.handSwapIntervalTicks;
            original[0] = client.options.mainHand().get();
            PlayerSettings.handSwap = false;
        });
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.gui.overlay() == null);
            context.runOnClient(client -> {
                for (HumanoidArm arm : HumanoidArm.values()) {
                    restoration(client, arm, false);
                    restoration(client, arm, true);
                }
                client.options.mainHand().set(original[0]);
                client.player.setMainArm(original[0]);
                PlayerSettings.handSwapIntervalTicks = 20;
                PlayerSettings.handSwap = true;
            });
            // Exercise the registered feature instance and real disconnect callback as well.
            context.waitFor(client -> client.options.mainHand().get() == original[0].getOpposite());
        } finally {
            context.runOnClient(client -> {
                HumanoidArm restored = client.options.mainHand().get();
                PlayerSettings.handSwap = enabled[0];
                PlayerSettings.handSwapIntervalTicks = interval[0];
                client.options.mainHand().set(original[0]);
                if (client.player != null) client.player.setMainArm(original[0]);
                check(restored == original[0], "Disconnect must restore the original hand before another tick");
            });
        }
    }

    private static void restoration(Minecraft client, HumanoidArm original, boolean swapBack) {
        var feature = new HandSwapFeature();
        var player = client.player;
        client.options.mainHand().set(original);
        player.setMainArm(original);
        PlayerSettings.handSwapIntervalTicks = 1;
        PlayerSettings.handSwap = true;
        feature.tick(client);
        check(client.options.mainHand().get() == original.getOpposite(), "Enabling must swap hands");
        check(player.getMainArm() == original.getOpposite(), "The player model must match the swapped hand");
        if (swapBack) {
            feature.tick(client);
            check(client.options.mainHand().get() == original, "The configured interval must still alternate hands");
        }
        try {
            client.player = null;
            feature.tick(client);
            check(client.options.mainHand().get() == original, "A missing player must restore the preference");
            // Repeated cleanup must not overwrite a preference changed in the main menu.
            client.options.mainHand().set(original.getOpposite());
            feature.finish(client);
            check(client.options.mainHand().get() == original.getOpposite(), "Cleanup must be idempotent");
            client.options.mainHand().set(original);
        } finally {
            client.player = player;
        }
        feature.tick(client);
        check(client.options.mainHand().get() == original.getOpposite(), "Reconnecting must start from the original hand");
        PlayerSettings.handSwap = false;
        feature.tick(client);
        check(client.options.mainHand().get() == original && player.getMainArm() == original,
                "Disabling after reconnect must restore both preference and model");
        PlayerSettings.handSwap = true;
        feature.tick(client);
        feature.finish(client);
        check(client.options.mainHand().get() == original && player.getMainArm() == original,
                "Lifecycle cleanup must restore the hand even while the feature remains enabled");
        PlayerSettings.handSwap = false;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
