package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import pingplus.voicechat.client.gui.glass.GlassEffectSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import pingplus.voicechat.client.gui.glass.WeatherGlassSettings;
import pingplus.voicechat.client.hud.PingHud;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Path;

/** Screen glass must sample the screen background even when HUD glass renders first. */
final class GlassBackdropGameTest {
    private static final int LEFT = 0xFF20C060, RIGHT = 0xFFC020A0;

    static void run(ClientGameTestContext context) {
        var saved = context.computeOnClient(client -> new Saved(PingHud.INSTANCE.isEnabled(),
                PingHud.INSTANCE.isGlass(), GlassEffectSettings.blurStep(), GlassEffectSettings.customTint(),
                WeatherGlassSettings.values()));
        try {
            context.getInput().resizeWindow(900, 600);
            context.runOnClient(client -> {
                if (!PingHud.INSTANCE.isEnabled()) PingHud.INSTANCE.toggle();
                if (GlassEffectSettings.customTint()) GlassEffectSettings.toggleCustomTint();
                WeatherGlassSettings.set(new WeatherGlassSettings.Values(false, WeatherGlassSettings.Mode.AUTOMATIC, false, 0));
            });
            try (var world = context.worldBuilder().create()) {
                context.waitFor(client -> client.player != null && client.gui.overlay() == null);
                for (int scale : new int[]{2, 3}) {
                    for (int blur : new int[]{0, 4}) {
                        for (boolean pingGlass : new boolean[]{false, true}) {
                            context.runOnClient(client -> {
                                client.options.guiScale().set(scale);
                                client.resizeGui();
                                GlassEffectSettings.setBlur(blur);
                                if (PingHud.INSTANCE.isGlass() != pingGlass) PingHud.INSTANCE.toggleGlass();
                            });
                            for (boolean vanillaBlur : new boolean[]{false, true}) {
                                context.setScreen(() -> new BackgroundScreen(vanillaBlur));
                                context.waitTicks(5);
                                Path screenshot = context.takeScreenshot("glass-backdrop-scale-" + scale
                                        + "-blur-" + blur + "-ping-" + pingGlass + "-vanilla-blur-" + vanillaBlur);
                                checkBackground(screenshot);
                            }
                        }
                    }
                }
                // Release the screen targets, then recreate them at a different framebuffer size.
                context.setScreen(() -> null);
                context.waitTicks(5);
                context.getInput().resizeWindow(854, 480);
                context.setScreen(() -> new BackgroundScreen(false));
                context.waitTicks(5);
                checkBackground(context.takeScreenshot("glass-backdrop-reopened-resized"));
                context.runOnClient(client -> client.reloadResourcePacks());
                context.waitFor(client -> client.gui.overlay() == null);
                context.waitTicks(5);
                checkBackground(context.takeScreenshot("glass-backdrop-reloaded"));
            }
            System.out.println("PASS: HUD and screen glass stay independent with Ping on/off, glass/vanilla blur, GUI scales 2/3, reopen, resize and reload");
        } finally {
            context.runOnClient(client -> {
                if (PingHud.INSTANCE.isEnabled() != saved.pingEnabled()) PingHud.INSTANCE.toggle();
                if (PingHud.INSTANCE.isGlass() != saved.pingGlass()) PingHud.INSTANCE.toggleGlass();
                GlassEffectSettings.setBlur(saved.blur());
                if (GlassEffectSettings.customTint() != saved.tint()) GlassEffectSettings.toggleCustomTint();
                WeatherGlassSettings.set(saved.weather());
            });
            context.setScreen(TitleScreen::new);
        }
    }

    private static void checkBackground(Path screenshot) {
        try {
            var image = ImageIO.read(screenshot.toFile());
            checkColor(image.getRGB(image.getWidth() / 4, image.getHeight() / 2), LEFT);
            checkColor(image.getRGB(image.getWidth() * 3 / 4, image.getHeight() / 2), RIGHT);
        } catch (IOException e) { throw new AssertionError("Could not read glass background screenshot", e); }
    }

    private static void checkColor(int actual, int background) {
        // The edgeless material absorbs 12% of the scene color at full opacity.
        for (int shift : new int[]{16, 8, 0}) {
            int expected = Math.round(((background >>> shift) & 255) * .88f);
            int channel = (actual >>> shift) & 255;
            if (Math.abs(channel - expected) > 12)
                throw new AssertionError("Screen glass sampled the HUD scene: expected background "
                        + Integer.toHexString(background) + ", got " + Integer.toHexString(actual));
        }
    }

    private record Saved(boolean pingEnabled, boolean pingGlass, int blur, boolean tint,
                         WeatherGlassSettings.Values weather) { }

    private static final class BackgroundScreen extends Screen {
        private final boolean vanillaBlur;
        BackgroundScreen(boolean vanillaBlur) {
            super(Component.literal("Glass background regression"));
            this.vanillaBlur = vanillaBlur;
        }

        @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
            if (vanillaBlur) g.blurBeforeThisStratum();
            g.fill(0, 0, width / 2, height, LEFT);
            g.fill(width / 2, 0, width, height, RIGHT);
            g.nextStratum();
        }

        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
            GlassStyle.surface(g, 16, height / 2 - 25, width - 32, 50, 1, 0, false);
            g.nextStratum();
        }
    }
}
