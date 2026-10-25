package com.coflnet.config;

/**
 * Utility class for managing the Angry Co-op protection configuration.
 */
public class AngryCoopProtectionManager {
    private static CoflModConfig config = null;

    private static void ensureConfig() {
        if (config == null) {
            config = CoflModConfig.load();
        }
    }

    public static void reloadConfig() {
        config = CoflModConfig.load();
    }

    public static CoflModConfig getConfig() {
        ensureConfig();
        return config;
    }

    public static boolean isEnabled() {
        return getConfig().angryCoopProtectionEnabled;
    }

    public static void setEnabled(boolean enabled) {
        CoflModConfig cfg = getConfig();
        cfg.angryCoopProtectionEnabled = enabled;
        cfg.save();
        reloadConfig();
    }
}
