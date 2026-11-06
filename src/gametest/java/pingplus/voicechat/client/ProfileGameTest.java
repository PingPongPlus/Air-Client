package pingplus.voicechat.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import pingplus.voicechat.client.damageglass.DamageGlassSettings;
import pingplus.voicechat.client.damageglass.GlassPreset;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.gui.ProfilesScreen;
import pingplus.voicechat.client.gui.glass.GlassEffectSettings;
import pingplus.voicechat.client.hud.*;
import pingplus.voicechat.client.hud.editor.*;
import pingplus.voicechat.client.profile.ClientProfiles;

import java.nio.file.Files;
import java.util.Map;

/** Exercises real settings, lifecycle callbacks, native input, pagination and resizing. */
final class ProfileGameTest {
    static void run(ClientGameTestContext context) {
        FpsHud fps = new FpsHud();
        CoordinatesHud coordinates = new CoordinatesHud();
        ArraylistHud arraylist = new ArraylistHud(fps, coordinates);
        ClientProfiles[] profiles = new ClientProfiles[1];
        java.nio.file.Path[] file = new java.nio.file.Path[1];
        String[] original = new String[1];
        try {
            context.waitFor(client -> client.gui.overlay() == null);
            context.runOnClient(client -> {
                client.getWindow().setWindowed(1440, 900);
                client.options.guiScale().set(2); client.resizeGui();
                try {
                    var directory = client.gameDirectory.toPath().resolve("profile-test");
                    Files.createDirectories(directory);
                    file[0] = directory.resolve("profiles-" + java.util.UUID.randomUUID() + ".properties");
                    profiles[0] = new ClientProfiles(file[0], fps, coordinates, arraylist);
                    original[0] = profiles[0].create("Original").id();
                } catch (Exception e) { throw new AssertionError(e); }
            });

            context.setScreen(() -> new ClickGuiScreen(fps, VoicechatClient.openGuiKey(), coordinates, arraylist));
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                Button open = button(screen, "Profiles");
                var click = new MouseButtonEvent((open.getX() + 20) * ClickGuiScreen.UI_SCALE,
                        (open.getY() + 9) * ClickGuiScreen.UI_SCALE, new MouseButtonInfo(0, 0));
                check(screen.mouseClicked(click, false), "Profiles toolbar button rejected scaled input");
                check(client.gui.screen() instanceof ProfilesScreen, "Profiles toolbar did not open profiles");
            });
            context.setScreen(() -> new ProfilesScreen(null, profiles[0]));
            context.runOnClient(client -> {
                PlayerSettings.forceSneak = true;
                if (fps.isEnabled()) fps.toggle();
                GlassEffectSettings.setTintColor(0x123456);
                DamageGlassSettings.setPreset(GlassPreset.values()[0]);
                DamageGlassSettings.setReflectivity(73);
                VoicechatClient.zoomKey().setKey(InputConstants.Type.MOUSE.getOrCreate(4));
                HudEditor.applyLayout(Map.of("fps", new HudLayout.Position(.2, .4, 1.5)));
                name(client.gui.screen()).setValue("Alpha");
            });
            context.clickScreenButton("Create");
            context.runOnClient(client -> {
                check(button(client.gui.screen(), "> Alpha") != null, "Created profile was not selected");
                PlayerSettings.forceSneak = false;
                fps.toggle();
                GlassEffectSettings.setTintColor(0xFFFFFF);
                DamageGlassSettings.setReflectivity(5);
                VoicechatClient.zoomKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(67));
                HudEditor.applyLayout(Map.of());
            });
            context.clickScreenButton("Load");
            context.runOnClient(client -> {
                check(PlayerSettings.forceSneak && !fps.isEnabled(), "Profile did not restore player and HUD toggles");
                check(GlassEffectSettings.tintColor() == 0x123456, "Profile did not restore glass color");
                check(DamageGlassSettings.reflectivity() == 73, "Preset reset the saved reflectivity");
                check(VoicechatClient.zoomKey().saveString().equals("key.mouse.5"), "Mouse keybind did not round-trip");
                check(HudEditor.layoutSnapshot().get("fps").equals(new HudLayout.Position(.2, .4, 1.5)), "HUD anchors and scale did not restore");
                PlayerSettings.forceSneak = false;
            });
            context.clickScreenButton("Save");
            context.runOnClient(client -> PlayerSettings.forceSneak = true);
            context.clickScreenButton("Load");
            context.runOnClient(client -> {
                check(!PlayerSettings.forceSneak, "Save did not overwrite the selected snapshot");
                name(client.gui.screen()).setValue("Renamed");
            });
            context.clickScreenButton("Rename");
            context.runOnClient(client -> {
                button(client.gui.screen(), "> Renamed");
                name(client.gui.screen()).setValue("renamed");
            });
            context.clickScreenButton("Create");
            context.runOnClient(client -> {
                try { check(profiles[0].store().profiles().size() == 2, "Duplicate profile name was accepted"); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            context.waitTicks(5);
            context.takeScreenshot("profiles-error");
            context.runOnClient(client -> {
                try {
                    for (int i = 0; i < 12; i++) profiles[0].create("Preset " + i);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            context.setScreen(() -> new ProfilesScreen(null, profiles[0]));
            context.clickScreenButton("Next");
            context.waitTicks(5);
            context.takeScreenshot("profiles-list");
            context.runOnClient(client -> {
                client.getWindow().setWindowed(854, 480);
                client.options.guiScale().set(3); client.resizeGui();
            });
            context.waitTicks(5);
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
                    check(widget.getX() >= 0 && widget.getRight() <= screen.width && widget.getY() >= 0 && widget.getBottom() <= screen.height,
                            "Profile control left the viewport on resize: " + widget.getMessage().getString());
            });
            context.takeScreenshot("profiles-small");
            context.runOnClient(client -> {
                client.options.guiScale().set(2); client.getWindow().setWindowed(1440, 900); client.resizeGui();
            });
            // A fresh screen starts on page one; keep paging until the renamed profile is visible.
            context.setScreen(() -> new ProfilesScreen(null, profiles[0]));
            for (int i = 0; i < 2; i++) context.clickScreenButton("Next");
            context.clickScreenButton("Renamed");
            context.clickScreenButton("Delete");
            context.runOnClient(client -> {
                try { check(profiles[0].store().profiles().stream().anyMatch(p -> p.name().equals("Renamed")), "Delete skipped confirmation"); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            context.clickScreenButton("Confirm");
            context.runOnClient(client -> {
                try {
                    check(profiles[0].store().profiles().stream().noneMatch(p -> p.name().equals("Renamed")), "Confirmed deletion did not remove the profile");
                    // Simulate startup through a new manager using the same file and different live values.
                    profiles[0].create("Startup");
                    var restarted = new ClientProfiles(file[0], fps, coordinates, arraylist);
                    PlayerSettings.forceSneak = true;
                    restarted.restoreActive();
                    check(!PlayerSettings.forceSneak, "Startup restore did not load the active snapshot");
                    var invalid = profiles[0].store().load(original[0]);
                    invalid.setProperty("player.forceSneak", "true");
                    invalid.setProperty("hud.layout", "{\"fps\":{\"x\":2,\"y\":0,\"scale\":1}}");
                    var bad = profiles[0].store().create("Invalid", invalid);
                    profiles[0].store().activate(original[0]);
                    try { profiles[0].load(bad.id()); throw new AssertionError("Invalid layout was accepted"); }
                    catch (IllegalArgumentException expected) {
                        check(!PlayerSettings.forceSneak, "Failed validation partially changed player settings");
                        check(profiles[0].store().activeId().equals(original[0]), "Failed validation changed the startup profile");
                    }
                } catch (Exception e) { throw new AssertionError(e); }
            });
            System.out.println("PASS: profiles toolbar, create/load/save/rename/delete, live settings, layout, keybinds, pagination and resize");
        } finally {
            context.runOnClient(client -> {
                if (profiles[0] != null && original[0] != null) try { profiles[0].load(original[0]); }
                catch (Exception e) { throw new AssertionError("Could not restore test settings", e); }
            });
        }
    }
    private static EditBox name(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }
    private static Button button(Screen screen, String label) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
