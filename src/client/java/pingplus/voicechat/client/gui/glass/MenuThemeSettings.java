package pingplus.voicechat.client.gui.glass;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Shared menu artwork and a saved preference; light mode preserves the original menu. */
public final class MenuThemeSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("voicechat-menu-theme.properties");
    private static final Artwork LIGHT_BACKGROUND = artwork("title_background.png", 1672, 941);
    private static final Artwork DARK_BACKGROUND = artwork("title_background_dark.png", 1672, 940);
    private static final Artwork LIGHT_LOGO = artwork("img_7.png", 1254, 1254);
    private static final Artwork DARK_LOGO = artwork("title_logo.png", 2171, 724);
    private static boolean darkMode = read(FILE);

    public record Artwork(Identifier id, int width, int height) { }

    public static boolean isDarkMode() { return darkMode; }
    public static Artwork background() { return darkMode ? DARK_BACKGROUND : LIGHT_BACKGROUND; }
    public static Artwork logo() { return darkMode ? DARK_LOGO : LIGHT_LOGO; }

    public static boolean setDarkMode(boolean value) {
        Properties properties = new Properties();
        properties.setProperty("darkMode", Boolean.toString(value));
        Path temp = null;
        try {
            Files.createDirectories(FILE.getParent());
            temp = Files.createTempFile(FILE.getParent(), "voicechat-menu-theme-", ".tmp");
            try (var writer = Files.newBufferedWriter(temp)) {
                properties.store(writer, "Menu dark / light mode");
            }
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            darkMode = value;
            return true;
        } catch (IOException e) {
            LoggerFactory.getLogger(MenuThemeSettings.class).warn("Could not save menu theme", e);
            return false;
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    public static boolean read(Path file) {
        if (!Files.exists(file)) return false;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            return Boolean.parseBoolean(properties.getProperty("darkMode", "false"));
        } catch (IOException | IllegalArgumentException e) {
            LoggerFactory.getLogger(MenuThemeSettings.class).warn("Could not load menu theme; using light mode", e);
            return false;
        }
    }

    private static Artwork artwork(String name, int width, int height) {
        return new Artwork(Identifier.fromNamespaceAndPath("voicechat", "textures/gui/" + name), width, height);
    }

    private MenuThemeSettings() { }
}
