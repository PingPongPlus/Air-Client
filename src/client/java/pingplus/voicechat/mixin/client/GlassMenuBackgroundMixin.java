package pingplus.voicechat.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.gui.glass.GlassRain;
import pingplus.voicechat.client.gui.glass.MenuBackground;

@Mixin(Screen.class)
public abstract class GlassMenuBackgroundMixin {
    // This final entry point runs before backgrounds, even when a screen overrides rendering.
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void updateParallax(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MenuBackground.update(mouseX, mouseY, g.guiWidth(), g.guiHeight());
    }

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void mountainAir(GuiGraphicsExtractor g, float delta, CallbackInfo ci) {
        MenuBackground.draw(g);
        ci.cancel();
    }

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void keepWallpaperSharp(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (Minecraft.getInstance().level == null) ci.cancel();
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("HEAD"), cancellable = true)
    private void rainBackground(GuiGraphicsExtractor g, CallbackInfo ci) {
        menuShade(g, 0, 0, g.guiWidth(), g.guiHeight());
        ci.cancel();
    }

    // Tabbed screens override the full-screen method and call this bounded overload instead.
    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V",
            at = @At("HEAD"), cancellable = true)
    private void boundedBackground(GuiGraphicsExtractor g, int x, int y, int width, int height, CallbackInfo ci) {
        menuShade(g, x, y, width, height);
        ci.cancel();
    }

    @Unique
    private static void menuShade(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        boolean inWorld = Minecraft.getInstance().level != null;
        g.fillGradient(x, y, x + width, y + height,
                inWorld ? 0x40101920 : 0x18101920, inWorld ? 0x70101920 : 0x48101920);
        // Outside a world the panorama hook already drew rain over the wallpaper.
        if (inWorld) GlassRain.draw(g);
    }
}
