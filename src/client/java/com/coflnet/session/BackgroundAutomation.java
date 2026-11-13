package com.coflnet.session;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.trading.Trader;
import com.coflnet.lowballing.LowballingMod;
import net.minecraft.client.Minecraft;

/** Keeps active bots independent of window focus without changing saved options. */
public final class BackgroundAutomation {
    public static final int MIN_FPS = 20;

    private BackgroundAutomation() {
    }

    public static boolean isActive(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return false;
        }
        if (AhFlipperMod.isRunning() || AhBidMacro.isRunning() || LowballingMod.isRunning()) {
            return true;
        }
        Trader trader = Trader.get();
        return trader != null && trader.isRunning() && !trader.isPaused();
    }
}
