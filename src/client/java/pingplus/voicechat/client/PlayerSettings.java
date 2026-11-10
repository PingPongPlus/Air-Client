package pingplus.voicechat.client;

import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class PlayerSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("laby-player-settings.properties");
    public static float xScale = 1F;
    public static float yScale = 1F;
    public static float zScale = 1F;
    public static float xyzHeadscale = 1;
    public static boolean mainBodyPart = true;
    public static boolean leftArm = true;
    public static boolean rightArm = true;
    public static boolean forceSneak = false;
    public static boolean handSwap = false;
    public static boolean fastPlace = false;
    public static int fastPlaceDelayTicks = 1;
    public static boolean autoTools = false;
    public static boolean autoToolsRestore = true;
    public static boolean hitboxes = false;
    public static boolean projectilePreview = false;
    public static boolean storageEsp = false;
    public static boolean mobEsp = false;
    public static boolean storageEspChests = true;
    public static boolean storageEspBarrels = true;
    public static boolean storageEspShulkers = true;
    public static boolean storageEspEnderChests = true;
    public static boolean storageEspFill = false;
    public static int storageEspRange = 64;
    public static int handSwapIntervalTicks = 6;
    public static boolean direction = false;
    public static boolean slayerBossHighlight = false;
    public static boolean slayerOutline = false;
    public static boolean slayerBoss = false;
    public static boolean slayerMiniboss = false;
    public static boolean slayerLine = false;
    public static int slayerBossColor = 0xFFF200;
    public static int slayerMinibossColor = 0xFF5757;
    public static boolean hideParticles = false;
    public static boolean hideMobNames = false;
    public static boolean mobHealthBar = false;
    public static float mobBarOpacity = 0.85f;
    public static boolean selfOutline = true;
    public static int selfOutlineColor = 0xFF69B4;
    public static boolean etherwarpHelper = true;
    public static boolean playerOutline = true;
    public static int playerOutlineColor = 0x7FDBFF;
    public static boolean dockHotbar = true;
    public static float dockMaxScale = 0.8f;
    public static float dockRadius = 1.5f;
    public static boolean dockShelf = true;
    public static boolean dockFrames = true;
    public static boolean dockGlassFrame = false;
    public static float dockSize = 0.7f;
    public static boolean dockAutoHide = false;
    public static int dockAutoHideSeconds = 20;
    public static boolean hideHealth = false;
    public static boolean hideHunger = false;
    public static boolean hideXp = false;
    public static boolean hideLocator = false;
    public static boolean fullbright = true;
    public static boolean zoom = true;
    public static float zoomStrength = 3f;
    public static boolean shoulderCam = true;
    public static boolean killAura = false;
    public static boolean killAuraPlayers = true;
    public static boolean killAuraMobs = true;
    public static float killAuraRange = 3f;
    public static float killAuraTurnSpeed = 170f;
    public static String followTarget = "";
    public static boolean followLookAtNearbyPlayers = false;
    /** Follow: attack the followed target (through KillAura, only that target). */
    public static boolean followAttack = false;
    /** Follow: distance in blocks Baritone keeps to the target. */
    public static int followDistance = 3;
    /** Follow attack speed (clicks per second, random between min and max). */
    public static double followCpsMin = 8;
    public static double followCpsMax = 12;

    static {
        load();
    }

    public static synchronized void save() {
        Properties values = new Properties();
        for (Field field : fields()) {
            try {
                values.setProperty(field.getName(), String.valueOf(field.get(null)));
            } catch (Exception ignored) {
            }
        }
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                values.store(out, "Client player settings");
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        Properties values = new Properties();
        try (var in = Files.newInputStream(FILE)) {
            values.load(in);
        } catch (Exception ignored) {
            return;
        }
        for (Field field : fields()) {
            String raw = values.getProperty(field.getName());
            if (raw == null) {
                continue;
            }
            try {
                Class<?> type = field.getType();
                if (type == boolean.class) {
                    field.setBoolean(null, Boolean.parseBoolean(raw));
                } else if (type == int.class) {
                    field.setInt(null, Integer.parseInt(raw));
                } else if (type == long.class) {
                    field.setLong(null, Long.parseLong(raw));
                } else if (type == float.class) {
                    field.setFloat(null, Float.parseFloat(raw));
                } else if (type == double.class) {
                    field.setDouble(null, Double.parseDouble(raw));
                } else if (type == String.class) {
                    field.set(null, raw);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static Field[] fields() {
        Field[] all = PlayerSettings.class.getDeclaredFields();
        int count = 0;
        for (Field field : all) {
            if (persistable(field)) {
                count++;
            }
        }
        Field[] result = new Field[count];
        int index = 0;
        for (Field field : all) {
            if (persistable(field)) {
                result[index++] = field;
            }
        }
        return result;
    }

    private static boolean persistable(Field field) {
        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
            return false;
        }
        Class<?> type = field.getType();
        return type == boolean.class || type == int.class || type == long.class
                || type == float.class || type == double.class || type == String.class;
    }
}







