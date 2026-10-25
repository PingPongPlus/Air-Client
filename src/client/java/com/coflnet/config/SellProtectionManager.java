package com.coflnet.config;

/**
 * Utility class for managing sell protection configuration
 */
public class SellProtectionManager {
    private static CoflModConfig config = null;
    
    public static void reloadConfig() {
        config = CoflModConfig.load();
    }
    
    public static CoflModConfig getConfig() {
        if (config == null) {
            config = CoflModConfig.load();
        }
        return config;
    }
    
    public static boolean isEnabled() {
        return getConfig().sellProtectionEnabled;
    }
    
    public static long getMaxAmount() {
        return getConfig().sellProtectionThreshold;
    }
    
    public static void setEnabled(boolean enabled) {
        CoflModConfig cfg = getConfig();
        cfg.sellProtectionEnabled = enabled;
        cfg.save();
        reloadConfig();
    }
    
    public static void setMaxAmount(long amount) {
        CoflModConfig cfg = getConfig();
        cfg.sellProtectionThreshold = amount;
        cfg.save();
        reloadConfig();
    }
}
