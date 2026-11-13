package pingplus.voicechat.client;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.trading.Trader;
import com.coflnet.lowballing.LowballingMod;
import com.coflnet.session.BackgroundAutomation;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Util;

/** Tests the actual pause and FPS mixins without starting trading workers or network feeds. */
public final class BackgroundAutomationGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.gui.overlay() == null);
            context.runOnClient(client -> {
                check(!BackgroundAutomation.isActive(client), "Bots must start inactive");
                var tracker = client.getFramerateLimitTracker();
                var inactivity = client.options.inactivityFpsLimit().get();
                boolean pauseOnLostFocus = client.options.pauseOnLostFocus;
                try {
                    client.options.pauseOnLostFocus = true;
                    client.options.inactivityFpsLimit().set(InactivityFpsLimit.AFK);
                    withField(tracker, "latestInputTime", Util.getMillis() - 600_001L, () -> {
                        check(tracker.getFramerateLimit() == 10, "Inactive client should retain AFK throttling");
                        vanillaPause(client);
                        withField(AhFlipperMod.class, "running", true, () -> activeBot(client, "AH flipper"));
                        withField(AhBidMacro.class, "running", true, () -> activeBot(client, "bid macro"));
                        withField(LowballingMod.class, "running", true, () -> activeBot(client, "lowballer"));
                        withField(Trader.get(), "state", Trader.State.RUNNING, () -> {
                            withField(Trader.get(), "paused", false, () -> activeBot(client, "Bazaar flipper"));
                            withField(Trader.get(), "paused", true, () -> {
                                check(!BackgroundAutomation.isActive(client), "Manual Bazaar pause must remain effective");
                                check(tracker.getFramerateLimit() == 10, "Paused Bazaar should allow AFK throttling");
                                vanillaPause(client);
                            });
                        });
                        check(!BackgroundAutomation.isActive(client), "Stopping all bots must restore normal behavior");
                        check(tracker.getFramerateLimit() == 10, "Stopping bots must restore AFK throttling");
                        vanillaPause(client);
                    });
                    check(client.options.pauseOnLostFocus, "Background mode changed the saved pause option");
                } finally {
                    client.options.pauseOnLostFocus = pauseOnLostFocus;
                    client.options.inactivityFpsLimit().set(inactivity);
                    client.gui.setScreen(null);
                }
            });
            context.waitTicks(3);
            context.runOnClient(client -> check(!client.isPaused(), "Client stayed paused after background checks"));
        }
        context.runOnClient(client -> withField(AhFlipperMod.class, "running", true,
                () -> check(!BackgroundAutomation.isActive(client), "Disconnected clients must retain normal menus")));
        System.out.println("PASS: all bot pause guards, focus-loss/Escape paths, mouse release, AFK FPS floor, menus, manual pause and stop/disconnect restoration");
    }

    private static void activeBot(Minecraft client, String name) {
        check(BackgroundAutomation.isActive(client), name + " did not enable background mode");
        var tracker = client.getFramerateLimitTracker();
        check(tracker.getFramerateLimit() == BackgroundAutomation.MIN_FPS, name + " did not raise the AFK FPS floor");
        withField(tracker, "latestInputTime", Util.getMillis(), () -> {
            int configured = client.options.framerateLimit().get();
            check(tracker.getFramerateLimit() == Math.max(configured, BackgroundAutomation.MIN_FPS),
                    name + " changed the foreground FPS limit");
        });
        for (boolean pauseOnly : new boolean[]{false, true}) {
            client.gui.setScreen(null);
            client.pauseGame(pauseOnly); // focus loss uses false; Escape uses true
            check(client.gui.screen() == null, name + " opened a blocking pause menu");
            check(!client.mouseHandler.isMouseGrabbed(), name + " did not release the mouse");
        }
        var inventory = new InventoryScreen(client.player);
        client.gui.setScreen(inventory);
        client.pauseGame(false);
        check(client.gui.screen() == inventory, name + " replaced an existing inventory");
        client.gui.setScreen(null);
    }

    private static void vanillaPause(Minecraft client) {
        for (boolean pauseOnly : new boolean[]{false, true}) {
            client.gui.setScreen(null);
            client.pauseGame(pauseOnly);
            check(client.gui.screen() instanceof PauseScreen, "Inactive bots blocked the normal pause menu");
        }
        client.gui.setScreen(null);
    }

    private static void withField(Object owner, String name, Object value, Runnable action) {
        try {
            Class<?> type = owner instanceof Class<?> cls ? cls : owner.getClass();
            Object target = owner instanceof Class<?> ? null : owner;
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            Object original = field.get(target);
            field.set(target, value);
            try {
                action.run();
            } finally {
                field.set(target, original);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
