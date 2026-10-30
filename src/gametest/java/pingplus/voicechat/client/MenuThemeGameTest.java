package pingplus.voicechat.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.gui.glass.GlassRainSettings;
import pingplus.voicechat.client.gui.glass.MenuThemeSettings;
import pingplus.voicechat.client.hud.ArraylistHud;
import pingplus.voicechat.client.hud.CoordinatesHud;
import pingplus.voicechat.client.hud.FpsHud;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real menu clicks, saved preferences, resource reload, small layouts, and background movement. */
final class MenuThemeGameTest {
    static void run(ClientGameTestContext context) {
        boolean originalDark = context.computeOnClient(client -> MenuThemeSettings.isDarkMode());
        boolean originalRain = context.computeOnClient(client -> GlassRainSettings.isEnabled());
        try {
            context.getInput().resizeWindow(1440, 900);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                check(MenuThemeSettings.setDarkMode(false), "Could not select light mode");
                check(GlassRainSettings.setEnabled(false), "Could not disable rain for parallax checks");
                checkArtwork(client, MenuThemeSettings.background());
                checkArtwork(client, MenuThemeSettings.logo());
                check(MenuThemeSettings.logo().id().getPath().endsWith("img_7.png"), "Light mode must use the original logo");
            });
            context.setScreen(TitleScreen::new);
            context.waitTicks(20);
            context.takeScreenshot("menu-light");
            checkParallax(context, "light");
            checkSubmenus(context, "light");

            context.clickScreenButton("Mode: Light");
            context.runOnClient(client -> {
                check(MenuThemeSettings.isDarkMode(), "Theme button did not enable dark mode");
                var file = FabricLoader.getInstance().getConfigDir().resolve("voicechat-menu-theme.properties");
                check(MenuThemeSettings.read(file), "Dark mode was not saved");
                checkArtwork(client, MenuThemeSettings.background());
                checkArtwork(client, MenuThemeSettings.logo());
                check(MenuThemeSettings.logo().id().getPath().endsWith("title_logo.png"), "Dark mode must use the Air Client title");
            });
            context.waitTicks(15);
            context.takeScreenshot("menu-dark");
            checkParallax(context, "dark");
            checkSubmenus(context, "dark");

