package pingplus.voicechat.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.PlayerSettings;

// Feature: hide ALL particles when enabled. Clearing the render state and
// cancelling the extract pass means nothing gets submitted for rendering.
@Mixin(ParticleEngine.class)
public class ParticleHideMixin {
    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void hideAllParticles(ParticlesRenderState state, Frustum frustum, Camera camera, float tickDelta, CallbackInfo ci) {
        if (PlayerSettings.hideParticles) {
            state.reset();
            ci.cancel();
        }
    }
}
