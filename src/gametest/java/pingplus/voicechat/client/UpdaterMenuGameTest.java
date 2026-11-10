package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import pingplus.voicechat.client.gui.UpdateScreen;
import pingplus.voicechat.client.updater.ClientUpdater;
import pingplus.voicechat.client.updater.GitHubReleases;

import java.net.URI;

/** Deterministic menu states without publishing a release or writing into an actual mods folder. */
public final class UpdaterMenuGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var updater = ClientUpdater.get();
        var original = updater.snapshot();
        var release = new GitHubReleases.Release("0.1.4", URI.create("https://github.com/AeroAlpine/Air-Client/releases/download/v0.1.4/Air-Client-0.1.4.jar"), 1024, "a".repeat(64));
        try {
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            setState(context, new ClientUpdater.Snapshot(ClientUpdater.State.CURRENT, "You are up to date.", 0, null));
            context.setScreen(TitleScreen::new);
            context.waitTicks(5);
            context.runOnClient(client -> {
                check(client.gui.screen().children().stream().anyMatch(child -> child instanceof AbstractWidget widget
                        && widget.getMessage().getString().contains("Air Client v" + updater.version())), "Installed version missing from title screen");
                check(client.gui.screen().children().stream().noneMatch(child -> child instanceof Button button
                        && button.visible && button.getMessage().getString().equals("Update")), "Update button visible without a newer release");
            });
            context.takeScreenshot("updater-current");
            // Change state after init: a completed background check must update the existing screen.
            setState(context, new ClientUpdater.Snapshot(ClientUpdater.State.AVAILABLE, "Air Client 0.1.4 is available.", 0, release));
            context.waitTicks(3);
            context.runOnClient(client -> check(client.gui.screen().children().stream().anyMatch(child -> child instanceof Button button
                    && button.visible && button.getMessage().getString().equals("Update")), "Update button did not appear after the background check"));
            context.takeScreenshot("updater-available");
            context.clickScreenButton("Update");
            context.waitForScreen(UpdateScreen.class);
            context.waitTicks(5);
            context.runOnClient(client -> {
                check(!updater.canInstall(), "Game tests must never install updates");
                check(client.gui.screen().children().stream().anyMatch(child -> child instanceof Button button
                        && button.getMessage().getString().equals("Update") && !button.active), "Development installs must be disabled");
            });
            context.takeScreenshot("updater-details");
            setState(context, new ClientUpdater.Snapshot(ClientUpdater.State.READY, "Update downloaded. Restart Minecraft to apply it.", 100, release));
            context.waitTicks(3);
            context.runOnClient(client -> check(client.gui.screen().children().stream().anyMatch(child -> child instanceof Button button
                    && button.getMessage().getString().equals("Quit to apply update") && button.active), "Restart action missing"));
            context.takeScreenshot("updater-ready");
            context.clickScreenButton("Back");
            context.waitForScreen(TitleScreen.class);
            context.getInput().resizeWindow(854, 480);
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            context.waitTicks(5);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                var button = screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals("Restart"))
                        .map(child -> (Button) child).findFirst().orElseThrow();
                check(button.visible && button.getX() >= 0 && button.getRight() <= screen.width
                        && button.getY() >= 0 && button.getBottom() <= screen.height, "Updater is outside the compact menu");
            });
            context.takeScreenshot("updater-compact");
            System.out.println("PASS: installed version, live update badge, update screen, development safeguards, restart action and compact layout");
        } finally { setState(context, original); }
    }

    private static void setState(ClientGameTestContext context, ClientUpdater.Snapshot snapshot) {
        context.runOnClient(client -> {
            try {
                var field = ClientUpdater.class.getDeclaredField("snapshot");
                field.setAccessible(true); field.set(ClientUpdater.get(), snapshot);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
