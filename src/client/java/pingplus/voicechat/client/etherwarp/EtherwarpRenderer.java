package pingplus.voicechat.client.etherwarp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.phys.AABB;
import pingplus.voicechat.client.PlayerSettings;

/**
 * World-space etherwarp target overlay: the whole targeted block is filled
 * green when an etherwarp there is possible, red when it is not.
 * Reads the throttled, block-quantized cache from {@link EtherwarpHelper},
 * so it renders every frame without flickering.
 */
public final class EtherwarpRenderer extends EntityHitboxDebugRenderer {
    // Semi-transparent fill over the whole block plus a crisp stroke.
    private static final GizmoStyle VALID =
            GizmoStyle.strokeAndFill(0xFF00FF00, 2.0F, 0x8000FF00);
    private static final GizmoStyle INVALID =
            GizmoStyle.strokeAndFill(0xFFFF0000, 2.0F, 0x80FF0000);

    public EtherwarpRenderer() {
        super(Minecraft.getInstance());
    }

    @Override
    public void emitGizmos(double camX, double camY, double camZ,
                           DebugValueAccess debugValues, Frustum frustum, float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PlayerSettings.etherwarpHelper || minecraft.level == null) {
            return;
        }
        EtherwarpHelper.Result state = EtherwarpHelper.snapshot();
        if (!state.active() || state.target() == null) {
            return;
        }
        try {
            Gizmos.cuboid(new AABB(state.target()), state.valid() ? VALID : INVALID);
        } catch (Exception ignored) {
        }
    }
}
