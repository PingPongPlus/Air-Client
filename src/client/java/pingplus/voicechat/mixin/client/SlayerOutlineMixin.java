package pingplus.voicechat.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.slayer.SlayerOutlineRenderer;

// Forces the vanilla entity outline (through walls, ESP style) onto
// slayer bosses/minibosses by setting the render state's outline color.
// Unlike the glowing flag, the server cannot reset this between frames,
// so the outline stays stable without flickering.
@Mixin(EntityRenderer.class)
public class SlayerOutlineMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void slayerOutline(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        int rgb = SlayerOutlineRenderer.colorFor(entity.getId());
        if (rgb >= 0) {
            state.outlineColor = ARGB.opaque(rgb);
        }
    }
}
