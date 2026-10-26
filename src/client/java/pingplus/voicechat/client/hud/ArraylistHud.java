package pingplus.voicechat.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import pingplus.voicechat.client.gui.glass.GlassButtonRenderer;
import pingplus.voicechat.client.spotify.SpotifySettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Right-aligned list of currently enabled ClickGUI modifications. */
public final class ArraylistHud {
    private static final int LINE = 11;
    private static final int PAD = 4;
    private boolean enabled = false;
    private boolean glass = true;
    private boolean rectangles;
    private boolean edges = false;
    private final FpsHud fpsHud;
    private final CoordinatesHud coordinatesHud;

    public ArraylistHud(FpsHud fpsHud, CoordinatesHud coordinatesHud) {
        this.fpsHud = fpsHud;
        this.coordinatesHud = coordinatesHud;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    public boolean isGlass() {
        return glass;
    }

    public void toggleGlass() {
        glass = !glass;
    }

    public boolean isRectangles() { return rectangles; }

    public void toggleRectangles() { rectangles = !rectangles; }

    public boolean isEdges() { return edges; }

    public void toggleEdges() { edges = !edges; }

    private int rowHeight() { return Minecraft.getInstance().font.lineHeight + 4; }

    public int width() {
        var font = Minecraft.getInstance().font;
        int max = 48;
        for (Named feature : features()) max = Math.max(max, font.width(GlassStyle.label(feature.name)));
        return max + PAD * 2;
    }

    public int height() {
        int rows = Math.max(1, active().size());
        return rectangles ? rows * rowHeight() : rows * LINE + PAD * 2;
    }

    public void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.player == null) {
            return;
        }
        int box = width();
        List<String> names = active();
        if (glass) {
            graphics.nextStratum();
            if (rectangles) {
                for (int i = 0; i < names.size(); i++) {
                    int rowWidth = client.font.width(GlassStyle.label(names.get(i))) + PAD * 2;
                    int top = i * rowHeight();
                    GlassButtonRenderer.drawHudRect(
                            graphics, box - rowWidth, top, rowWidth, rowHeight(), 0xE01FFF00, true, edges);
                }
            } else {
                GlassButtonRenderer.drawHudRect(graphics, 0, 0, box, height(), 0xE01FFF00, false, edges);
            }
            graphics.nextStratum();
        }
        int y = rectangles ? (rowHeight() - client.font.lineHeight) / 2 : PAD;
        for (String name : names) {
            var label = GlassStyle.label(name);
            graphics.text(client.font, label, box - client.font.width(label) - PAD, y, GlassStyle.TEXT, false);
            y += rectangles ? rowHeight() : LINE;
        }
    }

    private List<Named> features() {
        return List.of(
                new Named("Custom crosshair", CustomCrosshairHud.INSTANCE::isEnabled),
                new Named("Frame rate", fpsHud::isEnabled),
                new Named("Ping", PingHud.INSTANCE::isEnabled),
                new Named("CPS", MetricsHud.CPS::isEnabled),
                new Named("Speed", MetricsHud.SPEED::isEnabled),
                new Named("Keystrokes", KeystrokesHud.INSTANCE::isEnabled),
                new Named("Coordinates", coordinatesHud::isEnabled),
                new Named("Scoreboard", ScoreboardHud.INSTANCE::isEnabled),
                new Named("Chat", ChatHud.INSTANCE::isEnabled),
                new Named("Dock hotbar", () -> PlayerSettings.dockHotbar),
                new Named("No HUD bars", () -> PlayerSettings.hideHealth || PlayerSettings.hideHunger || PlayerSettings.hideXp || PlayerSettings.hideLocator),
                new Named("AIR logo", LogoHud.INSTANCE::isEnabled),
                new Named("Spotify", SpotifySettings::enabled),
                new Named("Hitboxes", () -> PlayerSettings.hitboxes),
                new Named("Projectile preview", () -> PlayerSettings.projectilePreview),
                new Named("Fullbright", () -> PlayerSettings.fullbright),
                new Named("Xray", pingplus.voicechat.client.XrayFeature::enabled),
                new Named("Storage ESP", () -> PlayerSettings.storageEsp),
                new Named("MobESP", () -> PlayerSettings.mobEsp),
                new Named("Zoom", () -> PlayerSettings.zoom),
                new Named("Body", () -> PlayerSettings.mainBodyPart),
                new Named("Force Sneak", () -> PlayerSettings.forceSneak),
                new Named("Left arm", () -> PlayerSettings.leftArm),
                new Named("Right arm", () -> PlayerSettings.rightArm),
                new Named("Direction", () -> PlayerSettings.direction),
                new Named("Middle-click volume", pingplus.voicechat.client.VoicechatClient::isMiddleClickVolumeEnabled),
                new Named("Hand swap", () -> PlayerSettings.handSwap),
                new Named("Fast Place", () -> PlayerSettings.fastPlace),
                new Named("Auto Tools", () -> PlayerSettings.autoTools),
                new Named("KillAura", () -> PlayerSettings.killAura)
        );
    }

    private List<String> active() {
        var font = Minecraft.getInstance().font;
        List<String> names = new ArrayList<>();
        for (Named feature : features()) if (feature.enabled.getAsBoolean()) names.add(feature.name);
        names.sort(Comparator.comparingInt((String name) -> font.width(GlassStyle.label(name))).reversed()
                .thenComparing(name -> name));
        return names;
    }

    private record Named(String name, BooleanSupplier enabled) {}
}
