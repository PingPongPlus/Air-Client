package pingplus.voicechat.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.gui.glass.MenuThemeSettings;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    @Unique
    private static final double BACKGROUND_ZOOM = 1.04;
    @Unique
    private static final double MOUSE_MOVEMENT = 0.012;
    @Unique
    private double voicechat$mouseX;
    @Unique
    private double voicechat$mouseY;
    @Unique
    private long voicechat$lastFrameTime;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void voicechat$updateParallax(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                         float delta, CallbackInfo ci) {
        long now = System.nanoTime();
        double seconds = voicechat$lastFrameTime == 0 ? 0
                : Math.clamp((now - voicechat$lastFrameTime) / 1_000_000_000.0, 0.0, 0.1);
        voicechat$lastFrameTime = now;
        double targetX = Math.clamp(mouseX * 2.0 / Math.max(1, width) - 1.0, -1.0, 1.0);
        double targetY = Math.clamp(mouseY * 2.0 / Math.max(1, height) - 1.0, -1.0, 1.0);
        // Frame-rate-independent easing instead of snapping to the cursor.
        double blend = 1.0 - Math.exp(-8.0 * seconds);
        voicechat$mouseX += (targetX - voicechat$mouseX) * blend;
        voicechat$mouseY += (targetY - voicechat$mouseY) * blend;
    }

    // LabyMod's MixinExtras 0.5.4 cannot read the array-valued @Redirect.at
    // emitted by our newer Mixin dependency. WrapOperation avoids that reader.
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/client/gui/screens/TitleScreen;extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V")

    )
    private void drawCustomBackground(
            TitleScreen screen, GuiGraphicsExtractor graphics, float delta, Operation<Void> original
    ) {
        var background = MenuThemeSettings.background();
        int imageWidth = background.width();
        int imageHeight = background.height();
        // Extra coverage allows mouse movement without exposing the texture edges.
        double scale = Math.max(width / (double) imageWidth, height / (double) imageHeight)
                * BACKGROUND_ZOOM;
        int drawWidth = (int) Math.ceil(imageWidth * scale);
        int drawHeight = (int) Math.ceil(imageHeight * scale);
        double marginX = (drawWidth - width) / 2.0;
        double marginY = (drawHeight - height) / 2.0;
        double offsetX = voicechat$mouseX * Math.min(width * MOUSE_MOVEMENT, Math.max(0, marginX - 1));
        double offsetY = voicechat$mouseY * Math.min(height * MOUSE_MOVEMENT, Math.max(0, marginY - 1));
        // Use a floating-point translation so slow movement doesn't jump by GUI pixels.
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate((float) (-marginX + offsetX), (float) (-marginY + offsetY));
            graphics.blit(RenderPipelines.GUI_TEXTURED, background.id(),
                    0, 0, 0.0F, 0.0F, drawWidth, drawHeight,
                    imageWidth, imageHeight, imageWidth, imageHeight);
        } finally {
            graphics.pose().popMatrix();
        }
        pingplus.voicechat.client.gui.glass.GlassRain.draw(graphics);
    }
}
