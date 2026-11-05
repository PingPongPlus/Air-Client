package pingplus.voicechat.client.gui.glass;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

/** One bounded material overlay per glass surface; reuses the existing scene capture. */
public final class WeatherGlass {
    private static final long EPOCH = System.nanoTime();
    private static final WeatherGlassClimate CLIMATE = new WeatherGlassClimate();
    private static long previous, sampled;
    private static WeatherGlassClimate.Environment environment = new WeatherGlassClimate.Environment(false, false, false, 0);
    private static WeatherGlassClimate.Amounts amounts = new WeatherGlassClimate.Amounts(0, 0);

    public static WeatherGlassClimate.Amounts amounts() {
        long now = System.nanoTime();
        // Shared across every surface. Sample the biome at most ten times per second.
        if (now - sampled > 100_000_000L) {
            sampled = now;
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) environment = new WeatherGlassClimate.Environment(false, false, false, 0);
            else {
                var position = client.player.blockPosition().above();
                var biome = client.level.getBiome(position).value();
                boolean cold = biome.coldEnoughToSnow(position, client.level.getSeaLevel());
                float rain = biome.hasPrecipitation() ? client.level.getRainLevel(1) : 0;
                environment = new WeatherGlassClimate.Environment(true, client.level.canSeeSky(position), cold, rain);
            }
        }
        double dt = previous == 0 ? 0 : (now - previous) / 1e9;
        previous = now;
        amounts = CLIMATE.advance(WeatherGlassClimate.target(WeatherGlassSettings.values(), environment), dt);
        return amounts;
    }

    public static void draw(GuiGraphicsExtractor g, int x, int y, int width, int height, float opacity, boolean square, int shape) {
        var material = amounts();
        if (height < 6 || opacity <= 0 || Math.max(material.rain(), material.frost()) < .004) return;
        int packed = (Math.round(Math.clamp(opacity, 0, 1) * 255) << 24)
            | (Math.round(material.rain() * 255) << 16) | (Math.round(material.frost() * 255) << 8)
            | (square ? 0 : GlassCornerSettings.buttonQuant());
        float seconds = ((System.nanoTime() - EPOCH) / 1e9f) % 128;
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle scissor = g.scissorStack.peek();
        ScreenRectangle bounds = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
        if (scissor != null) bounds = bounds.intersection(scissor);
        if (bounds == null) return;
        GlassBackdrop.request();
        g.nextStratum();
        g.guiRenderState.addGuiElement(new Material(pose, x, y, width, height, packed, seconds, shape, scissor, bounds));
        g.nextStratum();
    }

    private record Material(Matrix3x2f pose, int x, int y, int width, int height, int color, float seconds,
                            int shape, ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
        public RenderPipeline pipeline() { return GlassPipelines.WEATHER; }
        public TextureSetup textureSetup() { return GlassBackdrop.sceneTexture(); }
        public void buildVertices(VertexConsumer v) {
            vertex(v, x, y, 0, 0); vertex(v, x, y + height, 0, 1);
            vertex(v, x + width, y + height, 1, 1); vertex(v, x + width, y, 1, 0);
        }
        private void vertex(VertexConsumer v, float px, float py, float u, float uv) {
            // Integer parts encode shared time and geometry; fractional parts remain local coordinates.
            v.addVertexWith2DPose(pose, px, py).setUv((float)Math.floor(seconds * 32) * 2 + u, (height * 4 + shape) * 2 + uv).setColor(color);
        }
    }
    private WeatherGlass() { }
}
