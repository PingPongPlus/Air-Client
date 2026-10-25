package com.coflnet.lowballing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LowballingConfig {
    public boolean loop = true;
    public boolean debug = false;
    public boolean enforceBankroll = true;

    public String hubSelectorName = "Hub Selector";
    public int hubStartNumber = 1;
    public int maxHubNumber = 20;
    public int hubMessageIntervalSeconds = 5;
    public int hubSelectorAttempts = 3;
    public int hubSelectorRetryMs = 2000;
    public int hubSelectorFindTimeoutSeconds = 60;
    public int hubClickAttempts = 10;
    public int hubClickIntervalMs = 2000;
    public int hubMenuCloseGraceMs = 5000;
    public int hubSelectorFallbackSeconds = 30;
    public int hubWarpTimeoutSeconds = 120;
    public double hubForwardBlocks = 5.0;
    public int hubForwardTimeoutMs = 3000;

    public int advertiseIntervalSeconds = 10;
    public int advertiseCooldownSeconds = 300;
    public int bestOfferCooldownSeconds = 0;
    public int visitCommandIntervalSeconds = 5;
    public int returnIslandTimeoutSeconds = 120;

    public int islandSettleSeconds = 2;
    public int islandEmptyWaitSeconds = 15;
    public double minPlayerMoveBlocks = 1.0;
    public double interactReach = 3.5;

    public int sneakHoldMs = 500;
    public int menuRightClickIntervalMs = 1000;
    public int menuSneakTimeoutMs = 20000;
    public int menuUnSneakAttempts = 3;
    public int menuUnSneakIntervalMs = 1000;
    public int menuOpenTimeoutMs = 60000;

    public int offerGuiTimeoutMs = 10000;
    public int tradeClickIntervalMs = 2000;
    public int tradeClickMaxAttempts = 10;
    public int tradeCloseWaitMs = 5000;
    public int secondOfferWaitMs = 5000;

    public long reserveCoins = 1_000_000L;
    public int bankrollOverrideMillions = 0;
    public int fallbackBankrollMillions = 0;
    public double ahFeePercent = 5.0;
    public double minProfitPercent = 10.0;
    public long minItemCoins = 2_000_000L;
    public double firstOfferDiscountPercent = 5.0;
    public long firstOfferRoundCoins = 500_000L;
    public long chatOfferRoundCoins = 1_000_000L;
    public int maxCycles = 0;

    public int hubSlotOverride = -1;
    public int kickSlotOverride = -1;
    public int coinsSlotOverride = -1;
    public int tradeSlotOverride = -1;
    public int tradeItemSlotOverride = -1;

    public List<String> adMessages = defaultAdMessages();

    public List<String> bestOfferMessages = defaultBestOfferMessages();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("lowballing.json");
    }

    public static LowballingConfig load() {
        LowballingConfig config = new LowballingConfig();
        try {
            Path path = file();
            if (Files.exists(path)) {
                LowballingConfig loaded = GSON.fromJson(Files.readString(path), LowballingConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            }
        } catch (Exception ignored) {
        }
        config.normalize();
        return config;
    }

    private void normalize() {
        if (hubSelectorName == null || hubSelectorName.isBlank()) {
            hubSelectorName = "Hub Selector";
        }
        hubStartNumber = clamp(hubStartNumber, 1, 100);
        maxHubNumber = clamp(maxHubNumber, hubStartNumber, 100);
        hubMessageIntervalSeconds = clamp(hubMessageIntervalSeconds, 1, 60);
        hubSelectorAttempts = clamp(hubSelectorAttempts, 1, 10);
        hubSelectorRetryMs = clamp(hubSelectorRetryMs, 250, 10000);
        hubSelectorFindTimeoutSeconds = clamp(hubSelectorFindTimeoutSeconds, 10, 300);
        hubClickAttempts = clamp(hubClickAttempts, 1, 50);
        hubClickIntervalMs = clamp(hubClickIntervalMs, 250, 10000);
        hubMenuCloseGraceMs = clamp(hubMenuCloseGraceMs, 0, 30000);
        hubSelectorFallbackSeconds = clamp(hubSelectorFallbackSeconds, 5, 300);
        hubWarpTimeoutSeconds = clamp(hubWarpTimeoutSeconds, 10, 600);
        hubForwardBlocks = clamp(hubForwardBlocks, 0.0, 20.0);
        hubForwardTimeoutMs = clamp(hubForwardTimeoutMs, 500, 15000);

        advertiseIntervalSeconds = clamp(advertiseIntervalSeconds, 1, 300);
        advertiseCooldownSeconds = clamp(advertiseCooldownSeconds, 1, 3600);
        bestOfferCooldownSeconds = clamp(bestOfferCooldownSeconds, 0, 3600);
        visitCommandIntervalSeconds = clamp(visitCommandIntervalSeconds, 1, 60);
        returnIslandTimeoutSeconds = clamp(returnIslandTimeoutSeconds, 10, 600);

        islandSettleSeconds = clamp(islandSettleSeconds, 0, 30);
        islandEmptyWaitSeconds = clamp(islandEmptyWaitSeconds, 0, 120);
        minPlayerMoveBlocks = clamp(minPlayerMoveBlocks, 0.1, 20.0);
        interactReach = clamp(interactReach, 2.0, 5.0);

        sneakHoldMs = clamp(sneakHoldMs, 0, 5000);
        menuRightClickIntervalMs = clamp(menuRightClickIntervalMs, 100, 10000);
        menuSneakTimeoutMs = clamp(menuSneakTimeoutMs, 1000, 120000);
        menuUnSneakAttempts = clamp(menuUnSneakAttempts, 1, 10);
        menuUnSneakIntervalMs = clamp(menuUnSneakIntervalMs, 100, 10000);
        menuOpenTimeoutMs = clamp(menuOpenTimeoutMs, 5000, 180000);

        offerGuiTimeoutMs = clamp(offerGuiTimeoutMs, 1000, 60000);
        tradeClickIntervalMs = clamp(tradeClickIntervalMs, 250, 10000);
        tradeClickMaxAttempts = clamp(tradeClickMaxAttempts, 1, 100);
        tradeCloseWaitMs = clamp(tradeCloseWaitMs, 0, 60000);
        secondOfferWaitMs = clamp(secondOfferWaitMs, 0, 60000);

        reserveCoins = Math.max(0, reserveCoins);
        bankrollOverrideMillions = Math.max(0, bankrollOverrideMillions);
        fallbackBankrollMillions = Math.max(0, fallbackBankrollMillions);
        ahFeePercent = clamp(ahFeePercent, 0, 50);
        minProfitPercent = clamp(minProfitPercent, 0, 500);
        minItemCoins = Math.max(0, minItemCoins);
        firstOfferDiscountPercent = clamp(firstOfferDiscountPercent, 0, 100);
        firstOfferRoundCoins = Math.max(1, firstOfferRoundCoins);
        chatOfferRoundCoins = Math.max(1, chatOfferRoundCoins);
        maxCycles = Math.max(0, maxCycles);

        if (adMessages == null) {
            adMessages = defaultAdMessages();
        }
        if (bestOfferMessages == null) {
            bestOfferMessages = defaultBestOfferMessages();
        }
        adMessages.removeIf(message -> message == null || message.isBlank());
        bestOfferMessages.removeIf(message -> message == null || message.isBlank());
        if (adMessages.isEmpty()) {
            adMessages = defaultAdMessages();
        }
        if (bestOfferMessages.isEmpty()) {
            bestOfferMessages = defaultBestOfferMessages();
        }
    }

    private static List<String> defaultAdMessages() {
        return new ArrayList<>(List.of(
                "Lowballing {bankroll} coins",
                "Lowballing {bankroll} coins /visit me",
                "Lowballing {bankroll} coins no pet items",
                "Lowballing {bankroll} coins no skins or minions !!!!",
                "Lowballing {bankroll} !!!! look at my island"
        ));
    }

    private static List<String> defaultBestOfferMessages() {
        return new ArrayList<>(List.of(
                "Best i can do is {offer}",
                "My best offer is {offer}",
                "i got nothing better then {offer}"
        ));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
}
