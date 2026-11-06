package pingplus.voicechat.client.gui.glass;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2f;

/** The existing mark supplies the silhouette and relief; the scene supplies transmitted light. */
public final class GlassLogoRenderer {
    private static final Identifier MARK = Identifier.fromNamespaceAndPath("voicechat", "textures/gui/img_7.png");

    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float alpha) {
        if (width <= 0 || height <= 0 || alpha <= 0) return;
        if (Minecraft.getInstance().gui.overlay() instanceof LoadingOverlay) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, MARK, x, y, 0, 0, width, height,
                    1707, 924, 1707, 924, ARGB.white(alpha));
            return;
        }
        var backdrop = GlassBackdrop.request();
        graphics.nextStratum();
        var pose = new Matrix3x2f(graphics.pose());
        graphics.guiRenderState.addGuiElement(new LogoState(backdrop, pose, x, y, width, height, ARGB.white(alpha),
                new ScreenRectangle(x, y, width, height).transformMaxBounds(pose)));
        graphics.nextStratum();
    }

    private record LogoState(GlassBackdrop.Layer backdrop, Matrix3x2f pose, int x, int y, int width, int height, int color,
                             ScreenRectangle bounds) implements GuiElementRenderState {
        @Override public RenderPipeline pipeline() { return GlassPipelines.LOGO; }
        @Override public TextureSetup textureSetup() {
            return backdrop.logoTextures(Minecraft.getInstance().getTextureManager().getTexture(MARK).getTextureView());
        }
        @Override public ScreenRectangle scissorArea() { return null; }
        @Override public void buildVertices(VertexConsumer vertices) {
            vertex(vertices, x, y, 0, 0);
            vertex(vertices, x, y + height, 0, 1);
            vertex(vertices, x + width, y + height, 1, 1);
            vertex(vertices, x + width, y, 1, 0);
        }
        private void vertex(VertexConsumer vertices, float x, float y, float u, float v) {
            vertices.addVertexWith2DPose(pose, x, y).setUv(u, v).setColor(color);
        }
    }
    private GlassLogoRenderer() {}
}
