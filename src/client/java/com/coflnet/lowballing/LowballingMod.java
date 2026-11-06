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

    /**
     * @param fingerprint identity of the exact item (tag, reforge, stars, upgrades ...)
     *                    used for the check right before the deal is accepted
     */
    private record TradeItem(String tag, String name, long saleValue, String fingerprint) {
    }

    /** Names of trade items we do not want (partial offer); empty = take everything. */
    private static volatile List<String> rejectedTradeNames = List.of();
    /** 0 = normal, 1 = partial chat offer pending, 2 = sent (first offer), 3 = +5% conceded. */
    private static int partialStage;
    /** Second offer: coins already put into the menu. */
    private static boolean secondCoinsPlaced;
    private static long lastFinalCheckAt;
    /** Final check: total value may drop at most this much before we abort. */
    private static final double FINAL_VALUE_TOLERANCE = 0.08;
    private record DelayedChat(long dueAt, String text) {
    }
    private static final List<DelayedChat> DELAYED_CHAT = new ArrayList<>();

    static String fingerprint(AhItem item) {
        if (item == null) {
            return "";
        }
        return item.tag + "|" + item.count + "|" + item.reforge + "|" + item.stars
                + "|" + item.hpb + "|" + item.fumingHpb + "|" + item.recomb
                + "|" + item.artOfWar + "|" + item.dyeItem + "|" + item.skin
                + "|" + item.petSkin + "|" + item.petItem + "|" + item.petLevel
                + "|" + item.runeLevel + "|" + item.ethermerge + "|" + item.kills;
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
    private static boolean visitorPending;
    private static String visitorPendingName = "";
    private static long setCoinsClosedAt;
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
        PENDING_CHAT.clear();
        pausedAt = 0;
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
        PENDING_CHAT.clear();
        pausedAt = 0;
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
        // A visitor that arrived just before /hub must not be forgotten: the
        // hub sequence returns to the island for them as soon as it can.
        long lastVisit = LowballingChat.lastVisitorAt();
        if (lastVisit > 0 && System.currentTimeMillis() - lastVisit < 30_000L) {
            visitorPending = true;
            if (visitorPendingName.isBlank()) {
                visitorPendingName = "Besucher";
            }
        } else {
            visitorPending = false;
            visitorPendingName = "";
        }
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
        visitorPending = false;
        visitorPendingName = "";
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
        setCoinsClosedAt = 0;
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
            if (pauseForForeignGui(client, now)) {
                return;
            }
            flushDelayedChat(now);
            flushPendingChat(client);
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
            if (handleVisitorDuringHubSequence()) {
                return;
            }
            if (onIsland() && now - lastTrackUpdateAt >= 250L) {
                // Also while trading with someone else: new visitors are tracked
                // (and queued) right away instead of only between two players.
                lastTrackUpdateAt = now;
                updatePlayerTracks(client);
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

    /**
     * A visitor can arrive while the bot is still somewhere in the hub sequence
     * (selector, hub menu, settle walk). Instead of finishing the sequence and
     * only reacting in ADVERTISE, go straight back to the island. If a warp is
     * possibly in flight, the visitor is remembered and handled as soon as the
     * bot is in a stable state, so the island scan never runs on a hub.
     */
    private static boolean handleVisitorDuringHubSequence() {
        boolean hubSequence = switch (state) {
            case GO_HUB, FIND_SELECTOR, OPEN_SELECTOR, HUB_MENU, WAIT_HUB_WARP,
                 HUB_SETTLE, ADVERTISE -> true;
            default -> false;
        };
        if (!hubSequence) {
            return false;
        }
        LowballingChat.Visitor visitor = LowballingChat.consumeVisitor(0);
        if (visitor != null && !visitorPending) {
            visitorPending = true;
            visitorPendingName = visitor.name();
            debug("Besucher während " + state + ": " + visitor.name());
        }
        if (!visitorPending) {
            return false;
        }
        boolean canLeave = switch (state) {
            case FIND_SELECTOR, OPEN_SELECTOR, HUB_SETTLE, ADVERTISE -> true;
            case HUB_MENU -> hubClicks == 0 && warpRequestedAt == 0;
            default -> false;
        };
        if (!canLeave) {
            return false;
        }
        message("§6[Lowballing] §7" + visitorPendingName
                + " besucht die Insel - breche Hub-Sequenz ab und gehe zur Insel.");
        enterReturnIsland();
        return true;
    }

    private static long pausedAt;
    private static final Deque<String> PENDING_CHAT = new ArrayDeque<>();

    /**
     * Never move, turn or write while a GUI that is not part of the current step
     * is open (AH menu, chat, inventory ...) or while the AH flipper is busy with
     * its own task. The step continues where it was once that is over; all
     * timeouts are shifted by the pause so nothing "times out" meanwhile.
     */
    private static boolean pauseForForeignGui(Minecraft client, long now) {
        boolean screenOpen = client.gui.screen() != null;
        boolean ahScreen = com.coflnet.ahflipper.AhScreenUtil.currentScreen()
                != com.coflnet.ahflipper.AhScreenUtil.AhScreen.NONE;
        boolean ahBusy = com.coflnet.ahflipper.AhFlipperMod.automationBusyExcept("lowball");
        boolean block = switch (state) {
            case IDLE, RECONNECTING, LISTING -> false;
            // our own GUI (hub menu, trade, player menu) is expected here
            case HUB_MENU, PLAYER_MENU, OPEN_OFFER, VALUING, OFFER_READY, SET_COINS,
                 WAIT_TRADE, KICK_WAIT -> ahScreen;
            // a GUI may open as the result of our own click
            case FIND_SELECTOR, OPEN_SELECTOR, OPEN_MENU -> ahScreen || ahBusy;
            // walking, turning, chatting: nothing may be open
            default -> screenOpen || ahBusy;
        };
        if (block) {
            if (pausedAt == 0) {
                pausedAt = now;
                releaseForward();
                LowballingNavigator.stop();
                LowballingRotation.clear();
                debug("Pause - " + (ahBusy ? "AH-Flipper beschäftigt" : "GUI offen"));
            }
            return true;
        }
        if (pausedAt > 0) {
            long paused = now - pausedAt;
            pausedAt = 0;
            stateAt += paused;
            if (actionAt > 0) {
                actionAt += paused;
            }
            if (phaseAt > 0) {
                phaseAt += paused;
            }
            if (nextAdAt > 0) {
                nextAdAt += paused;
            }
            if (islandArrivedAt > 0) {
                islandArrivedAt += paused;
            }
            debug("Weiter nach " + (paused / 1000) + "s Pause");
        }
        return false;
    }

    private static void flushPendingChat(Minecraft client) {
        if (PENDING_CHAT.isEmpty() || client.gui.screen() != null
                || client.getConnection() == null) {
            return;
        }
        client.getConnection().sendChat(PENDING_CHAT.poll());
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

    /** After a "X is visiting your island" message the bot stays at least this long. */
    private static final long VISITOR_GRACE_MS = 15_000L;
    private static long lastTrackUpdateAt;

    private static boolean onIsland() {
        return switch (state) {
            case ISLAND_SCAN, APPROACH_PLAYER, OPEN_MENU, PLAYER_MENU, OPEN_OFFER, VALUING,
                 OFFER_READY, SET_COINS, WAIT_TRADE, LISTING, CHAT_OFFER, KICK_WAIT,
                 NEXT_PLAYER -> true;
            default -> false;
        };
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
            long lastVisit = LowballingChat.lastVisitorAt();
            if (lastVisit > 0 && now - lastVisit < VISITOR_GRACE_MS) {
                return; // someone just arrived - wait for them instead of leaving
            }
            finishCycle();
        }
    }

    private static void tickApproach(Minecraft client, long now) {
        Player target = resolvePlayer();
        if (target == null) {
            if (secondOffer && now - stateAt < SECOND_OFFER_FIND_MS) {
                // The player we are negotiating with can be out of sight for a
                // moment. Wait for them instead of grabbing another visitor.
                LowballingNavigator.stop();
                return;
            }
            if (secondOffer) {
                message("§e[Lowballing] §7" + targetName
                        + " nicht mehr gefunden - zweites Angebot abgebrochen.");
            }
            enterNextPlayer();
            return;
        }
        targetEntity = target;
        targetName = target.getName().getString();
        LowballingNavigator.tick(target);
        if (client.player.distanceTo(target) <= CONFIG.interactReach) {
            LowballingNavigator.stop();
            reachSince = 0;
            enterOpenMenu();
            return;
        }
        if (reachSince == 0) {
            reachSince = now;
        }
        // A visitor we cannot reach within 5 s (no jumping, blocked path ...) is
        // ignored for this cycle. The timer is not reset when OPEN_MENU bounces
        // back here, so we can no longer get stuck on one player. The player we
        // are already negotiating with gets the longer config timeout.
        long limit = secondOffer ? CONFIG.menuOpenTimeoutMs : REACH_TIMEOUT_MS;
        if (now - reachSince > limit) {
            message("§e[Lowballing] §7" + targetName + " in " + (limit / 1000)
                    + "s nicht erreichbar - ignoriert.");
            reachSince = 0;
            enterNextPlayer();
        }
    }

    private static final long REACH_TIMEOUT_MS = 5_000L;
    /** Since when we try to get within reach of the current target (0 = in reach). */
    private static long reachSince;

    private static void tickOpenMenu(Minecraft client, long now) {
        if (LowballingGui.isContainerOpen()) {
            enterPlayerMenu();
            return;
        }
        Player target = resolvePlayer();
        if (target == null) {
            if (secondOffer) {
                enterApproach(); // waits there for the same player
            } else {
                enterNextPlayer();
            }
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
        long sneakTimeout = secondOffer
                ? Math.max(CONFIG.menuSneakTimeoutMs, SECOND_OFFER_REQUEST_MS)
                : CONFIG.menuSneakTimeoutMs;
        if (now - stateAt < sneakTimeout) {
            setSneak(true);
            LowballingRotation.lookAt(target);
            if (now - actionAt >= CONFIG.menuRightClickIntervalMs
                    && !LowballingChat.tradeRequestPending(now)
                    && rightClickEntity(target)) {
                actionAt = now;
                debug("Sneak-Rechtsklick " + targetName);
            }
            return;
        }
        setSneak(false);
        if (secondOffer) {
            onFinalTradeFailed("Trade-Request nicht angenommen");
            return;
        }
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
        if (!rejectedTradeNames.isEmpty()) {
            // Some items are not wanted: do not pay for the whole trade. Tell the
            // visitor in chat which items we take (first offer) and negotiate.
            message("§e[Lowballing] §7Nicht alle Items gewollt - Teil-Angebot im Chat.");
            finalizingSecond = false;
            partialStage = 1;
            SignInput.clear();
            LowballingGui.close();
            if (tradeGuiAt < stateAt) {
                tradeGuiAt = stateAt;
            }
            enterChatOffer();
            chatOfferDelayMs = 1000L + ThreadLocalRandom.current().nextInt(501);
            return;
        }
        if (!LowballingGui.isContainerOpen()) {
            finalizingSecond = false;
            onFirstTradeClosed("Trade vor dem Angebot geschlossen");
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
            setCoinsClosedAt = 0;
            if (now - stateAt > 3000) {
                LowballingGui.close();
            }
            return;
        }
        if (!LowballingGui.isContainerOpen()) {
            if (setCoinsClosedAt == 0) {
                setCoinsClosedAt = now;
            }
            if (now - setCoinsClosedAt < 2500L) {
                return;
            }
            if (!finalizingSecond) {
                // The trade was closed while we were putting coins in: treat it
                // as a declined first offer, not as a reason to kick.
                onFirstTradeClosed("Trade beim Coins-Eintragen geschlossen");
                return;
            }
            if (now - stateAt > 5000) {
                onFinalTradeFailed("Trade beim Coins-Eintragen geschlossen");
            }
            return;
        }
        setCoinsClosedAt = 0;
        if (handleWrongTradePartner(client)) {
            return;
        }
        if (now - stateAt < 1000) {
            return;
        }
        SignInput.clear();
        if (LowballingGui.isOpenTradeWith(targetName)) {
            // Coins are in and we are inside the trade window already (the
            // visitor opened/accepted the trade). There is no "Trade" button
            // here - just wait for the deal like after a trade request.
            tradeMenuTitle = LowballingGui.currentTitle();
            enterWaitTrade();
            tradeGuiSeen = true;
            tradeGuiAt = now;
            tradeClickAt = now;
            debug("Coins im Trade - warte auf Deal");
            return;
        }
        int trade = LowballingGui.findTradeSlot();
        if (trade < 0) {
            if (now - stateAt > 5000) {
                message("§e[Lowballing] §7Trade-Button nicht gefunden - kicke Spieler.");
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
        if (container && handleWrongTradePartner(client)) {
            return;
        }
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
                    onFinalTradeFailed("Trade abgebrochen");
                } else {
                    onFirstTradeClosed("Trade geschlossen");
                }
                return;
            }
            if (now - stateAt >= CONFIG.tradeRequestTimeoutMs) {
                if (!finalizingSecond && pendingOffer != null && pendingOffer.buyable()) {
                    message("\u00a7e[Lowballing] \u00a77Trade declined - making the second chat offer.");
                    enterChatOffer();
                } else if (finalizingSecond) {
                    onFinalTradeFailed("Trade nicht angenommen");
                } else {
                    message("\u00a7e[Lowballing] \u00a77Trade did not open - kicking player.");
                    requestKick();
                }
            }
            return;
        }
        if (tradeGuiSeen) {
            if (now - tradeGuiAt > CONFIG.tradeGuiTimeoutMs) {
                if (finalizingSecond) {
                    LowballingGui.close();
                    onFinalTradeFailed("Trade-GUI Timeout");
                    return;
                }
                message("§e[Lowballing] §7Trade-GUI Timeout - kicke Spieler.");
                requestKick();
                return;
            }
            long acceptDelay = Math.max(CONFIG.tradeClickIntervalMs,
                    secondOffer ? CONFIG.secondOfferDealWaitMs : 10_000L);
            if (tradeClicks == 0 && now - tradeClickAt >= acceptDelay
                    && !LowballingGui.findTradeItemSlots().isEmpty()
                    && now - lastFinalCheckAt >= 500L) {
                int accept = LowballingGui.findButtonSlot(
                        "deal", "accept", "confirm", "trade");
                if (accept >= 0) {
                    // Last check before accepting: still the right item(s), value ok?
                    lastFinalCheckAt = now;
                    String problem = verifyTradeBeforeAccept();
                    if (problem != null && problem.isEmpty()) {
                        return; // values not loaded yet, check again shortly
                    }
                    if (problem != null) {
                        abortTradeBlockAndKick(problem);
                        return;
                    }
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
            String text = composeChatOffer(offer, now);
            if (text == null) {
                return;
            }
            sendChat(text);
            chatSent = true;
            phaseAt = now;
            chatSentAt = now;
            debug("Chat-Angebot: " + text);
            return;
        }
        // Read what the visitor answers to our chat offer and react to it. All
        // new lines are read together, and only once the visitor stopped typing
        // for a moment: "20m" + "for both" is one answer, and we no longer react
        // (or rush into a trade request) on the first half of it.
        List<LowballingChat.PlayerLine> fresh = LowballingChat.linesFrom(targetName,
                Math.max(chatSentAt - 1, lastReplyHandledAt));
        if (!fresh.isEmpty()) {
            LowballingChat.PlayerLine newest = fresh.get(fresh.size() - 1);
            if (now - newest.at() < REPLY_SETTLE_MS) {
                return; // still typing - also holds back the second-offer timeout
            }
            lastReplyHandledAt = newest.at();
            StringBuilder combined = new StringBuilder();
            for (LowballingChat.PlayerLine line : fresh) {
                if (combined.length() > 0) {
                    combined.append(' ');
                }
                combined.append(line.text());
            }
            debug("Antwort von " + targetName + ": " + combined);
            Offer offer = pendingOffer;
            LowballingChat.PlayerLine reply = new LowballingChat.PlayerLine(newest.name(),
                    combined.toString(), newest.at());
            if (offer != null && handleChatReply(offer, reply, now)) {
                return;
            }
            // Give them time to answer our reaction before the second offer starts.
            phaseAt = Math.max(phaseAt, now - secondOfferDelayMs / 2);
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
        if (partialStage == 2 && offer != null && offer.buyable()) {
            // No answer to the partial first offer: come up the 5 % and wait again.
            concedePartial(offer, now);
            return;
        }
        if (offer != null && offer.buyable() && offer.chatOffer() > 0) {
            goSecondOffer();
        } else {
            enterNextPlayer();
        }
    }

    private static long chatSentAt;
    private static long lastReplyHandledAt;
    /** The visitor must have stopped typing this long before we read the answer. */
    private static final long REPLY_SETTLE_MS = 1_200L;
    /** Price the visitor agreed to in chat (0 = none). */
    private static long agreedSecondPrice;
    private static boolean counterAnswered;

    /** One extra trade after the final offer failed, if the chat counter was close. */
    private static boolean finalRetryUsed;
    private static long finalPriceOverride;
    static final double FINAL_ACCEPT_GAP = 0.01;
    static final double FINAL_MEET_GAP = 0.10;
    static final double FINAL_MEET_STEP = 0.01;

    /**
     * The trade for the final (second) offer was cancelled or never accepted.
     * Look at what the visitor wrote in chat: a price within 1 % of our target is
     * accepted, within 10 % we come up 1 % ("take it or leave it"), otherwise kick.
     */
    private static void onFinalTradeFailed(String reason) {
        Offer offer = pendingOffer;
        if (finalRetryUsed || offer == null || offer.chatOffer() <= 0) {
            message("§e[Lowballing] §7" + reason + " - kicke Spieler.");
            requestKick();
            return;
        }
        long since = Math.max(0, chatSentAt - 30_000L);
        LowballingChat.PlayerLine line = LowballingChat.lastLineFrom(targetName, since);
        long ask = line == null ? 0
                : LowballingSmartChat.parseAsk(line.text(), offer.expectedValue());
        long target = offer.chatOffer();
        long price = finalRetryPrice(ask, target);
        if (price <= 0) {
            message("§e[Lowballing] §7" + reason
                    + (ask > 0 ? " - Gegenangebot " + ScreenUtil.formatCoins(ask) + " zu weit weg"
                    : " - kein Gegenangebot im Chat") + " - kicke Spieler.");
            requestKick();
            return;
        }
        long bankroll = bankrollCoins();
        if (CONFIG.enforceBankroll && bankroll >= 0 && price > bankroll) {
            message("§e[Lowballing] §7Gegenangebot über Bankroll - kicke Spieler.");
            requestKick();
            return;
        }
        String item = shortItemName(offer.displayName());
        String text = price == ask
                ? LowballingSmartChat.acceptFinal(price, item)
                : LowballingSmartChat.meetFinal(price, item);
        message("§e[Lowballing] §7" + reason + " - " + targetName + " will "
                + ScreenUtil.formatCoins(ask) + ", neuer Versuch mit "
                + ScreenUtil.formatCoins(price) + ".");
        finalRetryUsed = true;
        finalPriceOverride = price;
        pendingOffer = new Offer(offer.tag(), offer.displayName(), offer.expectedValue(),
                offer.maxAhPrice(), offer.firstOffer(), price, true, offer.reasons());
        SignInput.clear();
        if (LowballingGui.isContainerOpen()) {
            LowballingGui.close();
        }
        sendChatDelayed(text);
        goSecondOffer();
    }

    /**
     * Price for the extra trade, or 0 to kick. Counter at/below our target or at
     * most 1 % above: take their price. Up to 10 % above: our target + 1 %.
     */
    static long finalRetryPrice(long ask, long target) {
        if (ask <= 0 || target <= 0) {
            return 0;
        }
        double gap = (ask - target) / (double) target;
        if (gap <= FINAL_ACCEPT_GAP) {
            return ask;
        }
        if (gap <= FINAL_MEET_GAP) {
            return Math.min(ask, LowballingSmartChat.chatRound(target * (1.0 + FINAL_MEET_STEP)));
        }
        return 0;
    }

    private static void goSecondOffer() {
        secondOffer = true;
        finalizingSecond = true;
        secondCoinsPlaced = false;
        reachSince = 0;
        enterApproach();
    }

    /** First chat offer: reacts to what the visitor already wrote, else a config message. */
    private static String composeChatOffer(Offer offer, long now) {
        agreedSecondPrice = 0;
        counterAnswered = false;
        lastReplyHandledAt = 0;
        if (partialStage == 1) {
            partialStage = 2;
            lastReplyHandledAt = now;
            return partialOfferText(offer);
        }
        long since = Math.max(0, stateAt - 120_000L);
        LowballingChat.PlayerLine said = combinedLines(targetName, since);
        String item = shortItemName(offer.displayName());
        if (said != null) {
            long ask = LowballingSmartChat.parseAsk(said.text(), offer.expectedValue());
            if (ask > 0 && ask <= offer.chatOffer()) {
                agreedSecondPrice = ask;
                lastReplyHandledAt = said.at();
                return LowballingSmartChat.acceptAsk(ask, item);
            }
            if (ask > offer.chatOffer()) {
                counterAnswered = true;
                lastReplyHandledAt = said.at();
                return LowballingSmartChat.counterTooHigh(ask, offer.chatOffer(), item);
            }
            if (LowballingSmartChat.isComplaint(said.text())) {
                lastReplyHandledAt = said.at();
                return LowballingSmartChat.answerComplaint(offer.chatOffer(), item);
            }
        }
        String text = pickMessage(CONFIG.bestOfferMessages, bestCooldowns,
                CONFIG.bestOfferCooldownSeconds * 1000L, offer.chatOffer());
        return text == null ? null : text.replace("{item}", item);
    }

    /**
     * Visitor answered our chat offer. Returns true when the state changed
     * (going for the trade right away).
     */
    private static boolean handleChatReply(Offer offer, LowballingChat.PlayerLine reply, long now) {
        String item = shortItemName(offer.displayName());
        long ask = LowballingSmartChat.parseAsk(reply.text(), offer.expectedValue());
        if (ask > 0 && ask <= offer.chatOffer()) {
            agreedSecondPrice = ask;
            debug("Besucher will " + ScreenUtil.formatCoins(ask) + " - passt");
            sendChatDelayed(LowballingSmartChat.acceptAsk(ask, item));
            goSecondOffer();
            return true;
        }
        if (ask > offer.chatOffer()) {
            if (!counterAnswered) {
                counterAnswered = true;
                if (partialStage == 2) {
                    partialStage = 3; // the counter names our +5 % chat offer
                }
                sendChatDelayed(LowballingSmartChat.counterTooHigh(ask, offer.chatOffer(), item));
                phaseAt = now; // give them a moment to accept our price
            }
            return false;
        }
        if (LowballingSmartChat.isAgreement(reply.text())) {
            debug("Besucher stimmt zu: " + reply.text());
            if (partialStage == 2) {
                // They agreed to the partial first offer, not to the +5 %.
                agreedSecondPrice = offer.firstOffer();
            }
            goSecondOffer();
            return true;
        }
        if (partialStage == 2 && LowballingSmartChat.isRefusal(reply.text())) {
            concedePartial(offer, now);
            return false;
        }
        if (LowballingSmartChat.isRefusal(reply.text())) {
            debug("Besucher lehnt ab: " + reply.text());
            message("§e[Lowballing] §7" + targetName + " lehnt ab - kicke Spieler.");
            requestKick();
            return true;
        }
        return false;
    }

    /** The last (up to 3) lines of a player since {@code since}, joined; null if none. */
    private static LowballingChat.PlayerLine combinedLines(String name, long since) {
        List<LowballingChat.PlayerLine> lines = LowballingChat.linesFrom(name, since - 1);
        if (lines.isEmpty()) {
            return null;
        }
        int from = Math.max(0, lines.size() - 3);
        StringBuilder text = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(lines.get(i).text());
        }
        LowballingChat.PlayerLine last = lines.get(lines.size() - 1);
        return new LowballingChat.PlayerLine(last.name(), text.toString(), last.at());
    }

    /** "i'd take the x + y but not the z, 12.3m for the x + y" (first offer). */
    private static String partialOfferText(Offer offer) {
        String take = joinItemNames(pendingTradeItems.stream().map(TradeItem::name).toList());
        String leave = joinItemNames(rejectedTradeNames);
        String text = "i'd take the " + take + " but not the " + leave + ", "
                + formatOffer(offer.firstOffer()) + " for the " + take;
        if (text.length() > 240) {
            text = "i'd only take " + pendingTradeItems.size() + " of the items, not the "
                    + leave + ", " + formatOffer(offer.firstOffer()) + " for them";
        }
        return text.length() > 250 ? text.substring(0, 250) : text;
    }

    private static String joinItemNames(List<String> names) {
        List<String> shortNames = new ArrayList<>();
        for (String name : names) {
            shortNames.add(shortItemName(name));
        }
        if (shortNames.isEmpty()) {
            return "item";
        }
        if (shortNames.size() == 1) {
            return shortNames.get(0);
        }
        // "a, b and c" - no special characters in the chat text
        return String.join(", ", shortNames.subList(0, shortNames.size() - 1))
                + " and " + shortNames.get(shortNames.size() - 1);
    }

    /** Partial deal: no answer / no to the first offer -> come up the 5 % to the chat offer. */
    private static void concedePartial(Offer offer, long now) {
        partialStage = 3;
        String item = joinItemNames(pendingTradeItems.stream().map(TradeItem::name).toList());
        String text = pickMessage(CONFIG.bestOfferMessages, bestCooldowns,
                CONFIG.bestOfferCooldownSeconds * 1000L, offer.chatOffer());
        sendChatDelayed(text == null ? LowballingSmartChat.answerComplaint(offer.chatOffer(), item)
                : text.replace("{item}", item));
        phaseAt = now;
        chatSentAt = now;
        debug("Teil-Angebot: gehe auf " + ScreenUtil.formatCoins(offer.chatOffer()) + " hoch");
    }

    private static String shortItemName(String name) {
        if (name == null) {
            return "item";
        }
        // Plain chat text only: no stars, symbols, apostrophes or "+2" suffixes.
        String clean = ScreenUtil.cleanName(name).replaceAll("\\s\\+\\d+$", "")
                .replace("'", "")
                .replaceAll("[^A-Za-z0-9 ]", " ")
                .trim().replaceAll("\\s+", " ");
        return clean.isBlank() ? "item" : clean.toLowerCase(Locale.ROOT);
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

    private static final long SECOND_OFFER_FIND_MS = 15_000L;
    /** Two trade requests (each expires after ~10 s) for the second offer. */
    private static final long SECOND_OFFER_REQUEST_MS = 21_000L;

    private static long offerWaitMs() {
        return secondOffer ? CONFIG.secondOfferItemWaitMs
                : Math.max(10_000L, CONFIG.offerGuiTimeoutMs);
    }

    private static void handleOfferGui(long now) {
        long searchStart = stateAt;
        if (secondOffer) {
            // From the second offer on the price is already agreed in chat: put
            // exactly that amount in right away instead of waiting for the items.
            // The items are checked right before the deal is accepted.
            if (!secondCoinsPlaced && now - searchStart >= 600L) {
                placeSecondOfferCoins();
                return;
            }
            if (now - searchStart > offerWaitMs()) {
                message("§e[Lowballing] §7Coins-Slot im zweiten Angebot nicht gefunden - kicke Spieler.");
                requestKick();
            }
            return;
        }
        long grace = offerWaitMs();
        if (now - searchStart < grace) {
            return;
        }
        List<Integer> items = LowballingGui.findTradeItemSlots();
        if (!items.isEmpty()) {
            enterValuing(items);
            return;
        }
        if (now - searchStart > offerWaitMs()) {
            message("§e[Lowballing] §7Kein Item in der GUI - kicke Spieler.");
            requestKick();
        }
    }

    /** The price we named in chat for the second (final) offer. */
    private static long statedSecondPrice(Offer offer) {
        if (finalPriceOverride > 0) {
            return finalPriceOverride;
        }
        if (agreedSecondPrice > 0) {
            return agreedSecondPrice;
        }
        return offer.chatOffer();
    }

    private static void placeSecondOfferCoins() {
        Offer offer = pendingOffer;
        if (offer == null || pendingTradeItems.isEmpty()) {
            enterNextPlayer();
            return;
        }
        long price = statedSecondPrice(offer);
        if (price <= 0) {
            message("§e[Lowballing] §7Zweites Angebot ohne Preis - kicke Spieler.");
            requestKick();
            return;
        }
        long bankroll = bankrollCoins();
        if (CONFIG.enforceBankroll && bankroll >= 0 && price > bankroll) {
            message("§e[Lowballing] §7Zweites Angebot über Bankroll - kicke Spieler.");
            requestKick();
            return;
        }
        int coins = LowballingGui.findCoinsSlot();
        if (coins < 0) {
            return; // menu still loading, handleOfferGui times out eventually
        }
        secondCoinsPlaced = true;
        finalizingSecond = true;
        pendingOffer = new Offer(offer.tag(), offer.displayName(), offer.expectedValue(),
                offer.maxAhPrice(), offer.firstOffer(), price, true, List.of());
        SignInput.set(String.valueOf(price));
        LowballingGui.clickSlot(coins, 0);
        enterSetCoins();
        debug("Zweites Angebot: lege direkt " + ScreenUtil.formatCoins(price)
                + " (" + formatOffer(price) + " wie im Chat) ein");
    }

    /**
     * Right before clicking "accept": are exactly the valued items in the trade
     * and is their value still about the same? Returns null when everything
     * matches, "" when the values are still loading (try again shortly) and a
     * reason otherwise.
     */
    private static String verifyTradeBeforeAccept() {
        List<TradeItem> expected = pendingTradeItems;
        if (expected == null || expected.isEmpty()) {
            return "keine bewerteten Items";
        }
        List<Integer> slots = LowballingGui.findTradeItemSlots();
        List<String> actualPrints = new ArrayList<>();
        long actualTotal = 0;
        for (int slot : slots) {
            AhItem item = LowballingGui.itemAt(slot);
            if (item.tag.isBlank()) {
                return "unbekanntes Item im Trade";
            }
            actualPrints.add(fingerprint(item));
            long sale = saleValue(item, slot);
            long value = tooltipValue(item, slot, sale);
            if (value <= 0) {
                return "";
            }
            actualTotal += value;
        }
        List<String> expectedPrints = new ArrayList<>();
        long expectedTotal = 0;
        for (TradeItem item : expected) {
            expectedPrints.add(item.fingerprint());
            expectedTotal += Math.max(0, item.saleValue());
        }
        actualPrints.sort(null);
        expectedPrints.sort(null);
        if (!actualPrints.equals(expectedPrints)) {
            return "Items geaendert (erwartet " + expectedPrints.size() + ", im Trade "
                    + actualPrints.size() + ")";
        }
        if (expectedTotal > 0 && actualTotal < expectedTotal * (1.0 - FINAL_VALUE_TOLERANCE)) {
            return "Wert gefallen: " + ScreenUtil.formatCoins(actualTotal) + " statt "
                    + ScreenUtil.formatCoins(expectedTotal);
        }
        return null;
    }

    /** Wrong item or value at the last moment: cancel, block and kick. */
    private static void abortTradeBlockAndKick(String reason) {
        message("§c[Lowballing] §7Abschluss-Check fehlgeschlagen (" + reason
                + ") - Trade abgebrochen, " + targetName + " geblockt und gekickt.");
        debug("Abschluss-Check fehlgeschlagen: " + reason);
        SignInput.clear();
        LowballingGui.close();
        if (targetName != null && !targetName.isBlank()) {
            sendChat("/block add " + targetName);
        }
        finalizingSecond = false;
        finalRetryUsed = true;
        pendingTradeItems = List.of();
        requestKick();
    }

    /**
     * The first trade GUI was closed (by us or by the visitor) before the deal
     * went through. Only go back into a trade when we actually want the item:
     * first post the best-offer chat message, then re-open the trade with the
     * lower second offer. Otherwise kick the visitor.
     */
    private static void onFirstTradeClosed(String reason) {
        Offer offer = pendingOffer;
        boolean wanted = offer != null && offer.buyable() && offer.chatOffer() > 0;
        if (wanted && CONFIG.enforceBankroll) {
            long bankroll = bankrollCoins();
            wanted = bankroll < 0 || offer.chatOffer() <= bankroll;
        }
        if (!wanted) {
            message("§e[Lowballing] §7" + reason + " - Item nicht gewollt, kicke Spieler.");
            requestKick();
            return;
        }
        message("§e[Lowballing] §7" + reason + " - schreibe Angebot in den Chat"
                + " und mache das zweite Angebot.");
        if (tradeGuiAt < stateAt) {
            tradeGuiAt = stateAt;
        }
        enterChatOffer();
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

    /**
     * Values ALL items of the trade together. Items that fail the filters no
     * longer reject the whole trade: the wanted ones are summed up and the rest
     * goes into {@link #rejectedTradeNames}, which turns the deal into a partial
     * chat offer ("i take X and Y but not Z").
     */
    private static void computeOffer(List<Integer> slots) {
        try {
            long total = 0;
            String firstTag = "";
            String firstName = "";
            List<String> reasons = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            List<TradeItem> tradeItems = new ArrayList<>();
            for (int slot : slots) {
                AhItem item = LowballingGui.itemAt(slot);
                if (item.tag.isBlank()) {
                    List<String> itemReasons = List.of("Item ohne Tag in Slot " + slot);
                    logTradeItem("", item.displayName, 0, false, itemReasons);
                    reasons.addAll(itemReasons);
                    rejected.add(item.displayName);
                    continue;
                }
                item.coflMed = AhItem.parseCoflMed(LowballingGui.tooltipLinesAt(slot));
                long sale = saleValue(item, slot);
                if (sale <= 0) {
                    List<String> itemReasons = List.of("Kein Verkaufswert: " + item.tag);
                    logTradeItem(item.tag, item.displayName, sale, false, itemReasons);
                    reasons.addAll(itemReasons);
                    rejected.add(item.displayName);
                    continue;
                }
                AhAnalyzer.Result analysis = AhAnalyzer.analyze(item,
                        Math.max(1, sale), 0, "", CONFIG.minItemCoins);
                if (!analysis.buyable) {
                    logTradeItem(item.tag, item.displayName, sale, false,
                            analysis.reasons);
                    reasons.addAll(analysis.reasons);
                    rejected.add(item.displayName);
                    continue;
                }
                long value = tooltipValue(item, slot, sale);
                if (firstTag.isBlank()) {
                    firstTag = item.tag;
                    firstName = item.displayName;
                    currentTradeValue = value;
                }
                logTradeItem(item.tag, item.displayName, value, true, List.of());
                total += value;
                tradeItems.add(new TradeItem(item.tag, item.displayName, value,
                        fingerprint(item)));
            }
            if (tradeItems.isEmpty()) {
                pendingTradeItems = List.of();
                rejectedTradeNames = List.of();
                valuationOffer = new Offer(firstTag, firstName, 0, 0, 0, 0,
                        false, reasons.isEmpty() ? List.of("Kein kaufbares Item") : reasons);
                return;
            }
            if (total < CONFIG.minItemCoins) {
                pendingTradeItems = List.of();
                rejectedTradeNames = List.of();
                valuationOffer = new Offer(firstTag, firstName, total, total, 0, 0,
                        false, List.of("Verkaufswert unter "
                        + ScreenUtil.formatCoins(CONFIG.minItemCoins)));
                return;
            }
            // First offer -15 %, second (chat) offer -10 % of the summed value,
            // rounded down to the number that is written in chat.
            long first = LowballingSmartChat.chatRound(total * FIRST_OFFER_FACTOR);
            long chat = LowballingSmartChat.chatRound(total * SECOND_OFFER_FACTOR);
            if (first <= 0 || chat <= 0) {
                pendingTradeItems = List.of();
                rejectedTradeNames = List.of();
                valuationOffer = new Offer(firstTag, firstName, total, total, first, chat,
                        false, List.of("Angebot zu niedrig"));
                return;
            }
            String displayName = firstName.isBlank() ? firstTag : firstName;
            if (tradeItems.size() > 1) {
                displayName += " +" + (tradeItems.size() - 1);
            }
            pendingTradeItems = List.copyOf(tradeItems);
            rejectedTradeNames = List.copyOf(rejected);
            if (!rejected.isEmpty()) {
                debug("Teil-Angebot: nehme " + tradeItems.size() + " Item(s), nicht "
                        + String.join(", ", rejected) + " (" + String.join("; ", reasons) + ")");
            }
            valuationOffer = new Offer(firstTag, displayName, total, total,
                    first, chat, true, List.of());
        } catch (Exception e) {
            valuationError = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    private static final double FIRST_OFFER_FACTOR = 0.85;
    private static final double SECOND_OFFER_FACTOR = 0.90;
    private static final long OFFER_ROUND_COINS = 1_000L;

    /**
     * The value shown in OUR item tooltip ("[AH-Flipper] Wert: 12.3M"), i.e.
     * exactly what the player sees. It used to call AhValuation.estimate with
     * only the Cofl median filled in (no LBin / LBin count), so the offer was
     * based on a different number than the tooltip. Off the render thread we
     * wait up to 6 s for the tooltip to finish computing; otherwise the value
     * is computed with the same Cofl inputs the tooltip uses.
     */
    private static long tooltipValue(AhItem item, int slot, long fallback) {
        boolean mayWait = !Minecraft.getInstance().isSameThread();
        long deadline = System.currentTimeMillis() + (mayWait ? 6_000L : 0L);
        List<String> lines;
        while (true) {
            lines = LowballingGui.tooltipLinesAt(slot);
            long shown = parseShownTooltipValue(lines);
            if (shown > 1) {
                return shown;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            ScreenUtil.sleep(250);
        }
        item.applyCoflValues(AhItem.parseCoflValues(lines));
        AhValuation.Estimate estimate = AhValuation.estimate(item, "");
        if (estimate.estimated > 1) {
            return estimate.estimated;
        }
        return fallback > 0 ? fallback : saleValue(item, slot);
    }

    private static final java.util.regex.Pattern SHOWN_VALUE = java.util.regex.Pattern.compile(
            "(?i)\\[AH-Flipper\\]\\s*Wert:\\s*([0-9][0-9.,]*\\s*[kmbt]?)");

    private static long parseShownTooltipValue(List<String> lines) {
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            java.util.regex.Matcher matcher = SHOWN_VALUE.matcher(ScreenUtil.cleanName(line));
            if (matcher.find()) {
                return parseCompactCoins(matcher.group(1));
            }
        }
        return 0;
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
            finalRetryUsed = false;
            finalPriceOverride = 0;
            agreedSecondPrice = 0;
            partialStage = 0;
            secondCoinsPlaced = false;
            reachSince = 0;
            rejectedTradeNames = List.of();
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
            PlayerTrack track = new PlayerTrack(player.position());
            markIfAtSpawn(client, player, track);
            TRACKED.put(player.getUUID(), track);
        }
    }

    private static final double SPAWN_RADIUS = 3.0;
    private static final long SPAWN_WINDOW_EXTRA_MS = 3000L;

    /**
     * Players standing within 3 blocks of us right after we arrived on the
     * island are waiting for us, so they count as real even if they never move.
     */
    private static void markIfAtSpawn(Minecraft client, Player player, PlayerTrack track) {
        if (track.moved || client.player == null) {
            return;
        }
        long window = CONFIG.islandSettleSeconds * 1000L + SPAWN_WINDOW_EXTRA_MS;
        if (System.currentTimeMillis() - islandArrivedAt > window) {
            return;
        }
        if (player.distanceTo(client.player) <= SPAWN_RADIUS) {
            track.moved = true;
            debug("Spieler am Spawn (<= 3 Blöcke): " + player.getName().getString());
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
            }
            markIfAtSpawn(client, player, track);
            if (!track.moved && LowballingChat.visitedSince(player.getName().getString(),
                    islandArrivedAt - 120_000L)) {
                // "X is visiting your island": a real visitor even if they stand still.
                track.moved = true;
                debug("Besucher-Nachricht: " + player.getName().getString());
            }
            if (!track.moved
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

    /**
     * Players standing inside each other: the trade window ("You ... <name>")
     * can belong to the other one. Never trade with the wrong person: close it,
     * queue that player next and continue with the actual target.
     */
    private static boolean handleWrongTradePartner(Minecraft client) {
        String title = LowballingGui.currentTitle().toLowerCase(Locale.ROOT).trim();
        if (!title.startsWith("you ") || targetName == null || targetName.isBlank()
                || title.contains(targetName.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String other = "";
        if (client.level != null) {
            for (Player player : client.level.players()) {
                String name = player.getName().getString();
                if (player != client.player && title.contains(name.toLowerCase(Locale.ROOT))) {
                    other = name;
                    if (!PROCESSED.contains(player.getUUID())
                            && !PLAYER_QUEUE.contains(player.getUUID())) {
                        PlayerTrack track = TRACKED.computeIfAbsent(player.getUUID(),
                                ignored -> new PlayerTrack(player.position()));
                        track.moved = true;
                        PLAYER_QUEUE.addFirst(player.getUUID());
                    }
                    break;
                }
            }
        }
        message("§e[Lowballing] §7Trade mit falschem Spieler"
                + (other.isBlank() ? "" : " (" + other + ")") + " geöffnet - schließe, weiter mit "
                + targetName + ".");
        SignInput.clear();
        LowballingGui.close();
        reachSince = 0;
        enterApproach();
        return true;
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

    /** Same formatting as the smart chat: never rounds up (coins == chat number). */
    private static String formatOffer(long coins) {
        return LowballingSmartChat.format(coins);
    }

    private static String trimZeros(String number) {
        if (!number.contains(".")) {
            return number;
        }
        number = number.replaceAll("0+$", "");
        return number.endsWith(".") ? number.substring(0, number.length() - 1) : number;
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
                ? formatOffer(value) : formatBankroll(value);
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

    /**
     * Replies to the visitor are sent with a short human delay (1-1.5 s)
     * instead of instantly after their message.
     */
    private static void sendChatDelayed(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        long due = System.currentTimeMillis() + 1000L
                + ThreadLocalRandom.current().nextInt(501);
        synchronized (DELAYED_CHAT) {
            DELAYED_CHAT.add(new DelayedChat(due, text));
        }
    }

    private static void flushDelayedChat(long now) {
        synchronized (DELAYED_CHAT) {
            for (java.util.Iterator<DelayedChat> it = DELAYED_CHAT.iterator(); it.hasNext(); ) {
                DelayedChat chat = it.next();
                if (chat.dueAt() <= now) {
                    it.remove();
                    sendChat(chat.text());
                }
            }
        }
    }

    private static void sendChat(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return;
        }
        if (client.gui.screen() != null || !PENDING_CHAT.isEmpty()) {
            // Never write while a GUI is open; send it right after it closed.
            PENDING_CHAT.addLast(text);
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
