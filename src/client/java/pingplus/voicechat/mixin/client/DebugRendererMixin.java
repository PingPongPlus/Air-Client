package pingplus.voicechat.mixin.client;

import java.util.List;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.GreenHitboxRenderer;
import pingplus.voicechat.client.ProjectilePreviewRenderer;
import pingplus.voicechat.client.StorageEspRenderer;

@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {
    @Shadow
    @Final
    private List<DebugRenderer.SimpleDebugRenderer> renderers;

    @Inject(method = "refreshRendererList", at = @At("TAIL"))
    private void voicechat$addFeatureRenderers(CallbackInfo ci) {
        renderers.add(new GreenHitboxRenderer());
        renderers.add(new ProjectilePreviewRenderer());
        renderers.add(new StorageEspRenderer());
        renderers.add(new pingplus.voicechat.client.etherwarp.EtherwarpRenderer());
    }
}