            context.setScreen(TitleScreen::new);
            context.waitTicks(10);
            context.runOnClient(client -> check(client.gui.screen().children().stream()
                    .anyMatch(c -> c instanceof Button b && b.getMessage().getString().equals("Mode: Dark")),
                    "Theme label did not survive reopening the title screen"));
            context.clickScreenButton("Glass drops: OFF");
            context.waitTicks(25);
            context.takeScreenshot("menu-dark-rain");
            context.waitTicks(20);
            context.takeScreenshot("menu-dark-rain-motion");
            context.clickScreenButton("menu.options");
            context.waitForScreen(OptionsScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("menu-dark-options");
            context.setScreen(TitleScreen::new);
            context.getInput().resizeWindow(854, 480);
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            context.waitTicks(15);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                for (var child : screen.children()) {
                    if (child instanceof Button b && (b.getMessage().getString().startsWith("Mode: ")
                            || b.getMessage().getString().startsWith("Glass drops: "))) {
                        check(b.getX() >= 0 && b.getRight() <= screen.width && b.getY() >= 0
                                && b.getBottom() <= screen.height, "Menu toggle outside the small viewport");
                    }
                }
            });
            context.takeScreenshot("menu-dark-compact");
            context.getInput().resizeWindow(1440, 900);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); client.reloadResourcePacks(); });
            context.waitFor(client -> client.gui.overlay() == null);
            context.waitTicks(15);
            context.takeScreenshot("menu-dark-reloaded");
            context.clickScreenButton("Mode: Dark");
            context.runOnClient(client -> {
                var file = FabricLoader.getInstance().getConfigDir().resolve("voicechat-menu-theme.properties");
                check(!MenuThemeSettings.isDarkMode() && !MenuThemeSettings.read(file), "Light mode was not restored and saved");
                check(GlassRainSettings.isEnabled(), "Changing theme must preserve the rain preference");
                checkDefaults(file.getParent());
            });
            context.waitTicks(15);
            context.takeScreenshot("menu-light-rain");
            try (var world = context.worldBuilder().create()) {
                context.waitFor(client -> client.player != null && client.gui.overlay() == null);
                context.setScreen(() -> new PauseScreen(true));
                context.waitTicks(20);
                context.takeScreenshot("menu-world-pause");
                context.waitTicks(20);
                context.takeScreenshot("menu-world-pause-rain-motion");
                context.runOnClient(client -> client.gui.setScreen(new OptionsScreen(client.gui.screen(), client.options, false)));
                context.waitTicks(10);
                context.takeScreenshot("menu-world-options");
            }
            System.out.println("PASS: menu themes, persistence, parallax across menus and loading screens, rain, in-world menus, compact layout and reload");
        } finally {
            context.runOnClient(client -> {
                MenuThemeSettings.setDarkMode(originalDark);
                GlassRainSettings.setEnabled(originalRain);
            });
            context.setScreen(TitleScreen::new);
        }
    }

    private static void checkSubmenus(ClientGameTestContext context, String theme) {
        context.setScreen(() -> new SelectWorldScreen(new TitleScreen()));
        context.waitTicks(20);
        checkParallax(context, theme + "-singleplayer");
        context.setScreen(() -> new JoinMultiplayerScreen(new TitleScreen()));
        context.waitTicks(20);
        checkParallax(context, theme + "-multiplayer");
        context.runOnClient(client -> client.gui.setScreen(new OptionsScreen(new TitleScreen(), client.options, false)));
        context.waitTicks(10);
        checkParallax(context, theme + "-options");
        context.runOnClient(client -> client.gui.setScreen(new LanguageSelectScreen(new TitleScreen(),
                client.options, client.getLanguageManager())));
        context.waitTicks(10);
        checkParallax(context, theme + "-language");
        context.setScreen(() -> new GenericMessageScreen(Component.literal("Preparing world")));
        context.waitTicks(10);
        checkParallax(context, theme + "-message");
        context.setScreen(() -> new ProgressScreen(false));
        context.waitTicks(10);
        checkParallax(context, theme + "-progress");
        context.runOnClient(client -> CreateWorldScreen.openFresh(client,
                () -> client.gui.setScreen(new TitleScreen())));
        context.waitForScreen(CreateWorldScreen.class);
        context.waitTicks(10);
        checkParallax(context, theme + "-create-world");
        context.runOnClient(client -> client.gui.screen().onClose());
        context.waitForScreen(TitleScreen.class);
        var fps = new FpsHud();
        var coordinates = new CoordinatesHud();
        context.setScreen(() -> new ClickGuiScreen(fps, VoicechatClient.openGuiKey(), coordinates,
                new ArraylistHud(fps, coordinates)));
        context.waitTicks(15);
        checkParallax(context, theme + "-clickgui");
        context.setScreen(TitleScreen::new);
    }

    private static void checkParallax(ClientGameTestContext context, String theme) {
        context.getInput().setCursorPos(20, 450);
        context.waitTicks(25);
        Path left = context.takeScreenshot("menu-" + theme + "-parallax-left");
        context.getInput().setCursorPos(1420, 450);
        context.waitTicks(25);
        Path right = context.takeScreenshot("menu-" + theme + "-parallax-right");
        try {
            var a = ImageIO.read(left.toFile());
            var b = ImageIO.read(right.toFile());
            int changed = 0;
            // Outside all menu widgets: with rain disabled, only the wallpaper can move here.
            for (int y = a.getHeight() / 3; y < a.getHeight() * 9 / 10; y++) {
                for (int x = 0; x < a.getWidth() / 4; x++) {
                    if (a.getRGB(x, y) != b.getRGB(x, y)) changed++;
                }
            }
            check(changed > 200, theme + " background did not move with the cursor: " + changed);
        } catch (IOException e) { throw new AssertionError("Could not compare parallax screenshots", e); }
    }

    private static void checkArtwork(net.minecraft.client.Minecraft client, MenuThemeSettings.Artwork artwork) {
        try (var stream = client.getResourceManager().open(artwork.id()); var image = NativeImage.read(stream)) {
            check(image.getWidth() == artwork.width() && image.getHeight() == artwork.height(),
                    "Incorrect texture dimensions for " + artwork.id());
        } catch (IOException e) { throw new AssertionError("Missing menu artwork: " + artwork.id(), e); }
    }

    private static void checkDefaults(Path directory) {
        try {
            Path file = Files.createTempFile(directory, "menu-theme-invalid-", ".properties");
            try {
                Files.writeString(file, "darkMode=unknown\n");
                check(!MenuThemeSettings.read(file), "Invalid preference must default to light mode");
                Files.writeString(file, "darkMode=\\uINVALID\n");
                check(!MenuThemeSettings.read(file), "Malformed preference must default to light mode");
            } finally { Files.deleteIfExists(file); }
            check(!MenuThemeSettings.read(file), "Missing preference must default to light mode");
        } catch (IOException e) { throw new AssertionError("Could not check preference defaults", e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
