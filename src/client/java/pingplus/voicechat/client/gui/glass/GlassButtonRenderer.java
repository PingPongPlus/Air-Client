package pingplus.voicechat.client.gui.glass;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

import java.util.Map;
import java.util.WeakHashMap;

/** Extracts glass geometry only; frame capture and shaders run later in GuiRenderer. */
public final class GlassButtonRenderer {
    private static final Map<AbstractWidget, Hover> HOVERS = new WeakHashMap<>();
    private static final int SHADOW_PADDING = 3;

    public static void draw(GuiGraphicsExtractor graphics, AbstractWidget button) {
        if (button.getWidth() <= 0 || button.getHeight() <= 0 || button.getAlpha() <= 0) return;
        Hover hover = HOVERS.computeIfAbsent(button, key -> new Hover());
        long now = System.nanoTime();
        double dt = hover.time == 0 ? 0 : Math.min(0.1, (now - hover.time) / 1e9);
        hover.time = now;
        float target = button.active && button.isHoveredOrFocused() ? 1 : 0;
        hover.value += (target - hover.value) * (float) (1 - Math.exp(-14 * dt));
        int data = (Math.round(Math.clamp(button.getAlpha(), 0, 1) * 255) << 24)
                | (Math.round(hover.value * 255) << 16) | (button.active ? 0xFF00 : 0);
        drawRect(graphics, button.getX(), button.getY(), button.getWidth(), button.getHeight(), data);
    }

    public static void drawRect(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int data) {
        drawShape(graphics, x, y, width, height, data, GlassPipelines.BUTTON);
    }

    public static void control(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        drawShape(graphics, x, y, width, height, color, GlassPipelines.CONTROL);
    }

    public static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        drawShape(graphics, x, y, width, height, color, GlassPipelines.BAR);
    }

    /** Boss health bar piece: alpha=opacity, red channel=fill flag, blue channel=shine phase. */
    public static void bossBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                               boolean fill, float phase, float opacity) {
        int data = (Math.round(Math.clamp(opacity, 0, 1) * 255) << 24)
                | (fill ? 0x00FF0000 : 0)
                | (Math.round(Math.clamp(phase, 0, 1) * 255) << 8);
        drawShape(graphics, x, y, width, height, data, GlassPipelines.BOSS_BAR);
    }

    private static void drawShape(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int data, RenderPipeline pipeline) {
        if (width <= 0 || height <= 0) return;
        if (net.minecraft.client.Minecraft.getInstance().gui.overlay() instanceof net.minecraft.client.gui.screens.LoadingOverlay) {
            GlassStyle.round(graphics, x, y, width, height, 12, GlassStyle.alpha(0xFF263D54, (data >>> 24) / 255f));
            return;
        }
        if (pipeline == GlassPipelines.BUTTON) {
            // Green channel packs the enabled bit (bit 7) plus the global corner-radius scale (bits 0-6).
            boolean enabled = (data & 0x00FF00) != 0;
            data = (data & 0xFFFF00FF) | (((enabled ? 128 : 0) + GlassCornerSettings.buttonQuant()) << 8);
            data = (data & 0xFFFFFF00) | Math.min(height, 255);
        } else {
            // All control colors are grayscale: keep intensity in red/blue, corner scale (8-bit) in green.
            int gray = (data >>> 16) & 0xFF;
            data = (data & 0xFF000000) | (gray << 16) | (GlassCornerSettings.controlQuant() << 8) | gray;
        }
        GlassBackdrop.request();
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        ScreenRectangle scissor = graphics.scissorStack.peek();
        ScreenRectangle bounds = new ScreenRectangle(x - SHADOW_PADDING, y - SHADOW_PADDING,
                width + 2 * SHADOW_PADDING, height + 2 * SHADOW_PADDING).transformMaxBounds(pose);
        if (scissor != null) bounds = bounds.intersection(scissor);
        if (bounds == null) return;
        graphics.guiRenderState.addGuiElement(new GlassState(pipeline, pose, x, y, width, height, data, scissor, bounds));
    }

    private record GlassState(RenderPipeline pipeline, Matrix3x2f pose, int x, int y, int width, int height, int data,
                              ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
        // Resolved during render preparation, after beginFrame() allocates the targets.
        @Override public TextureSetup textureSetup() { return GlassBackdrop.textures(); }
        @Override public void buildVertices(VertexConsumer vertices) {
            float p = SHADOW_PADDING;
            vertex(vertices, x - p, y - p, -p / width, -p / height);
            vertex(vertices, x - p, y + height + p, -p / width, 1 + p / height);
            vertex(vertices, x + width + p, y + height + p, 1 + p / width, 1 + p / height);
            vertex(vertices, x + width + p, y - p, 1 + p / width, -p / height);
        }
        private void vertex(VertexConsumer vertices, float x, float y, float u, float v) {
            vertices.addVertexWith2DPose(pose, x, y).setUv(u, v).setColor(data);
        }
    }

    private static final class Hover { float value; long time; }
    private GlassButtonRenderer() {}
}
