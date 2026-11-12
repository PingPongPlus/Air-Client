package com.coflnet.experiments;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings for the Auto Experiment Table macro, stored in config/CoflSky/experiments.json. */
public class ExperimentConfig {
    public static final int MAX_CLICK_DELAY_MS = 1000;
    public static final int MAX_ROUNDS = 30;

    public boolean enabled = false;
    /** Wait between two clicks. The Titanic Experience Bottle purchase waits five times as long. */
    public int clickDelayMs = 250;
    /** Successful Chronomatron rounds before leaving; 0 skips the minigame. */
    public int chronomatronRounds = 12;
    /** Successful Ultrasequencer rounds before leaving; 0 skips the minigame. */
    public int ultrasequencerRounds = 9;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("experiments.json");
    }

    public static ExperimentConfig load() {
        ExperimentConfig config = new ExperimentConfig();
        try {
            Path path = file();
            if (Files.exists(path)) {
                ExperimentConfig loaded = GSON.fromJson(Files.readString(path), ExperimentConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            }
        } catch (Exception ignored) {
        }
        config.normalize();
        return config;
    }

    public void normalize() {
        clickDelayMs = Math.clamp(clickDelayMs, 0, MAX_CLICK_DELAY_MS);
        chronomatronRounds = Math.clamp(chronomatronRounds, 0, MAX_ROUNDS);
        ultrasequencerRounds = Math.clamp(ultrasequencerRounds, 0, MAX_ROUNDS);
    }

    public void save() {
        normalize();
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
