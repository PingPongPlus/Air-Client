package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import pingplus.voicechat.client.gui.hud.AggSettings;
import pingplus.voicechat.client.gui.hud.AggWidget;
import pingplus.voicechat.client.gui.hud.HudEditor;

/** AGG sticker: toggle/persistence, GIF frames, drag/scale via the real HUD machinery. */
public final class AggTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        // Toggle + persistence roundtrip.
        context.runOnClient(client -> {
            AggSettings.setEnabled(true);
            if (!AggSettings.isEnabled()) throw new AssertionError("AGG toggle failed");
            var saved = new java.util.Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("voicechat-agg.properties"))) { saved.load(reader); }
            catch (java.io.IOException e) { throw new AssertionError("Cannot read AGG preference", e); }
            if (!Boolean.parseBoolean(saved.getProperty("enabled")))
                throw new AssertionError("AGG preference was not saved");
        });
        // GIF frames: 44 frames, 498x498, ~2.2s loop, decoded once and cached.
        context.runOnClient(client -> {
            if (AggWidget.INSTANCE.frameCount() != 44)
                throw new AssertionError("AGG frame count: " + AggWidget.INSTANCE.frameCount());
            if (AggWidget.INSTANCE.frameWidth() != 498 || AggWidget.INSTANCE.frameHeight() != 498)
                throw new AssertionError("AGG frame size: "
                        + AggWidget.INSTANCE.frameWidth() + "x" + AggWidget.INSTANCE.frameHeight());
            int total = AggWidget.INSTANCE.totalDurationMs();
            if (total < 2000 || total > 2400)
                throw new AssertionError("AGG loop duration: " + total + "ms");
        });
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null);
            // Drag + wheel-scale through the real HUD editor, then screenshot in-world.
            context.setScreen(() -> new ChatScreen("agg", false));
            context.waitTicks(10);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                var before = HudEditor.bounds("agg", screen.width, screen.height);
                double cx = before.x() + before.width() / 2, cy = before.y() + before.height() / 2;
                var down = new MouseButtonEvent(cx, cy, new MouseButtonInfo(0, 0));
                if (!HudEditor.click(screen, down, false)) throw new AssertionError("AGG not hit");
                // Small drag: the headless test window is tiny, large deltas clamp to the corner.
                if (!HudEditor.drag(cx - 30, cy - 20)) throw new AssertionError("AGG drag failed");
                HudEditor.finish();
                var moved = HudEditor.bounds("agg", screen.width, screen.height);
                if (Math.abs(moved.x() - before.x() + 30) > 2 || Math.abs(moved.y() - before.y() + 20) > 2)
                    throw new AssertionError("AGG did not move: " + moved);
                double scaleBefore = moved.scale();
                double scx = moved.x() + moved.width() / 2, scy = moved.y() + moved.height() / 2;
                if (!HudEditor.scroll(scx, scy, 1)) throw new AssertionError("AGG scale failed");
                var scaled = HudEditor.bounds("agg", screen.width, screen.height);
                if (Math.abs(scaled.scale() - scaleBefore - 0.1) > 0.01)
                    throw new AssertionError("AGG did not scale: " + scaled.scale());
                var layout = new java.util.Properties();
                try (var reader = java.nio.file.Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getConfigDir().resolve("voicechat-hud-layout.properties"))) { layout.load(reader); }
                catch (java.io.IOException e) { throw new AssertionError("Cannot read HUD layout", e); }
                if (layout.getProperty("agg.x") == null || layout.getProperty("agg.scale") == null)
                    throw new AssertionError("AGG position was not saved");
            });
            context.runOnClient(client -> client.gui.setScreen(null));
            context.waitTicks(15);
            context.takeScreenshot("agg-widget");
            context.runOnClient(client -> {
                AggSettings.setEnabled(false);
                if (AggSettings.isEnabled()) throw new AssertionError("AGG disable failed");
            });
        }
    }
}
