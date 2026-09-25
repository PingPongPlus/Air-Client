package pingplus.voicechat.client.gui.hud;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** AGG sticker preference, persisted across restarts (position/scale live in the HUD layout file). */
public final class AggSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("voicechat-agg.properties");
    private static boolean enabled = load();

    public static boolean isEnabled() { return enabled; }

    public static boolean setEnabled(boolean value) {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(value));
        Path temp = null;
        try {
            Files.createDirectories(FILE.getParent());
            temp = Files.createTempFile(FILE.getParent(), "voicechat-agg-", ".tmp");
            try (var writer = Files.newBufferedWriter(temp)) {
                properties.store(writer, "AGG sticker");
            }
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            enabled = value;
            return true;
        } catch (IOException e) {
            LoggerFactory.getLogger(AggSettings.class).warn("Could not save AGG preference", e);
            return false;
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    static Path fileForTest() { return FILE; }

    private static boolean load() {
        if (!Files.exists(FILE)) return false;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(FILE)) {
            properties.load(reader);
            return "true".equalsIgnoreCase(properties.getProperty("enabled"));
        } catch (IOException | IllegalArgumentException e) {
            LoggerFactory.getLogger(AggSettings.class).warn("Could not load AGG preference; using default", e);
            return false;
        }
    }
    private AggSettings() { }
}
