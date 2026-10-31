package pingplus.voicechat.client.gui.glass;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.TextureSetup;

import java.util.Optional;

/** Render-thread-owned scene snapshot and shared half-resolution separable blur. */
public final class GlassBackdrop {
    private static TextureTarget scene;
    private static TextureTarget blurX;
    private static TextureTarget blurred;
    private static boolean requested;
    private static boolean captured;

    public static void request() { requested = true; }

    public static void beginFrame(RenderTarget target) {
        captured = false;
        if (!requested) { if (scene != null) close(); return; }
        requested = false;
        if (scene != null && scene.width == target.width && scene.height == target.height
                && scene.getColorTexture().getFormat() == target.getColorTexture().getFormat()) return;
        close();
        scene = new TextureTarget("Glass scene", target.width, target.height, false,
                target.getColorTexture().getFormat());
        int width = Math.max(1, target.width / 2);
        int height = Math.max(1, target.height / 2);
        blurX = new TextureTarget("Glass blur horizontal", width, height, false,
                target.getColorTexture().getFormat());
        blurred = new TextureTarget("Glass blur vertical", width, height, false,
                target.getColorTexture().getFormat());
    }

    public static boolean needsCapture() { return !captured; }

    /** Called between GUI render passes, after the backdrop and before the first glass draw. */
    public static void capture(RenderTarget target) {
        captureScene(target);
        if (GlassEffectSettings.blurStep() == 0) { captured = true; return; }
        blur(scene.getColorTextureView(), blurX, GlassPipelines.blurHorizontal());
        blur(blurX.getColorTextureView(), blurred, GlassPipelines.blurVertical());
        captured = true;
    }

    private static void captureScene(RenderTarget target) {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                target.getColorTexture(), scene.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
    }

    public static TextureSetup sceneTexture() {
        return TextureSetup.singleTexture(scene.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    public static TextureSetup logoTextures(GpuTextureView mark) {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        return TextureSetup.doubleTexture(mark, sampler, scene.getColorTextureView(), sampler);
    }

    private static void blur(GpuTextureView source, TextureTarget destination, RenderPipeline pipeline) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Glass background blur", destination.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("Sampler0", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    public static TextureSetup textures() {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        return TextureSetup.doubleTexture(scene.getColorTextureView(), sampler, GlassEffectSettings.blurStep() == 0 ? scene.getColorTextureView() : blurred.getColorTextureView(), sampler);
    }

    public static void close() {
        if (scene != null) scene.destroyBuffers();
        if (blurX != null) blurX.destroyBuffers();
        if (blurred != null) blurred.destroyBuffers();
        scene = blurX = blurred = null;
        captured = false;
    }

    private GlassBackdrop() {}
}
