package com.coflnet.config;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class TextWidgetPositionConfig {
    private static final Gson gson = new Gson();
    private static final File POSITION_CONFIG_FILE = new File(Minecraft.getInstance().gameDirectory, "config/CoflSky/coflsky_text_position.json");
    
    public int offsetX = -5;
    public int offsetY = 5;
    
    public static TextWidgetPositionConfig load() {
        // Try to load from combined config first
        CoflModConfig combinedConfig = CoflModConfig.load();
        TextWidgetPositionConfig config = new TextWidgetPositionConfig();
        config.offsetX = combinedConfig.textWidgetOffsetX;
        config.offsetY = combinedConfig.textWidgetOffsetY;
        
        // Check if old config exists and migrate
        try {
            if (POSITION_CONFIG_FILE.exists()) {
                FileReader reader = new FileReader(POSITION_CONFIG_FILE);
                TextWidgetPositionConfig oldConfig = gson.fromJson(reader, TextWidgetPositionConfig.class);
                reader.close();
                if (oldConfig != null) {
                    // Migrate to new config
                    combinedConfig.textWidgetOffsetX = oldConfig.offsetX;
                    combinedConfig.textWidgetOffsetY = oldConfig.offsetY;
                    combinedConfig.save();
                    
                    // Delete old config file
                    POSITION_CONFIG_FILE.delete();
                    
                    config.offsetX = oldConfig.offsetX;
                    config.offsetY = oldConfig.offsetY;
                }
            }
        } catch (IOException e) {
            // Use default values if loading fails
        }
        
        return config;
    }
    
    public void save() {
        // Save to combined config instead
        CoflModConfig combinedConfig = CoflModConfig.load();
        combinedConfig.textWidgetOffsetX = this.offsetX;
        combinedConfig.textWidgetOffsetY = this.offsetY;
        combinedConfig.save();
    }
}
