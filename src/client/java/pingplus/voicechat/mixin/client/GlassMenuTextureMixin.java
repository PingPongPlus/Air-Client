package pingplus.voicechat.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remove tiled menu decorations, including those drawn directly by lists and tab headers. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GlassMenuTextureMixin {
    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V",
            at = @At("HEAD"), cancellable = true)
    private void transparentMenuTexture(RenderPipeline pipeline, Identifier texture, int x, int y,
                                        float u, float v, int width, int height,
                                        int textureWidth, int textureHeight, CallbackInfo ci) {
        if (!texture.getNamespace().equals("minecraft")) return;
        int color = switch (texture.getPath()) {
            case "textures/gui/menu_background.png", "textures/gui/inworld_menu_background.png",
                    "textures/gui/menu_list_background.png", "textures/gui/inworld_menu_list_background.png" -> 0x24101920;
            case "textures/gui/tab_header_background.png" -> 0x30101920;
            case "textures/gui/header_separator.png", "textures/gui/footer_separator.png",
                    "textures/gui/inworld_header_separator.png", "textures/gui/inworld_footer_separator.png" -> 0x30FFFFFF;
            default -> 0;
        };
        if (color == 0) return;
        ((GuiGraphicsExtractor) (Object) this).fill(x, y, x + width, y + height, color);
        ci.cancel();
    }
}
