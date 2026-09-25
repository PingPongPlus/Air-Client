package pingplus.voicechat.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import pingplus.voicechat.client.spotify.SpotifySettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Right-aligned list of currently enabled ClickGUI modifications. */
public final class ArraylistHud {
    private static final int LINE = 11;
    private static final int PAD = 4;
    private boolean enabled = true;
    private boolean glass = true;
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

    public int width() {
        var font = Minecraft.getInstance().font;
        int max = 48;
        for (Named feature : features()) max = Math.max(max, font.width(GlassStyle.label(feature.name)));
        return max + PAD * 2;
    }

    public int height() {
        int rows = Math.max(1, active().size());
        return rows * LINE + PAD * 2;
    }

    public void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.player == null) {
            return;
        }
        int box = width();
        if (glass) {
            graphics.nextStratum();
            GlassStyle.surface(graphics, 0, 0, box, height(), .88f, .12f);
            graphics.nextStratum();
        }
        int y = PAD;
        for (String name : active()) {
            var label = GlassStyle.label(name);
            graphics.text(client.font, label, box - client.font.width(label) - PAD, y, GuiTheme.TEXT, false);
            y += LINE;
        }
    }

    private List<Named> features() {
        return List.of(
                new Named("Frame rate", fpsHud::isEnabled),
                new Named("Coordinates", coordinatesHud::isEnabled),
                new Named("Spotify", SpotifySettings::enabled),
                new Named("Hitboxes", () -> PlayerSettings.hitboxes),
                new Named("Body", () -> PlayerSettings.mainBodyPart),
                new Named("Left arm", () -> PlayerSettings.leftArm),
                new Named("Right arm", () -> PlayerSettings.rightArm),
                new Named("Direction", () -> PlayerSettings.direction),
                new Named("Hand swap", () -> PlayerSettings.handSwap)
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
