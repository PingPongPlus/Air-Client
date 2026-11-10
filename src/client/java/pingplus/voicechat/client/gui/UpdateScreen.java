package pingplus.voicechat.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import pingplus.voicechat.client.updater.ClientUpdater;

/** Download is explicit; closing this screen keeps the background download running. */
public final class UpdateScreen extends Screen {
    private final Screen parent;
    private final ClientUpdater updater = ClientUpdater.get();
    private Button action;
    private int top;

    public UpdateScreen(Screen parent) {
        super(Component.literal("Air Client update"));
        this.parent = parent;
    }

    @Override protected void init() {
        top = Math.max(12, height / 2 - 70);
        int buttonWidth = Math.min(150, (width - 28) / 2);
        action = addRenderableWidget(Button.builder(Component.literal("Update"), button -> {
            if (updater.snapshot().state() == ClientUpdater.State.READY) minecraft.stop();
            else if (updater.snapshot().release() != null) updater.download();
            else updater.check();
        }).bounds(width / 2 - buttonWidth - 4, top + 108, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 + 4, top + 108, buttonWidth, 20).build());
        refresh();
    }

    @Override public void tick() { refresh(); }

    private void refresh() {
        var state = updater.snapshot();
        action.setMessage(Component.literal(switch (state.state()) {
            case READY -> "Quit to apply update";
            case DOWNLOADING -> "Downloading...";
            case CHECKING -> "Checking...";
            default -> state.release() != null ? "Update" : "Check again";
        }));
        action.active = switch (state.state()) {
            case READY -> true;
            case DOWNLOADING, CHECKING -> false;
            default -> state.release() == null || updater.canInstall();
        };
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        var state = updater.snapshot();
        g.centeredText(font, title, width / 2, top, 0xFFFFFFFF);
        line(g, "Installed version: " + updater.version(), top + 22, 0xFFDDDDDD);
        if (state.release() != null) line(g, "Available version: " + state.release().version(), top + 36, 0xFFA4F0C1);
        line(g, state.message(), top + 56, state.state() == ClientUpdater.State.ERROR ? 0xFFFF9B99 : 0xFFFFFFFF);
        if (!updater.canInstall()) line(g, updater.installUnavailable(), top + 72, 0xFFFFCF8A);
        else if (state.state() == ClientUpdater.State.READY)
            line(g, "Launch Minecraft again after closing it.", top + 72, 0xFFDDDDDD);
        line(g, "Releases: " + updater.repository(), top + 90, 0xFFAAAAAA);
    }

    private void line(GuiGraphicsExtractor g, String text, int y, int color) {
        g.centeredText(font, font.plainSubstrByWidth(text, Math.max(40, width - 20)), width / 2, y, color);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
