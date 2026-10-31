package pingplus.voicechat.client.gui.glass;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/** One continuous, cursor-driven backdrop shared by menus and loading screens. */
public final class MenuBackground {
    private static final double ZOOM = 1.04;
    private static final double MOUSE_MOVEMENT = 0.012;
    private static double mouseX;
    private static double mouseY;
    private static long lastFrameTime;

    public static void update(int cursorX, int cursorY, int width, int height) {
        long now = System.nanoTime();
        double seconds = lastFrameTime == 0 ? 0
                : Math.clamp((now - lastFrameTime) / 1_000_000_000.0, 0.0, 0.1);
        lastFrameTime = now;
        double targetX = Math.clamp(cursorX * 2.0 / Math.max(1, width) - 1.0, -1.0, 1.0);
        double targetY = Math.clamp(cursorY * 2.0 / Math.max(1, height) - 1.0, -1.0, 1.0);
        double blend = 1.0 - Math.exp(-8.0 * seconds);
        mouseX += (targetX - mouseX) * blend;
        mouseY += (targetY - mouseY) * blend;
    }

    public static void draw(GuiGraphicsExtractor graphics) {
        drawWallpaper(graphics);
    }

    public static void drawWallpaper(GuiGraphicsExtractor graphics) {
        var background = MenuThemeSettings.background();
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        int imageWidth = background.width(), imageHeight = background.height();
        // Overscan keeps the texture edges covered at every cursor position and aspect ratio.
        double scale = Math.max(width / (double) imageWidth, height / (double) imageHeight) * ZOOM;
        int drawWidth = (int) Math.ceil(imageWidth * scale);
        int drawHeight = (int) Math.ceil(imageHeight * scale);
        double marginX = (drawWidth - width) / 2.0;
        double marginY = (drawHeight - height) / 2.0;
        double offsetX = mouseX * Math.min(width * MOUSE_MOVEMENT, Math.max(0, marginX - 1));
        double offsetY = mouseY * Math.min(height * MOUSE_MOVEMENT, Math.max(0, marginY - 1));
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate((float) (-marginX + offsetX), (float) (-marginY + offsetY));
            graphics.blit(RenderPipelines.GUI_TEXTURED, background.id(),
                    0, 0, 0.0F, 0.0F, drawWidth, drawHeight,
                    imageWidth, imageHeight, imageWidth, imageHeight);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private MenuBackground() { }
}
