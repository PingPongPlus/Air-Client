package pingplus.voicechat.client.gui;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
// The start point is the player's CURRENT interpolated position, recomputed
// every frame (never cached), so the line always begins in the middle of the
// player even while moving. Boss endpoints are smoothed in screen space and
// snapped to whole pixels so the one-pixel line stays thin and steady while
// the camera or the boss moves, and is identical in first and third person.
public final class BossLineHud {
    private static final Map<Integer, Endpoint> ENDPOINTS = new ConcurrentHashMap<>();
    private static long lastFrame;

    private BossLineHud() {}

    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) { ENDPOINTS.clear(); return; }
        if (!PlayerSettings.slayerLine) { ENDPOINTS.clear(); return; }

        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.05F : Math.min(0.1F, (now - lastFrame) / 1e9F);
        lastFrame = now;
        float blend = (float) (1 - Math.exp(-22 * dt));

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 forward = new Vec3(camera.forwardVector());

        // Render partial tick: interpolated player position (last tick -> current).
        float tickDelta = 1.0F;
        try {
            tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(
                    !client.level.tickRateManager().isEntityFrozen(client.player));
        } catch (NoSuchMethodError | Exception ignored) {
            // API drift: fall back to the raw current position.
        }

        // Anchor slightly in front of the face: on the camera axis it always
        // projects to the screen centre, so rotation never jerks the start.
        // Interpolated player position (last tick -> current tick), recomputed
        // fresh every frame so player movement never lags behind.
        Vec3 look = client.player.getLookAngle();
        Vec3 eyeAnchor = client.player.getPosition(tickDelta)
                .add(0, client.player.getEyeHeight(), 0)
                .add(look.x * 0.4, look.y * 0.4, look.z * 0.4);

        Vec3 eyeNdc = client.gameRenderer.projectPointToScreen(eyeAnchor);
        double startX = width / 2.0, startY = height / 2.0;
        if (eyeNdc.z >= -1 && eyeNdc.z <= 1) {
            startX = (eyeNdc.x * 0.5 + 0.5) * width;
            startY = (0.5 - eyeNdc.y * 0.5) * height;
        }

        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        for (SlayerOutlineRenderer.Target target : SlayerOutlineRenderer.targets()) {
            if (target.kind() != SlayerMobDetector.Kind.BOSS
                    && target.kind() != SlayerMobDetector.Kind.MINIBOSS) {
                continue;
            }
            seen.add(target.id());
            Entity entity = client.level.getEntity(target.id());
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
                continue;
            }
            Vec3 anchor = living.position().add(0, living.getBbHeight() * 0.6, 0);
            Vec3 rel = anchor.subtract(camera.position());
            if (rel.dot(forward) <= 0.1) continue;

            Vec3 ndc = client.gameRenderer.projectPointToScreen(anchor);
            if (ndc.z < -1 || ndc.z > 1) continue;

            double tx = (ndc.x * 0.5 + 0.5) * width;
            double ty = (0.5 - ndc.y * 0.5) * height;
            Endpoint endpoint = ENDPOINTS.computeIfAbsent(target.id(), id -> new Endpoint(tx, ty));
            endpoint.x += (tx - endpoint.x) * blend;
            endpoint.y += (ty - endpoint.y) * blend;

            int rgb = target.kind() == SlayerMobDetector.Kind.BOSS
                    ? PlayerSettings.slayerBossColor & 0xFFFFFF
                    : PlayerSettings.slayerMinibossColor & 0xFFFFFF;
            drawLine(graphics, startX, startY, endpoint.x, endpoint.y, rgb);
        }
        ENDPOINTS.keySet().removeIf(id -> !seen.contains(id));
    }

    private static void drawLine(GuiGraphicsExtractor g, double ax, double ay, double bx, double by, int rgb) {
        int x1 = (int) Math.round(ax), y1 = (int) Math.round(ay);
        int x2 = (int) Math.round(bx), y2 = (int) Math.round(by);
        int dx = x2 - x1, dy = y2 - y1;
        int len = (int) Math.ceil(Math.hypot(dx, dy));
        if (len < 2) return;

        g.pose().pushMatrix();
        g.pose().translate(x1, y1);
        g.pose().rotate((float) Math.atan2(dy, dx));
        // Single-pixel crisp core.
        g.fill(0, 0, len, 1, GlassStyle.alpha(0xFF000000 | rgb, 0.9f));
        g.pose().popMatrix();
    }

    private static final class Endpoint {
        double x, y;
        Endpoint(double x, double y) { this.x = x; this.y = y; }
    }
}
