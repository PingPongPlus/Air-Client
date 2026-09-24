package pingplus.voicechat.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class CoordinatesHud {
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

        String text = "XYZ  "
                + client.player.getBlockX() + " "
                + client.player.getBlockY() + " "
                + client.player.getBlockZ();

        graphics.text(client.font, client.font.plainSubstrByWidth(text, 192), 4, 4, GuiTheme.TEXT, false);
    }
}
