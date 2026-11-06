package pingplus.voicechat.client.gui.glass;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.TextureSetup;

import java.util.Optional;

/** HUD and screen glass each share a scene snapshot and half-resolution separable blur. */
public final class GlassBackdrop {
    private static final Layer HUD = new Layer("HUD");
    private static final Layer SCREEN = new Layer("screen");
    private static final ThreadLocal<Layer> EXTRACTING = ThreadLocal.withInitial(() -> HUD);

    /** Keep the extraction layer on each element: textures are resolved later by GuiRenderer. */
    public static Layer request() {
        Layer layer = EXTRACTING.get();
        layer.requested = true;
        return layer;
    }

    public static Layer beginScreen() {
        Layer previous = EXTRACTING.get();
        EXTRACTING.set(SCREEN);
        return previous;
    }

    public static void endScreen(Layer previous) { EXTRACTING.set(previous); }

    public static void beginFrame(RenderTarget target) {
        HUD.beginFrame(target);
        SCREEN.beginFrame(target);
    }

    /** Identify the scene actually bound by this draw, including logos' second sampler. */
    public static Layer pendingCapture(TextureSetup textures) {
        if (HUD.needsCapture(textures)) return HUD;
        if (SCREEN.needsCapture(textures)) return SCREEN;
        return null;
    }

    public static void close() {
        HUD.close();
        SCREEN.close();
    }

    public static final class Layer {
        private final String name;
        private TextureTarget scene;
        private TextureTarget blurX;
        private TextureTarget blurred;
        private boolean requested;
        private boolean captured;

        private Layer(String name) { this.name = name; }

        private void beginFrame(RenderTarget target) {
            captured = false;
            if (!requested) { if (scene != null) close(); return; }
            requested = false;
            if (scene != null && scene.width == target.width && scene.height == target.height
                    && scene.getColorTexture().getFormat() == target.getColorTexture().getFormat()) return;
            close();
            scene = new TextureTarget("Glass " + name + " scene", target.width, target.height, false,
                    target.getColorTexture().getFormat());
            int width = Math.max(1, target.width / 2);
            int height = Math.max(1, target.height / 2);
            blurX = new TextureTarget("Glass " + name + " blur horizontal", width, height, false,
                    target.getColorTexture().getFormat());
            blurred = new TextureTarget("Glass " + name + " blur vertical", width, height, false,
                    target.getColorTexture().getFormat());
        }

        private boolean needsCapture(TextureSetup textures) {
            if (captured || scene == null) return false;
            var view = scene.getColorTextureView();
            return textures.texure0() == view || textures.texure1() == view;
        }

        /** Called after this layer's background pass and before its first glass draw. */
        public void capture(RenderTarget target) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    target.getColorTexture(), scene.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
            if (GlassEffectSettings.blurStep() != 0) {
                blur(scene.getColorTextureView(), blurX, GlassPipelines.blurHorizontal());
                blur(blurX.getColorTextureView(), blurred, GlassPipelines.blurVertical());
            }
            captured = true;
        }

        public TextureSetup sceneTexture() {
            return TextureSetup.singleTexture(scene.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }

        public TextureSetup logoTextures(GpuTextureView mark) {
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            return TextureSetup.doubleTexture(mark, sampler, scene.getColorTextureView(), sampler);
        }

        public TextureSetup textures() {
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            return TextureSetup.doubleTexture(scene.getColorTextureView(), sampler,
                    GlassEffectSettings.blurStep() == 0 ? scene.getColorTextureView() : blurred.getColorTextureView(), sampler);
        }

        private void close() {
            if (scene != null) scene.destroyBuffers();
            if (blurX != null) blurX.destroyBuffers();
            if (blurred != null) blurred.destroyBuffers();
            scene = blurX = blurred = null;
            captured = false;
        }
    }

    private static void blur(GpuTextureView source, TextureTarget destination, RenderPipeline pipeline) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Glass background blur", destination.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("Sampler0", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    private GlassBackdrop() {}
}
