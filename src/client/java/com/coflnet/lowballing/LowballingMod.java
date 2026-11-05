package com.coflnet.lowballing;

import com.coflnet.CoflModClient;
import com.coflnet.ahflipper.AhAnalyzer;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.ahflipper.AhItem;
import com.coflnet.ahflipper.AhValuation;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.flipper.trading.SignInput;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class LowballingMod {
    public static volatile LowballingConfig CONFIG = LowballingConfig.load();

    private enum State {
        IDLE, GO_HUB, FIND_SELECTOR, OPEN_SELECTOR, HUB_MENU, WAIT_HUB_WARP,
        HUB_SETTLE, ADVERTISE,
        RETURN_ISLAND, ISLAND_SCAN, APPROACH_PLAYER, OPEN_MENU, PLAYER_MENU,
        OPEN_OFFER, VALUING, OFFER_READY, SET_COINS, WAIT_TRADE, LISTING,
        CHAT_OFFER, KICK_WAIT, NEXT_PLAYER, RECONNECTING
    }

    private record Offer(String tag, String displayName, long expectedValue,
                         long maxAhPrice, long firstOffer, long chatOffer,
                         boolean buyable, List<String> reasons) {
    }

    private record TradeItem(String tag, String name, long saleValue) {
    }

    public record ItemView(String tag, String name, long value) {
    }

    public record Snapshot(String state, String target, String itemTag,
                           String itemName, long estimatedValue, long firstOffer,
                           long chatOffer, boolean buyable, String note,
                           int queueSize, List<ItemView> items) {
    }

    private static final class PlayerTrack {
        final Vec3 start;
        boolean moved;

        PlayerTrack(Vec3 start) {
            this.start = start;
        }
    }

    private static KeyMapping toggleKey;
    private static volatile boolean running;
    private static volatile State state = State.IDLE;
    private static long stateAt;
    private static long actionAt;
    private static long phaseAt;
    private static long lastLogAt;

    private static int cycles;
    private static int hubNumber;
    private static int hubClicks;
    private static int selectorClicks;
    private static boolean hubMenuStarted;
    private static long warpWatchAt;
    private static long warpRequestedAt;
    private static long switchConfirmedAt;
    private static long levelChangedAt;
    private static Object lastLevel;
    private static State stateBeforeDisconnect = State.IDLE;
    private static double warpX;
    private static double warpY;
    private static double warpZ;

    private static UUID targetUuid;
    private static volatile String targetName = "";
    private static Entity targetEntity;
    private static boolean secondOffer;
    private static boolean finalizingSecond;
    private static boolean kickAfterMenu;
    private static boolean coinsClicked;
    private static int unSneakClicks;
    private static boolean sneakOwned;
    private static boolean forwardOwned;
    private static double settleStartX;
    private static double settleStartZ;
    private static double settleLookX;
    private static double settleLookZ;

    private static int tradeClicks;
    private static long tradeClickAt;
    private static String tradeMenuTitle = "";
    private static boolean tradeGuiSeen;
    private static long tradeGuiAt;
    private static long tradeClosedAt;
    private static long offerSearchStartedAt;
    private static boolean chatSent;
    private static long nextChatAt;
    private static long chatOfferDelayMs;
    private static long secondOfferDelayMs;

    private static volatile Offer valuationOffer;
    private static volatile String valuationError;
    private static volatile String currentTradeTag = "";
    private static volatile String currentTradeName = "";
    private static volatile long currentTradeValue;
    private static volatile List<TradeItem> pendingTradeItems = List.of();
    private static volatile boolean listingDone;
    private static volatile Offer pendingOffer;

    private static final Deque<UUID> PLAYER_QUEUE = new ArrayDeque<>();
    private static final Map<UUID, PlayerTrack> TRACKED = new HashMap<>();
    private static final Set<UUID> PROCESSED = new HashSet<>();

    private static long[] adCooldowns = new long[0];
    private static long[] bestCooldowns = new long[0];
    private static long nextAdAt;
    private static long advertiseStartedAt;
    private static long islandArrivedAt;
    private static long bankrollMissingSince;

    private LowballingMod() {
    }

    public static void init() {
        LowballingChat.init();
        registerKeybinding();
        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(LowballingMod::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!running) {
                return;
            }
            stateBeforeDisconnect = state;
            releaseSneak();
            LowballingNavigator.stop();
            SignInput.clear();
            LowballingChat.reset();
            setState(State.RECONNECTING);
            message("§e[Lowballing] §7Verbindung getrennt - warte auf Reconnect.");
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (running && state == State.RECONNECTING) {
                if (stateBeforeDisconnect == State.HUB_MENU
                        || stateBeforeDisconnect == State.WAIT_HUB_WARP
                        || stateBeforeDisconnect == State.HUB_SETTLE) {
                    switchConfirmedAt = System.currentTimeMillis();
                    setState(State.WAIT_HUB_WARP);
                    actionAt = stateAt;
                    message("§a[Lowballing] §7Serverwechsel erkannt - warte auf Hub.");
                } else {
                    message("§a[Lowballing] §7Reconnect erkannt - starte Sequenz neu.");
                    enterGoHub();
                }
                stateBeforeDisconnect = State.IDLE;
            }
        });
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean stopForRestart() {
        boolean wasRunning = running;
        if (wasRunning) {
            stop("\u00a76[Lowballing] \u00a77Stopped for server restart.");
        }
        return wasRunning;
    }

    public static void resumeAfterRestart(boolean wasRunning) {
        if (wasRunning && !running) {
            start();
        }
    }

    public static Snapshot snapshot() {
        Offer offer = valuationOffer != null ? valuationOffer : pendingOffer;
        List<ItemView> items = new ArrayList<>();
        long itemValue = 0;
        for (TradeItem item : pendingTradeItems) {
            items.add(new ItemView(item.tag(), item.name(), item.saleValue()));
            itemValue += Math.max(0, item.saleValue());
        }
        String tag = offer != null ? offer.tag()
                : !currentTradeTag.isBlank() ? currentTradeTag
                : items.isEmpty() ? "" : items.get(0).tag();
        String name = offer != null ? offer.displayName()
                : !currentTradeName.isBlank() ? currentTradeName
                : items.isEmpty() ? "" : items.get(0).name();
        long value = offer != null ? offer.expectedValue()
                : currentTradeValue > 0 ? currentTradeValue : itemValue;
        String note = valuationError;
        if (note == null || note.isBlank()) {
            note = offer != null && !offer.buyable()
                    ? String.join("; ", offer.reasons()) : "";
        }
        return new Snapshot(state.name(), targetName, tag, name, value,
                offer != null ? offer.firstOffer() : 0,
                offer != null ? offer.chatOffer() : 0,
                offer != null && offer.buyable(), note,
                PLAYER_QUEUE.size(), List.copyOf(items));
    }

    public static void toggle() {
        if (running) {
            stop("§6[Lowballing] §cgestoppt.");
        } else {
            start();
        }
    }

    public static KeyMapping toggleKey() {
        return toggleKey;
    }

    private static void registerKeybinding() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("coflnet", "lowballing"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.lowballing",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                category));
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                registerLowballingCommands(dispatcher));
    }

    private static void registerLowballingCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("lowballing")
                .executes(context -> toggleCommand())
                .then(ClientCommands.literal("start").executes(context -> startCommand()))
                .then(ClientCommands.literal("stop").executes(context -> stopCommand()))
                .then(ClientCommands.literal("status").executes(context -> statusCommand()))
                .then(ClientCommands.literal("reload").executes(context -> reloadCommand()))
                .then(ClientCommands.literal("debug").executes(context -> debugCommand()))
                .then(ClientCommands.literal("bankroll")
                        .then(ClientCommands.argument("millions", IntegerArgumentType.integer(0))
                                .executes(LowballingMod::bankrollCommand))));
        dispatcher.register(ClientCommands.literal("lb")
                .executes(context -> toggleCommand())
                .then(ClientCommands.literal("start").executes(context -> startCommand()))
                .then(ClientCommands.literal("stop").executes(context -> stopCommand()))
                .then(ClientCommands.literal("status").executes(context -> statusCommand())));
    }

    private static int toggleCommand() {
        toggle();
        return 1;
    }

    private static int startCommand() {
        start();
        return 1;
    }

    private static int stopCommand() {
        if (!running) {
            message("§c[Lowballing] läuft nicht.");
            return 0;
        }
        stop("§6[Lowballing] §cgestoppt.");
        return 1;
    }

    private static int statusCommand() {
        message("§6[Lowballing] §7Status: " + (running ? "§aAKTIV" : "§cGESTOPPT"));
        message("§7State: §f" + state + " §7· Target: §f"
                + (targetName.isBlank() ? "-" : targetName)
                + " §7· Queue: §f" + PLAYER_QUEUE.size()
                + " §7· Zyklus: §f" + cycles);
        long bankroll = bankrollCoins();
        message("§7Bankroll: §f" + (bankroll >= 0 ? formatBankroll(bankroll) : "?")
                + " §7· Reserve: §f" + ScreenUtil.formatCoins(CONFIG.reserveCoins)
                + " §7· Nächste Werbung: §f" + Math.max(0, nextAdAt - System.currentTimeMillis()) / 1000 + "s");
        if (pendingOffer != null) {
            message("§7Angebot: §f" + pendingOffer.displayName()
                    + " §7· Wert §f" + ScreenUtil.formatCoins(pendingOffer.expectedValue())
                    + " §7· Max AH §f" + ScreenUtil.formatCoins(pendingOffer.maxAhPrice())
                    + " §7· Erstes §f" + ScreenUtil.formatCoins(pendingOffer.firstOffer())
                    + " §7· Chat §f" + ScreenUtil.formatCoins(pendingOffer.chatOffer())
                    + (pendingOffer.buyable() ? " §aBUY" : " §cNO"));
        }
        return 1;
    }

    private static int reloadCommand() {
        CONFIG = LowballingConfig.load();
        ensureCooldowns();
        message("§a[Lowballing] §7Config neu geladen.");
        return 1;
    }

    private static int debugCommand() {
        CONFIG.debug = !CONFIG.debug;
        CONFIG.save();
        message("§6[Lowballing] §7Debug " + (CONFIG.debug ? "§aan" : "§caus"));
        return 1;
    }

    private static int bankrollCommand(CommandContext<FabricClientCommandSource> context) {
        int millions = IntegerArgumentType.getInteger(context, "millions");
        CONFIG.bankrollOverrideMillions = millions;
        CONFIG.save();
        message("§a[Lowballing] §7Bankroll-Override: §f" + millions + "m");
        return 1;
    }

    private static void start() {
        if (running) {
            message("§c[Lowballing] läuft bereits.");
            return;
        }
        if (!ScreenUtil.onSkyBlock()) {
            message("§c[Lowballing] kann nur auf Hypixel SkyBlock gestartet werden.");
            return;
        }
        running = true;
        cycles = 0;
        targetUuid = null;
        targetName = "";
        targetEntity = null;
        currentTradeTag = "";
        currentTradeName = "";
        currentTradeValue = 0;
        pendingOffer = null;
        secondOffer = false;
        finalizingSecond = false;
        kickAfterMenu = false;
        valuationOffer = null;
        valuationError = null;
        PLAYER_QUEUE.clear();
        TRACKED.clear();
        PROCESSED.clear();
        ensureCooldowns();
        LowballingChat.reset();
        message("§6[Lowballing] §aaktiv §7· Sequenz startet mit /hub.");
        enterGoHub();
    }

    private static void stop(String text) {
        running = false;
        releaseSneak();
        releaseForward();
        LowballingRotation.clear();
        LowballingNavigator.stop();
        LowballingGui.close();
        SignInput.clear();
        PLAYER_QUEUE.clear();
        TRACKED.clear();
        PROCESSED.clear();
        pendingOffer = null;
        secondOffer = false;
        finalizingSecond = false;
        kickAfterMenu = false;
        valuationOffer = null;
        valuationError = null;
        currentTradeTag = "";
        currentTradeName = "";
        currentTradeValue = 0;
        setState(State.IDLE);
        if (text != null && !text.isBlank()) {
            message(text);
        }
    }

    private static void fail(String reason) {
        System.out.println("[Lowballing] Fehler: " + reason);
        LowballingGui.close();
        running = false;
        releaseSneak();
        releaseForward();
        LowballingRotation.clear();
        LowballingNavigator.stop();
        SignInput.clear();
        PLAYER_QUEUE.clear();
        TRACKED.clear();
        PROCESSED.clear();
        pendingOffer = null;
        secondOffer = false;
        finalizingSecond = false;
        kickAfterMenu = false;
        setState(State.IDLE);
        message("§c[Lowballing] Fehler: §f" + reason + " §c- Lowballing beendet.");
    }

    private static void setState(State next) {
        state = next;
        stateAt = System.currentTimeMillis();
        actionAt = 0;
        phaseAt = 0;
    }

    private static void enterGoHub() {
        releaseSneak();
        releaseForward();
        LowballingRotation.clear();
        LowballingNavigator.stop();
        LowballingGui.close();
        hubMenuStarted = false;
        selectorClicks = 0;
        SignInput.clear();
        LowballingChat.reset();
        setState(State.GO_HUB);
        warpWatchAt = stateAt;
        warpRequestedAt = 0;
        switchConfirmedAt = 0;
        levelChangedAt = 0;
        lastLevel = null;
        stateBeforeDisconnect = State.IDLE;
        setWarpPosition();
        sendChat("/hub");
        actionAt = stateAt;
        debug("Sequenz: /hub");
    }

    private static void enterFindSelector() {
        releaseSneak();
        releaseForward();
        LowballingNavigator.stop();
        LowballingChat.clearWarp();
        setState(State.FIND_SELECTOR);
        debug("Suche " + CONFIG.hubSelectorName);
    }

    private static void enterOpenSelector() {
        LowballingNavigator.stop();
        setState(State.OPEN_SELECTOR);
        selectorClicks = 0;
        actionAt = stateAt - CONFIG.hubSelectorRetryMs;
        debug("Öffne " + CONFIG.hubSelectorName);
    }

    private static void enterHubMenu() {
        releaseSneak();
        if (!hubMenuStarted) {
            hubMenuStarted = true;
            hubNumber = CONFIG.hubStartNumber;
            hubClicks = 0;
        }
        LowballingChat.clearWarp();
        setState(State.HUB_MENU);
        actionAt = stateAt;
        warpWatchAt = stateAt;
        warpRequestedAt = 0;
        switchConfirmedAt = 0;
        levelChangedAt = 0;
        setWarpPosition();
        debug("Hub-Menü #" + hubNumber);
    }

    private static void enterWaitHubWarp() {
        setState(State.WAIT_HUB_WARP);
        actionAt = stateAt;
        debug("Hub-Menü geschlossen - warte auf Warp");
    }

    private static void enterHubSettle() {
        releaseSneak();
        releaseForward();
        LowballingNavigator.stop();
        LowballingGui.close();
        LowballingRotation.clear();
        setState(State.HUB_SETTLE);
        switchConfirmedAt = System.currentTimeMillis();
        actionAt = stateAt;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || CONFIG.hubForwardBlocks <= 0.0) {
            enterAdvertise();
            return;
        }
        settleStartX = client.player.getX();
        settleStartZ = client.player.getZ();
        double yaw = Math.toRadians(client.player.getYRot());
        double lookDistance = CONFIG.hubForwardBlocks + 5.0;
        settleLookX = settleStartX - Math.sin(yaw) * lookDistance;
        settleLookZ = settleStartZ + Math.cos(yaw) * lookDistance;
        LowballingRotation.lookAt(new Vec3(settleLookX, client.player.getEyeY(), settleLookZ));
        debug("Hub #" + hubNumber + " erreicht - gehe "
                + String.format(Locale.ROOT, "%.1f", CONFIG.hubForwardBlocks)
                + " Blöcke nach vorne");
    }

    private static void enterAdvertise() {
        releaseSneak();
        releaseForward();
        LowballingRotation.clear();
        LowballingNavigator.stop();
        LowballingGui.close();
        hubMenuStarted = false;
        LowballingChat.clearWarp();
        setState(State.ADVERTISE);
        advertiseStartedAt = stateAt;
        nextAdAt = stateAt + 3000L;
        bankrollMissingSince = 0;
        debug("Warp erfolgreich - Werbung");
    }

    private static void enterReturnIsland() {
        releaseSneak();
        releaseForward();
        LowballingRotation.clear();
        LowballingNavigator.stop();
        LowballingGui.close();
        SignInput.clear();
        LowballingChat.clearWarp();
        setState(State.RETURN_ISLAND);
        actionAt = stateAt;
        warpWatchAt = stateAt;
        setWarpPosition();
        sendChat("/is");
        debug("Besucher erkannt - /is");
    }

    private static void enterIslandScan() {
        releaseSneak();
        LowballingNavigator.stop();
        LowballingChat.clearWarp();
        setState(State.ISLAND_SCAN);
        islandArrivedAt = stateAt;
        TRACKED.clear();
        PROCESSED.clear();
        PLAYER_QUEUE.clear();
        collectInitialPlayers();
        debug("Insel erreicht - beobachte Spieler");
    }

    private static void enterApproach() {
        releaseSneak();
        setState(State.APPROACH_PLAYER);
        actionAt = stateAt;
        coinsClicked = false;
        kickAfterMenu = false;
        unSneakClicks = 0;
        offerSearchStartedAt = 0;
        if (!secondOffer) {
            pendingTradeItems = List.of();
        }
        debug("Nähere mich " + targetName + (secondOffer ? " (zweites Angebot)" : ""));
    }

    private static void enterOpenMenu() {
        setState(State.OPEN_MENU);
        actionAt = kickAfterMenu ? stateAt
                : stateAt + CONFIG.sneakHoldMs - CONFIG.menuRightClickIntervalMs;
        unSneakClicks = 0;
        coinsClicked = false;
        setSneak(!kickAfterMenu);
        debug((kickAfterMenu ? "Rechtsklicke ohne Sneak " : "Sneake und rechtsklicke ")
                + targetName);
    }

    private static void enterPlayerMenu() {
        releaseSneak();
        setState(State.PLAYER_MENU);
        actionAt = stateAt;
        coinsClicked = false;
        if (offerSearchStartedAt == 0) {
            offerSearchStartedAt = stateAt;
        }
        debug("Spieler-GUI offen");
    }

    private static void enterOpenOffer() {
        setState(State.OPEN_OFFER);
        actionAt = stateAt;
        coinsClicked = true;
        debug("Warte auf Angebots-GUI");
    }

    private static void enterValuing(int slot) {
        enterValuing(List.of(slot));
    }

    private static void enterValuing(List<Integer> slots) {
        if (slots == null || slots.isEmpty()) {
            requestKick();
            return;
        }
        AhItem item = LowballingGui.itemAt(slots.get(0));
        if (item.tag.isBlank()) {
            requestKick();
            return;
        }
        setState(State.VALUING);
        actionAt = stateAt;
        valuationOffer = null;
        valuationError = null;
        currentTradeTag = item.tag;
        currentTradeName = item.displayName;
        currentTradeValue = 0;
        Thread.startVirtualThread(() -> computeOffer(slots));
        debug("Bewerte " + item.displayName
                + (slots.size() > 1 ? " +" + (slots.size() - 1) : ""));
    }

    private static void enterOfferReady() {
        setState(State.OFFER_READY);
        actionAt = stateAt;
    }

    private static void enterSetCoins() {
        setState(State.SET_COINS);
        actionAt = stateAt;
    }

    private static void enterWaitTrade() {
        setState(State.WAIT_TRADE);
        actionAt = stateAt;
        tradeClicks = 0;
        tradeClickAt = stateAt;
        tradeGuiSeen = false;
        tradeGuiAt = 0;
        tradeClosedAt = 0;
        debug("Klicke Trade");
    }

    private static void enterChatOffer() {
        SignInput.clear();
        setState(State.CHAT_OFFER);
        actionAt = stateAt;
        phaseAt = stateAt;
        chatSent = false;
        nextChatAt = 0;
        chatOfferDelayMs = 5000L + ThreadLocalRandom.current().nextInt(2001);
        secondOfferDelayMs = 5000L + ThreadLocalRandom.current().nextInt(2001);
        debug("Trade-GUI geschlossen - Chat-Angebot");
    }

    private static void enterKickWait() {
        setState(State.KICK_WAIT);
        actionAt = stateAt;
    }

    private static void enterNextPlayer() {
        releaseSneak();
        releaseForward();
        LowballingRotation.clear();
        LowballingNavigator.stop();
        currentTradeTag = "";
        currentTradeName = "";
        currentTradeValue = 0;
        setState(State.NEXT_PLAYER);
        actionAt = stateAt;
    }

    private static void onClientTick(Minecraft client) {
        if (toggleKey != null && toggleKey.consumeClick()) {
            if (running) {
                stop("§6[Lowballing] §cgestoppt.");
            } else {
                start();
            }
            return;
        }
        if (!running) {
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }
        if (lastLevel != null && client.level != lastLevel) {
            levelChangedAt = System.currentTimeMillis();
        }
        lastLevel = client.level;
        try {
            long now = System.currentTimeMillis();
            if (LowballingChat.consumeKick(0)) {
                message("§e[Lowballing] §7Server-Kick erkannt - starte Sequenz neu.");
                enterGoHub();
                return;
            }
            if (!expectsWarp() && now - stateAt > 5000 && LowballingChat.consumeWarp(0)) {
                message("§e[Lowballing] §7Serverwechsel erkannt - starte Sequenz neu.");
                enterGoHub();
                return;
            }
            switch (state) {
                case GO_HUB -> tickGoHub(client, now);
                case FIND_SELECTOR -> tickFindSelector(client, now);
                case OPEN_SELECTOR -> tickOpenSelector(client, now);
                case HUB_MENU -> tickHubMenu(client, now);
                case WAIT_HUB_WARP -> tickWaitHubWarp(client, now);
                case HUB_SETTLE -> tickHubSettle(client, now);
                case ADVERTISE -> tickAdvertise(client, now);
                case RETURN_ISLAND -> tickReturnIsland(client, now);
                case ISLAND_SCAN -> tickIslandScan(client, now);
                case APPROACH_PLAYER -> tickApproach(client, now);
                case OPEN_MENU -> tickOpenMenu(client, now);
                case PLAYER_MENU -> tickPlayerMenu(client, now);
                case OPEN_OFFER -> tickOpenOffer(client, now);
                case VALUING -> tickValuing(client, now);
                case OFFER_READY -> tickOfferReady(client, now);
                case SET_COINS -> tickSetCoins(client, now);
                case WAIT_TRADE -> tickWaitTrade(client, now);
                case LISTING -> tickListing(client, now);
                case CHAT_OFFER -> tickChatOffer(client, now);
                case KICK_WAIT -> tickKickWait(client, now);
                case NEXT_PLAYER -> tickNextPlayer(client, now);
                case RECONNECTING, IDLE -> {
                }
            }
        } catch (Exception e) {
            System.out.println("[Lowballing] Tick-Fehler: " + e);
            fail("Interner Fehler: " + e.getMessage());
        }
    }

    private static boolean expectsWarp() {
        return switch (state) {
            case GO_HUB, FIND_SELECTOR, OPEN_SELECTOR, HUB_MENU, WAIT_HUB_WARP,
                 HUB_SETTLE, RETURN_ISLAND -> true;
            default -> false;
        };
    }

    private static void tickGoHub(Minecraft client, long now) {
        if (LowballingChat.consumeAlreadyHere(warpWatchAt)) {
            enterFindSelector();
            return;
        }
        if (LowballingChat.consumeWarp(warpWatchAt) || coordsMoved(5.0)) {
            enterFindSelector();
            return;
        }
        if (now - actionAt >= CONFIG.hubMessageIntervalSeconds * 1000L) {
            sendChat("/hub");
            actionAt = now;
            debug("Sende /hub");
        }
        if (now - stateAt > CONFIG.hubSelectorFallbackSeconds * 1000L
                && findEntity(CONFIG.hubSelectorName) != null) {
            debug("/hub ohne Koordinatenwechsel - bereits im Hub");
            enterFindSelector();
            return;
        }
        if (now - stateAt > CONFIG.hubWarpTimeoutSeconds * 1000L) {
            stateAt = now;
            actionAt = 0;
            setWarpPosition();
            debug("/hub Timeout - versuche erneut");
        }
    }

    private static void tickFindSelector(Minecraft client, long now) {
        if (LowballingGui.isContainerOpen()) {
            enterHubMenu();
            return;
        }
        Entity selector = findEntity(CONFIG.hubSelectorName);
        if (selector == null) {
            targetEntity = null;
            if (now - stateAt > CONFIG.hubSelectorFindTimeoutSeconds * 1000L) {
                fail(CONFIG.hubSelectorName + " nicht gefunden");
            }
            return;
        }
        targetEntity = selector;
        targetName = selector.getName().getString();
        LowballingNavigator.tick(selector);
        if (client.player.distanceTo(selector) <= CONFIG.interactReach) {
            enterOpenSelector();
        }
    }

    private static void tickOpenSelector(Minecraft client, long now) {
        if (LowballingGui.isContainerOpen()) {
            enterHubMenu();
            return;
        }
        Entity selector = resolveTarget();
        if (selector == null) {
            enterFindSelector();
            return;
        }
        targetEntity = selector;
        LowballingNavigator.tick(selector);
        if (client.player.distanceTo(selector) > CONFIG.interactReach + 1.0) {
            return;
        }
        if (now - actionAt < CONFIG.hubSelectorRetryMs) {
            return;
        }
        if (selectorClicks >= CONFIG.hubSelectorAttempts) {
            fail(CONFIG.hubSelectorName + "-GUI öffnet nicht");
            return;
        }
        LowballingRotation.lookAt(selector);
        if (!rightClickEntity(selector)) {
            return;
        }
        selectorClicks++;
        actionAt = now;
        debug("Rechtsklick " + CONFIG.hubSelectorName + " " + selectorClicks + "/"
                + CONFIG.hubSelectorAttempts);
    }

    private static void tickHubMenu(Minecraft client, long now) {
        if (LowballingChat.consumeAlreadyHere(warpWatchAt)) {
            LowballingGui.close();
            releaseSneak();
            debug("Already on this server - skipping hub selector warp");
            enterAdvertise();
            return;
        }
        if (LowballingChat.consumeWarp(warpWatchAt)) {
            warpRequestedAt = now;
        }
        if (!LowballingGui.isContainerOpen()) {
            enterWaitHubWarp();
            return;
        }
        if (now - actionAt < CONFIG.hubClickIntervalMs) {
            return;
        }
        if (hubClicks >= CONFIG.hubClickAttempts) {
            hubNumber++;
            hubClicks = 0;
            actionAt = now;
            if (hubNumber > CONFIG.maxHubNumber) {
                fail("Kein Hub-Warp möglich");
            }
            return;
        }
        int slot = LowballingGui.findHubSlot(hubNumber);
        if (slot < 0) {
            hubNumber++;
            hubClicks = 0;
            actionAt = now;
            if (hubNumber > CONFIG.maxHubNumber) {
                fail("Kein Skyblock-Hub-Slot gefunden");
            }
            return;
        }
        LowballingGui.clickSlot(slot, 1);
        hubClicks++;
        actionAt = now;
        debug("Klicke Skyblock Hub #" + hubNumber + " " + hubClicks + "/"
                + CONFIG.hubClickAttempts);
    }

    private static void tickWaitHubWarp(Minecraft client, long now) {
        if (LowballingChat.consumeAlreadyHere(warpWatchAt)) {
            LowballingGui.close();
            releaseSneak();
            debug("Already on this server - skipping warp wait");
            enterAdvertise();
            return;
        }
        if (LowballingChat.consumeWarp(warpWatchAt)) {
            warpRequestedAt = now;
        }
        if (serverSwitchDetected(now) && client.player != null && client.level != null) {
            enterHubSettle();
            return;
        }
        if (now - actionAt > CONFIG.hubMenuCloseGraceMs) {
            enterOpenSelector();
        }
    }

    private static boolean serverSwitchDetected(long now) {
        if (switchConfirmedAt > 0 && now - switchConfirmedAt >= 800) {
            return true;
        }
        if (warpRequestedAt <= 0 || now - warpRequestedAt < 1500) {
            return false;
        }
        if (coordsMoved(5.0) || levelChangedAt >= warpRequestedAt) {
            return true;
        }
        return selectorGone();
    }

    private static void tickHubSettle(Minecraft client, long now) {
        if (client.player == null) {
            return;
        }
        LowballingRotation.lookAt(
                new Vec3(settleLookX, client.player.getEyeY(), settleLookZ));
        if (now - stateAt < 400) {
            return;
        }
        if (!forwardOwned) {
            setForward(true);
        }
        double moved = Math.hypot(client.player.getX() - settleStartX,
                client.player.getZ() - settleStartZ);
        if (moved >= CONFIG.hubForwardBlocks
                || now - stateAt >= CONFIG.hubForwardTimeoutMs) {
            releaseForward();
            debug("Hub #" + hubNumber + " bereit - "
                    + String.format(Locale.ROOT, "%.1f", moved) + " Blöcke vorwärts");
            enterAdvertise();
        }
    }

    private static void tickAdvertise(Minecraft client, long now) {
        LowballingChat.Visitor visitor = LowballingChat.consumeVisitor(advertiseStartedAt);
        if (visitor != null) {
            debug("Besucher: " + visitor.name());
            enterReturnIsland();
            return;
        }
        if (now < nextAdAt) {
            return;
        }
        long bankroll = bankrollCoins();
        if (bankroll < 0) {
            if (bankrollMissingSince == 0) {
                bankrollMissingSince = now;
            }
            if (now - bankrollMissingSince > 60000) {
                fail("Bankroll/Purse konnte nicht gelesen werden");
                return;
            }
            nextAdAt = now + 5000;
            return;
        }
        bankrollMissingSince = 0;
        String text = pickMessage(CONFIG.adMessages, adCooldowns,
                CONFIG.advertiseCooldownSeconds * 1000L, bankroll);
        if (text == null) {
            return;
        }
        sendChat(text);
        nextAdAt = now + CONFIG.advertiseIntervalSeconds * 1000L;
        debug("Werbung: " + text);
    }

    private static void tickReturnIsland(Minecraft client, long now) {
        if (coordsMoved(5.0)) {
            enterIslandScan();
            return;
        }
        if (now - actionAt >= CONFIG.visitCommandIntervalSeconds * 1000L) {
            sendChat("/is");
            actionAt = now;
            debug("Sende /is");
        }
        if (now - stateAt > CONFIG.returnIslandTimeoutSeconds * 1000L) {
            fail("/is hat die Insel nicht geladen");
        }
    }

    private static void tickIslandScan(Minecraft client, long now) {
        updatePlayerTracks(client);
        if (now - islandArrivedAt < CONFIG.islandSettleSeconds * 1000L) {
            return;
        }
        collectMovedPlayers(client);
        if (!PLAYER_QUEUE.isEmpty()) {
            startNextPlayer(client);
            return;
        }
        long wait = (CONFIG.islandSettleSeconds + CONFIG.islandEmptyWaitSeconds) * 1000L;
        if (now - islandArrivedAt >= wait) {
            finishCycle();
        }
    }

    private static void tickApproach(Minecraft client, long now) {
        Player target = resolvePlayer();
        if (target == null) {
            enterNextPlayer();
            return;
        }
        targetEntity = target;
        targetName = target.getName().getString();
        LowballingNavigator.tick(target);
        if (client.player.distanceTo(target) <= CONFIG.interactReach) {
            LowballingNavigator.stop();
            enterOpenMenu();
            return;
        }
        if (now - stateAt > CONFIG.menuOpenTimeoutMs) {
            message("§e[Lowballing] §7" + targetName + " nicht erreichbar - übersprungen.");
            enterNextPlayer();
        }
    }

    private static void tickOpenMenu(Minecraft client, long now) {
        if (LowballingGui.isContainerOpen()) {
            enterPlayerMenu();
            return;
        }
        Player target = resolvePlayer();
        if (target == null) {
            enterNextPlayer();
            return;
        }
        targetEntity = target;
        if (client.player.distanceTo(target) > CONFIG.interactReach + 1.0) {
            enterApproach();
            return;
        }
        if (kickAfterMenu) {
            if (now - actionAt < CONFIG.menuUnSneakIntervalMs) {
                return;
            }
            LowballingRotation.lookAt(target);
            if (rightClickEntity(target)) {
                actionAt = now;
                debug("Kick-Menue: Rechtsklick ohne Sneak " + targetName);
            }
            return;
        }
        if (now - stateAt < CONFIG.sneakHoldMs) {
            setSneak(true);
            return;
        }
        if (now - stateAt < CONFIG.menuSneakTimeoutMs) {
            setSneak(true);
            LowballingRotation.lookAt(target);
            if (now - actionAt >= CONFIG.menuRightClickIntervalMs
                    && rightClickEntity(target)) {
                actionAt = now;
                debug("Sneak-Rechtsklick " + targetName);
            }
            return;
        }
        setSneak(false);
        message("§e[Lowballing] §7Trade-Request nicht angenommen - kicke Spieler.");
        requestKick();
    }

    private static void tickPlayerMenu(Minecraft client, long now) {
        if (!LowballingGui.isContainerOpen()) {
            if (kickAfterMenu) {
                enterOpenMenu();
                return;
            }
            if (now - stateAt > 1000) {
                if (offerSearchStartedAt > 0
                        && now - offerSearchStartedAt > offerWaitMs()) {
                    enterNextPlayer();
                } else {
                    enterOpenMenu();
                }
            }
            return;
        }
        if (now - stateAt < 500) {
            return;
        }
        if (kickAfterMenu) {
            int kick = LowballingGui.findKickSlot();
            if (kick >= 0) {
                LowballingGui.clickSlot(kick, 0);
                enterKickWait();
            } else {
                LowballingGui.close();
                enterNextPlayer();
            }
            return;
        }
        handleOfferGui(now);
    }

    private static void tickOpenOffer(Minecraft client, long now) {
        if (LowballingGui.isSignOpen()) {
            if (now - stateAt > 3000) {
                LowballingGui.close();
                coinsClicked = false;
                requestKick();
            }
            return;
        }
        if (LowballingGui.isContainerOpen()) {
            handleOfferGui(now);
            return;
        }
        if (now - stateAt > 1500) {
            coinsClicked = false;
            setState(State.PLAYER_MENU);
        }
    }

    private static void tickValuing(Minecraft client, long now) {
        if (valuationError != null) {
            String error = valuationError;
            valuationError = null;
            message("§c[Lowballing] §7Bewertung fehlgeschlagen: §f" + error);
            requestKick();
            return;
        }
        Offer offer = valuationOffer;
        if (offer == null) {
            if (now - stateAt > 60000) {
                fail("Bewertung dauert zu lange");
            }
            return;
        }
        valuationOffer = null;
        pendingOffer = offer;
        debug("Bewertet: " + offer.tag() + " value=" + offer.expectedValue()
                + " max=" + offer.maxAhPrice() + " first=" + offer.firstOffer()
                + " chat=" + offer.chatOffer() + " buy=" + offer.buyable());
        enterOfferReady();
    }

    private static void tickOfferReady(Minecraft client, long now) {
        Offer offer = pendingOffer;
        if (offer == null) {
            fail("Angebot fehlt");
            return;
        }
        if (!offer.buyable()) {
            for (String reason : offer.reasons()) {
                debug("Grund: " + reason);
            }
            message("§e[Lowballing] §7" + offer.displayName() + " nicht kaufbar - kicke Spieler.");
            requestKick();
            return;
        }
        long bankroll = bankrollCoins();
        if (CONFIG.enforceBankroll && bankroll >= 0
                && (offer.firstOffer() > bankroll || offer.chatOffer() > bankroll)) {
            message("§e[Lowballing] §7Angebot über Bankroll - kicke Spieler.");
            requestKick();
            return;
        }
        int coins = LowballingGui.findCoinsSlot();
        if (coins < 0) {
            message("§e[Lowballing] §7Coins-Slot nicht gefunden - kicke Spieler.");
            requestKick();
            return;
        }
        finalizingSecond = false;
        SignInput.set(String.valueOf(offer.firstOffer()));
        LowballingGui.clickSlot(coins, 0);
        enterSetCoins();
        debug("Setze Coins " + ScreenUtil.formatCoins(offer.firstOffer()));
    }

    private static void tickSetCoins(Minecraft client, long now) {
        if (LowballingGui.isSignOpen()) {
            if (now - stateAt > 3000) {
                LowballingGui.close();
            }
            return;
        }
        if (!LowballingGui.isContainerOpen()) {
            if (now - stateAt > 5000) {
                requestKick();
            }
            return;
        }
        if (now - stateAt < 1000) {
            return;
        }
        SignInput.clear();
        int trade = LowballingGui.findTradeSlot();
        if (trade < 0) {
            if (now - stateAt > 5000) {
                requestKick();
            }
            return;
        }
        tradeMenuTitle = LowballingGui.currentTitle();
        LowballingGui.clickSlot(trade, 0);
        enterWaitTrade();
    }

    private static void tickWaitTrade(Minecraft client, long now) {
        boolean container = LowballingGui.isContainerOpen();
        if (container && !tradeGuiSeen && LowballingGui.isTradeWindow(tradeMenuTitle)) {
            tradeMenuTitle = LowballingGui.currentTitle();
            tradeGuiSeen = true;
            tradeGuiAt = now;
            tradeClicks = 0;
            tradeClickAt = now;
            debug("Trade-GUI offen - warte auf Spieler");
            return;
        }
        if (!container) {
            if (tradeGuiSeen) {
                if (tradeClosedAt == 0) {
                    tradeClosedAt = now;
                }
                if (now - tradeClosedAt < 1200L) {
                    return;
                }
                boolean success = LowballingChat.consumeTradeSuccess(tradeGuiAt);
                tradeGuiSeen = false;
                if (success) {
                    message("§a[Lowballing] §7Trade erfolgreich - liste Item(s) im AH.");
                    startListingTradedItems();
                } else if (finalizingSecond) {
                    requestKick();
                } else {
                    enterChatOffer();
                }
                return;
            }
            if (now - stateAt >= CONFIG.tradeRequestTimeoutMs) {
                if (!finalizingSecond && pendingOffer != null && pendingOffer.buyable()) {
                    message("\u00a7e[Lowballing] \u00a77Trade declined - making the second chat offer.");
                    enterChatOffer();
                } else {
                    message("\u00a7e[Lowballing] \u00a77Trade did not open - kicking player.");
                    requestKick();
                }
            }
            return;
        }
        if (tradeGuiSeen) {
            if (now - tradeGuiAt > CONFIG.tradeGuiTimeoutMs) {
                message("§e[Lowballing] §7Trade-GUI Timeout - kicke Spieler.");
                requestKick();
                return;
            }
            long acceptDelay = Math.max(CONFIG.tradeClickIntervalMs,
                    secondOffer ? CONFIG.secondOfferDealWaitMs : 10_000L);
            if (tradeClicks == 0 && now - tradeClickAt >= acceptDelay
                    && !LowballingGui.findTradeItemSlots().isEmpty()) {
                int accept = LowballingGui.findButtonSlot(
                        "deal", "accept", "confirm", "trade");
                if (accept >= 0) {
                    LowballingGui.clickSlot(accept, 0);
                    tradeClicks = 1;
                    tradeClickAt = now;
                    debug("Deal bestätigt");
                }
            }
            return;
        }
        if (now - stateAt >= CONFIG.tradeRequestTimeoutMs) {
            message("§e[Lowballing] §7Trade-GUI öffnet nicht - kicke Spieler.");
            requestKick();
            return;
        }
        if (now - tradeClickAt < CONFIG.tradeClickIntervalMs) {
            return;
        }
        int trade = LowballingGui.findTradeSlot();
        if (trade < 0) {
            return;
        }
        LowballingGui.clickSlot(trade, 0);
        tradeClicks++;
        tradeClickAt = now;
        debug("Trade-Klick " + tradeClicks);
    }

    private static void tickChatOffer(Minecraft client, long now) {
        if (!chatSent) {
            if (LowballingChat.consumeTradeSuccess(tradeGuiAt)) {
                message("§a[Lowballing] §7Trade erfolgreich - liste Item(s) im AH.");
                startListingTradedItems();
                return;
            }
            if (now - phaseAt < chatOfferDelayMs) {
                return;
            }
            if (nextChatAt > 0 && now < nextChatAt) {
                return;
            }
            Offer offer = pendingOffer;
            if (offer == null) {
                enterNextPlayer();
                return;
            }
            String text = pickMessage(CONFIG.bestOfferMessages, bestCooldowns,
                    CONFIG.bestOfferCooldownSeconds * 1000L, offer.chatOffer());
            if (text == null) {
                return;
            }
            sendChat(text);
            chatSent = true;
            phaseAt = now;
            debug("Chat-Angebot: " + text);
            return;
        }
        if (LowballingChat.consumeTradeSuccess(tradeGuiAt)) {
            message("§a[Lowballing] §7Trade erfolgreich - liste Item(s) im AH.");
            startListingTradedItems();
            return;
        }
        if (now - phaseAt < secondOfferDelayMs) {
            return;
        }
        Offer offer = pendingOffer;
        if (offer != null && offer.buyable() && offer.chatOffer() > 0) {
            secondOffer = true;
            finalizingSecond = true;
            enterApproach();
        } else {
            enterNextPlayer();
        }
    }

    private static void startListingTradedItems() {
        List<TradeItem> items = pendingTradeItems;
        if (items == null || items.isEmpty()) {
            message("§e[Lowballing] §7Keine Trade-Items zum Listen - kicke Spieler.");
            requestKick();
            return;
        }
        setState(State.LISTING);
        actionAt = stateAt;
        listingDone = false;
        Thread.startVirtualThread(() -> {
            try {
                for (TradeItem item : items) {
                    boolean listed = AhFlipperMod.listLowballedFixed(
                            item.tag(), item.name(), item.saleValue());
                    if (!listed) {
                        message("§c[Lowballing] §7Listing fehlgeschlagen: §f" + item.name());
                    }
                }
            } finally {
                listingDone = true;
            }
        });
        debug("Liste " + items.size() + " Trade-Item(s) mit Verkaufswert");
    }

    private static void tickListing(Minecraft client, long now) {
        if (listingDone) {
            message("§a[Lowballing] §7Listing fertig - kicke Spieler.");
            requestKick();
            return;
        }
        if (now - stateAt > 180_000L) {
            message("§e[Lowballing] §7Listing-Timeout - kicke Spieler.");
            requestKick();
        }
    }

    private static void tickKickWait(Minecraft client, long now) {
        if (now - stateAt < 500) {
            return;
        }
        LowballingGui.close();
        kickAfterMenu = false;
        enterNextPlayer();
    }

    private static void tickNextPlayer(Minecraft client, long now) {
        startNextPlayer(client);
    }

    private static long offerWaitMs() {
        return secondOffer ? CONFIG.secondOfferItemWaitMs
                : Math.max(10_000L, CONFIG.offerGuiTimeoutMs);
    }

    private static void handleOfferGui(long now) {
        long searchStart = stateAt;
        long grace = offerWaitMs();
        if (now - searchStart < grace) {
            return;
        }
        List<Integer> items = LowballingGui.findTradeItemSlots();
        if (!items.isEmpty()) {
            if (secondOffer) {
                handleSecondOffer(items.get(0));
            } else {
                enterValuing(items);
            }
            return;
        }
        if (now - searchStart > offerWaitMs()) {
            message("§e[Lowballing] §7Kein Item in der GUI - kicke Spieler.");
            requestKick();
        }
    }

    private static void handleSecondOffer(int slot) {
        Offer offer = pendingOffer;
        if (offer == null) {
            enterNextPlayer();
            return;
        }
        AhItem item = LowballingGui.itemAt(slot);
        if (item.tag.isBlank() || !item.tag.equalsIgnoreCase(offer.tag())) {
            message("§e[Lowballing] §7Different item in second trade - kicking player.");
            requestKick();
            return;
        }
        long sale = saleValue(item, slot);
        if (sale <= 0) {
            message("§c[Lowballing] §7Second item has no valuation - kicking player.");
            requestKick();
            return;
        }
        AhAnalyzer.Result analysis = AhAnalyzer.analyze(item, Math.max(1, sale), 0, "",
                CONFIG.minItemCoins);
        if (!analysis.buyable) {
            logTradeItem(item.tag, item.displayName, sale, false, analysis.reasons);
            message("§e[Lowballing] §7Second item no longer passes filters - kicking player.");
            requestKick();
            return;
        }
        long value = analysis.expectedValue > 1 ? analysis.expectedValue : sale;
        long secondPrice = floorTo(value * 0.90, CONFIG.chatOfferRoundCoins);
        if (secondPrice <= 0) {
            message("§e[Lowballing] §7Second offer too low - kicking player.");
            requestKick();
            return;
        }
        long bankroll = bankrollCoins();
        if (CONFIG.enforceBankroll && bankroll >= 0 && secondPrice > bankroll) {
            message("§e[Lowballing] §7Second offer above bankroll - kicking player.");
            requestKick();
            return;
        }
        int coins = LowballingGui.findCoinsSlot();
        if (coins < 0) {
            message("§e[Lowballing] §7Coins slot not found - kicking player.");
            requestKick();
            return;
        }
        pendingTradeItems = List.of(new TradeItem(item.tag, item.displayName, value));
        pendingOffer = new Offer(offer.tag(), offer.displayName(), value, value,
                offer.firstOffer(), secondPrice, true, List.of());
        finalizingSecond = true;
        SignInput.set(String.valueOf(secondPrice));
        LowballingGui.clickSlot(coins, 0);
        enterSetCoins();
        debug("Second offer revalued: " + ScreenUtil.formatCoins(value)
                + " -> " + ScreenUtil.formatCoins(secondPrice));
    }

    private static void requestKick() {
        SignInput.clear();
        if (LowballingGui.isContainerOpen()) {
            int kick = LowballingGui.findKickSlot();
            if (kick >= 0) {
                LowballingGui.clickSlot(kick, 0);
                enterKickWait();
                return;
            }
        }
        LowballingGui.close();
        kickAfterMenu = true;
        enterOpenMenu();
    }

    private static void computeOffer(List<Integer> slots) {
        try {
            long total = 0;
            String firstTag = "";
            String firstName = "";
            List<String> reasons = new ArrayList<>();
            List<TradeItem> tradeItems = new ArrayList<>();
            for (int slot : slots) {
                AhItem item = LowballingGui.itemAt(slot);
                if (item.tag.isBlank()) {
                    List<String> itemReasons = List.of("Item ohne Tag in Slot " + slot);
                    logTradeItem("", item.displayName, 0, false, itemReasons);
                    reasons.addAll(itemReasons);
                    continue;
                }
                if (firstTag.isBlank()) {
                    firstTag = item.tag;
                    firstName = item.displayName;
                }
                item.coflMed = AhItem.parseCoflMed(LowballingGui.tooltipLinesAt(slot));
                long sale = saleValue(item, slot);
                if (item.tag.equals(firstTag) && sale > 0) {
                    currentTradeValue = sale;
                }
                if (sale <= 0) {
                    List<String> itemReasons = List.of("Kein Verkaufswert: " + item.tag);
                    logTradeItem(item.tag, item.displayName, sale, false, itemReasons);
                    reasons.addAll(itemReasons);
                    continue;
                }
                AhAnalyzer.Result analysis = AhAnalyzer.analyze(item,
                        Math.max(1, sale), 0, "", CONFIG.minItemCoins);
                if (!analysis.buyable) {
                    logTradeItem(item.tag, item.displayName, sale, false,
                            analysis.reasons);
                    reasons.addAll(analysis.reasons);
                    continue;
                }
                long value = analysis.expectedValue > 1 ? analysis.expectedValue : sale;
                if (item.tag.equals(firstTag)) {
                    currentTradeValue = value;
                }
                logTradeItem(item.tag, item.displayName, value, true, List.of());
                total += value;
                tradeItems.add(new TradeItem(item.tag, item.displayName, value));
            }
            if (!reasons.isEmpty()) {
                pendingTradeItems = List.of();
                valuationOffer = new Offer(firstTag, firstName, 0, 0, 0, 0,
                        false, reasons);
                return;
            }
            if (total < CONFIG.minItemCoins) {
                pendingTradeItems = List.of();
                valuationOffer = new Offer(firstTag, firstName, total, total, 0, 0,
                        false, List.of("Verkaufswert unter "
                        + ScreenUtil.formatCoins(CONFIG.minItemCoins)));
                return;
            }
            long first = floorTo(total * 0.85, 500_000L);
            long chat = floorTo(total * 0.90, CONFIG.chatOfferRoundCoins);
            if (first <= 0 || chat <= 0) {
                pendingTradeItems = List.of();
                valuationOffer = new Offer(firstTag, firstName, total, total, first, chat,
                        false, List.of("Angebot zu niedrig"));
                return;
            }
            String displayName = firstName.isBlank() ? firstTag : firstName;
            if (slots.size() > 1) {
                displayName += " +" + (slots.size() - 1);
            }
            pendingTradeItems = List.copyOf(tradeItems);
            valuationOffer = new Offer(firstTag, displayName, total, total,
                    first, chat, true, List.of());
        } catch (Exception e) {
            valuationError = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    private static long saleValue(AhItem item, int slot) {
        List<String> texts = new ArrayList<>(LowballingGui.tooltipLinesAt(slot));
        if (texts.isEmpty()) {
            if (item.displayName != null) {
                texts.add(item.displayName);
            }
            texts.addAll(LowballingGui.loreAt(slot));
        }
        long med = parseLabeledCoins(texts, "med");
        long lbin = parseLabeledCoins(texts, "lbin");
        long addonAverage = parseLabeledCoins(texts, "addon-vergleich");
        long addonBin = parseLabeledCoins(texts, "lowest addon-bin");
        long best = 0;
        for (long value : new long[]{med, lbin, addonAverage, addonBin}) {
            if (value > 0 && (best <= 0 || value < best)) {
                best = value;
            }
        }
        AhValuation.Estimate estimate = AhValuation.estimate(item, "");
        long ahValue = estimate.estimated;
        if (best > 0) {
            return ahValue > 1 ? Math.min(best, ahValue) : best;
        }
        return ahValue;
    }

    private static long parseLabeledCoins(List<String> lines, String label) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)" + label + "\\s*:?\\s*([0-9][0-9.,]*\\s*[kmbt]?)");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            java.util.regex.Matcher matcher = pattern.matcher(
                    ScreenUtil.cleanName(line));
            if (matcher.find()) {
                long value = parseCompactCoins(matcher.group(1));
                if (value > 0) {
                    return value;
                }
            }
        }
        return 0;
    }

    private static long parseCompactCoins(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double multiplier = 1.0;
        char last = text.charAt(text.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b' || last == 't') {
            multiplier = switch (last) {
                case 'k' -> 1_000.0;
                case 'm' -> 1_000_000.0;
                case 'b' -> 1_000_000_000.0;
                default -> 1_000_000_000_000.0;
            };
            text = text.substring(0, text.length() - 1);
        } else {
            text = text.replace(".", "");
        }
        try {
            return Math.max(0, Math.round(Double.parseDouble(text) * multiplier));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long floorTo(double value, long unit) {
        if (value <= 0 || unit <= 0) {
            return 0;
        }
        return (long) Math.floor(value / unit) * unit;
    }

    private static void startNextPlayer(Minecraft client) {
        updatePlayerTracks(client);
        collectMovedPlayers(client);
        SignInput.clear();
        while (true) {
            UUID id = PLAYER_QUEUE.poll();
            if (id == null) {
                finishCycle();
                return;
            }
            if (PROCESSED.contains(id)) {
                continue;
            }
            Player player = findPlayerById(id);
            if (player == null) {
                continue;
            }
            PlayerTrack track = TRACKED.get(id);
            if (track == null || !track.moved) {
                continue;
            }
            PROCESSED.add(id);
            targetUuid = id;
            targetName = player.getName().getString();
            targetEntity = player;
            secondOffer = false;
            finalizingSecond = false;
            pendingOffer = null;
            enterApproach();
            return;
        }
    }

    private static void finishCycle() {
        cycles++;
        if (CONFIG.maxCycles > 0 && cycles >= CONFIG.maxCycles) {
            stop("§6[Lowballing] §7Maximale Zyklen erreicht - gestoppt.");
            return;
        }
        if (!CONFIG.loop) {
            stop("§6[Lowballing] §7Zyklus beendet.");
            return;
        }
        message("§6[Lowballing] §7Zyklus " + cycles + " beendet - starte neu.");
        enterGoHub();
    }

    private static void collectInitialPlayers() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        for (Player player : client.level.players()) {
            if (player == client.player) {
                continue;
            }
            TRACKED.put(player.getUUID(), new PlayerTrack(player.position()));
        }
    }

    private static void updatePlayerTracks(Minecraft client) {
        if (client.level == null) {
            return;
        }
        for (Player player : client.level.players()) {
            if (player == client.player) {
                continue;
            }
            PlayerTrack track = TRACKED.get(player.getUUID());
            if (track == null) {
                track = new PlayerTrack(player.position());
                TRACKED.put(player.getUUID(), track);
            } else if (!track.moved
                    && player.position().distanceTo(track.start) >= CONFIG.minPlayerMoveBlocks) {
                track.moved = true;
                debug("Spieler bewegt: " + player.getName().getString());
            }
        }
    }

    private static void collectMovedPlayers(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        List<Player> moved = new ArrayList<>();
        for (Player player : client.level.players()) {
            if (player == client.player) {
                continue;
            }
            PlayerTrack track = TRACKED.get(player.getUUID());
            if (track == null || !track.moved || PROCESSED.contains(player.getUUID())) {
                continue;
            }
            if (PLAYER_QUEUE.contains(player.getUUID())) {
                continue;
            }
            moved.add(player);
        }
        moved.sort(Comparator.comparingDouble(player -> player.distanceToSqr(client.player)));
        for (Player player : moved) {
            PLAYER_QUEUE.addLast(player.getUUID());
        }
    }

    private static Player resolvePlayer() {
        if (targetUuid != null) {
            Player byId = findPlayerById(targetUuid);
            if (byId != null) {
                return byId;
            }
        }
        if (!targetName.isBlank()) {
            return findPlayerByName(targetName);
        }
        return null;
    }

    private static Player findPlayerById(UUID id) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || id == null) {
            return null;
        }
        for (Player player : client.level.players()) {
            if (player != client.player && player.getUUID().equals(id)) {
                return player;
            }
        }
        return null;
    }

    private static Player findPlayerByName(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Player player : client.level.players()) {
            if (player == client.player) {
                continue;
            }
            if (player.getName().getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                return player;
            }
        }
        return null;
    }

    private static Entity resolveTarget() {
        if (targetUuid != null) {
            Player player = findPlayerById(targetUuid);
            if (player != null) {
                return player;
            }
        }
        if (targetEntity != null && targetEntity.isAlive() && !targetEntity.isRemoved()) {
            return targetEntity;
        }
        if (!targetName.isBlank()) {
            return findEntity(targetName);
        }
        return null;
    }

    private static Entity findEntity(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || !entity.isAlive() || entity.isRemoved()) {
                continue;
            }
            String entityName = entity.getName().getString().toLowerCase(Locale.ROOT);
            if (!entityName.contains(wanted)) {
                continue;
            }
            double distance = client.player.distanceToSqr(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private static boolean rightClickEntity(Entity target) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || target == null || client.gameMode == null) {
            return false;
        }
        LowballingRotation.lookAt(target);
        if (!LowballingRotation.isAligned(player, target, 4.0)) {
            return false;
        }
        client.gameMode.interact(player, target, new EntityHitResult(target),
                InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static void setSneak(boolean down) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null || client.options.keyShift == null) {
            return;
        }
        if (down) {
            client.options.keyShift.setDown(true);
            sneakOwned = true;
        } else if (sneakOwned) {
            client.options.keyShift.setDown(false);
            sneakOwned = false;
        }
    }

    private static void releaseSneak() {
        if (sneakOwned) {
            setSneak(false);
        }
    }

    private static void setForward(boolean down) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null || client.options.keyUp == null) {
            return;
        }
        if (down) {
            client.options.keyUp.setDown(true);
            forwardOwned = true;
        } else if (forwardOwned) {
            client.options.keyUp.setDown(false);
            forwardOwned = false;
        }
    }

    private static void releaseForward() {
        if (forwardOwned) {
            setForward(false);
        }
    }

    private static boolean selectorGone() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        Entity selector = findEntity(CONFIG.hubSelectorName);
        return selector == null || client.player.distanceTo(selector) > 32.0;
    }

    private static boolean coordsMoved(double threshold) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        double dx = client.player.getX() - warpX;
        double dy = client.player.getY() - warpY;
        double dz = client.player.getZ() - warpZ;
        return dx * dx + dy * dy + dz * dz >= threshold * threshold;
    }

    private static void setWarpPosition() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        warpX = client.player.getX();
        warpY = client.player.getY();
        warpZ = client.player.getZ();
    }

    private static long bankrollCoins() {
        if (CONFIG.bankrollOverrideMillions > 0) {
            return CONFIG.bankrollOverrideMillions * 1_000_000L;
        }
        long purse = ScreenUtil.readPurse();
        if (purse > 0) {
            return Math.max(0, purse - CONFIG.reserveCoins);
        }
        if (CONFIG.fallbackBankrollMillions > 0) {
            return CONFIG.fallbackBankrollMillions * 1_000_000L;
        }
        return -1;
    }

    private static String formatBankroll(long coins) {
        if (coins >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2fb", coins / 1_000_000_000.0);
        }
        long millions = Math.max(0, Math.round(coins / 1_000_000.0));
        return millions + "m";
    }

    private static String pickMessage(List<String> messages, long[] cooldowns,
                                      long cooldownMs, long value) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        if (cooldowns.length != messages.size()) {
            cooldowns = new long[messages.size()];
            if (messages == CONFIG.adMessages) {
                adCooldowns = cooldowns;
            } else if (messages == CONFIG.bestOfferMessages) {
                bestCooldowns = cooldowns;
            }
        }
        long now = System.currentTimeMillis();
        List<Integer> available = new ArrayList<>();
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            if (cooldowns[i] <= now) {
                available.add(i);
            } else {
                earliest = Math.min(earliest, cooldowns[i]);
            }
        }
        if (available.isEmpty()) {
            long next = earliest == Long.MAX_VALUE ? now + 1000 : earliest + 50;
            if (messages == CONFIG.adMessages) {
                nextAdAt = next;
            } else {
                nextChatAt = next;
            }
            return null;
        }
        int index = available.get(ThreadLocalRandom.current().nextInt(available.size()));
        cooldowns[index] = now + cooldownMs;
        String template = messages.get(index);
        String replacement = messages == CONFIG.bestOfferMessages
                ? formatBankroll(value) : formatBankroll(value);
        return template.replace("{bankroll}", replacement).replace("{offer}", replacement);
    }

    private static void ensureCooldowns() {
        if (adCooldowns.length != CONFIG.adMessages.size()) {
            adCooldowns = new long[CONFIG.adMessages.size()];
        }
        if (bestCooldowns.length != CONFIG.bestOfferMessages.size()) {
            bestCooldowns = new long[CONFIG.bestOfferMessages.size()];
        }
    }

    private static void sendChat(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return;
        }
        client.getConnection().sendChat(text);
    }

    private static final Object TRADE_LOG_LOCK = new Object();

    private static void logTradeItem(String tag, String name, long saleValue,
                                     boolean accept, List<String> reasons) {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir()
                    .resolve("CoflSky").resolve("lowballing").resolve("items");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String verdict = accept ? "ANNEHMEN" : "ABLEHNEN";
            String reason = accept ? "Regeln erfuellt"
                    : String.join("; ", reasons);
            String line = stamp + " | " + verdict + " | " + tag + " | "
                    + name + " | Verkaufswert=" + saleValue + " | "
                    + reason + System.lineSeparator();
            synchronized (TRADE_LOG_LOCK) {
                Files.writeString(dir.resolve("items.txt"), line,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            System.out.println("[Lowballing] Item-Log Fehler: " + e.getMessage());
        }
    }

    private static void message(String text) {
        CoflModClient.displayModMessage(Component.literal(text));
    }

    private static void debug(String text) {
        System.out.println("[Lowballing] " + text);
        if (!CONFIG.debug) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLogAt < 500) {
            return;
        }
        lastLogAt = now;
        message("§8[Lowballing] §7" + text);
    }
}
