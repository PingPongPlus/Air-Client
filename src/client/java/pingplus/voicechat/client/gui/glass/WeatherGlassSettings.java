package pingplus.voicechat.client.gui.glass;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.Properties;

/** Preferences for rain and frost on glass surfaces. */
public final class WeatherGlassSettings {
    public enum Mode {
        AUTOMATIC("Automatic"), RAIN("Rain"), FROST("Frost");
        public final String label;
        Mode(String label) { this.label = label; }
        public Mode next() { return values()[(ordinal() + 1) % values().length]; }
    }
    public record Values(boolean enabled, Mode mode, boolean alwaysActive, int intensity) {
        public Values { mode = mode == null ? Mode.AUTOMATIC : mode; intensity = Math.clamp(intensity, 0, 100); }
    }
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("voicechat-weather-glass.properties");
    private static Values values = read(FILE);
    public static Values values() { return values; }
    public static boolean enabled() { return values.enabled(); }
    public static Mode mode() { return values.mode(); }
    public static boolean alwaysActive() { return values.alwaysActive(); }
    public static int intensity() { return values.intensity(); }
    public static void toggleEnabled() { set(new Values(!enabled(), mode(), alwaysActive(), intensity())); }
    public static void cycleMode() { set(new Values(enabled(), mode().next(), alwaysActive(), intensity())); }
    public static void toggleAlwaysActive() { set(new Values(enabled(), mode(), !alwaysActive(), intensity())); }
    public static void setIntensity(int intensity) { set(new Values(enabled(), mode(), alwaysActive(), intensity)); }
    public static void set(Values next) {
        if (next.equals(values)) return;
        values = next;
        write(FILE, next);
    }
    public static Values read(Path path) {
        Properties p = new Properties();
        if (Files.exists(path)) try (var reader = Files.newBufferedReader(path)) { p.load(reader); }
        catch (Exception e) { LoggerFactory.getLogger(WeatherGlassSettings.class).warn("Could not load weather glass preferences", e); }
        Mode mode;
        try { mode = Mode.valueOf(p.getProperty("mode", "AUTOMATIC")); }
        catch (IllegalArgumentException e) { mode = Mode.AUTOMATIC; }
        int intensity;
        try { intensity = Integer.parseInt(p.getProperty("intensity", "65")); }
        catch (NumberFormatException e) { intensity = 65; }
        return new Values(Boolean.parseBoolean(p.getProperty("enabled", "false")), mode,
            Boolean.parseBoolean(p.getProperty("alwaysActive", "false")), intensity);
    }
    public static void write(Path path, Values v) {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(v.enabled())); p.setProperty("mode", v.mode().name());
        p.setProperty("alwaysActive", Boolean.toString(v.alwaysActive())); p.setProperty("intensity", Integer.toString(v.intensity()));
        Path temp = null;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            temp = Files.createTempFile(path.toAbsolutePath().getParent(), "weather-glass-", ".tmp");
            try (var writer = Files.newBufferedWriter(temp)) { p.store(writer, "Weather glass preferences"); }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { LoggerFactory.getLogger(WeatherGlassSettings.class).warn("Could not save weather glass preferences", e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { } }
    }
    private WeatherGlassSettings() { }
}
