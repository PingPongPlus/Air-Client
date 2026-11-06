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
        int pendingStart = start;
        for (int i = start; i < end; i++) {
            var textures = ((GlassGuiDrawAccessor) draws.get(i)).voicechat$getTextureSetup();
            var layer = GlassBackdrop.pendingCapture(textures);
            if (layer == null) continue;
            // Finish preceding HUD/background draws before capturing each layer independently.
            // HUD and screen glass can occur in the same range when a screen skips vanilla blur.
            if (i > pendingStart) original.call(label, target, transforms, pendingStart, i);
            layer.capture(target);
            pendingStart = i;
        }
        if (pendingStart < end) original.call(label, target, transforms, pendingStart, end);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voicechat$closeGlass(CallbackInfo ci) {
        GlassBackdrop.close();
    }
}
