package pingplus.voicechat.client.spotify;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.io.IOException;
import java.util.Properties;

/** Relative coordinates preserve placement across window resizing and GUI scale changes. */
public final class SpotifySettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("voicechat-spotify-hud.properties");
    private static boolean enabled = true;
    private static double horizontal = 1, vertical = 0;
    static {
        try (var reader = Files.newBufferedReader(FILE)) {
            Properties p = new Properties(); p.load(reader);
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            horizontal = fraction(Double.parseDouble(p.getProperty("x", "1")));
            vertical = fraction(Double.parseDouble(p.getProperty("y", "0")));
        } catch (IOException | IllegalArgumentException ignored) {}
    }
    private static double fraction(double n) { return Double.isFinite(n) ? Math.clamp(n, 0, 1) : 0; }
    public static boolean enabled() { return enabled; }
    public static void toggle() { enabled = !enabled; save(); }
    public static int x(int sw, int pw) { return 8 + (int)Math.round(horizontal * Math.max(0, sw - pw - 16)); }
    public static int y(int sh, int ph) { return 8 + (int)Math.round(vertical * Math.max(0, sh - ph - 36)); }
    public static void position(int x, int y, int sw, int sh, int pw, int ph) {
        horizontal = fraction((x - 8.0) / Math.max(1, sw - pw - 16));
        vertical = fraction((y - 8.0) / Math.max(1, sh - ph - 36));
    }
    public static void save() {
        Properties p = new Properties(); p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("x", Double.toString(horizontal)); p.setProperty("y", Double.toString(vertical));
        try {
            Files.createDirectories(FILE.getParent());
            try (var out = Files.newBufferedWriter(FILE)) { p.store(out, "Spotify HUD visibility and position"); }
        } catch (IOException e) { pingplus.voicechat.client.VoicechatClient.LOG.warn("Could not save Spotify HUD settings"); }
    }
    private SpotifySettings() {}
}
