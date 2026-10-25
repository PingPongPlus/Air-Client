package com.coflnet.lowballing;

import com.coflnet.CoflModClient;
import com.coflnet.ahflipper.AhAnalyzer;
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
        OPEN_OFFER, VALUING, OFFER_READY, SET_COINS, WAIT_TRADE, CHAT_OFFER,
        KICK_WAIT, NEXT_PLAYER, RECONNECTING
    }

    private record Offer(String tag, String displayName, long expectedValue,
                         long maxAhPrice, long firstOffer, long chatOffer,
                         boolean buyable, List<String> reasons) {
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
    private static State state = State.IDLE;
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
    private static double warpX;
    private static double warpY;
    private static double warpZ;

    private static UUID targetUuid;
    private static String targetName = "";
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
    private static long offerSearchStartedAt;
    private static boolean chatSent;
    private static long nextChatAt;

    private static volatile Offer valuationOffer;
    private static volatile String valuationError;
    private static Offer pendingOffer;

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
            releaseSneak();
            LowballingNavigator.stop();
            SignInput.clear();
            LowballingChat.reset();
            setState(State.RECONNECTING);
            message("§e[Lowballing] §7Verbindung getrennt - warte auf Reconnect.");
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (running && state == State.RECONNECTING) {
                message("§a[Lowballing] §7Reconnect erkannt - starte Sequenz neu.");
                enterGoHub();
            }
        });
    }

    public static boolean isRunning() {
        return running;
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
                GLFW.GLFW_KEY_F7,
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
        if (running) {
            stop("§6[Lowballing] §cgestoppt.");
        } else {
            start();
        }
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
        message("§7Bankroll: §f" + (bankroll >= 0 ? formatMillions(bankroll) : "?")
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
        nextAdAt = stateAt;
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
        debug("Nähere mich " + targetName + (secondOffer ? " (zweites Angebot)" : ""));
    }

    private static void enterOpenMenu() {
        setState(State.OPEN_MENU);
        actionAt = stateAt + CONFIG.sneakHoldMs - CONFIG.menuRightClickIntervalMs;
        unSneakClicks = 0;
        coinsClicked = false;
        setSneak(true);
        debug("Sneake und rechtsklicke " + targetName);
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
        AhItem item = LowballingGui.itemAt(slot);
        if (item.tag.isBlank()) {
            requestKick();
            return;
        }
        setState(State.VALUING);
        actionAt = stateAt;
        valuationOffer = null;
        valuationError = null;
        Thread.startVirtualThread(() -> computeOffer(item, slot));
        debug("Bewerte " + item.displayName);
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
        debug("Klicke Trade");
    }

    private static void enterChatOffer() {
        SignInput.clear();
        setState(State.CHAT_OFFER);
        actionAt = stateAt;
        phaseAt = stateAt;
        chatSent = false;
        nextChatAt = 0;
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
        LowballingRotation.tick(client.player);
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
        if (LowballingChat.consumeWarp(warpWatchAt) || coordsMoved(5.0)) {
            enterHubSettle();
            return;
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
        if (LowballingChat.consumeWarp(warpWatchAt) || coordsMoved(5.0) || selectorGone()) {
            enterHubSettle();
            return;
        }
        if (now - actionAt > CONFIG.hubMenuCloseGraceMs) {
            enterOpenSelector();
        }
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
        if (now - actionAt < CONFIG.menuUnSneakIntervalMs) {
            return;
        }
        if (unSneakClicks >= CONFIG.menuUnSneakAttempts) {
            fail("Spieler-GUI öffnet nicht");
            return;
        }
        LowballingRotation.lookAt(target);
        if (!rightClickEntity(target)) {
            return;
        }
        unSneakClicks++;
        actionAt = now;
        debug("Rechtsklick ohne Sneak " + unSneakClicks + "/" + CONFIG.menuUnSneakAttempts);
    }

    private static void tickPlayerMenu(Minecraft client, long now) {
        if (!LowballingGui.isContainerOpen()) {
            if (kickAfterMenu) {
                enterOpenMenu();
                return;
            }
            if (now - stateAt > 1000) {
                if (offerSearchStartedAt > 0
                        && now - offerSearchStartedAt > CONFIG.offerGuiTimeoutMs) {
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
        LowballingGui.clickSlot(trade, 0);
        enterWaitTrade();
    }

    private static void tickWaitTrade(Minecraft client, long now) {
        if (!LowballingGui.isContainerOpen()) {
            if (finalizingSecond) {
                enterNextPlayer();
            } else {
                enterChatOffer();
            }
            return;
        }
        if (now - tradeClickAt < CONFIG.tradeClickIntervalMs) {
            return;
        }
        if (tradeClicks >= CONFIG.tradeClickMaxAttempts) {
            LowballingGui.close();
            if (finalizingSecond) {
                enterNextPlayer();
            } else {
                enterChatOffer();
            }
            return;
        }
        int trade = LowballingGui.findTradeSlot();
        if (trade < 0) {
            if (now - stateAt > CONFIG.tradeClickMaxAttempts * CONFIG.tradeClickIntervalMs) {
                LowballingGui.close();
                if (finalizingSecond) {
                    enterNextPlayer();
                } else {
                    enterChatOffer();
                }
            }
            return;
        }
        LowballingGui.clickSlot(trade, 0);
        tradeClicks++;
        tradeClickAt = now;
        debug("Trade-Klick " + tradeClicks + "/" + CONFIG.tradeClickMaxAttempts);
    }

    private static void tickChatOffer(Minecraft client, long now) {
        if (!chatSent) {
            if (now - phaseAt < CONFIG.tradeCloseWaitMs) {
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
        if (now - phaseAt < CONFIG.secondOfferWaitMs) {
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

    private static void handleOfferGui(long now) {
        if (offerSearchStartedAt > 0
                && now - offerSearchStartedAt > CONFIG.offerGuiTimeoutMs) {
            message("§e[Lowballing] §7Kein Item in der GUI - kicke Spieler.");
            requestKick();
            return;
        }
        List<Integer> items = LowballingGui.findTradeItemSlots();
        if (!items.isEmpty()) {
            int slot = items.get(0);
            if (secondOffer) {
                handleSecondOffer(slot);
            } else {
                enterValuing(slot);
            }
            return;
        }
        if (!coinsClicked) {
            int coins = LowballingGui.findCoinsSlot();
            if (coins >= 0) {
                LowballingGui.clickSlot(coins, 0);
                enterOpenOffer();
                return;
            }
        }
        if (now - stateAt > CONFIG.offerGuiTimeoutMs) {
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
            message("§e[Lowballing] §7Anderes Item - kicke Spieler.");
            requestKick();
            return;
        }
        int coins = LowballingGui.findCoinsSlot();
        if (coins < 0) {
            message("§e[Lowballing] §7Coins-Slot nicht gefunden - kicke Spieler.");
            requestKick();
            return;
        }
        finalizingSecond = true;
        SignInput.set(String.valueOf(offer.chatOffer()));
        LowballingGui.clickSlot(coins, 0);
        enterSetCoins();
        debug("Zweites Angebot " + ScreenUtil.formatCoins(offer.chatOffer()));
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

    private static void computeOffer(AhItem item, int slot) {
        try {
            AhValuation.Estimate estimate = AhValuation.estimate(item, "");
            long value = estimate.estimated;
            if (value <= 0) {
                valuationOffer = new Offer(item.tag, item.displayName, 0, 0, 0, 0,
                        false, List.of("Keine Preisbewertung"));
                return;
            }
            double fee = CONFIG.ahFeePercent / 100.0;
            double margin = CONFIG.minProfitPercent / 100.0;
            long maxPay = (long) Math.floor(value * (1.0 - fee) / (1.0 + margin));
            if (maxPay < CONFIG.minItemCoins) {
                valuationOffer = new Offer(item.tag, item.displayName, value, maxPay, 0, 0,
                        false, List.of("Maximalpreis unter " + ScreenUtil.formatCoins(CONFIG.minItemCoins)));
                return;
            }
            AhAnalyzer.Result analysis = AhAnalyzer.analyze(item, maxPay, 0);
            long first = floorTo(maxPay * (1.0 - CONFIG.firstOfferDiscountPercent / 100.0),
                    CONFIG.firstOfferRoundCoins);
            long chat = floorTo(maxPay, CONFIG.chatOfferRoundCoins);
            long net = Math.round(analysis.expectedValue * (1.0 - fee));
            boolean buyable = analysis.buyable && first > 0 && chat > 0
                    && net >= Math.round(maxPay * (1.0 + margin));
            List<String> reasons = new ArrayList<>(analysis.reasons);
            if (!buyable && reasons.isEmpty()) {
                reasons.add("Gewinnregel nicht erfüllt");
            }
            valuationOffer = new Offer(item.tag, item.displayName, analysis.expectedValue,
                    maxPay, first, chat, buyable, reasons);
        } catch (Exception e) {
            valuationError = e.getMessage() == null ? e.toString() : e.getMessage();
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

    private static String formatMillions(long coins) {
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
                ? formatMillions(value) : formatMillions(value);
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
