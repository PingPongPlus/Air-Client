package pingplus.voicechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.HumanoidArm;




//A feature to quickly swap hands
//When enabled it looks like you are hitting with both hands at the same time

public final class HandSwapFeature {
    private boolean wasEnabled;
    private int ticks;
    private HumanoidArm savedMainArm = HumanoidArm.RIGHT;

    public void tick(Minecraft client) {
        if (client.player == null) {
            finish(client);
            return;
        }

        if (PlayerSettings.handSwap) {
            if (!wasEnabled) {
                savedMainArm = client.options.mainHand().get();
                ticks = 0;
                applyMainArm(client, savedMainArm.getOpposite());
            } else {
                ticks++;
                if (ticks >= Math.max(1, PlayerSettings.handSwapIntervalTicks)) {
                    ticks = 0;
                    applyMainArm(client, client.options.mainHand().get().getOpposite());
                }
            }
        } else if (wasEnabled) {
            finish(client);
        }
        wasEnabled = PlayerSettings.handSwap;
    }

    /** Restore the user's preference before forgetting the temporary hand swap. */
    public void finish(Minecraft client) {
        if (wasEnabled) applyMainArm(client, savedMainArm);
        wasEnabled = false;
        ticks = 0;
    }

    private static void applyMainArm(Minecraft client, HumanoidArm arm) {
        client.options.mainHand().set(arm);
        if (client.player != null) client.player.setMainArm(arm);
        client.options.broadcastOptions();
    }
}
