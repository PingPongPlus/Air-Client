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
        drawShape(graphics, x, y, width, height, data, GlassPipelines.button());
    }

    public static void drawHudRect(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int data,
                                   boolean square, boolean edges) {
        drawShape(graphics, x, y, width, height, data, GlassPipelines.hud(edges), square);
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
        drawShape(graphics, x, y, width, height, data, pipeline, false);
    }

    private static void drawShape(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int data,
                                  RenderPipeline pipeline, boolean square) {
        if (width <= 0 || height <= 0) return;
        float opacity = (data >>> 24) / 255f;
        if (net.minecraft.client.Minecraft.getInstance().gui.overlay() instanceof net.minecraft.client.gui.screens.LoadingOverlay) {
            GlassStyle.round(graphics, x, y, width, height, square ? 0 : 12, GlassStyle.alpha(0xFF263D54, (data >>> 24) / 255f));
            drawTint(graphics,x,y,width,height,opacity,pipeline,square);
            return;
        }
        if (GlassPipelines.isButton(pipeline)) {
            // Green channel packs the enabled bit (bit 7) plus the global corner-radius scale (bits 0-6).
            boolean enabled = (data & 0x00FF00) != 0;
            data = (data & 0xFFFF00FF) | (((enabled ? 128 : 0) + (square ? 0 : GlassCornerSettings.buttonQuant())) << 8);
            data = (data & 0xFFFFFF00) | Math.min(height, 255);
        } else {
            // All control colors are grayscale: keep intensity in red/blue, corner scale (8-bit) in green.
            int gray = (data >>> 16) & 0xFF;
            data = (data & 0xFF000000) | (gray << 16) | (GlassCornerSettings.controlQuant() << 8) | gray;
        }
        GlassBackdrop.request();
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        ScreenRectangle scissor = graphics.scissorStack.peek();
        int padding = pipeline == GlassPipelines.BOSS_BAR ? 8 : GlassPipelines.isButton(pipeline) ? Math.max(1, (int)Math.ceil(SHADOW_PADDING * GlassEffectSettings.shadowScale())) : SHADOW_PADDING;
        ScreenRectangle bounds = new ScreenRectangle(x - padding, y - padding,
                width + 2 * padding, height + 2 * padding).transformMaxBounds(pose);
        if (scissor != null) bounds = bounds.intersection(scissor);
        if (bounds == null) return;
        graphics.guiRenderState.addGuiElement(new GlassState(pipeline, pose, x, y, width, height, data, padding, scissor, bounds));
        drawTint(graphics,x,y,width,height,opacity,pipeline,square);
        WeatherGlass.draw(graphics, x, y, width, height, opacity, square,
                GlassPipelines.isButton(pipeline) ? 0 : pipeline == GlassPipelines.CONTROL ? 1 : pipeline == GlassPipelines.BOSS_BAR ? 3 : 2);
    }

    private static void drawTint(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                 float opacity, RenderPipeline pipeline, boolean square) {
        if (GlassPipelines.isButton(pipeline) || GlassEffectSettings.customTint()) {
            int tint = GlassStyle.alpha(pingplus.voicechat.client.spotify.MusicGlass.tint(), opacity);
            if ((tint >>> 24) != 0) {
                graphics.nextStratum();
                float half = Math.min(width,height)/2f;
                if (GlassPipelines.isButton(pipeline)) {
                    int radius = square ? 0 : Math.min((int)half, height > 40 ? 14 : Integer.MAX_VALUE);
                    GlassStyle.round(graphics,x,y,width,height,radius,tint);
                } else if (pipeline == GlassPipelines.BOSS_BAR) {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(x+width/2f,y+height/2f);
                    graphics.pose().mul(new Matrix3x2f(1,0,.22f,1,0,0));
                    graphics.pose().translate(-width/2f,-height/2f);
                    GlassStyle.roundExact(graphics,0,0,width,height,
                            Math.min(half,5f/(float)net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()),tint);
                    graphics.pose().popMatrix();
                } else {
                    // These shader radii use physical pixels, including compact health/bar pieces.
                    float radius = pipeline == GlassPipelines.CONTROL
                            ? half*.96f*GlassCornerSettings.controlQuant()/128f
                            : Math.min(half,4f/(float)net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
                    GlassStyle.roundExact(graphics,x,y,width,height,radius,tint);
                }
                graphics.nextStratum();
            }
        }
    }

    private record GlassState(RenderPipeline pipeline, Matrix3x2f pose, int x, int y, int width, int height, int data, int padding,
                              ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
        // Resolved during render preparation, after beginFrame() allocates the targets.
        @Override public TextureSetup textureSetup() { return GlassBackdrop.textures(); }
        @Override public void buildVertices(VertexConsumer vertices) {
            float p = padding;
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
