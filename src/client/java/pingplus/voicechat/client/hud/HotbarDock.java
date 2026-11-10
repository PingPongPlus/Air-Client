package pingplus.voicechat.client.hud;

import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassButtonRenderer;

/** macOS-style dock hotbar: the selected slot magnifies and pushes its neighbors apart. */
public final class HotbarDock {
    public static final HotbarDock INSTANCE = new HotbarDock();
    private static final Identifier SELECTION = Identifier.fromNamespaceAndPath("minecraft", "hud/hotbar_selection");
    private static final Identifier HOTBAR_FRAME = Identifier.fromNamespaceAndPath("minecraft", "hud/hotbar");
    private static final Identifier OFFHAND_LEFT = Identifier.fromNamespaceAndPath("minecraft", "hud/hotbar_offhand_left");
    private static final Identifier OFFHAND_RIGHT = Identifier.fromNamespaceAndPath("minecraft", "hud/hotbar_offhand_right");
    private static final Identifier ATTACK_BACKGROUND = Identifier.fromNamespaceAndPath("minecraft", "hud/hotbar_attack_indicator_background");
    private static final Identifier ATTACK_PROGRESS = Identifier.fromNamespaceAndPath("minecraft", "hud/hotbar_attack_indicator_progress");
    private static final int SLOT = 20, BAR_HEIGHT = 22, OFFHAND = 9;
    private final float[] scale = new float[10];
    private final float[] velocity = new float[10];
    private final float[] restX = new float[10];
    private float dockCenter;
    private float centerVelocity;
    private float frameAvg = 1f / 60;
    private long last = System.nanoTime();
    private int heldSlot = -1;
    private net.minecraft.world.item.Item heldItem;
    private double heldTime;
    private float slideY;
    private float slideVelocity;
    private static float barLift;

