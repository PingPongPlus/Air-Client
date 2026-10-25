package com.coflnet.ahflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AhAutomationConfig {
    public boolean enabled;
    public String targetName = "";
    public boolean autoAttack = true;
    public double attackCpsMin = 1.0;
    public double attackCpsMax = 2.0;
    public double reach = 3.5;
    public boolean allowSprint = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AhAutomationConfig instance = new AhAutomationConfig();

    public static AhAutomationConfig get() {
        return instance;
    }

    public static void load() {
        try {
            Path path = file();
            if (Files.exists(path)) {
                AhAutomationConfig loaded = GSON.fromJson(Files.readString(path), AhAutomationConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            }
        } catch (Exception ignored) {
        }
        normalize();
    }

    private static void normalize() {
        instance.attackCpsMin = clamp(instance.attackCpsMin, 0.5, 5.0);
        instance.attackCpsMax = clamp(instance.attackCpsMax, instance.attackCpsMin, 5.0);
        instance.reach = clamp(instance.reach, 2.5, 5.0);
        if (instance.targetName == null) {
            instance.targetName = "";
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("automation.json");
    }
}
