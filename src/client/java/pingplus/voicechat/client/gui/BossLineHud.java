package pingplus.voicechat.client.gui;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import pingplus.voicechat.client.slayer.SlayerMobDetector;
import pingplus.voicechat.client.slayer.SlayerOutlineRenderer;

// Clean screen-space ESP line from the player to every highlighted
// boss/miniboss, in the outline colour (yellow boss, red miniboss).
// Drawn as a 2D line on the HUD, so it is stable and identical in first
// and third person and is not hidden by depth testing.
public final class BossLineHud {
    private BossLineHud() {}

    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (!PlayerSettings.slayerLine) return;

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 forward = new Vec3(camera.forwardVector());

        // Slightly in front of the face, so the projection is stable in F1.
        Vec3 look = client.player.getLookAngle();
        Vec3 eyeAnchor = client.player.getPosition(1.0F)
                .add(0, client.player.getEyeHeight(), 0)
                .add(look.x * 0.4, look.y * 0.4, look.z * 0.4);

        for (SlayerOutlineRenderer.Target target : SlayerOutlineRenderer.targets()) {
            if (target.kind() != SlayerMobDetector.Kind.BOSS
                    && target.kind() != SlayerMobDetector.Kind.MINIBOSS) {
                continue;
            }
            Entity entity = client.level.getEntity(target.id());
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
                continue;
            }
            Vec3 anchor = living.position().add(0, living.getBbHeight() * 0.6, 0);
            Vec3 rel = anchor.subtract(camera.position());
            if (rel.dot(forward) <= 0.1) continue;

            Vec3 ndc = client.gameRenderer.projectPointToScreen(anchor);
            if (ndc.z < -1 || ndc.z > 1) continue;
            Vec3 eyeNdc = client.gameRenderer.projectPointToScreen(eyeAnchor);
            if (eyeNdc.z < -1 || eyeNdc.z > 1) continue;

            double ax = (eyeNdc.x * 0.5 + 0.5) * width;
            double ay = (0.5 - eyeNdc.y * 0.5) * height;
            double bx = (ndc.x * 0.5 + 0.5) * width;
            double by = (0.5 - ndc.y * 0.5) * height;

            int rgb = target.kind() == SlayerMobDetector.Kind.BOSS
                    ? PlayerSettings.slayerBossColor & 0xFFFFFF
                    : PlayerSettings.slayerMinibossColor & 0xFFFFFF;
            drawLine(graphics, ax, ay, bx, by, rgb);
        }
    }

    private static void drawLine(GuiGraphicsExtractor g, double ax, double ay, double bx, double by, int rgb) {
        double dx = bx - ax;
        double dy = by - ay;
        int len = (int) Math.ceil(Math.hypot(dx, dy));
        if (len < 2) return;

        g.pose().pushMatrix();
        g.pose().translate((float) ax, (float) ay);
        g.pose().rotate((float) Math.atan2(dy, dx));
        // Soft glow underlay, then the thin crisp core.
        g.fill(0, -3, len, 6, GlassStyle.alpha(0xFF000000 | rgb, 0.22f));
        g.fill(0, -1, len, 2, GlassStyle.alpha(0xFF000000 | rgb, 0.9f));
        g.pose().popMatrix();
    }
}
