package pingplus.voicechat.client.gui.glass;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Global corner-radius scale for every glass surface, button and control. 1 = default. */
public final class GlassCornerSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("voicechat-glass-corners.properties");
    public static final float MIN = 0f, MAX = 2f, DEFAULT = 1f;
    private static float scale = load();

    public static float getScale() { return scale; }

    /** 7-bit encoding for the BUTTON pipeline (enabled bit + scale). */
    public static int buttonQuant() { return Math.clamp(Math.round(scale * 64f), 0, 127); }

    /** 8-bit encoding for the CONTROL pipeline (dedicated channel). */
    public static int controlQuant() { return Math.clamp(Math.round(scale * 128f), 0, 255); }

    public static void setScale(float value) {
        float clamped = Math.clamp(Float.isFinite(value) ? value : DEFAULT, MIN, MAX);
        if (clamped == scale) return;
        scale = clamped;
        save();
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("scale", Float.toString(scale));
        Path temp = null;
        try {
            Files.createDirectories(FILE.getParent());
            temp = Files.createTempFile(FILE.getParent(), "voicechat-glass-corners-", ".tmp");
            try (var writer = Files.newBufferedWriter(temp)) {
                properties.store(writer, "Global glass corner-radius scale (0 = square, 1 = default, 2 = extra round)");
            }
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LoggerFactory.getLogger(GlassCornerSettings.class).warn("Could not save corner-radius preference", e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private static float load() {
        if (!Files.exists(FILE)) return DEFAULT;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(FILE)) {
            properties.load(reader);
            float parsed = Float.parseFloat(properties.getProperty("scale", Float.toString(DEFAULT)));
            return Math.clamp(Float.isFinite(parsed) ? parsed : DEFAULT, MIN, MAX);
        } catch (IOException | IllegalArgumentException e) {
            LoggerFactory.getLogger(GlassCornerSettings.class).warn("Could not load corner-radius preference; using default", e);
            return DEFAULT;
        }
    }
    private GlassCornerSettings() { }
}
