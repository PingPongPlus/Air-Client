package pingplus.voicechat.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.PlayerSettings;

// Feature: hide names above mobs (all non-player entities) when enabled.
// SkyHanni does the same via CheckRenderEntityEvent (HideMobNames.kt),
// here we simply null the name tag in the render state.
@Mixin(EntityRenderer.class)
public class MobNameHideMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void hideMobNames(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (PlayerSettings.hideMobNames && !(entity instanceof Player)) {
            state.nameTag = null;
        }
    }
}
