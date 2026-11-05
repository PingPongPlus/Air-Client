package pingplus.voicechat.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.gui.glass.GlassBackdrop;
import pingplus.voicechat.client.gui.glass.GlassPipelines;

import java.util.List;
import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public abstract class GlassGuiRendererMixin {
    @Shadow @Final private List<?> draws;

    @Inject(method = "render", at = @At("HEAD"))
    private void voicechat$prepareGlass(CallbackInfo ci) {
        GlassBackdrop.beginFrame(Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }

    @WrapMethod(method = "executeDrawRange")
    private void voicechat$captureBeforeGlass(Supplier<String> label, RenderTarget target,
                                             GpuBufferSlice transforms, int start, int end,
                                             Operation<Void> original) {
        if (GlassBackdrop.needsCapture()) {
            for (int i = start; i < end; i++) {
                var pipeline = ((GlassGuiDrawAccessor) draws.get(i)).voicechat$getPipeline();
                if (GlassPipelines.isButton(pipeline) || pipeline == GlassPipelines.CONTROL || pipeline == GlassPipelines.LOGO
                        || pipeline == GlassPipelines.BAR || pipeline == GlassPipelines.BOSS_BAR || pipeline == GlassPipelines.WEATHER) {
                    // Close the background pass before copying: never read from an active color attachment.
                    if (i > start) original.call(label, target, transforms, start, i);
                    GlassBackdrop.capture(target);
                    original.call(label, target, transforms, i, end);
                    return;
                }
            }
        }
        original.call(label, target, transforms, start, end);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voicechat$closeGlass(CallbackInfo ci) {
        GlassBackdrop.close();
    }
}
