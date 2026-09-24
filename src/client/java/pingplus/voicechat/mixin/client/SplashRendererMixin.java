package pingplus.voicechat.mixin.client;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;

@Mixin(SplashRenderer.class)
public abstract class SplashRendererMixin {

    Component customSplash = Component.literal("by pingplus & Immamaster");    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void drawCustomSplah(
            GuiGraphicsExtractor graphics,
            int screenWidth,
            Font font,
            float alpha,
            CallbackInfo ci
    ){

        int textWidth = font.width(customSplash);
        ActiveTextCollector textRenderer = graphics.textRenderer();
        float textPhase = 1.8F - Mth.abs(Mth.sin((double)((float)(Util.getMillis() % 1000L) / 1000.0F * ((float)Math.PI * 2F))) * 0.1F);
        float textScale = textPhase * 100 / (float)(textWidth + 32);
        Matrix3x2f transform = (new Matrix3x2f(textRenderer.defaultParameters().pose())).translate((float)screenWidth / 2.0F + 123.0F, 69.0F).rotate(-0.34906584F).scale(textScale);
        ActiveTextCollector.Parameters renderParameters = textRenderer.defaultParameters().withOpacity(alpha).withPose(transform);
        textRenderer.accept(TextAlignment.LEFT, -textWidth / 2 -40, 0, renderParameters, customSplash);
        ci.cancel();
    }
}
