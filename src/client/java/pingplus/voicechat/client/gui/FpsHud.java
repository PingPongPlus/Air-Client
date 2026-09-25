package pingplus.voicechat.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Holds the FPS toggle and draws its display during gameplay. */
public final class FpsHud {
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    public void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.player == null) {
            return;
        }

        String text = "FPS  " + client.getFps();
        graphics.text(client.font, text, 4, 4, GuiTheme.TEXT, false);
    }
}
