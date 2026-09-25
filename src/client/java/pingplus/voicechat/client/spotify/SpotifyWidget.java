package pingplus.voicechat.client.spotify;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

/** One HUD instance, shared with chat for keyboard controls and drag positioning. */
public final class SpotifyWidget {
    public static final SpotifyWidget INSTANCE = new SpotifyWidget();
    private static final Identifier ART = Identifier.fromNamespaceAndPath("voicechat", "spotify_cover");
    public static final int HEIGHT = 100;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final SpotifyClient service = SpotifyClient.INSTANCE;
    private final MediaButton previous = new MediaButton("Previous track", -1, () -> service.command("previous"));
    private final MediaButton toggle = new MediaButton("Play", 0, () -> {
        var track = service.state().playback(); if (track != null) service.command(track.playing() ? "pause" : "play");
    });
    private final MediaButton next = new MediaButton("Next track", 1, () -> service.command("next"));
    private final List<Button> buttons = List.of(previous, toggle, next);
    private SpotifyPlayback seen;
    private String image = "";
    private int imageWidth, imageHeight;
    private static final int x = 0, y = 0, width = 240;
    private boolean hasArt;
    private SpotifyWidget() {
        for (int i = 0; i < buttons.size(); i++) { buttons.get(i).setX(135 + i * 32); buttons.get(i).setY(64); }
    }
    public List<Button> buttons() { return buttons; }
    public void render(GuiGraphicsExtractor g, int mx, int my, float dt, boolean editing) {
        if (!SpotifySettings.enabled() || minecraft.gui.hud.isHidden()) return;
        var state = service.state(); var track = state.playback();
        previous.active = track != null && track.previous(); next.active = track != null && track.next(); toggle.active = track != null && track.toggle();
        toggle.pause = track != null && track.playing();
        toggle.setMessage(Component.literal(toggle.pause ? "Pause" : "Play"));
        if (seen != track) {
            seen = track;
            String wanted = track == null ? "" : track.image();
            if (!wanted.equals(image)) loadArt(wanted);
        }
        g.nextStratum(); GlassStyle.surface(g, x, y, width, HEIGHT, .92f, .1f); g.nextStratum();
        text(g, "SPOTIFY", x + 10, y + 8, 80, GlassStyle.STATUS);
        GlassStyle.round(g, x + 10, y + 26, 32, 32, 5, 0x553D685B);
        if (hasArt) g.blit(RenderPipelines.GUI_TEXTURED, ART, x + 10, y + 26, 0, 0, 32, 32, imageWidth, imageHeight, imageWidth, imageHeight);
        else text(g, "♪", x + 20, y + 37, 20, GlassStyle.MUTED);
        text(g, track == null ? "Your music, here" : track.title(), x + 50, y + 27, width - 60, GlassStyle.TEXT);
        text(g, track == null ? state.message() : track.artist(), x + 50, y + 41, width - 60, GlassStyle.MUTED);
        long position = track == null ? 0 : track.position(System.nanoTime());
        text(g, track == null ? "" : time(position) + " / " + time(track.duration()), x + 10, y + 72, width - 120, GlassStyle.MUTED);
        int bar = width - 20;
        g.fill(x + 10, y + 92, x + 10 + bar, y + 94, 0x557F9991);
        if (track != null && track.duration() > 0) g.fill(x + 10, y + 92, x + 10 + (int)(bar * position / track.duration()), y + 94, 0xFF86E5B0);
        for (Button button : buttons) {
            button.setTooltip(Tooltip.create(Component.literal(button.getMessage().getString() + " • " + state.message())));
            button.extractRenderState(g, editing ? mx : -100, editing ? my : -100, dt);
        }
    }
    private void text(GuiGraphicsExtractor g, String value, int x, int y, int max, int color) {
        GlassStyle.line(g, value, x, y, max, color);
    }
    private static String time(long ms) { return (ms / 60000) + ":" + String.format(java.util.Locale.ROOT, "%02d", ms / 1000 % 60); }
    private void loadArt(String data) {
        image = data; releaseArt();
        if (data.isEmpty() || data.length() > 2_666_672) return;
        try {
            NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
            if (nativeImage.getWidth() > 1024 || nativeImage.getHeight() > 1024) { nativeImage.close(); return; }
            imageWidth = nativeImage.getWidth(); imageHeight = nativeImage.getHeight();
            minecraft.getTextureManager().register(ART, new DynamicTexture(() -> "Spotify album artwork", nativeImage)); hasArt = true;
        } catch (Exception ignored) { /* A missing cover must not hide the controls. */ }
    }
    private void releaseArt() { if (hasArt) minecraft.getTextureManager().release(ART); hasArt = false; }
    public void clear() { releaseArt(); image = ""; seen = null; buttons.forEach(b -> b.setFocused(false)); }
    private static final class MediaButton extends Button {
        private final int direction;
        private boolean pause;
        MediaButton(String name, int direction, Runnable action) {
            super(0, 0, 28, 24, Component.literal(name), b -> action.run(), supplier -> supplier.get()); this.direction = direction;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float dt) {
            // Geometry keeps icons crisp and independent of the active font's Unicode coverage.
            int cx = getX() + 14, cy = getY() + 12, color = active ? GlassStyle.TEXT : 0xFF87978E;
            if (pause && direction == 0) {
                g.fill(cx - 4, cy - 5, cx - 1, cy + 5, color); g.fill(cx + 1, cy - 5, cx + 4, cy + 5, color);
            } else {
                for (int row = -5; row < 5; row++) {
                    int extent = 6 - Math.abs(row);
                    if (direction < 0) g.fill(cx + 3 - extent, cy + row, cx + 3, cy + row + 1, color);
                    else g.fill(cx - 3, cy + row, cx - 3 + extent, cy + row + 1, color);
                }
                if (direction < 0) g.fill(cx - 6, cy - 5, cx - 4, cy + 5, color);
                if (direction > 0) g.fill(cx + 5, cy - 5, cx + 7, cy + 5, color);
            }
        }
    }
}