    public boolean render(GuiGraphicsExtractor g, DeltaTracker dt) {
        Minecraft client = Minecraft.getInstance();
        if (!PlayerSettings.dockHotbar) return false;
        if (!(client.getCameraEntity() instanceof Player player)) return false;
        int width = g.guiWidth(), height = g.guiHeight();
        int centerX = width / 2;
        int itemY = height - 19;
        Inventory inventory = player.getInventory();
        ItemStack offhand = player.getOffhandItem();
        HumanoidArm arm = player.getMainArm().getOpposite();
        int selected = inventory.getSelectedSlot();
        int offX = arm == HumanoidArm.LEFT ? centerX - 91 - 26 : centerX + 91 + 10;

        long now = System.nanoTime();
        float rawDt = (float) Math.min(0.05, (now - last) / 1e9);
        last = now;
        frameAvg += (rawDt - frameAvg) * 0.5f;
        float frame = frameAvg;
        float stiffness = 100, damping = 15;

        float maxScale = Math.max(0, PlayerSettings.dockMaxScale);
        float radius = Math.max(0.1f, PlayerSettings.dockRadius);
        int offhandIndex = arm == HumanoidArm.LEFT ? -1 : 9;

        for (int i = 0; i < 9; i++) restX[i] = centerX - 80 + i * SLOT;
        restX[OFFHAND] = offX + 8;

        centerVelocity += (selected - dockCenter) * 90 * frame;
        centerVelocity *= (float) Math.exp(-14 * frame);
        dockCenter = Math.clamp(dockCenter + centerVelocity * frame, 0, 8);

        for (int i = 0; i < 10; i++) {
            float distance = i < 9 ? Math.abs(i - dockCenter) : Math.abs(offhandIndex - dockCenter);
            float boost = i == selected ? 0.15f : 0;
            float target = 1 + maxScale * curve(distance, radius) + boost;
            target = clampForScreen(target, i, width);
            velocity[i] += (target - scale[i]) * stiffness * frame;
            velocity[i] *= (float) Math.exp(-damping * frame);
            scale[i] = Math.max(1, Math.min(3.5f, scale[i] + velocity[i] * frame));
        }

        float[] pos = displacedPositions(dockCenter);

        ItemStack held = inventory.getSelectedItem();
        boolean holding = !held.isEmpty();
        if (!holding || selected != heldSlot || held.getItem() != heldItem) {
            heldSlot = selected;
            heldItem = holding ? held.getItem() : null;
            heldTime = 0;
        } else {
            heldTime += frame;
        }
        float hideTarget = PlayerSettings.dockAutoHide && holding && heldTime >= PlayerSettings.dockAutoHideSeconds ? 200f : 0f;
        slideVelocity += (hideTarget - slideY) * 80 * frame;
        slideVelocity *= (float) Math.exp(-14 * frame);
        slideY = Math.max(0, Math.min(200f, slideY + slideVelocity * frame));

        int shelfX = Math.round(pos[0] - SLOT / 2f * scale[0] - 1);
        int shelfRight = Math.round(pos[8] + SLOT / 2f * scale[8] + 1);
        float size = Math.max(0.5f, Math.min(2f, PlayerSettings.dockSize));
        float dockMidX = (shelfX + shelfRight) / 2f;
        float maxItem = 0;
        for (int i = 0; i < 10; i++) maxItem = Math.max(maxItem, scale[i]);
        float liftTarget = Math.max(0, 16 * maxItem - 19) * size * (1 - slideY / 200f);
        barLift += (liftTarget - barLift) * (float) (1 - Math.exp(-12 * frame));

        g.pose().pushMatrix();
        g.pose().translate(0, slideY);
        g.pose().translate(dockMidX, height);
        g.pose().scale(size);
        g.pose().translate(-dockMidX, -height);

        if (PlayerSettings.dockShelf) {
            g.nextStratum();
            GlassButtonRenderer.drawHudRect(g, shelfX, height - BAR_HEIGHT, shelfRight - shelfX, BAR_HEIGHT, 0xE01FFF00, false, true);
            g.nextStratum();
        }

        for (int i = 0; i < 9; i++) if (i != selected) drawSlot(g, client, dt, player, inventory, offhand, arm, i, itemY, height, selected, pos);
        drawSlot(g, client, dt, player, inventory, offhand, arm, OFFHAND, itemY, height, selected, pos);
        drawSlot(g, client, dt, player, inventory, offhand, arm, selected, itemY, height, selected, pos);

        if (client.player != null && client.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR) {
            float strength = client.player.getAttackStrengthScale(0);
            if (strength < 1) {
                int indicatorX = arm == HumanoidArm.RIGHT ? centerX + 91 + 6 : centerX - 91 - 22;
                int indicatorY = height - 20;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, ATTACK_BACKGROUND, indicatorX, indicatorY, 18, 18);
                int progress = (int) (strength * 19);
                g.blitSprite(RenderPipelines.GUI_TEXTURED, ATTACK_PROGRESS, 18, 18, 0, 18 - progress,
                        indicatorX, indicatorY + 18 - progress, 18, progress);
            }
        }
        g.pose().popMatrix();
        return true;
    }

    public static float barLift() { return barLift; }
    public static boolean shifting() {
        return PlayerSettings.dockHotbar && Minecraft.getInstance().getCameraEntity() instanceof Player;
    }

    /** Continuous-center dock layout: every slot keeps its order with zero gaps, anchored so the bulge slides through positions. */
    private float[] displacedPositions(float center) {
        int k0 = (int) Math.floor(center);
        float f = Math.max(0, Math.min(1, center - k0));
        float[] h = new float[10];
        for (int i = 0; i < 9; i++) h[i] = SLOT / 2f * scale[i];
        h[OFFHAND] = 14.5f * scale[OFFHAND];
        float anchorRest = restX[k0] + f * (restX[Math.min(k0 + 1, 8)] - restX[k0]);
        float[] pos = new float[10];
        pos[k0] = anchorRest - f * (h[k0] + h[Math.min(k0 + 1, 8)]);
        for (int i = k0 + 1; i <= 8; i++) pos[i] = pos[i - 1] + h[i - 1] + h[i];
        for (int i = k0 - 1; i >= 0; i--) pos[i] = pos[i + 1] - h[i + 1] - h[i];
        pos[OFFHAND] = restX[OFFHAND] < restX[0]
                ? pos[0] - h[0] - h[OFFHAND]
                : pos[8] + h[8] + h[OFFHAND];
        return pos;
    }

    private void drawSlot(GuiGraphicsExtractor g, Minecraft client, DeltaTracker dt, Player player, Inventory inventory,
                          ItemStack offhand, HumanoidArm arm, int i, int itemY, int height, int selected, float[] pos) {
        float s = scale[i];
        if (i < 9) {
            applyScale(g, pos[i], height - 3, s);
            if (i == selected && PlayerSettings.dockFrames) {
                if (PlayerSettings.dockGlassFrame) {
                    drawGlassFrame(g, Math.round(pos[i] - 12), height - 23);
                } else {
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR_FRAME, 182, 22, 1 + i * SLOT, 0,
                            Math.round(pos[i] - 10), height - 22, 20, 22);
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, Math.round(pos[i] - 12), height - 23, 24, 23);
                }
            }
            drawItem(g, client, dt, inventory.getItem(i), Math.round(pos[i] - 8), itemY, i + 1, player);
            g.pose().popMatrix();
        } else if (!offhand.isEmpty()) {
            if (!PlayerSettings.dockGlassFrame) {
                float spriteCenter = pos[i] + (arm == HumanoidArm.LEFT ? 3.5f : -3.5f);
                applyScale(g, spriteCenter, height + 1, s);
                if (arm == HumanoidArm.LEFT) {
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, Math.round(spriteCenter - 14.5f), height - 23, 29, 24);
                } else {
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_RIGHT, Math.round(spriteCenter - 14.5f), height - 23, 29, 24);
                }
                g.pose().popMatrix();
            }
            applyScale(g, pos[i], height - 3, s);
            if (PlayerSettings.dockGlassFrame && PlayerSettings.dockFrames) {
                drawGlassFrame(g, Math.round(pos[i] - 12), height - 23);
            }
            drawItem(g, client, dt, offhand, Math.round(pos[i] - 8), itemY, 10, player);
            g.pose().popMatrix();
        }
    }

    private static void drawGlassFrame(GuiGraphicsExtractor g, int x, int y) {
        g.nextStratum();
        GlassButtonRenderer.drawHudFrame(g, x, y, 24, 23, 0.96f);
        g.nextStratum();
    }

    private static void applyScale(GuiGraphicsExtractor g, float cx, float cy, float s) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(s);
        g.pose().translate(-cx, -cy);
    }

    private static void drawItem(GuiGraphicsExtractor g, Minecraft client, DeltaTracker dt, ItemStack stack,
                                 int x, int y, int slotIndex, Player player) {
        if (stack.isEmpty()) return;
        float pop = stack.getPopTime() - dt.getGameTimeDeltaPartialTick(false);
        if (pop > 0) {
            float s = 1 + pop / 5f;
            g.pose().pushMatrix();
            g.pose().translate(x + 8, y + 12);
            g.pose().scale(1f / s, (s + 1) / 2f);
            g.pose().translate(-(x + 8), -(y + 12));
        }
        g.item(player, stack, x, y, slotIndex);
        if (pop > 0) g.pose().popMatrix();
        g.itemDecorations(client.font, stack, x, y);
    }

    private float clampForScreen(float target, int i, int width) {
        float max = Math.min(target, Math.min(restX[i], width - restX[i]) / 8f);
        return Math.max(1, Math.min(max, 3.5f));
    }

    private static float curve(float distance, float radius) {
        float t = Math.min(distance / radius, 1);
        return (float) Math.cos(t * Math.PI / 2);
    }

    private HotbarDock() {
        java.util.Arrays.fill(scale, 1);
    }
}
