package pingplus.voicechat.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.gui.glass.MenuThemeSettings;

@Mixin(LogoRenderer.class)
public abstract class LogoRendererMixin {
    @Shadow
    public abstract boolean keepLogoThroughFade();

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void drawCustomLogo(
            GuiGraphicsExtractor graphics,
            int width,
            float alpha,
            int heightOffset,
            CallbackInfo ci
    ) {
        var logo = MenuThemeSettings.logo();
        int textureWidth = logo.width();
        int textureHeight = logo.height();

        // Fit the supplied artwork above the menu without changing its proportions.
        int y = Math.max(0, heightOffset - 30);
        int headerSpace = Math.max(0, graphics.guiHeight() / 4 + 40 - y);
        int maxWidth = MenuThemeSettings.isDarkMode() ? 360 : 216;
        double scale = Math.min(Math.min(maxWidth, Math.max(0, width - 24)) / (double) textureWidth,
                headerSpace / (double) textureHeight);
        int logoWidth = (int) Math.round(textureWidth * scale);
        int logoHeight = (int) Math.round(textureHeight * scale);

        int x = (width - logoWidth) / 2;
        float effectiveAlpha = keepLogoThroughFade() ? 1.0F : alpha;
        if (logoWidth > 0 && logoHeight > 0 && effectiveAlpha > 0) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, logo.id(), x, y, 0, 0, logoWidth, logoHeight,
                    textureWidth, textureHeight, textureWidth, textureHeight, ARGB.white(effectiveAlpha));
        }

        // Skip both the Minecraft logo and the Java Edition subtitle.
        ci.cancel();
    }
}
