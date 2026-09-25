package pingplus.voicechat.client.gui;

import java.util.HashSet;
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
import pingplus.voicechat.client.gui.glass.GlassButtonRenderer;
import pingplus.voicechat.client.slayer.SlayerOutlineRenderer;

// Uniform glossy boss bar above mobs: soft blue gradient with a water-like
// animated interior, glow halo and moving shine. Same design for every boss.
// The bar width scales with perspective (1/distance), exactly like the mob
// appears to shrink, and fades out beyond 20 blocks. The bar height is
// boss-specific and untouched. The fill follows the mob's HP.
// Unlocked only while "No mob names" is enabled.
public final class MobHealthBarRenderer {
    private static final int BAR_WIDTH = 72;
    private static final int BAR_HEIGHT = 8;
    private static final double MAX_DISTANCE = 20.0;
    private static final double REFERENCE_DISTANCE = 3.2;
    private static final Map<Integer, Anim> ANIMS = new ConcurrentHashMap<>();
    private static long lastFrame;

    private MobHealthBarRenderer() {}

    // Perspective scale like the mob itself, shrinking faster with distance:
    // apparent size follows (reference/distance)^1.3 and fades out near 20.
    public static double scaleFor(double distSq) {
        double dist = Math.sqrt(distSq);
        if (dist >= MAX_DISTANCE) return 0;
        double scale = Math.pow(Math.min(1.0, REFERENCE_DISTANCE / dist), 1.3);
        double fade = 1.0 - smoothstep(12, MAX_DISTANCE, dist);
        return scale * fade;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    // Called from the HUD element every frame.
    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) { ANIMS.clear(); return; }
        if (!PlayerSettings.hideMobNames || !PlayerSettings.mobHealthBar) { ANIMS.clear(); return; }

        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.05F : Math.min(0.1F, (now - lastFrame) / 1e9F);
        lastFrame = now;
        // Shine and water waves loop once every 3 seconds (subtle, never distracting).
        float phase = (float) ((now % 3_000_000_000L) / 3_000_000_000.0);
        float userOpacity = Math.clamp(PlayerSettings.mobBarOpacity, 0, 1);

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 forward = new Vec3(camera.forwardVector());

        Set<Integer> seen = new HashSet<>();
        for (SlayerOutlineRenderer.Target target : SlayerOutlineRenderer.targets()) {
            seen.add(target.id());
            Entity entity = client.level.getEntity(target.id());
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isRemoved() || !living.isAlive()) continue;
            double distSq = living.distanceToSqr(client.player);
            double scale = scaleFor(distSq);
            if (scale <= 0) continue; // hidden beyond 20 blocks

            // Tight anchor right above the mob, not floating high.
            Vec3 anchor = living.position().add(0, living.getBbHeight() + 0.3, 0);
            Vec3 rel = anchor.subtract(camera.position());
            if (rel.dot(forward) <= 0.1) continue;

            Vec3 ndc = client.gameRenderer.projectPointToScreen(anchor);
            if (ndc.z < -1 || ndc.z > 1) continue;

            double x = (ndc.x * 0.5 + 0.5) * width;
            double y = (0.5 - ndc.y * 0.5) * height;

            Anim anim = ANIMS.computeIfAbsent(target.id(), id -> new Anim());
            anim.feed(dt, target.hp(), target.maxHp());
            drawBar(graphics, anim, x, y, scale, phase, userOpacity);
        }

        // Fade out bars whose mob is gone, then forget them.
        ANIMS.forEach((id, anim) -> {
            if (!seen.contains(id)) anim.fade(dt);
        });
        ANIMS.values().removeIf(anim -> anim.alpha() < 0.02F);
    }

    private static void drawBar(GuiGraphicsExtractor graphics, Anim anim,
                                double cx, double cy, double scale, float phase, float userOpacity) {
        float opacity = anim.alpha() * userOpacity;
        if (opacity <= 0.02F) return;

        int w = Math.max(2, (int) Math.round(BAR_WIDTH * scale));
        int h = Math.max(2, (int) Math.round(BAR_HEIGHT * scale));
        int x = (int) (cx - w / 2.0);
        int y = (int) cy;

        // Only the inner blue water fill, no grey track behind it.
        int fillWidth = Math.max(0, Math.round(Math.clamp(anim.fill(), 0, 1) * w));
        if (fillWidth > 0) {
            GlassButtonRenderer.bossBar(graphics, x, y, fillWidth, h, true, phase, opacity);
        }
    }

    // Smoothly animated bar state: alpha (fade/pop) and fill ratio.
    private static final class Anim {
        private float alpha;
        private float fill;

        void feed(float dt, float hp, float maxHp) {
            float step = 1 - (float) Math.exp(-12 * dt);
            alpha += (1 - alpha) * step;
            float target = maxHp > 0 ? Math.clamp(hp / maxHp, 0, 1) : 0;
            fill += (target - fill) * step;
        }

        void fade(float dt) {
            float step = 1 - (float) Math.exp(-9 * dt);
            alpha += (0 - alpha) * step;
        }

        float alpha() { return alpha; }
        float fill() { return fill; }
    }
}
