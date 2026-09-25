package pingplus.voicechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-seitige gelbe Outline für Slayer-Bosse.
 * Funktioniert auch wenn der Server den Vanilla-Glow zurücksetzt
 * (Hypixel überschreibt Entity-Metadaten jeden Tick).
 * Gleiches Prinzip wie GreenHitboxRenderer, nur gelb + gefiltert.
 */
public final class SlayerBossOutlineRenderer extends EntityHitboxDebugRenderer {
    // Gelb, clean leuchtend: volle Outline + leicht transparente Füllung
    private static final GizmoStyle STYLE = GizmoStyle.strokeAndFill(
        0xFFFFFF00, 2.0F, 0x40FFFF00);

    public SlayerBossOutlineRenderer() {
        super(Minecraft.getInstance());
    }

    @Override
    public void emitGizmos(double camX, double camY, double camZ,
                           DebugValueAccess debugValues, Frustum frustum, float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PlayerSettings.slayerBossHighlight || minecraft.level == null) {
            return;
        }

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!SlayerBossHighlight.isSlayerBoss(entity)) continue;
            if (entity.isRemoved()) continue;

            // Smooth position wie im GreenHitboxRenderer
            float pt;
            try {
                pt = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(
                    !minecraft.level.tickRateManager().isEntityFrozen(entity));
            } catch (NoSuchMethodError | Exception e) {
                pt = partialTicks;
            }
            Vec3 offset;
            try {
                offset = entity.getPosition(pt).subtract(entity.position());
            } catch (NoSuchMethodError | Exception e) {
                offset = Vec3.ZERO;
            }
            try {
                Gizmos.cuboid(entity.getBoundingBox().move(offset).inflate(0.15), STYLE);
            } catch (Exception ignored) {
            }
        }
    }
}
