package pingplus.voicechat.mixin.client;

import net.minecraft.client.gui.render.TextureSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.render.GuiRenderer$Draw")
public interface GlassGuiDrawAccessor {
    @Accessor("textureSetup")
    TextureSetup voicechat$getTextureSetup();
}
