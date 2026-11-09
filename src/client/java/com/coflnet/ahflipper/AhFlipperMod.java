package com.coflnet.ahflipper;

import com.coflnet.CoflModClient;
import com.coflnet.flipper.FlipperMod;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.lowballing.LowballingMod;
import com.coflnet.profit.ProfitTracker;

import com.coflnet.flipper.trading.SignInput;
import com.coflnet.flipper.trading.Trader;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public class AhFlipperMod {
    public static final AhFlipperConfig CONFIG = AhFlipperConfig.load();
    private static KeyMapping toggleKey;
    private static KeyMapping investKey;
    private static KeyMapping fireSaleKey;
    private static KeyMapping blacklistKey;
    private static KeyMapping mouseUngrabKey;
    private static volatile boolean running;
    private static volatile boolean recon;
    private static volatile boolean autoBuy = CONFIG.autoBuy;
    private static volatile boolean purchaseInProgress;
    private static volatile long bidPriorityUntil;
    private static volatile AutomationGate.Lease bidLease;
    private static volatile boolean lastBuyOk;
    private static volatile boolean autoWorkerRunning;
    private static volatile boolean debugAnalyze = CONFIG.debugAnalyze;
    private static volatile boolean debugWorkerRunning;
    private static final java.util.List<AutoCandidate> AUTO_QUEUE = new java.util.ArrayList<>();
    private static final java.util.List<AutoCandidate> DEBUG_QUEUE = new java.util.ArrayList<>();
    private static final java.util.ArrayDeque<ListingTask> LISTING_QUEUE = new java.util.ArrayDeque<>();
    private static final java.util.Map<String, Long> RECENT_LISTINGS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> ACTIVE_LISTINGS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int AH_MAX_ACTIVE = 17;
    private static volatile int activeAuctionCount = -1;
    private static volatile boolean listingWorkerRunning;
    private static volatile long lastActiveCountRefreshAt;
    private static volatile boolean activeCountRefreshRunning;
    private static volatile boolean claimActionPerformed;
    private static volatile String lastFlipId;
    private static volatile long lastFlipWorth;
    private static volatile long lastFlipAt;
    private static volatile String lastBoughtTag = "";
    private static volatile String lastBoughtName = "";
    private static volatile long lastBoughtValue;
    private static final java.util.concurrent.atomic.AtomicBoolean SOLD_CLAIM_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastSoldClaimAt;
    private static String lastScreenKey = "";
    private static Screen watchdogScreen;
    private static long watchdogSince;
    private static long lastWatchdogCloseAt;
    private static long lastPriorityRefreshAt;
    private static long lastPriorityReleaseCheckAt;

    private record AutoCandidate(String id, long value, long profit, long at, boolean prevalidated) {
        private AutoCandidate(String id, long value, long profit, long at) {
            this(id, value, profit, at, false);
        }
    }

    private record ListingTask(String tag, String name, long value, long boughtPrice,
                               String excludeUuid, long fixedPrice, long at,
                               int attempts, long notBefore) {
        ListingTask retryLater(long delayMs) {
            return new ListingTask(tag, name, value, boughtPrice, excludeUuid, fixedPrice,
                    at, attempts + 1, System.currentTimeMillis() + delayMs);
        }

        /** Waiting for a temporary condition (AH full ...) does not use up an attempt. */
        ListingTask waitLater(long delayMs) {
            return new ListingTask(tag, name, value, boughtPrice, excludeUuid, fixedPrice,
                    at, attempts, System.currentTimeMillis() + delayMs);
        }

        ListingTask now() {
            return new ListingTask(tag, name, value, boughtPrice, excludeUuid, fixedPrice,
                    at, attempts, 0L);
        }
    }

    /**
     * Outcome of a listing attempt, so the worker knows what to do next.
     * AH_FULL and NO_SPACE are temporary (wait, no attempt used), GONE means the
     * item will never arrive (auction lost, purchase refunded).
     */
    private enum ListResult { LISTED, NO_ITEM, NO_PRICE, FAILED, AH_FULL, NO_SPACE, GONE }

    /** Outcome of the claim of a bought/won item. */
    private enum ClaimResult { CLAIMED, NOT_FOUND, FAILED, LOST, NO_SPACE }

    /** What Hypixel answered to the confirm click of a BIN purchase. */
    private enum BuyOutcome { BOUGHT, FAILED, UNKNOWN }

    /** Retries for a purchase whose item has not arrived in the inventory yet. */
    private static final int MAX_LISTING_RETRIES = 4;
    /** A listing task waiting for free AH slots etc. is dropped after this long. */
    private static final long LISTING_TASK_MAX_AGE_MS = 6 * 3_600_000L;
    /** Delay while the AH is full or the inventory has no room. */
    private static final long LISTING_WAIT_MS = 30_000L;

    // Facts from the Hypixel chat: the flows verify their result with them instead
    // of guessing from fixed waits.
    private static volatile long auctionStartedAt;
    private static volatile long slotBusyChatAt;
    private static volatile long claimedChatAt;
    private static volatile long lostAuctionChatAt;
    private static volatile long claimCooldownChatAt;
    private static volatile long purchaseFailedChatAt;
    /** The AH refused new auctions ("maximum number of concurrent auctions") until then. */
    private static volatile long ahFullUntil;
    /** Listing paused until then: the auction slot could not be emptied (inventory full). */
    private static volatile long listingBlockedUntil;
    private static volatile long lastListingWarnAt;
    /** Listing key of a purchase Hypixel has not confirmed yet; a late refund cancels it. */
    private static volatile String unconfirmedPurchaseKey = "";
    private static volatile long unconfirmedPurchaseAt;
    private static final java.util.regex.Pattern AUCTION_STARTED_CHAT = java.util.regex.Pattern.compile(
            "^(?:BIN )?Auction started for (.+?)!?$");
    /**
     * Purchases whose item could not be claimed/listed. Hypixel sometimes does not
     * auto-claim a BIN purchase ("Visit the Auction House to collect your item!");
     * when the item is claimed later by any flow (or by hand), the "You claimed X
     * from Y's auction!" chat line re-queues the listing from here.
     */
    private static final java.util.Map<String, ListingTask> UNCLAIMED_PURCHASES =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Normalized item name -> time Hypixel said the item must be collected in the AH. */
    private static final java.util.Map<String, Long> COLLECT_HINTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastPurchasedChatName = "";
    private static volatile long lastPurchasedChatAt;
    private static final java.util.regex.Pattern PURCHASED_CHAT = java.util.regex.Pattern.compile(
            "^You purchased (.+?) for [\\d,.]+ coins!?$");
    private static final java.util.regex.Pattern CLAIMED_CHAT = java.util.regex.Pattern.compile(
            "^You claimed (.+?) from (?:\\[[^\\]]+\\] )?[A-Za-z0-9_]+'s auction!?$");

    public static void init() {
        System.out.println("[AH-Flipper] BUILD CLEAN-2026-11-08 geladen (investmentGui="
                + CONFIG.investmentGui + ", fireSaleSkins=" + CONFIG.fireSaleSkins
                + ", keys=F10/F8)");
        debugAnalyze = false;
        CONFIG.debugAnalyze = false;
        PriorityAccess.init();
        registerKeybinding();
        registerSoldChatClaim();
        com.coflnet.gui.panel.FlipperPanels.init();
        AhBidMacro.init();
        AhInvestmentTracker.init();
        AhFireSaleSkinTracker.init();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                message("\u00a76[AH-Flipper] \u00a77Build \u00a7fCLEAN-2026-11-08"
                        + " \u00a77\u00b7 \u00a7fF10 \u00a77Investment \u00b7 \u00a7fF8"
                        + " \u00a77Fire Sale"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.consumeClick()) {
                toggle();
            }
            if (investKey.consumeClick()) {
                openInvestmentGui();
            }
            if (fireSaleKey.consumeClick()) {
                openFireSaleGui();
            }
            if (blacklistKey.consumeClick() && client.gui.screen() == null
                    && client.player != null) {
                handleBlacklistKey(client.player.getMainHandItem());
            }
            if (mouseUngrabKey.consumeClick() && client.gui.screen() == null) {
                toggleMouseGrab(client);
            }
            if (recon || running) {
                trackScreens(client);
            }
            watchdogScreens(client);
            long now = System.currentTimeMillis();
            if (isBusy() && now - lastPriorityRefreshAt > 10_000L) {
                lastPriorityRefreshAt = now;
                ensureAhPriority();
            }
            if (now - lastPriorityReleaseCheckAt > 1000L) {
                lastPriorityReleaseCheckAt = now;
                releaseAhPriorityIfIdle();
            }
            if (running && client.player != null
                    && now - lastActiveCountRefreshAt > 30_000L) {
                refreshActiveAuctionCountAsync();
            }
        });
    }

    public static KeyMapping toggleKey() {
        return toggleKey;
    }

    public static KeyMapping investKey() {
        return investKey;
    }

    public static KeyMapping fireSaleKey() {
        return fireSaleKey;
    }

    public static KeyMapping mouseUngrabKey() {
        return mouseUngrabKey;
    }

    public static void toggleMouseGrab(Minecraft client) {
        if (client == null || client.mouseHandler == null) {
            return;
        }
        if (client.mouseHandler.isMouseGrabbed()) {
            client.mouseHandler.releaseMouse();
        } else {
            client.mouseHandler.grabMouse();
        }
    }

    public static void openFireSaleGui() {
        openFireSaleGui(AhFireSaleSkinScreen.Mode.SCAN);
    }

    public static void openFireSaleGui(AhFireSaleSkinScreen.Mode mode) {
        if (!CONFIG.fireSaleSkins) {
            CONFIG.fireSaleSkins = true;
            CONFIG.save();
        }
        AhFireSaleSkinTracker.start();
        if (mode == AhFireSaleSkinScreen.Mode.BACKTEST) {
            AhFireSaleBacktest.runAsync();
        } else if (mode == AhFireSaleSkinScreen.Mode.COMPS) {
            AhFireSaleComps.runAsync();
        }
        message("\u00a76[Investment] \u00a7aFire Sale skin tracker active \u00a77- scan running.");
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.setScreen(new AhFireSaleSkinScreen(mode)));
    }

    public static void openInvestmentGui() {
        if (!CONFIG.investmentGui) {
            CONFIG.investmentGui = true;
            CONFIG.save();
        }
        AhInvestmentTracker.start();
        message("\u00a76[Investment] \u00a7aTracker active \u00a77- background scan running.");
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.setScreen(new AhInvestmentScreen()));
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean stopForRestart() {
        boolean wasRunning = running;
        if (wasRunning) {
            stop();
        }
        return wasRunning;
    }

    public static void resumeAfterRestart(boolean wasRunning) {
        if (wasRunning && !running) {
            start();
        }
    }

    public static boolean wantsFlips() {
        return debugAnalyze;
    }

    public static boolean isRecon() {
        return recon;
    }

    private static void registerKeybinding() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("coflnet", "ahflipper"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.ahflipper",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                category));
        investKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.investment",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                category));
        fireSaleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.firesale",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category));
        blacklistKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.blacklist_hovered",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                category));
        mouseUngrabKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.mouse_ungrab",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                category));
    }

    public static KeyMapping blacklistKey() {
        return blacklistKey;
    }

    public static boolean handleToggleKeys(net.minecraft.client.input.KeyEvent input) {
        if (toggleKey != null && toggleKey.matches(input)) {
            toggle();
            return true;
        }
        if (FlipperMod.toggleKey() != null && FlipperMod.toggleKey().matches(input)) {
            FlipperMod.toggle();
            return true;
        }
        if (LowballingMod.toggleKey() != null && LowballingMod.toggleKey().matches(input)) {
            LowballingMod.toggle();
            return true;
        }
        return false;
    }

    public static boolean handleBlacklistKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            message("\u00a7e[AH-Flipper] \u00a77No item under cursor / in hand.");
            return false;
        }
        AhItem item = AhItem.fromStack(stack);
        if (item.tag == null || item.tag.isBlank()) {
            message("\u00a7e[AH-Flipper] \u00a77No SkyBlock ID detected.");
            return false;
        }
        String base = AhFlipperConfig.baseTag(item.tag);
        boolean locked = CONFIG.toggleBlacklist(item.tag, item.displayName);
        CONFIG.save();
        if (locked) {
            message("\u00a7a[AH-Flipper] \u00a77Blocked: \u00a7f" + item.displayName + " \u00a78(" + base + ", all variants)");
        } else {
            message("\u00a7e[AH-Flipper] \u00a77Unblocked: \u00a7f" + item.displayName);
        }
        return true;
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerAhCommands(dispatcher);
        });
    }

    private static void registerAhCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("ahflip")
                .executes(AhFlipperMod::toggleCommand)
                .then(ClientCommands.literal("start").executes(AhFlipperMod::startCommand))
                .then(ClientCommands.literal("stop").executes(AhFlipperMod::stopCommand))
                .then(ClientCommands.literal("recon").executes(AhFlipperMod::reconCommand))
                .then(ClientCommands.literal("claim").executes(AhFlipperMod::claimCommand))
                .then(ClientCommands.literal("scan").executes(AhFlipperMod::scanCommand))
                .then(ClientCommands.literal("stream").executes(AhFlipperMod::streamCommand))
                .then(ClientCommands.literal("feed").executes(AhFlipperMod::streamCommand))
                .then(ClientCommands.literal("automations").executes(AhFlipperMod::automationsCommand))
                .then(ClientCommands.literal("invest").executes(AhFlipperMod::investmentCommand))
                .then(ClientCommands.literal("investment").executes(AhFlipperMod::investmentCommand))
                .then(ClientCommands.literal("skins").executes(AhFlipperMod::fireSaleCommand))
                .then(ClientCommands.literal("firesale").executes(AhFlipperMod::fireSaleCommand))
                .then(ClientCommands.literal("backtest").executes(AhFlipperMod::fireSaleBacktestCommand))
                .then(ClientCommands.literal("comps").executes(AhFlipperMod::fireSaleCompsCommand))
                .then(ClientCommands.literal("buy")
                        .then(ClientCommands.argument("auktion", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(AhFlipperMod::buyCommand)))
                .then(ClientCommands.literal("buycurrent").executes(AhFlipperMod::buyCurrentCommand))
                .then(ClientCommands.literal("buyauktion")
                        .then(ClientCommands.argument("spieler", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(AhFlipperMod::buyPlayerAuctionCommand)))
                .then(ClientCommands.literal("dump").executes(context -> {
                    AhScreenUtil.dumpScreen();
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(AhFlipperMod::statusCommand))
                .then(ClientCommands.literal("auctions").executes(AhFlipperMod::activeAuctionsCommand))
                .then(ClientCommands.literal("open")
                        .then(ClientCommands.argument("spieler", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(AhFlipperMod::openCommand)))
                .then(ClientCommands.literal("view")
                        .then(ClientCommands.argument("auktion", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(AhFlipperMod::viewCommand)))
                .then(ClientCommands.literal("analyze").executes(AhFlipperMod::analyzeCommand))
                .then(ClientCommands.literal("sell")
                        .executes(AhFlipperMod::sellCommand)
                        .then(ClientCommands.argument("preis", DoubleArgumentType.doubleArg(1))
                                .executes(AhFlipperMod::sellCommand)))
                .then(ClientCommands.literal("item").executes(context -> {
                    logCurrentItem();
                    return 1;
                })));
    }

    public static void toggle() {
        if (running) {
            stop();
        } else {
            start();
        }
    }

    private static void start() {
        if (!ScreenUtil.onSkyBlock()) {
            message("\u00a7cAH-Flipper can only start on Hypixel SkyBlock.");
            return;
        }
        running = true;
        recon = true;
        if (CONFIG.liveFeed) {
            AhLiveScanner.start();
        }
        if (CONFIG.autoScan) {
            AhScanner.start();
        }
        if (CONFIG.bidMacro) {
            AhBidMacro.start();
        }
        long oldest = System.currentTimeMillis() - LISTING_TASK_MAX_AGE_MS;
        synchronized (LISTING_QUEUE) {
            LISTING_QUEUE.removeIf(task -> task.at() < oldest);
        }
        ensureListingWorker();
        refreshActiveAuctionCountAsync();
        message("\u00a76[AH-Flipper] \u00a7aactive \u00a77(Live " + (CONFIG.liveFeed ? "\u00a7aon" : "\u00a7coff") + "\u00a77, Scanner " + (CONFIG.autoScan ? "\u00a7aon" : "\u00a7coff") + "\u00a77, Auto-Buy " + (autoBuy ? "\u00a7aon" : "\u00a7coff") + "\u00a77).");
        message("\u00a77Investment: \u00a7fF10 \u00a77\u00b7 Fire Sale: \u00a7fF8");
    }

    private static void stop() {
        running = false;
        recon = false;
        AhLiveScanner.stop();
        AhScanner.stop();
        AhBidMacro.stop();
        clearBidPriority();
        synchronized (AUTO_QUEUE) {
            AUTO_QUEUE.clear();
        }
        // The listing queue stays: these items are paid for, and stopping for a
        // server restart must not forget them. start() continues with them.
        releaseAhPriorityIfIdle();
        Trader trader = Trader.get();
        if (trader != null && !isBusy()) {
            trader.releaseAhPriority();
        }
        message("\u00a76[AH-Flipper] \u00a7cstopped.");
    }

    private static void trackScreens(Minecraft client) {
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            lastScreenKey = "";
            return;
        }
        String key = container.getTitle().getString() + "|"
                + (container.getMenu() instanceof net.minecraft.world.inventory.ChestMenu chest
                        ? chest.getContainer().getContainerSize()
                        : container.getMenu().getItems().size());
        if (key.equals(lastScreenKey)) {
            return;
        }
        lastScreenKey = key;
        if (!AhScreenUtil.isRelevant(container)) {
            return;
        }
        AhScreenUtil.logContainer(container);
    }

    private static void watchdogScreens(Minecraft client) {
        Screen screen = client.gui.screen();
        if (screen == null) {
            watchdogScreen = null;
            watchdogSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (screen != watchdogScreen) {
            watchdogScreen = screen;
            watchdogSince = now;
        }
        if (!isAutomationScreen(screen)) {
            watchdogSince = now;
            return;
        }
        long activity = AutomationGate.lastActivityAt();
        boolean automationActive = running || AhBidMacro.isRunning() || isTraderRunning();
        if (!automationActive && (activity <= 0 || now - activity > 15_000L)) {
            return;
        }
        if (isBusy() || isBidPriorityActive()) {
            watchdogSince = now;
            return;
        }
        Trader trader = Trader.get();
        if (trader != null && trader.isGuiOwned()) {
            watchdogSince = now;
            return;
        }
        if (trader != null && trader.isRunning() && !trader.isAhPriority()
                && trader.hasBazaarGui()) {
            watchdogSince = now;
            return;
        }
        if (activity <= 0 || now - activity > 15_000L) {
            return;
        }
        if (now - watchdogSince < 3000L || now - lastWatchdogCloseAt < 1000L) {
            return;
        }
        lastWatchdogCloseAt = now;
        watchdogSince = now;
        ScreenUtil.closeCurrentScreen();
        System.out.println("[AH-Flipper] GUI-Watchdog: schliesse haengende GUI nach 3s: "
                + ScreenUtil.cleanName(screen.getTitle().getString()));
    }

    private static boolean isTraderRunning() {
        Trader trader = Trader.get();
        return trader != null && trader.isRunning();
    }

    private static boolean isAutomationScreen(Screen screen) {
        if (screen instanceof AbstractSignEditScreen) {
            return true;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        String title = ScreenUtil.cleanName(container.getTitle().getString())
                .toLowerCase(java.util.Locale.ROOT);
        return title.contains("auction")
                || title.contains("bazaar")
                || title.contains("orders")
                || title.contains("bids")
                || title.contains("confirm")
                || title.contains("create")
                || title.contains("manage")
                || title.contains("how many")
                || title.contains("how much")
                || title.contains("price")
                || title.contains("selling")
                || title.contains("buy order")
                || title.contains("sell offer");
    }

    private static int toggleCommand(CommandContext<FabricClientCommandSource> context) {
        toggle();
        return 1;
    }

    private static int investmentCommand(CommandContext<FabricClientCommandSource> context) {
        openInvestmentGui();
        return 1;
    }

    private static int fireSaleCommand(CommandContext<FabricClientCommandSource> context) {
        openFireSaleGui();
        return 1;
    }

    private static int fireSaleBacktestCommand(CommandContext<FabricClientCommandSource> context) {
        openFireSaleGui(AhFireSaleSkinScreen.Mode.BACKTEST);
        return 1;
    }

    private static int fireSaleCompsCommand(CommandContext<FabricClientCommandSource> context) {
        openFireSaleGui(AhFireSaleSkinScreen.Mode.COMPS);
        return 1;
    }

    private static int startCommand(CommandContext<FabricClientCommandSource> context) {
        if (running) {
            message("§cAH-Flipper läuft bereits.");
            return 1;
        }
        start();
        return 1;
    }

    private static int stopCommand(CommandContext<FabricClientCommandSource> context) {
        if (!running && !recon) {
            message("§cAH-Flipper läuft nicht.");
            return 1;
        }
        stop();
        return 1;
    }

    private static int reconCommand(CommandContext<FabricClientCommandSource> context) {
        recon = !recon;
        message("§6[AH-Flipper] §7Recon-Logging " + (recon ? "§aan" : "§caus"));
        return 1;
    }

    private static int activeAuctionsCommand(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            message("§cSpieler nicht verfügbar.");
            return 0;
        }
        String uuid = client.player.getUUID().toString().replace("-", "");
        message("§7Lade aktive Auktionen ...");
        Thread thread = new Thread(() -> {
            int count = AhMarketData.activePlayerAuctionCount(uuid);
            if (count >= 0) {
                activeAuctionCount = count;
                message("§6[AH-Flipper] §7Aktive Auktionen: §f" + count
                        + "§7/§f" + AH_MAX_ACTIVE);
                ensureListingWorker();
            } else {
                message("§cAktive Auktionen konnten nicht geladen werden.");
            }
        }, "skycofl-ah-active-auctions");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int statusCommand(CommandContext<FabricClientCommandSource> context) {
        message("§6[Flipper] §7AH-Status: " + (running ? "§aAKTIV" : "§cGESTOPPT")
                + (recon ? " §7(Recon an)" : ""));
                message("§7Exit: §fmin(Craft-Cost, LBin) §7· Fee: §f"
                + String.format(java.util.Locale.US, "%.1f%%", CONFIG.ahFeePercent)
                + " §7· Min. Profit: §f" + String.format(java.util.Locale.US, "%.0f%%", CONFIG.minProfitPercent)
                + " §7· Min. Item: §f" + ScreenUtil.formatCoins(CONFIG.minItemCoins));
        message("§7Regeln: Total-Sales > §f" + CONFIG.minTotalSales
                + " §7· Clean ≥ §f" + CONFIG.minCleanSales + " §7nur bei Addon ≤ §f"
                + String.format(java.util.Locale.US, "%.0fM", CONFIG.cleanSalesAddonValueCoins / 1_000_000.0)
                + " §7· CV ≤ §f" + String.format(java.util.Locale.US, "%.0f%%", CONFIG.maxDeviationPercent));
        message("§7Addon: ≥ §f" + CONFIG.addonMinSalesPerWeek + "x/Woche"
                + " §7· Addonwert ≥ §f" + String.format(java.util.Locale.US, "%.0fM", CONFIG.minAddonValueMillions));
        message("§7Pets: §firgendwelche Verkäufe §7(kein Level-Minimum)");
        message("§7Kaufreihenfolge: §fFIFO §7(erster passender Treffer, kein Score-Vergleich)");
        message("§7Max. Auktionen: §f" + CONFIG.maxAuctions
                + " §7· Reserve: §f" + ScreenUtil.formatCoins(CONFIG.reserveCoins));
        message("§7Auto-Buy: " + (autoBuy ? "§aan" : "§caus")
                + " §7· Kauf läuft: " + (isBusy() ? "§eja" : "§7nein")
                + " §7· Top-5-Analyse: " + (debugAnalyze ? "§aan" : "§caus"));
        int queued;
        synchronized (AUTO_QUEUE) {
            queued = AUTO_QUEUE.size();
        }
        int listings;
        synchronized (LISTING_QUEUE) {
            listings = LISTING_QUEUE.size();
        }
        message("§7Listing-Queue: §f" + listings + " §7· Ungeclaimt: §f" + UNCLAIMED_PURCHASES.size()
                + " §7· Aktive Auktionen: §f" + activeAuctionCount + "§7/§f" + AH_MAX_ACTIVE
                + (System.currentTimeMillis() < ahFullUntil ? " §c(AH voll)" : ""));
        long age = lastFlipAt > 0 ? (System.currentTimeMillis() - lastFlipAt) / 1000 : -1;
        message("§7Letzter Flip: §f" + (lastFlipId == null ? "-" : lastFlipId)
                + " §7· Profit §f" + (lastFlipWorth > 0 ? ScreenUtil.formatCoins(lastFlipWorth) : "-")
                + " §7· Alter §f" + (age >= 0 ? age + "s" : "-")
                + " §7· Queue §f" + queued);
        String conn = CoflCore.CoflCore.Wrapper != null
                ? CoflCore.CoflCore.Wrapper.GetStatus() : "null";
            message("§7Market: §f" + conn);
        message("§7Live: " + AhLiveScanner.status());
        message("§7Scanner: " + AhScanner.status());
        message("§7Investment: " + (CONFIG.investmentGui ? "§aan" : "§caus")
                + " §7· §fF10 §7oder §f/ahflip invest");
        message("§7Fire-Sale-Skins: " + (CONFIG.fireSaleSkins ? "§aan" : "§caus")
                + " §7· §fF8 §7oder §f/ahflip skins");
        return 1;
    }

    private static int analyzeCommand(CommandContext<FabricClientCommandSource> context) {
        long price = AhScreenUtil.currentPriceAt(31);
        AhItem item = AhItem.fromStack(AhScreenUtil.currentItem(13));
        if (item.tag.isBlank()) {
            message("§cKein Item erkannt – öffne eine BIN-Auktion.");
            return 0;
        }
        message("§7Analysiere §f" + item.tag + "§7 (Preis: §f"
                + (price > 0 ? ScreenUtil.formatCoins(price) : "?") + "§7) ...");
        Thread thread = new Thread(() -> {
            try {
                AhAnalyzer.Result result = AhAnalyzer.analyze(item, Math.max(price, 0), -1);
                logAnalysis(result);
                message((result.buyable ? "§a" : "§c") + result.shortSummary());
                for (String reason : result.reasons) {
                    message("§c- " + reason);
                }
            } catch (Exception e) {
                message("§cAnalyse fehlgeschlagen: " + e.getMessage());
            }
        }, "skycofl-ah-analyze");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static void logCurrentItem() {
        var stack = AhScreenUtil.currentItem(13);
        AhItem item = AhItem.fromStack(stack);
        System.out.println("[AH-Flipper] Item: tag=" + item.tag + " name=" + item.displayName
                + " count=" + item.count + " reforge=" + item.reforge + " stars=" + item.stars
                + " hpb=" + item.hpb + " recomb=" + item.recomb
                + " artOfWar=" + item.artOfWar + " petItem=" + item.petItem
                + " enchants=" + item.enchants + " scrolls=" + item.abilityScrolls);
        System.out.println("[AH-Flipper] Item-NBT: " + AhItem.rawCustomData(stack));
        message("§6[AH-Flipper] §f" + item.tag + " §7Reforge: §f" + (item.reforge.isBlank() ? "-" : item.reforge)
                + " §7Stars: §f" + item.stars + " §7HPB: §f" + item.hpb
                + " §7Recomb: §f" + item.recomb + " §7Enchants: §f" + item.enchants.size()
                + (item.enchants.isEmpty() ? "" : " " + item.enchants));
    }

    private static void logAnalysis(AhAnalyzer.Result result) {
        System.out.println("[AH-Flipper] " + result.shortSummary());
        System.out.println("[AH-Flipper]   Clean=" + result.cleanPrice + " E=" + result.expectedValue
                + " AddonWert=" + (long) result.addonValue + " Profit=" + result.profit
                + " Pct=" + String.format(java.util.Locale.US, "%.1f", result.profitPercent)
                + " CV=" + String.format(java.util.Locale.US, "%.3f", result.cleanCv)
                + " overallSales=" + result.overallSales + " minAddonSales=" + result.minAddonSales);
        System.out.println("[AH-Flipper]   Scores: clean=" + String.format(java.util.Locale.US, "%.2f", result.cleanScore)
                + " sales=" + String.format(java.util.Locale.US, "%.2f", result.salesScore)
                + " volume=" + String.format(java.util.Locale.US, "%.2f", result.volumeScore)
                + " stability=" + String.format(java.util.Locale.US, "%.2f", result.stabilityScore)
                + " quality=" + String.format(java.util.Locale.US, "%.2f", result.quality)
                + " score=" + String.format(java.util.Locale.US, "%.0f", result.score));
        for (AhAnalyzer.AddonCheck check : result.addons) {
            System.out.println("[AH-Flipper]   Addon " + check.label + ": " + check.sales
                    + (check.supported ? "" : " (nicht prüfbar)"));
        }
        for (String reason : result.reasons) {
            System.out.println("[AH-Flipper]   Reject: " + reason);
        }
    }

    private static int claimCommand(CommandContext<FabricClientCommandSource> context) {
        claim();
        return 1;
    }

    private static void claim() {
        if (!ScreenUtil.onSkyBlock()) {
            message("§cClaim geht nur auf Hypixel SkyBlock.");
            return;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("claim", false);
            if (lease == null) {
                message("§cEs läuft bereits eine AH-Aktion.");
                return;
            }
            ensureAhPriority();
            try {
                claimBlocking();
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        }, "skycofl-ah-claim");
        thread.setDaemon(true);
        thread.start();
    }

    private static void registerSoldChatClaim() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                return;
            }
            try {
                onClaimRelevantChat(message);
                onSoldAuctionChat(message);
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Chat handler failed: " + e);
            }
        });
    }

    /** Lowercase, letters/digits only, single spaces: robust against symbols and colours. */
    static String normalizeItemName(String name) {
        if (name == null) {
            return "";
        }
        return ScreenUtil.cleanName(name).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * Tracks the purchase/claim chat lines that matter for listing:
     * "You purchased X for N coins!" followed by "Visit the Auction House to collect
     * your item!" means the item is NOT in the inventory and has to be claimed.
     * "You claimed X from Y's auction!" re-queues a listing that failed earlier
     * because the item was missing.
     */
    private static void onClaimRelevantChat(Component message) {
        if (message == null) {
            return;
        }
        String plain = ScreenUtil.cleanName(message.getString()).trim();
        if (plain.isEmpty() || plain.length() > 200) {
            return;
        }
        long now = System.currentTimeMillis();
        String lower = plain.toLowerCase(java.util.Locale.ROOT);
        if (AUCTION_STARTED_CHAT.matcher(plain).matches()) {
            auctionStartedAt = now;
            activeAuctionCount = Math.max(0, activeAuctionCount) + 1;
            return;
        }
        if (lower.startsWith("you already have an item in the auction slot")) {
            slotBusyChatAt = now;
            return;
        }
        if (lower.startsWith("claiming this auction is on cooldown")) {
            claimCooldownChatAt = now;
            return;
        }
        if (lower.startsWith("you didn't participate in this auction")
                || (lower.startsWith("you collected ") && lower.contains(" back from an auction"))) {
            lostAuctionChatAt = now;
            return;
        }
        if ((lower.startsWith("you collected ") && lower.contains(" from selling "))
                || lower.contains("back from your expired auction")) {
            // An ended auction was collected: its AH slot is free again.
            ahFullUntil = 0;
            if (activeAuctionCount > 0) {
                activeAuctionCount--;
            }
        }
        if (lower.startsWith("this auction wasn't found")) {
            // Also the answer to /viewauction: says nothing about an earlier purchase.
            purchaseFailedChatAt = now;
            return;
        }
        if (lower.startsWith("there was an error with the auction house")
                || (lower.startsWith("escrow refunded") && lower.contains("bin auction buy"))) {
            onPurchaseFailureChat(plain, now);
            return;
        }
        java.util.regex.Matcher purchased = PURCHASED_CHAT.matcher(plain);
        if (purchased.matches()) {
            lastPurchasedChatName = normalizeItemName(purchased.group(1));
            lastPurchasedChatAt = now;
            return;
        }
        if (lower.startsWith("visit the auction house to collect your item")) {
            if (!lastPurchasedChatName.isEmpty() && now - lastPurchasedChatAt < 5000L) {
                COLLECT_HINTS.put(lastPurchasedChatName, now);
                System.out.println("[AH-Flipper] Kauf nicht auto-geclaimt (Hypixel: "
                        + "'Visit the Auction House') - Claim noetig: " + lastPurchasedChatName);
            }
            COLLECT_HINTS.entrySet().removeIf(entry -> now - entry.getValue() > 10 * 60_000L);
            return;
        }
        java.util.regex.Matcher claimed = CLAIMED_CHAT.matcher(plain);
        if (claimed.matches()) {
            claimedChatAt = now;
            onAuctionItemClaimed(normalizeItemName(claimed.group(1)));
        }
    }

    /**
     * "There was an error with the auction house!" / "Escrow refunded ... for BIN
     * Auction Buy!": someone else was faster, no item is coming. The repeated
     * confirm clicks produce the same lines right AFTER a successful purchase, so
     * this only counts as a failure when "You purchased" did not come first.
     */
    private static void onPurchaseFailureChat(String plain, long now) {
        purchaseFailedChatAt = now;
        String key = unconfirmedPurchaseKey;
        if (key.isEmpty() || now - unconfirmedPurchaseAt > 15_000L
                || lastPurchasedChatAt >= unconfirmedPurchaseAt) {
            return;
        }
        unconfirmedPurchaseKey = "";
        boolean removed;
        synchronized (LISTING_QUEUE) {
            removed = LISTING_QUEUE.removeIf(task ->
                    listingKey(task.tag(), task.name(), task.excludeUuid()).equals(key));
        }
        removed |= UNCLAIMED_PURCHASES.remove(key) != null;
        if (removed) {
            System.out.println("[AH-Flipper] Kauf nicht zustande gekommen (" + plain
                    + ") - Listing gestrichen: " + key);
        }
    }

    private static boolean hasCollectHint(String name) {
        String key = normalizeItemName(name);
        Long at = key.isEmpty() ? null : COLLECT_HINTS.get(key);
        return at != null && System.currentTimeMillis() - at < 10 * 60_000L;
    }

    /** @return true if a tracked purchase belongs to this item and got (re-)queued */
    private static boolean onAuctionItemClaimed(String normalizedName) {
        if (normalizedName.isEmpty()) {
            return false;
        }
        COLLECT_HINTS.remove(normalizedName);
        boolean requeued = false;
        synchronized (LISTING_QUEUE) {
            java.util.List<ListingTask> tasks = new java.util.ArrayList<>(LISTING_QUEUE);
            LISTING_QUEUE.clear();
            for (ListingTask task : tasks) {
                if (task.notBefore() > 0 && normalizeItemName(task.name()).equals(normalizedName)) {
                    LISTING_QUEUE.add(task.now());
                    requeued = true;
                } else {
                    LISTING_QUEUE.add(task);
                }
            }
        }
        for (java.util.Iterator<java.util.Map.Entry<String, ListingTask>> it =
                     UNCLAIMED_PURCHASES.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, ListingTask> entry = it.next();
            ListingTask task = entry.getValue();
            if (!normalizeItemName(task.name()).equals(normalizedName)) {
                continue;
            }
            it.remove();
            if (System.currentTimeMillis() - task.at() > 7L * 24 * 3_600_000L
                    || ACTIVE_LISTINGS.contains(entry.getKey())) {
                continue;
            }
            synchronized (LISTING_QUEUE) {
                boolean queued = false;
                for (ListingTask other : LISTING_QUEUE) {
                    if (listingKey(other.tag(), other.name(), other.excludeUuid())
                            .equals(entry.getKey())) {
                        queued = true;
                        break;
                    }
                }
                if (!queued) {
                    LISTING_QUEUE.add(new ListingTask(task.tag(), task.name(), task.value(),
                            task.boughtPrice(), task.excludeUuid(), task.fixedPrice(),
                            task.at(), 0, 0L));
                }
            }
            requeued = true;
            break;
        }
        if (requeued) {
            System.out.println("[AH-Flipper] Geclaimtes Item erkannt, Listing neu eingeplant: "
                    + normalizedName);
            message("§aClaimed item detected - listing queued: §f" + normalizedName);
            ensureListingWorker();
        }
        return requeued;
    }

    private static void onSoldAuctionChat(Component message) {
        if (message == null || !running) {
            return;
        }
        String lower = ScreenUtil.cleanName(message.getString()).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("[ah-flipper]") || lower.contains("[flipper]")
                || lower.contains("[investment]") || lower.contains("[live]")
                || lower.contains("[scan]")) {
            return;
        }
        if (lower.contains("outbid")) {
            return;
        }
        if (!lower.contains("auction") || !lower.contains("coins")
                || !lower.contains("click")) {
            return;
        }
        if (!lower.contains("sold") && !lower.contains("ended")
                && !lower.contains("bought")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSoldClaimAt < 1500) {
            return;
        }
        lastSoldClaimAt = now;
        if (!SOLD_CLAIM_PENDING.compareAndSet(false, true)) {
            return;
        }
        System.out.println("[AH-Flipper] Verkaufs-Chat erkannt: " + message.getString());
        message("\u00a77Sold auction detected - claiming...");
        Thread thread = new Thread(AhFlipperMod::soldClaimWorker, "skycofl-ah-sold-claim");
        thread.setDaemon(true);
        thread.start();
    }

    private static void soldClaimWorker() {
        try {
            long deadline = System.currentTimeMillis() + 30000;
            while ((isBusy() || isBidPriorityActive()) && running
                    && System.currentTimeMillis() < deadline) {
                ScreenUtil.sleep(50);
            }
            if (!running) {
                System.out.println("[AH-Flipper] Sold-Claim übersprungen (running=false)");
                return;
            }
            AutomationGate.Lease lease = AutomationGate.acquire("sold-claim", false, 5_000L);
            if (lease == null) {
                System.out.println("[AH-Flipper] Sold-Claim übersprungen (busy="
                        + AutomationGate.owner() + ")");
                return;
            }
            ensureAhPriority();
            try {
                int claimed = claimEndedAuctionsBlocking(false);
                if (claimed > 0) {
                    message("§aClaimed " + claimed + " sold auction(s).");
                }
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Sold-Claim fehlgeschlagen: " + e);
            message("§cSold claim failed: " + e.getMessage());
        } finally {
            SOLD_CLAIM_PENDING.set(false);
        }
    }

    /**
     * Collects own ended auctions in Manage Auctions. Ended auctions keep using one
     * of the AH slots until they are collected, so this is also what frees the AH
     * when Hypixel reports the maximum number of auctions.
     *
     * @return how many were collected
     */
    private static int claimEndedAuctionsBlocking(boolean includeExpired) {
        int claimed = 0;
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.BIN_VIEW) {
                    if (clickCollectSlot()) {
                        claimed++;
                        ScreenUtil.sleep(250);
                        continue;
                    }
                    ScreenUtil.closeAndWait(200);
                }
                if (!openManageAuctions()) {
                    break;
                }
                int slot = waitForEndedAuctionSlot(includeExpired, 2500);
                if (slot < 0) {
                    break;
                }
                ScreenUtil.clickSlot(slot, 0);
                if (!waitForTitle(2500, "auction view")) {
                    System.out.println("[AH-Flipper] Sold-Claim: Auction-View nicht geöffnet (Slot "
                            + slot + ").");
                    break;
                }
                ScreenUtil.sleep(350);
                if (!clickCollectSlot()) {
                    System.out.println("[AH-Flipper] Sold-Claim: Collect-Slot nicht gefunden.");
                    break;
                }
                claimed++;
                System.out.println("[AH-Flipper] Beendete Auktion eingesammelt (Slot " + slot + ").");
                ScreenUtil.sleep(900);
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Sold-Claim Fehler: " + e);
        }
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
            ScreenUtil.closeAndWait(250);
        }
        return claimed;
    }

    private static boolean openManageAuctions() {
        AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
        if (screen == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                || screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
            return true;
        }
        if (screen != AhScreenUtil.AhScreen.NONE) {
            ScreenUtil.closeAndWait(200);
        }
        sendCommandSilent("/ah");
        if (!waitForScreenUntil(0L, 3500, AhScreenUtil.AhScreen.AH_MAIN,
                AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                AhScreenUtil.AhScreen.MANAGE_ORDERS)) {
            return false;
        }
        screen = AhScreenUtil.currentScreen();
        if (screen == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                || screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
            return true;
        }
        AhScreenUtil.AuctionsEntry entry = waitForAuctionsEntry(4000);
        if (entry.slot() < 0 || entry.owned() == 0) {
            // owned == 0: the button is "Create Auction", there is nothing to manage.
            return false;
        }
        ScreenUtil.clickSlot(entry.slot(), 0);
        return waitForScreenUntil(0L, 3500, AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                AhScreenUtil.AhScreen.MANAGE_ORDERS);
    }

    /**
     * The auctions button of the AH main menu. It first shows "Loading..." and a
     * click at that moment is ignored, which used to end in "'Manage Auctions'
     * nicht erreicht" and a failed listing.
     */
    private static AhScreenUtil.AuctionsEntry waitForAuctionsEntry(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        AhScreenUtil.AuctionsEntry entry = AhScreenUtil.readAuctionsEntry();
        while (System.currentTimeMillis() < end) {
            if (entry.slot() >= 0 && !entry.loading()) {
                break;
            }
            ScreenUtil.sleep(50);
            entry = AhScreenUtil.readAuctionsEntry();
        }
        if (entry.owned() >= 0) {
            activeAuctionCount = entry.owned();
            if (entry.owned() < AH_MAX_ACTIVE) {
                ahFullUntil = 0;
            }
        }
        return entry;
    }

    private static int waitForClaimEntryButton(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int bids = findButtonOrText("manage bids", "your bids", "manage orders");
            if (bids >= 0) {
                return bids;
            }
            ScreenUtil.sleep(50);
        }
        return -1;
    }

    private static int waitForCreateAuctionButton(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int create = findCreateAuctionButton();
            if (create >= 0) {
                return create;
            }
            ScreenUtil.sleep(50);
        }
        return -1;
    }

    private static int waitForEndedAuctionSlot(boolean includeExpired, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int slot = AhScreenUtil.findEndedAuctionSlot(includeExpired);
            if (slot >= 0) {
                return slot;
            }
            ScreenUtil.sleep(50);
        }
        return -1;
    }

    /** In the view of an own ended auction: collects the coins or takes the item back. */
    private static boolean clickCollectSlot() {
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.BIN_VIEW) {
            return false;
        }
        Integer slot = AhScreenUtil.findSlotWithLore("click to collect");
        if (slot == null || slot < 0) {
            slot = AhScreenUtil.findSlotWithLore("click to pick up item");
        }
        if (slot == null || slot < 0) {
            Integer ended = AhScreenUtil.findSlotWithLore("status: sold");
            if (ended == null || ended < 0) {
                ended = AhScreenUtil.findSlotWithLore("status: expired");
            }
            if (ended != null && ended >= 0) {
                slot = AhScreenUtil.findSlotByName("Collect Auction");
            }
        }
        if (slot == null || slot < 0) {
            return false;
        }
        ProfitTracker.startPurseWatch(ProfitTracker.AH);
        ScreenUtil.clickSlot(slot, 0);
        System.out.println("[AH-Flipper] Sold-Claim: Collect geklickt (Slot " + slot + ").");
        ScreenUtil.sleep(900);
        ProfitTracker.finishPurseWatch(ProfitTracker.AH);
        return true;
    }

    static boolean claimWonAuction(String tag, String name) {
        return claimWonAuction(tag, name, -1);
    }

    /**
     * @param baseline how many matching items were in the inventory before the
     *                 bid/purchase, or -1 if unknown. With a baseline, only an
     *                 additional copy counts as "already in the inventory".
     */
    static boolean claimWonAuction(String tag, String name, int baseline) {
        return claimItem(tag, name, baseline) == ClaimResult.CLAIMED;
    }

    private static ClaimResult claimItem(String tag, String name, int baseline) {
        // Inventory first: a won/bought item can land there directly, so only
        // open the AH claim menus when it really is not in the inventory.
        if (waitForNewInventoryItem(tag, name, baseline, INVENTORY_CHECK_MS)) {
            System.out.println("[AH-Flipper] Claim: Item bereits im Inventar: " + name);
            return ClaimResult.CLAIMED;
        }
        if (ScreenUtil.freeInventorySlots() <= 0) {
            System.out.println("[AH-Flipper] Claim: Inventar voll - warte: " + name);
            return ClaimResult.NO_SPACE;
        }
        ClaimResult result = ClaimResult.FAILED;
        for (int attempt = 1; attempt <= 2 && result == ClaimResult.FAILED; attempt++) {
            if (attempt > 1) {
                ScreenUtil.sleep(3000);
            }
            result = claimItemOnce(tag, name, baseline);
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeAndWait(250);
            }
        }
        return result;
    }

    private static ClaimResult claimItemOnce(String tag, String name, int baseline) {
        try {
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeAndWait(250);
            }
            sendCommandSilent("/ah");
            if (!waitForTitle(3500, "auction")) {
                return ClaimResult.FAILED;
            }
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen != AhScreenUtil.AhScreen.YOUR_BIDS
                    && screen != AhScreenUtil.AhScreen.MANAGE_ORDERS) {
                int entry = waitForClaimEntryButton(2500);
                if (entry < 0) {
                    return ClaimResult.FAILED;
                }
                ScreenUtil.clickSlot(entry, 0);
                if (!waitForClaimScreen(3500)) {
                    return ClaimResult.FAILED;
                }
            }
            int slot = waitForClaimableBidSlot(tag, name, 2000);
            if (slot < 0) {
                System.out.println("[AH-Flipper] Claim: kein abholbarer Eintrag fuer " + name);
                return waitForNewInventoryItem(tag, name, baseline, 300)
                        ? ClaimResult.CLAIMED : ClaimResult.NOT_FOUND;
            }
            ScreenUtil.clickSlot(slot, 0);
            if (!waitForTitle(2500, "auction view")) {
                return waitForNewInventoryItem(tag, name, baseline, 300)
                        ? ClaimResult.CLAIMED : ClaimResult.FAILED;
            }
            int collect = waitForCollectAuctionSlot(1500);
            if (collect < 0) {
                System.out.println("[AH-Flipper] Claim: kein 'Collect Auction' fuer " + name);
                return ClaimResult.FAILED;
            }
            long clickedAt = System.currentTimeMillis();
            ScreenUtil.clickSlot(collect, 0);
            long end = clickedAt + 3000L;
            while (System.currentTimeMillis() < end && claimedChatAt < clickedAt) {
                if (lostAuctionChatAt >= clickedAt) {
                    // "You collected N coins back ...": someone else got the item.
                    System.out.println("[AH-Flipper] Claim: Auktion verloren: " + name);
                    return ClaimResult.LOST;
                }
                if (claimCooldownChatAt >= clickedAt) {
                    return ClaimResult.FAILED;
                }
                ScreenUtil.sleep(50);
            }
            return waitForNewInventoryItem(tag, name, baseline, 2500)
                    ? ClaimResult.CLAIMED : ClaimResult.FAILED;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Claim Fehler: " + e);
            return ClaimResult.FAILED;
        }
    }

    private static int waitForClaimableBidSlot(String tag, String name, long timeoutMs) {
        String own = ownPlayerName();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int slot = AhScreenUtil.findClaimableBidSlot(tag, name, own);
            if (slot >= 0) {
                return slot;
            }
            ScreenUtil.sleep(50);
        }
        return -1;
    }

    private static int waitForCollectAuctionSlot(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Integer slot = AhScreenUtil.findSlotWithLore("click to pick up item");
            if (slot == null || slot < 0) {
                slot = AhScreenUtil.findSlotByName("Collect Auction");
            }
            if (slot != null && slot >= 0) {
                return slot;
            }
            ScreenUtil.sleep(50);
        }
        return -1;
    }

    private static boolean claimBlocking() {
        return claimBlocking("");
    }

    static boolean claimBlocking(String wantedName) {
        return claimBlocking("", wantedName);
    }

    static boolean claimBlocking(String wantedTag, String wantedName) {
        return claimBlocking(wantedTag, wantedName, -1);
    }

    static boolean claimBlocking(String wantedTag, String wantedName, int baseline) {
        claimActionPerformed = false;
        if ((wantedTag != null && !wantedTag.isBlank())
                || (wantedName != null && !wantedName.isBlank())) {
            if (waitForNewInventoryItem(wantedTag, wantedName, baseline, INVENTORY_CHECK_MS)) {
                System.out.println("[AH-Flipper] Claim: Item bereits im Inventar: " + wantedName);
                return true;
            }
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (claimBlockingOnce(wantedTag, wantedName)) {
                return true;
            }
            if (attempt == 1) {
                message("\u00a7eClaim not found - trying Manage Bids...");
                System.out.println("[AH-Flipper] Claim: 2. Versuch ueber Manage Bids.");
                if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                    ScreenUtil.closeCurrentScreen();
                    ScreenUtil.sleep(400);
                }
                ScreenUtil.sleep(500);
            }
        }
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(250);
        }
        if (claimActionPerformed) {
            message("\u00a7eClaim triggered, item not detected yet - trying listing.");
        }
        return claimActionPerformed;
    }

    private static boolean claimBlockingOnce(String wantedTag, String wantedName) {
        try {
            ScreenUtil.sleep(250);
            if (collectOpenAuction(wantedName)) {
                return true;
            }
            AhScreenUtil.AhScreen claimScreen = AhScreenUtil.currentScreen();
            if (claimScreen != AhScreenUtil.AhScreen.YOUR_BIDS
                    && claimScreen != AhScreenUtil.AhScreen.MANAGE_ORDERS) {
                sendCommand("/ah");
                if (!waitForTitle(3500, "auction")) {
                    message("\u00a7cAH menu not detected.");
                    return false;
                }
                int bids = waitForClaimEntryButton(2500);
                if (bids < 0) {
                    message("\u00a7cManage Bids/Orders not found.");
                    AhScreenUtil.dumpScreen();
                    return false;
                }
                ScreenUtil.clickSlot(bids, 0);
                if (!waitForClaimScreen(3500)) {
                    message("\u00a7cBids/Orders menu did not open.");
                    AhScreenUtil.dumpScreen();
                    return false;
                }
            }
            ScreenUtil.sleep(450);
            if (wantedName != null && !wantedName.isBlank()) {
                int itemSlot = -1;
                if (wantedTag != null && !wantedTag.isBlank()) {
                    itemSlot = AhScreenUtil.findSlotByTag(wantedTag);
                }
                if (itemSlot < 0) {
                    itemSlot = waitForSlotByName(wantedName, 1500);
                }
                if (itemSlot >= 0) {
                    ScreenUtil.clickSlot(itemSlot, 0);
                    claimActionPerformed = true;
                    ScreenUtil.sleep(700);
                    if (collectOpenAuction(wantedName)) {
                        return true;
                    }
                    if (waitForInventoryItem(wantedTag, wantedName, 2000)) {
                        message("\u00a7aItem claimed: \u00a7f" + wantedName);
                        return true;
                    }
                    message("\u00a7eClaim clicked - item not found after 2s.");
                    return false;
                } else {
                    System.out.println("[AH-Flipper] Claim: kein Slot für " + wantedName);
                }
            }
            int claimed = clickClaimableItems();
            if (claimed > 0) {
                claimActionPerformed = true;
                message("\u00a7aClaimed " + claimed + " item(s).");
                if (wantedName == null || wantedName.isBlank()) {
                    return true;
                }
                return waitForInventoryItem(wantedTag, wantedName, 2000);
            }
            int claimAll = ScreenUtil.findButton("claim all");
            if (claimAll >= 0) {
                ScreenUtil.clickSlot(claimAll, 0);
                claimActionPerformed = true;
                ScreenUtil.sleep(600);
                message("\u00a7aClaim All clicked.");
                if (wantedName == null || wantedName.isBlank()) {
                    return true;
                }
                return waitForInventoryItem(wantedTag, wantedName, 2000);
            }
            message("\u00a77Nothing to claim (no item slot / Click to claim).");
            AhScreenUtil.dumpScreen();
            return false;
        } catch (Exception e) {
            message("\u00a7cClaim failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean collectOpenAuction(String wantedName) {
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.BIN_VIEW) {
            return false;
        }
        Integer slot = AhScreenUtil.findSlotWithLore("click to pick up item");
        if (slot == null || slot < 0) {
            slot = AhScreenUtil.findSlotByName("Collect Auction");
        }
        if (slot == null || slot < 0) {
            System.out.println("[AH-Flipper] Claim: kein 'Collect Auction'-Slot in BIN-View.");
            return false;
        }
        ScreenUtil.clickSlot(slot, 0);
        claimActionPerformed = true;
        System.out.println("[AH-Flipper] Collect Auction geklickt: Slot " + slot);
        if (wantedName == null || wantedName.isBlank()) {
            message("\u00a7aCollect Auction clicked.");
            return true;
        }
        if (waitForInventoryItem("", wantedName, 2000)) {
            message("\u00a7aItem claimed: \u00a7f" + wantedName);
            return true;
        }
        message("\u00a7eCollect Auction clicked - item not found after 2s.");
        return false;
    }

    private static int clickClaimableItems() {
        int clicked = 0;
        java.util.Set<Integer> clickedSlots = new java.util.HashSet<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            Integer slot = AhScreenUtil.findSlotWithLore("click to claim");
            if (slot == null || slot < 0) {
                slot = AhScreenUtil.findSlotWithLore("click to pick up item");
            }
            if (slot == null || slot < 0) {
                break;
            }
            if (!clickedSlots.add(slot)) {
                break;
            }
            ScreenUtil.clickSlot(slot, 0);
            clicked++;
            ScreenUtil.sleep(700);
        }
        return clicked;
    }

    /** How long to look in the inventory before falling back to an AH claim. */
    private static final long INVENTORY_CHECK_MS = 1500L;

    /**
     * Like waitForInventoryItem, but with a baseline (>= 0) it only succeeds
     * once there are more matching items than before the purchase.
     */
    private static boolean waitForNewInventoryItem(String tag, String name, int baseline,
                                                   long timeoutMs) {
        if (baseline < 0) {
            return waitForInventoryItem(tag, name, timeoutMs);
        }
        if ((tag == null || tag.isBlank()) && (name == null || name.isBlank())) {
            return false;
        }
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (AhScreenUtil.countInventoryMatches(tag, name) > baseline) {
                return true;
            }
            if (System.currentTimeMillis() >= end) {
                return false;
            }
            ScreenUtil.sleep(100);
        }
    }

    private static boolean waitForInventoryItem(String tag, String name, long timeoutMs) {
        if ((tag == null || tag.isBlank()) && (name == null || name.isBlank())) {
            return false;
        }
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (AhScreenUtil.findInventorySlot(tag, name) >= 0) {
                return true;
            }
            ScreenUtil.sleep(100);
        }
        return false;
    }

    private static int waitForSlotByName(String name, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int slot = AhScreenUtil.findSlotByName(name);
            if (slot >= 0) {
                return slot;
            }
            ScreenUtil.sleep(10);
        }
        return -1;
    }

    private static boolean waitForTitle(long timeoutMs, String contains) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String title = AhScreenUtil.currentScreenTitle();
            if (title != null && title.toLowerCase(java.util.Locale.ROOT).contains(contains)) {
                return true;
            }
            ScreenUtil.sleep(10);
        }
        return false;
    }

    private static boolean waitForAnyTitle(long timeoutMs, String... contains) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String title = AhScreenUtil.currentScreenTitle();
            if (title != null) {
                String lower = title.toLowerCase(java.util.Locale.ROOT);
                for (String needle : contains) {
                    if (lower.contains(needle)) {
                        return true;
                    }
                }
            }
            ScreenUtil.sleep(10);
        }
        return false;
    }

    private static boolean waitForAnyTitleUntil(long deadline, long timeoutMs, String... contains) {
        return waitForAnyTitle(remainingMs(deadline, timeoutMs), contains);
    }

    private static boolean waitForClaimScreen(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
                return true;
            }
            String title = AhScreenUtil.currentScreenTitle();
            if (title != null) {
                String lower = title.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("bids") || lower.contains("orders")) {
                    return true;
                }
            }
            ScreenUtil.sleep(10);
        }
        return false;
    }

    private static int findButtonOrText(String... texts) {
        for (String text : texts) {
            int slot = ScreenUtil.findButton(text);
            if (slot >= 0) {
                return slot;
            }
        }
        for (String text : texts) {
            Integer slot = AhScreenUtil.findSlotWithText(text, 0, 53);
            if (slot != null && slot >= 0) {
                return slot;
            }
        }
        return -1;
    }

    private static int findCreateAuctionButton() {
        return findButtonOrText("create auction", "create bin auction");
    }

    private static boolean waitForScreenUntil(long deadline, long timeoutMs,
                                               AhScreenUtil.AhScreen... screens) {
        long end = System.currentTimeMillis() + remainingMs(deadline, timeoutMs);
        while (System.currentTimeMillis() < end) {
            AhScreenUtil.AhScreen current = AhScreenUtil.currentScreen();
            for (AhScreenUtil.AhScreen screen : screens) {
                if (current == screen) {
                    return true;
                }
            }
            ScreenUtil.sleep(25);
        }
        return false;
    }

    private static long remainingMs(long deadline, long fallbackMs) {
        if (deadline <= 0) {
            return fallbackMs;
        }
        return Math.max(0L, deadline - System.currentTimeMillis());
    }

    private static boolean deadlineExpired(long deadline) {
        return deadline > 0 && System.currentTimeMillis() >= deadline;
    }

    private static boolean waitForTitleUntil(long deadline, long timeoutMs, String contains) {
        return waitForTitle(remainingMs(deadline, timeoutMs), contains);
    }

    private static void sleepUntil(long deadline, long ms) {
        long sleep = remainingMs(deadline, ms);
        if (sleep > 0) {
            ScreenUtil.sleep(sleep);
        }
    }


    public static boolean isAutoBuyOn() {
        return autoBuy;
    }

    public static void toggleAutoBuy() {
        setAutoBuy(!autoBuy);
    }

    public static void setAutoBuy(boolean enabled) {
        autoBuy = enabled;
        CONFIG.autoBuy = enabled;
        CONFIG.save();
        if (!enabled) {
            synchronized (AUTO_QUEUE) {
                AUTO_QUEUE.clear();
            }
            releaseAhPriorityIfIdle();
        }
    }

    public static boolean isDebugAnalyzeOn() {
        return debugAnalyze;
    }

    public static void toggleDebugAnalyze() {
        setDebugAnalyze(!debugAnalyze);
    }

    public static void setDebugAnalyze(boolean enabled) {
        debugAnalyze = enabled;
        CONFIG.debugAnalyze = enabled;
        CONFIG.save();
        if (!enabled) {
            synchronized (DEBUG_QUEUE) {
                DEBUG_QUEUE.clear();
            }
        }
    }

    public static boolean isTooltipValueOn() {
        return CONFIG.tooltipValue;
    }

    public static void toggleTooltipValue() {
        CONFIG.tooltipValue = !CONFIG.tooltipValue;
        CONFIG.save();
    }

    public static boolean isTooltipDetailsOn() {
        return CONFIG.tooltipDetails;
    }

    public static void toggleTooltipDetails() {
        CONFIG.tooltipDetails = !CONFIG.tooltipDetails;
        CONFIG.save();
    }

    static String ownPlayerName() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) {
            return "";
        }
        String name = client.getUser().getName();
        return name == null ? "" : name;
    }

    static String ownPlayerId() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null
                || client.getUser().getProfileId() == null) {
            return "";
        }
        return client.getUser().getProfileId().toString().replace("-", "");
    }

    static boolean isOwnPlayer(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String own = ownPlayerName();
        return !own.isBlank() && own.equalsIgnoreCase(name.trim());
    }

    static boolean isOwnSeller(String seller, String playerName) {
        if (isOwnPlayer(playerName) || isOwnPlayer(seller)) {
            return true;
        }
        String ownId = ownPlayerId();
        return !ownId.isBlank() && seller != null
                && seller.replace("-", "").equalsIgnoreCase(ownId);
    }

    static void ensureAhPriority() {
        ensureAhPriority(60_000L);
    }

    static void ensureAhPriority(long holdMs) {
        Trader trader = Trader.get();
        if (trader != null && trader.isRunning()) {
            trader.yieldToAhFlipper(holdMs);
        }
    }

    /** A listing that can start right now (not one waiting for a retry or free AH slots). */
    private static boolean hasDueListing() {
        long now = System.currentTimeMillis();
        if (now < ahFullUntil || now < listingBlockedUntil) {
            return false;
        }
        synchronized (LISTING_QUEUE) {
            for (ListingTask task : LISTING_QUEUE) {
                if (task.notBefore() <= now) {
                    return true;
                }
            }
        }
        return false;
    }

    static void releaseAhPriorityIfIdle() {
        boolean idle;
        synchronized (AUTO_QUEUE) {
            idle = AUTO_QUEUE.isEmpty();
        }
        idle = idle && !hasDueListing();
        if (!idle || isBusy() || isBidPriorityActive()
                || AhBidMacro.hasPendingWork()) {
            return;
        }
        Trader trader = Trader.get();
        if (trader != null) {
            trader.releaseAhPriority();
        }
    }

    private static String listingKey(String tag, String name, String excludeUuid) {
        return (tag == null ? "" : tag) + "|" + (name == null ? "" : name)
                + "|" + (excludeUuid == null ? "" : excludeUuid);
    }

    private static boolean recentlyListed(String key) {
        Long at = RECENT_LISTINGS.get(key);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > 10 * 60_000L) {
            RECENT_LISTINGS.remove(key);
            return false;
        }
        return true;
    }

    private static void markListed(String key) {
        RECENT_LISTINGS.put(key, System.currentTimeMillis());
        if (RECENT_LISTINGS.size() > 500) {
            long cutoff = System.currentTimeMillis() - 10 * 60_000L;
            RECENT_LISTINGS.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    private static void enqueueListing(String tag, String name, long value, long boughtPrice,
                                       String excludeUuid) {
        enqueueListing(tag, name, value, boughtPrice, excludeUuid, 0L);
    }

    private static void enqueueListing(String tag, String name, long value, long boughtPrice,
                                       String excludeUuid, long fixedPrice) {
        String key = listingKey(tag, name, excludeUuid);
        if (recentlyListed(key) || ACTIVE_LISTINGS.contains(key)) {
            return;
        }
        synchronized (LISTING_QUEUE) {
            for (ListingTask queued : LISTING_QUEUE) {
                if (listingKey(queued.tag(), queued.name(), queued.excludeUuid()).equals(key)) {
                    return;
                }
            }
            LISTING_QUEUE.add(new ListingTask(tag, name == null ? "" : name, value, boughtPrice,
                    excludeUuid == null ? "" : excludeUuid, fixedPrice,
                    System.currentTimeMillis(), 0, 0L));
            if (LISTING_QUEUE.size() > 50) {
                LISTING_QUEUE.removeFirst();
            }
        }
        if (activeAuctionCount >= AH_MAX_ACTIVE) {
            message("\u00a7eAH full (" + activeAuctionCount + "/" + AH_MAX_ACTIVE + ") - item queued: \u00a7f"
                    + (name == null || name.isBlank() ? tag : name));
        }
        ensureListingWorker();
    }

    /**
     * Hands a purchase whose direct listing failed (e.g. a won bid) to the listing
     * worker, which retries with a delay and keeps it registered until the item
     * is claimed and listed.
     */
    static void enqueueListingRetry(String tag, String name, long value, long boughtPrice,
                                    String excludeUuid) {
        String key = listingKey(tag, name, excludeUuid);
        if (recentlyListed(key) || ACTIVE_LISTINGS.contains(key)) {
            return;
        }
        synchronized (LISTING_QUEUE) {
            for (ListingTask queued : LISTING_QUEUE) {
                if (listingKey(queued.tag(), queued.name(), queued.excludeUuid()).equals(key)) {
                    return;
                }
            }
            LISTING_QUEUE.add(new ListingTask(tag, name == null ? "" : name, value, boughtPrice,
                    excludeUuid == null ? "" : excludeUuid, 0L, System.currentTimeMillis(),
                    1, System.currentTimeMillis() + 20_000L));
        }
        UNCLAIMED_PURCHASES.put(key, new ListingTask(tag, name == null ? "" : name, value,
                boughtPrice, excludeUuid == null ? "" : excludeUuid, 0L,
                System.currentTimeMillis(), 0, 0L));
        ensureListingWorker();
    }

    /**
     * A bid that could not be claimed may simply be lost, so it is not retried, but
     * a later "You claimed" line (claimed by hand, for example) still lists it.
     */
    static void rememberUnclaimed(String tag, String name, long value, long boughtPrice,
                                  String excludeUuid) {
        UNCLAIMED_PURCHASES.put(listingKey(tag, name, excludeUuid),
                new ListingTask(tag, name == null ? "" : name, value, boughtPrice,
                        excludeUuid == null ? "" : excludeUuid, 0L,
                        System.currentTimeMillis(), 0, 0L));
    }

    /** Fixed-price items (lowballing) are listed even while the flipper itself is off. */
    private static boolean hasFixedPriceListing() {
        synchronized (LISTING_QUEUE) {
            for (ListingTask task : LISTING_QUEUE) {
                if (task.fixedPrice() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void ensureListingWorker() {
        synchronized (AhFlipperMod.class) {
            if (listingWorkerRunning || !(running || hasFixedPriceListing())) {
                return;
            }
            listingWorkerRunning = true;
        }
        Thread thread = new Thread(AhFlipperMod::listingWorkerLoop, "skycofl-ah-listing");
        thread.setDaemon(true);
        thread.start();
    }

    private static void listingWorkerLoop() {
        try {
            while (running || hasFixedPriceListing()) {
                if (isBidPriorityActive() || (running && activeAuctionCount >= AH_MAX_ACTIVE)) {
                    ScreenUtil.sleep(500);
                    continue;
                }
                if (!processNextListing()) {
                    ScreenUtil.sleep(250);
                }
            }
        } finally {
            synchronized (AhFlipperMod.class) {
                listingWorkerRunning = false;
            }
            if (running || hasFixedPriceListing()) {
                ensureListingWorker();
            }
        }
    }

    private static void refreshActiveAuctionCountAsync() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || activeCountRefreshRunning) {
            return;
        }
        activeCountRefreshRunning = true;
        lastActiveCountRefreshAt = System.currentTimeMillis();
        String uuid = client.player.getUUID().toString().replace("-", "");
        Thread thread = new Thread(() -> {
            try {
                int count = AhMarketData.activePlayerAuctionCount(uuid);
                if (count >= 0) {
                    activeAuctionCount = count;
                    ensureListingWorker();
                }
            } finally {
                activeCountRefreshRunning = false;
            }
        }, "skycofl-ah-count");
        thread.setDaemon(true);
        thread.start();
    }

    /** Next task that is due; tasks waiting for a retry stay in the queue. */
    private static ListingTask takeListing() {
        long now = System.currentTimeMillis();
        synchronized (LISTING_QUEUE) {
            for (java.util.Iterator<ListingTask> it = LISTING_QUEUE.iterator(); it.hasNext(); ) {
                ListingTask task = it.next();
                if (task.notBefore() <= now && (running || task.fixedPrice() > 0)) {
                    it.remove();
                    return task;
                }
            }
            return null;
        }
    }

    static boolean enqueueLive(String id, long value, long profit) {
        if (!running || !autoBuy) {
            return false;
        }
        ensureAhPriority(15_000L);
        synchronized (AUTO_QUEUE) {
            for (int i = 0; i < AUTO_QUEUE.size(); i++) {
                AutoCandidate candidate = AUTO_QUEUE.get(i);
                if (candidate.id.equals(id)) {
                    if (!candidate.prevalidated()) {
                        AUTO_QUEUE.set(i, new AutoCandidate(id, value, profit, candidate.at(), true));
                    }
                    return true;
                }
            }
            AUTO_QUEUE.add(new AutoCandidate(id, value, profit, System.currentTimeMillis(), true));
        }
        message("\u00a76[Live] \u00a7f" + id + " \u00a77added to buy queue");
        ensureAutoWorker();
        return true;
    }

    static boolean beginBidAction() {
        return beginBidAction(false);
    }

    static boolean beginBidAction(boolean priority) {
        synchronized (AhFlipperMod.class) {
            if (bidLease != null || purchaseInProgress || SOLD_CLAIM_PENDING.get()) {
                return false;
            }
            AutomationGate.Lease lease = AutomationGate.tryAcquire("bid", true);
            if (lease == null) {
                return false;
            }
            bidLease = lease;
        }
        ensureAhPriority(15_000L);
        return true;
    }

    static void requestBidPriority(long durationMs) {
        long duration = Math.max(1000L, durationMs);
        bidPriorityUntil = Math.max(bidPriorityUntil,
                System.currentTimeMillis() + duration);
        ensureAhPriority(duration + 3_000L);
    }

    static void clearBidPriority() {
        bidPriorityUntil = 0;
        releaseAhPriorityIfIdle();
    }

    static boolean isBidPriorityActive() {
        return System.currentTimeMillis() < bidPriorityUntil;
    }

    static void closeGuiForBidPriority() {
        if (isBusy()) {
            return;
        }
        Trader trader = Trader.get();
        if (trader != null && trader.isGuiOwned()) {
            return;
        }
        ScreenUtil.closeCurrentScreen();
    }

    static boolean isPurchaseInProgress() {
        return purchaseInProgress;
    }

    static boolean isBidActionBusy() {
        return AutomationGate.isBidBusy();
    }

    static void endBidAction() {
        AutomationGate.Lease lease;
        synchronized (AhFlipperMod.class) {
            lease = bidLease;
            bidLease = null;
        }
        AutomationGate.release(lease);
        releaseAhPriorityIfIdle();
    }

    static boolean isBusy() {
        return AutomationGate.isBusy();
    }

    /**
     * True while an AH task (buy, claim, listing, bid ...) holds the GUI/automation
     * lock and its owner does not start with {@code ignoreOwnerPrefix}.
     */
    public static boolean automationBusyExcept(String ignoreOwnerPrefix) {
        if (!AutomationGate.isBusy()) {
            return false;
        }
        String owner = AutomationGate.owner();
        return ignoreOwnerPrefix == null || ignoreOwnerPrefix.isBlank()
                || owner == null || !owner.startsWith(ignoreOwnerPrefix);
    }

    static boolean hasPendingAutoBuy() {
        synchronized (AUTO_QUEUE) {
            return !AUTO_QUEUE.isEmpty();
        }
    }

    public static int activeAuctionCount() {
        return activeAuctionCount;
    }

    public static int maxActiveAuctions() {
        return AH_MAX_ACTIVE;
    }

    public static void onFlip(CoflCore.commands.models.FlipData flip) {
        long profit = flip == null ? 0
                : (long) flip.Worth * Math.max(1, CONFIG.coflWorthMultiplier);
        System.out.println("[AH-Flipper] Flip empfangen: id=" + (flip == null ? "null" : flip.Id)
                + " worth=" + (flip == null ? 0 : flip.Worth)
                + " profit=" + profit
                + " running=" + running + " autoBuy=" + autoBuy + " analyse5=" + debugAnalyze);
        if (flip == null || flip.Id == null) {
            return;
        }
        if (debugAnalyze) {
            synchronized (DEBUG_QUEUE) {
                DEBUG_QUEUE.add(new AutoCandidate(flip.Id, profit, profit,
                        System.currentTimeMillis()));
            }
            ensureDebugWorker();
        }
        if (!running) {
            return;
        }
        String messageText = "";
        if (flip.Messages != null) {
            try {
                messageText = flip.getMessageAsString().replace('\n', ' ');
            } catch (Exception ignored) {
            }
        }
        System.out.println("[AH-Flipper] Flip: id=" + flip.Id + " worth=" + flip.Worth + " msg=" + messageText);
        lastFlipId = flip.Id;
        lastFlipWorth = profit;
        lastFlipAt = System.currentTimeMillis();
    }

    private static void ensureAutoWorker() {
        synchronized (AUTO_QUEUE) {
            if (autoWorkerRunning) {
                return;
            }
            autoWorkerRunning = true;
        }
        Thread thread = new Thread(AhFlipperMod::autoWorkerLoop, "skycofl-ah-autobuy");
        thread.setDaemon(true);
        thread.start();
    }

    private static AutoCandidate pickBestCandidate() {
        synchronized (AUTO_QUEUE) {
            long now = System.currentTimeMillis();
            AUTO_QUEUE.removeIf(c -> now - c.at > CONFIG.autoBuyMaxAgeSeconds * 1000L);
            AutoCandidate best = null;
            for (AutoCandidate candidate : AUTO_QUEUE) {
                if (best == null || candidate.at > best.at) {
                    best = candidate;
                }
            }
            if (best != null) {
                AUTO_QUEUE.remove(best);
            }
            return best;
        }
    }

    private static void ensureDebugWorker() {
        synchronized (DEBUG_QUEUE) {
            if (debugWorkerRunning) {
                return;
            }
            debugWorkerRunning = true;
        }
        Thread thread = new Thread(AhFlipperMod::debugWorkerLoop, "skycofl-ah-analyze5");
        thread.setDaemon(true);
        thread.start();
    }

    private static void debugWorkerLoop() {
        try {
            while (debugAnalyze) {
                ScreenUtil.sleep(Math.max(1, CONFIG.autoBuyBatchSeconds) * 1000L);
                java.util.List<AutoCandidate> batch;
                synchronized (DEBUG_QUEUE) {
                    long now = System.currentTimeMillis();
                    DEBUG_QUEUE.removeIf(c -> now - c.at > 60_000L);
                    batch = new java.util.ArrayList<>(DEBUG_QUEUE);
                    DEBUG_QUEUE.clear();
                }
                if (batch.isEmpty()) {
                    continue;
                }
                batch.sort((a, b) -> Long.compare(b.profit, a.profit));
                if (batch.size() > 5) {
                    batch = new java.util.ArrayList<>(batch.subList(0, 5));
                }
                while (isBusy() && debugAnalyze) {
                    ScreenUtil.sleep(50);
                }
                if (!debugAnalyze) {
                    break;
                }
                AutomationGate.Lease lease = AutomationGate.tryAcquire("analyze", false);
                if (lease == null) {
                    synchronized (DEBUG_QUEUE) {
                        DEBUG_QUEUE.addAll(batch);
                    }
                    continue;
                }
                try {
                    int rank = 1;
                    for (AutoCandidate candidate : batch) {
                        if (!debugAnalyze) {
                            break;
                        }
                        analyzeCandidate(candidate, rank);
                        rank++;
                        ScreenUtil.sleep(150);
                    }
                } finally {
                    AutomationGate.release(lease);
                }
            }
        } finally {
            debugWorkerRunning = false;
            boolean pending;
            synchronized (DEBUG_QUEUE) {
                pending = !DEBUG_QUEUE.isEmpty();
            }
            if (pending && debugAnalyze) {
                ensureDebugWorker();
            }
        }
    }

    private static void analyzeCandidate(AutoCandidate candidate, int rank) {
        try {
            System.out.println("[AH-Flipper] Top-" + rank + " Analyse: id=" + candidate.id
                    + " value=" + candidate.value + " profit=" + candidate.profit);
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeAndWait(200);
            }
            sendCommandSilent("/viewauction " + candidate.id);
            if (!waitForTitle(4000, "bin auction view")) {
                System.out.println("[AH-Flipper] Top-" + rank + ": Auktion nicht geöffnet");
                message("\u00a7c#" + rank + " auction did not open: " + candidate.id);
                return;
            }
            if (AhScreenUtil.currentAuctionUnavailable()) {
                System.out.println("[AH-Flipper] Top-" + rank
                        + ": Auktion nicht mehr verfügbar (Collect/Someone else) - schließe sofort");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            long price = waitForBuyPrice(4000);
            if (price <= 0 && AhScreenUtil.currentAuctionUnavailable()) {
                System.out.println("[AH-Flipper] Top-" + rank
                        + ": Auktion nicht mehr verfügbar - schließe sofort");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            AhItem item = AhItem.fromStack(AhScreenUtil.currentItem(13));
            if (item.tag.isBlank()) {
                System.out.println("[AH-Flipper] Top-" + rank + ": kein Item-Tag");
                message("\u00a7c#" + rank + " item tag not detected.");
                AhScreenUtil.dumpScreen();
                ScreenUtil.closeCurrentScreen();
                return;
            }
            AhAnalyzer.Result result = AhAnalyzer.analyze(item, Math.max(price, 0), candidate.profit);
            System.out.println("[AH-Flipper] TOP" + rank + " " + result.shortSummary());
            logAnalysis(result);
            message((result.buyable ? "\u00a7a" : "\u00a7c") + "#" + rank + " " + result.shortSummary());
            for (String reason : result.reasons) {
                message("\u00a7c   - " + reason);
            }
            ScreenUtil.closeAndWait(200);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Top-" + rank + " Fehler: " + e);
            message("\u00a7c#" + rank + " analysis failed: " + e.getMessage());
        }
    }

    private static void autoWorkerLoop() {
        try {
            while (running && autoBuy) {
                if (SOLD_CLAIM_PENDING.get() || isBidPriorityActive()) {
                    ScreenUtil.sleep(100);
                    continue;
                }
                AutoCandidate best = pickBestCandidate();
                if (best == null) {
                    ScreenUtil.sleep(10);
                    continue;
                }
                while (isBusy() && running) {
                    ScreenUtil.sleep(10);
                }
                if (!running || !autoBuy) {
                    break;
                }
                AutomationGate.Lease lease = AutomationGate.tryAcquire("auto-buy", false);
                if (lease == null) {
                    synchronized (AUTO_QUEUE) {
                        AUTO_QUEUE.add(best);
                    }
                    ScreenUtil.sleep(25);
                    continue;
                }
                System.out.println("[AH-Flipper] Auto-Buy Kandidat: id=" + best.id
                        + " value=" + best.value + " profit=" + best.profit + " alter="
                        + (System.currentTimeMillis() - best.at) / 1000 + "s");
                lastBuyOk = false;
                try {
                    buyFlow(best.id, best.profit, true, false, best.prevalidated(), best.value);
                } finally {
                    AutomationGate.release(lease);
                }
            }
        } finally {
            autoWorkerRunning = false;
            boolean pending;
            synchronized (AUTO_QUEUE) {
                pending = !AUTO_QUEUE.isEmpty();
            }
            if (pending && running && autoBuy) {
                ensureAutoWorker();
            }
        }
    }

    private static boolean processNextListing() {
        long now = System.currentTimeMillis();
        if (isBidPriorityActive() || (running && activeAuctionCount >= AH_MAX_ACTIVE)
                || now < ahFullUntil || now < listingBlockedUntil) {
            return false;
        }
        ListingTask task = takeListing();
        if (task == null) {
            return false;
        }
        if (!CONFIG.autoList && task.fixedPrice() <= 0) {
            // Auto-list is off for now: keep the purchase, it may be turned on again.
            requeueWaiting(task, LISTING_WAIT_MS);
            return true;
        }
        AutomationGate.Lease lease = AutomationGate.tryAcquire("listing", false);
        if (lease == null) {
            synchronized (LISTING_QUEUE) {
                LISTING_QUEUE.addFirst(task);
            }
            return false;
        }
        ensureAhPriority();
        String key = listingKey(task.tag, task.name, task.excludeUuid);
        ACTIVE_LISTINGS.add(key);
        ListResult result = ListResult.FAILED;
        boolean freedSlots = false;
        try {
            result = listPurchasedResult(task.tag, task.name, task.value,
                    task.boughtPrice, task.excludeUuid, task.fixedPrice, task.fixedPrice <= 0);
            if (result == ListResult.AH_FULL) {
                // Ended auctions block AH slots until they are collected. Expired
                // items come back to the inventory, so they need room there.
                freedSlots = claimEndedAuctionsBlocking(ScreenUtil.freeInventorySlots() >= 2) > 0;
                if (freedSlots) {
                    ahFullUntil = 0;
                    message("§aAuction House had no free slot - collected ended auctions.");
                }
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Listing-Fehler fuer " + task.tag + ": " + e);
        } finally {
            ACTIVE_LISTINGS.remove(key);
            if (result == ListResult.LISTED) {
                markListed(key);
                UNCLAIMED_PURCHASES.remove(key);
            }
            AutomationGate.release(lease);
            releaseAhPriorityIfIdle();
        }
        switch (result) {
            case NO_ITEM, FAILED -> scheduleListingRetry(key, task, result);
            case AH_FULL -> requeueWaiting(task, freedSlots ? 1000L : LISTING_WAIT_MS);
            case NO_SPACE -> requeueWaiting(task, LISTING_WAIT_MS);
            case GONE -> {
                UNCLAIMED_PURCHASES.remove(key);
                message("§eItem went to another buyer - nothing to list: §f"
                        + displayOrTag(task));
            }
            default -> {
            }
        }
        return true;
    }

    /** Back into the queue without using up an attempt, within the maximum age. */
    private static void requeueWaiting(ListingTask task, long delayMs) {
        if (System.currentTimeMillis() - task.at() > LISTING_TASK_MAX_AGE_MS) {
            System.out.println("[AH-Flipper] Listing zu alt, verworfen: " + task.tag());
            message("§cListing gave up after waiting too long: §f" + displayOrTag(task));
            return;
        }
        synchronized (LISTING_QUEUE) {
            LISTING_QUEUE.add(task.waitLater(delayMs));
        }
    }

    /**
     * A purchase is never dropped silently: missing items (not claimed yet) and
     * failed GUI flows are retried with a growing delay. After the last retry the
     * purchase stays in {@link #UNCLAIMED_PURCHASES} so a later claim still lists it.
     */
    private static void scheduleListingRetry(String key, ListingTask task, ListResult result) {
        if (task.attempts() + 1 >= MAX_LISTING_RETRIES) {
            UNCLAIMED_PURCHASES.put(key, task);
            if (result == ListResult.NO_ITEM) {
                System.out.println("[AH-Flipper] Listing aufgegeben (Item nicht im Inventar/AH): "
                        + task.tag + " - wird gelistet, sobald es geclaimt wird.");
                message("§cItem not received: §f" + displayOrTag(task)
                        + " §7- will be listed as soon as it is claimed.");
            } else {
                System.out.println("[AH-Flipper] Listing nach " + (task.attempts() + 1)
                        + " Durchlaeufen aufgegeben: " + task.tag);
                message("§cListing gave up: §f" + displayOrTag(task));
            }
            return;
        }
        long delay = 30_000L << task.attempts();
        ListingTask retry = task.retryLater(delay);
        synchronized (LISTING_QUEUE) {
            LISTING_QUEUE.add(retry);
        }
        if (result == ListResult.NO_ITEM) {
            UNCLAIMED_PURCHASES.put(key, task);
        }
        System.out.println("[AH-Flipper] Listing-Retry " + (retry.attempts()) + "/"
                + (MAX_LISTING_RETRIES - 1) + " in " + (delay / 1000) + "s fuer " + task.tag
                + " (" + result + ")");
    }

    private static String displayOrTag(ListingTask task) {
        return task.name() == null || task.name().isBlank() ? task.tag() : task.name();
    }

    static void listPurchased(String tag, String name, long value, long boughtPrice) {
        listPurchased(tag, name, value, boughtPrice, "");
    }

    static boolean listPurchased(String tag, String name, long value, long boughtPrice,
                                 String excludeUuid) {
        return listPurchased(tag, name, value, boughtPrice, excludeUuid, 0L);
    }

    /**
     * Lists an item bought by the lowballing bot. Paid items must not get lost: if
     * this does not work right now, the listing worker keeps trying in the background.
     *
     * @return false when the item is not on the AH yet (it stays queued)
     */
    public static boolean listLowballedFixed(String tag, String name, long price) {
        if (tag == null || tag.isBlank() || price <= 0) {
            return false;
        }
        AutomationGate.Lease lease = AutomationGate.acquire("lowball-list", false, 30_000L);
        ListResult result = ListResult.FAILED;
        if (lease != null) {
            ensureAhPriority();
            try {
                result = listPurchasedResult(tag, name, price, 0, "", price, false);
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Lowball-Listing Fehler: " + e);
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        }
        if (result == ListResult.LISTED) {
            return true;
        }
        System.out.println("[AH-Flipper] Lowball-Listing " + result
                + " - bleibt in der Warteschlange: " + tag);
        enqueueListing(tag, name, price, 0, "lowball-" + System.nanoTime(), price);
        return false;
    }

    private static boolean listPurchased(String tag, String name, long value, long boughtPrice,
                                         String excludeUuid, long fixedPrice) {
        return listPurchasedResult(tag, name, value, boughtPrice, excludeUuid, fixedPrice,
                fixedPrice <= 0) == ListResult.LISTED;
    }

    /**
     * Makes sure the item can be listed: in the inventory, or still in the auction
     * slot from an earlier attempt (an item there is NOT in the inventory, which
     * used to send the bot looking for it in Manage Bids for minutes). A purchase
     * is not always claimed automatically by Hypixel ("Visit the Auction House to
     * collect your item!"); then it is claimed via Manage Bids.
     *
     * @return LISTED when the item is available, otherwise the reason it is not
     */
    private static ListResult ensureItemAvailable(String tag, String name, boolean mayClaim) {
        // With the collect hint the bought copy cannot be here yet: another copy
        // with the same id in the inventory must not be mistaken for it.
        boolean collectHint = hasCollectHint(name);
        if (!collectHint) {
            if (waitForInventoryItem(tag, name, 4_000)) {
                return ListResult.LISTED;
            }
            ListResult menu = openCreateMenu();
            if (menu == ListResult.AH_FULL) {
                return menu;
            }
            if (menu == ListResult.LISTED
                    && createSlotHolds(waitForCreateSlot(2_000), tag, name)) {
                System.out.println("[AH-Flipper] Listing: " + tag + " liegt noch im Auktions-Slot.");
                return ListResult.LISTED;
            }
            ScreenUtil.closeAndWait(250);
        }
        if (!mayClaim) {
            System.out.println("[AH-Flipper] Listing: " + tag + " nicht im Inventar"
                    + " (kein Claim erlaubt).");
            return ListResult.NO_ITEM;
        }
        System.out.println("[AH-Flipper] Listing: " + tag + " (" + name + ") nicht im Inventar"
                + (collectHint ? " - Hypixel hat nicht auto-geclaimt" : "")
                + " - claime ueber Manage Bids.");
        int before = AhScreenUtil.countInventoryMatches(tag, name);
        ClaimResult claim = claimItem(tag, name, before);
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
            ScreenUtil.closeAndWait(250);
        }
        switch (claim) {
            case CLAIMED -> {
                COLLECT_HINTS.remove(normalizeItemName(name));
                System.out.println("[AH-Flipper] Listing: Item geclaimt: " + tag);
                return ListResult.LISTED;
            }
            case LOST -> {
                COLLECT_HINTS.remove(normalizeItemName(name));
                return ListResult.GONE;
            }
            case NO_SPACE -> {
                warnListing("§cInventory full - cannot claim: §f"
                        + (name == null || name.isBlank() ? tag : name));
                return ListResult.NO_SPACE;
            }
            default -> {
                System.out.println("[AH-Flipper] Listing: Claim fuer " + tag + " fehlgeschlagen ("
                        + claim + ").");
                return ListResult.NO_ITEM;
            }
        }
    }

    /** Status messages of a waiting listing, at most one every 30 s. */
    private static void warnListing(String text) {
        long now = System.currentTimeMillis();
        if (now - lastListingWarnAt >= LISTING_WAIT_MS) {
            lastListingWarnAt = now;
            message(text);
        }
    }

    private static ListResult listPurchasedResult(String tag, String name, long value,
                                                  long boughtPrice, String excludeUuid,
                                                  long fixedPrice, boolean mayClaim) {
        ListResult available = ensureItemAvailable(tag, name, mayClaim);
        if (available != ListResult.LISTED) {
            closeOpenScreens();
            return available;
        }
        long price = fixedPrice > 0
                ? fixedPrice
                : listingPrice(tag, name, value, boughtPrice, excludeUuid);
        if (price <= 0) {
            message("§cNo safe listing price - not listing: " + tag);
            closeOpenScreens();
            return ListResult.NO_PRICE;
        }
        ListResult result = ListResult.FAILED;
        for (int attempt = 1; attempt <= 2 && result == ListResult.FAILED; attempt++) {
            if (attempt > 1) {
                ScreenUtil.sleep(800);
            }
            result = listFlow(tag, name, price);
            if (result == ListResult.FAILED) {
                System.out.println("[AH-Flipper] Listing-Versuch " + attempt + "/2 fehlgeschlagen: "
                        + tag);
            }
        }
        if (result == ListResult.LISTED) {
            markListed(listingKey(tag, name, excludeUuid));
        } else if (result == ListResult.FAILED) {
            message("§cListing failed after 2 attempts: " + tag);
        }
        return result;
    }

    private static long listingPrice(String tag, String name, long value, long boughtPrice,
                                     String excludeUuid) {
        AhItem item = AhScreenUtil.findInventoryItem(tag, name);
        if (item != null && !AhItem.sameTag(item.tag, tag)) {
            item = null;
        }
        AhValuation.Estimate estimate = AhValuation.estimate(item,
                excludeUuid == null ? "" : excludeUuid);
        double feeFraction = AhFlipperMod.CONFIG.ahFeePercent / 100.0;
        long minNetPrice = boughtPrice > 0
                ? Math.round(boughtPrice / Math.max(0.01, 1.0 - feeFraction)) : 0;
        long fresh = estimate.estimated;
        long expected;
        if (value > 0) {
            expected = fresh > 1 ? Math.min(fresh, value) : value;
        } else {
            expected = fresh;
        }
        if (minNetPrice > 0 && expected < minNetPrice) {
            // Used to return 0 here ("Listing verworfen: Wert < Kaufpreis-Netto"),
            // which left bought/claimed items in the inventory forever. A fresh
            // estimate below cost is mostly market noise right after the buy, so
            // list at break-even (purchase price + AH fee) instead: no loss, and
            // the item is on the AH instead of rotting in the inventory.
            System.out.println("[AH-Flipper] Listenpreis " + tag + ": Schaetzung=" + expected
                    + " < Kaufpreis-Netto=" + minNetPrice + " (Kauf " + boughtPrice
                    + ", Frisch=" + fresh + ", Kaufwert=" + value
                    + ") -> liste zum Break-even statt gar nicht");
            message("§eValue below cost - listing at break-even: §f"
                    + (name == null || name.isBlank() ? tag : name) + " §7@ §f"
                    + ScreenUtil.formatCoins(minNetPrice));
            expected = minNetPrice;
        }
        System.out.println("[AH-Flipper] Listenpreis " + tag + ": " + estimate.source
                + " Ref=" + estimate.reference + " Addons=" + estimate.addonValue
                + " AddonsAsk=" + estimate.addonValueAsk
                + " Gems=" + estimate.gemValue
                + " Slots=" + estimate.slotUnlockValue
                + " Comparable=" + estimate.comparableAverage + " ("
                + estimate.comparableSales + " Sales, "
                + estimate.comparableExcluded + " Ausrei\u00dfer) Bin="
                + estimate.comparableBin + " AskSafety="
                + estimate.askSafetyValue
                + " Ceiling=" + estimate.ceiling
                + " Frisch=" + fresh + " Kaufwert=" + value + " Netto=" + minNetPrice
                + " -> " + expected
                + (boughtPrice > 0 ? " (Kauf " + boughtPrice + ")" : ""));
        if (expected <= 1) {
            System.out.println("[AH-Flipper] Kein belastbarer Listenpreis fuer " + tag
                    + ": " + estimate.source);
            return 0;
        }
        return expected;
    }

    private static synchronized void startBuy(String id, long expectedProfit, boolean auto) {
        if (id == null || id.isEmpty()) {
            message("§cKeine Auktions-ID.");
            return;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("buy", false);
            if (lease == null) {
                if (!auto) {
                    message("§cEs läuft bereits ein Kauf.");
                }
                return;
            }
            try {
                buyFlow(id, expectedProfit, auto);
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        }, "skycofl-ah-buy");
        thread.setDaemon(true);
        thread.start();
    }

    private static void buyFlow(String id, long expectedProfit, boolean auto) {
        buyFlow(id, expectedProfit, auto, false, false, 0);
    }

    private static void buyFlow(String id, long expectedProfit, boolean auto, boolean alreadyOpen) {
        buyFlow(id, expectedProfit, auto, alreadyOpen, false, 0);
    }

    private static void buyFlow(String id, long expectedProfit, boolean auto, boolean alreadyOpen,
                                boolean prevalidated, long expectedValue) {
        ensureAhPriority();
        purchaseInProgress = true;
        boolean purchaseAttempted = false;
        boolean purchaseSucceeded = false;
        String recoveryTag = "";
        String recoveryName = "";
        long recoveryValue = 0;
        long recoveryPrice = 0;
        long confirmAt = 0;
        try {
            System.out.println("[AH-Flipper] buyFlow: id=" + id + " erwartet=" + expectedProfit
                    + " auto=" + auto + " offen=" + alreadyOpen);
            long settle = prevalidated ? 100 : (auto ? 150 : 200);
            if (!alreadyOpen) {
                Trader trader = Trader.get();
                if (trader != null) {
                    trader.closeBazaarGui();
                }
                AhScreenUtil.AhScreen open = AhScreenUtil.currentScreen();
                if (open != AhScreenUtil.AhScreen.NONE || AhScreenUtil.isContainerOpen()) {
                    ScreenUtil.closeAndWait(settle);
                } else {
                    ScreenUtil.sleep(25);
                }
            }
            long purseBefore = ScreenUtil.readPurse();
            if (!alreadyOpen) {
                sendCommandSilent("/viewauction " + id);
                if (!waitForTitle(4000, "bin auction view")) {
                    System.out.println("[AH-Flipper] BUYABLE-Kauf abgebrochen (Auktion evtl. vorbei): id="
                            + id + " erwartet=" + expectedProfit);
                    message((auto ? "\u00a7eAuto-Buy: " : "\u00a7c") + "auction did not open: " + id);
                    return;
                }
            } else if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.BIN_VIEW) {
                message("\u00a7cNo BIN auction open.");
                return;
            }
            System.out.println("[AH-Flipper] BIN-View offen, lese Preis");
            if (AhScreenUtil.isOwnAuctionCurrent()) {
                message("\u00a7cOwn auction - skipped.");
                return;
            }
            if (AhScreenUtil.currentAuctionUnavailable()) {
                System.out.println("[AH-Flipper] Auktion nicht mehr verfügbar (Collect/Someone else) - schließe sofort: "
                        + id);
                ScreenUtil.closeCurrentScreen();
                return;
            }
            long price = waitForBuyPrice(auto || prevalidated ? 3000 : 1500);
            if (price <= 0) {
                price = AhScreenUtil.currentPrice();
            }
            if (price <= 0) {
                if (AhScreenUtil.currentAuctionUnavailable()) {
                    System.out.println("[AH-Flipper] Auktion nicht mehr verfügbar - schließe sofort: " + id);
                    ScreenUtil.closeCurrentScreen();
                    return;
                }
                System.out.println("[AH-Flipper] Kein Preis erkannt");
                message("\u00a7cNo price detected - skipped.");
                AhScreenUtil.dumpScreen();
                return;
            }
            System.out.println("[AH-Flipper] Preis=" + price);
            long bankroll = purseBefore > 0 ? purseBefore - CONFIG.reserveCoins : -1;
            if (bankroll > 0 && CONFIG.maxBankrollCoins > 0) {
                bankroll = Math.min(bankroll, CONFIG.maxBankrollCoins);
            }
            long maxPrice = bankroll > 0
                    ? Math.round(bankroll * CONFIG.maxBankFractionPerOrder) : -1;
            if (maxPrice > 0 && price > maxPrice) {
                System.out.println("[AH-Flipper] Bankroll-Gate: Preis=" + price
                        + " max=" + maxPrice + " Bankroll=" + bankroll);
                message("\u00a7cPrice above " + Math.round(CONFIG.maxBankFractionPerOrder * 100) + "% of bankroll (\u00a7f" + ScreenUtil.formatCoins(maxPrice) + "\u00a7c) - skipped.");
                return;
            }
            AhItem item = AhItem.resolveCofl(AhScreenUtil.currentItem(13), 4000);
            if (item.tag.isBlank()) {
                message("\u00a7cItem tag not detected - cancelled.");
                AhScreenUtil.dumpScreen();
                return;
            }
            if (item.isPet() && !CONFIG.petsEnabled) {
                message("\u00a7cPets are disabled in AH-Flipper.");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            if (item.coflValue() <= 0) {
                System.out.println("[AH-Flipper] Market-Median/LBin nicht geladen: " + item.tag);
                message("\u00a7cMarket tooltip value not loaded - no purchase.");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            System.out.println("[AH-Flipper] Cofl-Wert am Item: " + item.coflValue()
                    + " (" + item.coflPick + ", Med=" + item.coflMed
                    + ", LBin=" + item.coflLbin
                    + ", LBinCount=" + item.coflLbinCount
                    + ", min=" + CONFIG.coflLbinMinCount + ")");
            AhValuation.Estimate tooltip = AhValuation.estimate(item, "");
            long resale = tooltip.estimated;
            if (resale <= 1) {
                message("\u00a7cTooltip returned no value - hover the item.");
                System.out.println("[AH-Flipper] Tooltip-Wert fehlt für "
                        + item.tag + ": " + tooltip.source);
                ScreenUtil.closeCurrentScreen();
                return;
            }
            double feeFraction = CONFIG.ahFeePercent / 100.0;
            double minProfit = Math.max(10.0, CONFIG.minProfitPercent);
            long net = Math.round(resale * (1.0 - feeFraction));
            long profit = net - price;
            double profitPercent = price > 0 ? profit * 100.0 / price : -1;
            message("\u00a77Tooltip value: \u00a7f" + ScreenUtil.formatCoins(resale) + " \u00a77\u00b7 Buy: \u00a7f" + ScreenUtil.formatCoins(price) + " \u00a77\u00b7 Profit: \u00a7f" + String.format(java.util.Locale.US, "%.1f%%", profitPercent));
            if (profitPercent < minProfit) {
                message("\u00a7c- Profit " + String.format(java.util.Locale.US, "%.1f%%", profitPercent) + " (< " + String.format(java.util.Locale.US, "%.0f%%", minProfit) + ")");
                message("\u00a7cNo purchase.");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            if (expectedProfit > 0 && profit < expectedProfit * 0.5) {
                message("\u00a7cProfit " + ScreenUtil.formatCoins(profit) + " is far below scanner expectation " + ScreenUtil.formatCoins(expectedProfit) + " - no purchase.");
                System.out.println("[AH-Flipper] Kauf verworfen (Scanner-Erwartung): id="
                        + id + " profit=" + profit + " expected=" + expectedProfit);
                ScreenUtil.closeCurrentScreen();
                return;
            }
            recoveryTag = item.tag;
            recoveryName = item.displayName;
            recoveryValue = resale;
            recoveryPrice = price;
            PriorityAccess.applyDelay();
            if (!spamBuyButton()) {
                // The buy button never got through, so nothing can have been
                // bought: no claim/recovery run afterwards.
                System.out.println("[AH-Flipper] Kaufbutton nicht aktiv");
                message("\u00a7cBuy button not active - cancelled.");
                AhScreenUtil.dumpScreen();
                ScreenUtil.closeCurrentScreen();
                return;
            }
            long confirmPrice = AhScreenUtil.currentPriceAt(11);
            if (confirmPrice <= 0) {
                confirmPrice = AhScreenUtil.currentPrice();
            }
            if (confirmPrice > 0 && confirmPrice != price) {
                long confirmProfit = Math.round(resale * (1.0 - feeFraction)) - confirmPrice;
                double confirmPercent = confirmProfit * 100.0 / confirmPrice;
                System.out.println("[AH-Flipper] Confirm-Preis " + confirmPrice
                        + " weicht ab (Screen " + price + ") -> "
                        + String.format(java.util.Locale.US, "%.1f%%", confirmPercent));
                if (confirmPercent < minProfit
                        || (expectedProfit > 0 && confirmProfit < expectedProfit * 0.5)) {
                    message("\u00a7cConfirm price " + ScreenUtil.formatCoins(confirmPrice) + " makes the purchase unprofitable - cancelled.");
                    ScreenUtil.closeCurrentScreen();
                    return;
                }
                price = confirmPrice;
                profit = confirmProfit;
                profitPercent = confirmPercent;
                recoveryPrice = price;
            }
            // From here on a purchase may really happen (confirm clicked), so a
            // failure has to be followed by a look at what Hypixel answered.
            purchaseAttempted = true;
            confirmAt = System.currentTimeMillis();
            if (!spamConfirmButton()) {
                System.out.println("[AH-Flipper] Confirm Purchase nicht bestätigt");
                message("§cConfirmation did not go through.");
                AhScreenUtil.dumpScreen();
                return;
            }
            // The confirm menu closing proves nothing: when someone else was faster
            // it closes too ("Escrow refunded"). Treating that as a purchase made
            // the bot look for an item that never existed, over and over.
            BuyOutcome outcome = waitForBuyOutcome(confirmAt, 4_000L);
            purchaseSucceeded = true;
            if (outcome == BuyOutcome.FAILED) {
                System.out.println("[AH-Flipper] Kauf nicht zustande gekommen: id=" + id);
                message("§eToo late - auction is gone, coins refunded: §f"
                        + item.displayName);
                ScreenUtil.closeCurrentScreen();
                return;
            }
            if (outcome == BuyOutcome.UNKNOWN) {
                System.out.println("[AH-Flipper] Keine Kauf-Antwort im Chat: id=" + id);
                unconfirmedPurchaseKey = listingKey(item.tag, item.displayName, id);
                unconfirmedPurchaseAt = System.currentTimeMillis();
            } else {
                unconfirmedPurchaseKey = "";
            }
            lastBuyOk = true;
            lastBoughtTag = item.tag;
            lastBoughtName = item.displayName;
            lastBoughtValue = resale;
            AhFlipperConfig.blockTemporarily(item.tag, item.displayName);
            ScreenUtil.sleep(prevalidated || auto ? 50 : 100);
            long purseAfter = ScreenUtil.readPurse();
            System.out.println("[AH-Flipper] Kauf ausgelöst: Purse " + purseBefore + " -> " + purseAfter);
            if (purseBefore > 0 && purseAfter > 0 && purseAfter < purseBefore) {
                long purseDiff = purseBefore - purseAfter;
                boolean plausible = price <= 0
                        || (purseDiff <= price * 1.5 && purseDiff >= price * 0.5);
                if (plausible) {
                    message("\u00a7aPurchased! \u00a77Paid: \u00a7f" + ScreenUtil.formatCoins(purseDiff) + (profit > 0 ? " \u00a77>> Expected profit: \u00a7f" + ScreenUtil.formatCoins(profit) : ""));
                } else {
                    message("\u00a7aPurchased! \u00a77Screen price: \u00a7f" + ScreenUtil.formatCoins(price) + " \u00a77(purse diff \u00a7f" + ScreenUtil.formatCoins(purseDiff) + "\u00a77 differs - Bazaar escrow?)" + (profit > 0 ? " \u00a77>> Expected profit: \u00a7f" + ScreenUtil.formatCoins(profit) : ""));
                }
            } else {
                message("\u00a7ePurchase triggered - purse: " + (purseBefore > 0 ? ScreenUtil.formatCoins(purseBefore) : "?") + " \u2192 " + (purseAfter > 0 ? ScreenUtil.formatCoins(purseAfter) : "?"));
            }
            ScreenUtil.closeCurrentScreen();
            if (CONFIG.autoList && resale > 0) {
                enqueueListing(item.tag, item.displayName, resale, price, id);
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] buyFlow Fehler: " + e);
            message("\u00a7cPurchase failed: " + e.getMessage());
        } finally {
            if (purchaseAttempted && !purchaseSucceeded) {
                // The confirm menu never closed or the flow crashed: only the chat
                // can tell whether the purchase happened.
                String key = listingKey(recoveryTag, recoveryName, id);
                if (lastPurchasedChatAt >= confirmAt) {
                    if (CONFIG.autoList && recoveryValue > 0) {
                        enqueueListing(recoveryTag, recoveryName, recoveryValue, recoveryPrice, id);
                    }
                } else if (recoveryValue > 0) {
                    rememberUnclaimed(recoveryTag, recoveryName, recoveryValue, recoveryPrice, id);
                    unconfirmedPurchaseKey = key;
                    unconfirmedPurchaseAt = System.currentTimeMillis();
                }
                ScreenUtil.closeCurrentScreen();
            }
            purchaseInProgress = false;
            releaseAhPriorityIfIdle();
        }
    }

    /**
     * What Hypixel answered to the confirm click. "You purchased" always wins: the
     * repeated clicks make the error/refund lines appear right after a real
     * purchase, so an error is only believed if no purchase line follows shortly.
     */
    private static BuyOutcome waitForBuyOutcome(long confirmAt, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        long failedSeenAt = 0;
        while (System.currentTimeMillis() < end) {
            if (lastPurchasedChatAt >= confirmAt) {
                return BuyOutcome.BOUGHT;
            }
            if (purchaseFailedChatAt >= confirmAt) {
                long now = System.currentTimeMillis();
                if (failedSeenAt == 0) {
                    failedSeenAt = now;
                } else if (now - failedSeenAt >= 1_000L) {
                    return BuyOutcome.FAILED;
                }
            }
            ScreenUtil.sleep(20);
        }
        return lastPurchasedChatAt >= confirmAt ? BuyOutcome.BOUGHT : BuyOutcome.UNKNOWN;
    }

    private static int sellCommand(CommandContext<FabricClientCommandSource> context) {
        long price;
        try {
            price = Math.round(DoubleArgumentType.getDouble(context, "preis"));
        } catch (IllegalArgumentException e) {
            price = lastBoughtValue;
        }
        String tag = lastBoughtTag;
        String name = lastBoughtName;
        if (tag == null || tag.isBlank()) {
            AhItem current = AhItem.fromStack(AhScreenUtil.currentItem(13));
            if (!current.tag.isBlank()) {
                tag = current.tag;
                name = current.displayName;
            }
        }
        if (tag == null || tag.isBlank()) {
            message("§cKein Item bekannt – erst kaufen oder BIN-View öffnen.");
            return 0;
        }
        if (price <= 0) {
            message("§cKein Preis – nutze /ahflip sell <preis>.");
            return 0;
        }
        if (isBusy()) {
            message("§cEs läuft bereits ein Kauf/Listing.");
            return 0;
        }
        String finalTag = tag;
        String finalName = name;
        long finalPrice = price;
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("sell", false);
            if (lease == null) {
                message("§cEs läuft bereits eine AH-Aktion.");
                return;
            }
            ensureAhPriority();
            try {
                listFlow(finalTag, finalName, finalPrice);
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        }, "skycofl-ah-list");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    /**
     * One complete listing. Every step checks the menu instead of assuming the
     * previous one worked, and the result of the last click is only believed when
     * Hypixel confirms it in chat.
     */
    private static ListResult listFlow(String tag, String displayName, long price) {
        SignInput.clear();
        try {
            System.out.println("[AH-Flipper] listFlow: tag=" + tag + " name=" + displayName
                    + " preis=" + price);
            ListResult menu = openCreateMenu();
            if (menu != ListResult.LISTED) {
                return menu;
            }
            ListResult slot = prepareCreateSlot(tag, displayName);
            if (slot != ListResult.LISTED) {
                return slot;
            }
            if (!selectDuration(0L)) {
                message("§cDuration-Auswahl fehlgeschlagen.");
                AhScreenUtil.dumpScreen();
                return ListResult.FAILED;
            }
            if (!setCreatePrice(price)) {
                message("§cListenpreis konnte nicht gesetzt werden - nicht gelistet.");
                AhScreenUtil.dumpScreen();
                return ListResult.FAILED;
            }
            // Duration and price re-open the menu: check once more what gets sold.
            AhScreenUtil.CreateSlot held = waitForCreateSlot(2_000);
            if (held == null || held.empty()
                    || (tag != null && !tag.isBlank() && !AhItem.sameTag(tag, held.tag()))) {
                message("§cFalsches Item im Auktions-Slot - nicht gelistet.");
                AhScreenUtil.dumpScreen();
                return ListResult.FAILED;
            }
            int create = waitForButton(2_000, "create bin auction");
            if (create < 0) {
                message("§c'Create BIN Auction' nicht gefunden.");
                AhScreenUtil.dumpScreen();
                return ListResult.FAILED;
            }
            ScreenUtil.clickSlot(create, 0);
            if (!waitForScreenUntil(0L, 4_000, AhScreenUtil.AhScreen.CONFIRM_LISTING)) {
                message("§c'Confirm Auction' nicht erreicht.");
                AhScreenUtil.dumpScreen();
                return ListResult.FAILED;
            }
            return confirmListing(tag, displayName, price);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] listFlow Fehler: " + e);
            message("§cListing fehlgeschlagen: " + e.getMessage());
            return ListResult.FAILED;
        } finally {
            SignInput.clear();
            closeOpenScreens();
        }
    }

    /**
     * Opens "Create BIN Auction" via /ah. LISTED = the menu is open, AH_FULL =
     * Hypixel refuses new auctions right now, FAILED = a menu did not open.
     */
    private static ListResult openCreateMenu() {
        AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
        if (screen != AhScreenUtil.AhScreen.CREATE_BIN) {
            if (screen != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeAndWait(250);
            }
            sendCommandSilent("/ah");
            if (!waitForScreenUntil(0L, 4_000, AhScreenUtil.AhScreen.AH_MAIN,
                    AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                    AhScreenUtil.AhScreen.MANAGE_ORDERS,
                    AhScreenUtil.AhScreen.CREATE_BIN,
                    AhScreenUtil.AhScreen.OTHER)) {
                message("§cAH-Menü nicht geöffnet.");
                return ListResult.FAILED;
            }
            screen = AhScreenUtil.currentScreen();
            if (screen == AhScreenUtil.AhScreen.AH_MAIN
                    || screen == AhScreenUtil.AhScreen.OTHER) {
                AhScreenUtil.AuctionsEntry entry = waitForAuctionsEntry(4_000);
                if (entry.slot() < 0) {
                    message("§c'Manage Auctions' nicht gefunden.");
                    AhScreenUtil.dumpScreen();
                    return ListResult.FAILED;
                }
                ScreenUtil.clickSlot(entry.slot(), 0);
                if (!waitForScreenUntil(0L, 3_500,
                        AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                        AhScreenUtil.AhScreen.MANAGE_ORDERS,
                        AhScreenUtil.AhScreen.CREATE_BIN)) {
                    message("§c'Manage Auctions' nicht erreicht.");
                    AhScreenUtil.dumpScreen();
                    return ListResult.FAILED;
                }
                screen = AhScreenUtil.currentScreen();
            }
            if (screen == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                    || screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
                int create = waitForCreateAuctionButton(2_500);
                if (create < 0) {
                    message("§c'Create Auction' nicht gefunden.");
                    AhScreenUtil.dumpScreen();
                    return ListResult.FAILED;
                }
                if (AhScreenUtil.currentSlotHasText(create, "reached the maximum")) {
                    ahFullUntil = System.currentTimeMillis() + 60_000L;
                    activeAuctionCount = Math.max(activeAuctionCount, AH_MAX_ACTIVE);
                    System.out.println("[AH-Flipper] Hypixel: maximale Anzahl Auktionen erreicht.");
                    warnListing("§eAuction House full - listing waits for a free slot.");
                    return ListResult.AH_FULL;
                }
                ScreenUtil.clickSlot(create, 0);
                if (!waitForScreenUntil(0L, 4_000, AhScreenUtil.AhScreen.CREATE_BIN)) {
                    message("§c'Create Auction' nicht erreicht.");
                    AhScreenUtil.dumpScreen();
                    return ListResult.FAILED;
                }
            }
        }
        return ensureBinMode() ? ListResult.LISTED : ListResult.FAILED;
    }

    /** Never list with a starting bid: the item could go for almost nothing. */
    private static boolean ensureBinMode() {
        if (createTitleIsBin()) {
            return true;
        }
        int button = waitForButton(2_000, "switch to bin");
        if (button >= 0) {
            ScreenUtil.clickSlot(button, 0);
            long end = System.currentTimeMillis() + 3_000L;
            while (System.currentTimeMillis() < end) {
                if (createTitleIsBin()) {
                    return true;
                }
                ScreenUtil.sleep(50);
            }
        }
        message("§cBIN-Modus nicht aktiv - nicht gelistet.");
        AhScreenUtil.dumpScreen();
        return false;
    }

    private static boolean createTitleIsBin() {
        String title = AhScreenUtil.currentScreenTitle();
        return title != null
                && title.toLowerCase(java.util.Locale.ROOT).contains("create bin auction");
    }

    private static AhScreenUtil.CreateSlot waitForCreateSlot(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            AhScreenUtil.CreateSlot slot = AhScreenUtil.readCreateSlot();
            if (slot != null) {
                return slot;
            }
            if (System.currentTimeMillis() >= end) {
                return null;
            }
            ScreenUtil.sleep(50);
        }
    }

    /** Does the auction slot hold this very item (same id, and the same name if known)? */
    private static boolean createSlotHolds(AhScreenUtil.CreateSlot slot, String tag, String name) {
        if (slot == null || slot.empty()) {
            return false;
        }
        String wanted = normalizeItemName(name);
        String held = normalizeItemName(slot.name());
        if (tag != null && !tag.isBlank() && !slot.tag().isBlank()) {
            return AhItem.sameTag(tag, slot.tag())
                    && (wanted.isEmpty() || held.isEmpty() || wanted.equals(held));
        }
        return !wanted.isEmpty() && wanted.equals(held);
    }

    /**
     * Gets the right item into the auction slot. The slot is checked first: an item
     * left there by a failed attempt blocked every later listing ("You already have
     * an item in the auction slot!"). If it is the wanted item, it just stays; any
     * other item is taken back to the inventory first.
     */
    private static ListResult prepareCreateSlot(String tag, String name) {
        AhScreenUtil.CreateSlot slot = waitForCreateSlot(3_000);
        if (slot == null) {
            message("§cAuktions-Slot nicht lesbar.");
            AhScreenUtil.dumpScreen();
            return ListResult.FAILED;
        }
        if (createSlotHolds(slot, tag, name)) {
            System.out.println("[AH-Flipper] Listing: Item liegt bereits im Auktions-Slot.");
            return ListResult.LISTED;
        }
        if (!slot.empty()) {
            ListResult cleared = emptyCreateSlot(slot);
            if (cleared != ListResult.LISTED) {
                return cleared;
            }
        }
        int inventorySlot = AhScreenUtil.findClickableInventorySlot(tag, name);
        if (inventorySlot < 0) {
            System.out.println("[AH-Flipper] Listing: Item nicht im Inventar: " + tag);
            return ListResult.NO_ITEM;
        }
        long clickedAt = System.currentTimeMillis();
        ScreenUtil.clickSlot(inventorySlot, 1);
        long end = clickedAt + 4_000L;
        while (System.currentTimeMillis() < end) {
            AhScreenUtil.CreateSlot placed = AhScreenUtil.readCreateSlot();
            if (placed != null && !placed.empty()
                    && (tag == null || tag.isBlank() || AhItem.sameTag(tag, placed.tag()))) {
                return ListResult.LISTED;
            }
            if (slotBusyChatAt >= clickedAt) {
                System.out.println("[AH-Flipper] Listing: Auktions-Slot ist doch belegt.");
                return ListResult.FAILED;
            }
            ScreenUtil.sleep(50);
        }
        message("§cItem wurde nicht in den Auktions-Slot gelegt.");
        AhScreenUtil.dumpScreen();
        return ListResult.FAILED;
    }

    /** Takes a foreign item out of the auction slot; a tracked purchase gets listed next. */
    private static ListResult emptyCreateSlot(AhScreenUtil.CreateSlot slot) {
        if (ScreenUtil.freeInventorySlots() <= 0) {
            // Nothing can be listed before this is solved, so stop trying for a while.
            listingBlockedUntil = System.currentTimeMillis() + LISTING_WAIT_MS;
            warnListing("§cInventory full - cannot empty the auction slot: §f" + slot.name());
            return ListResult.NO_SPACE;
        }
        System.out.println("[AH-Flipper] Auktions-Slot belegt von '" + slot.name()
                + "' - nehme es heraus.");
        for (int attempt = 0; attempt < 2; attempt++) {
            ScreenUtil.clickSlot(13, 0);
            long end = System.currentTimeMillis() + 3_000L;
            while (System.currentTimeMillis() < end) {
                AhScreenUtil.CreateSlot now = AhScreenUtil.readCreateSlot();
                if (now != null && now.empty()) {
                    if (!onAuctionItemClaimed(normalizeItemName(slot.name()))) {
                        message("§eItem from the auction slot is back in the inventory: §f"
                                + slot.name());
                    }
                    return ListResult.LISTED;
                }
                ScreenUtil.sleep(50);
            }
        }
        message("§cAuktions-Slot konnte nicht geleert werden: §f" + slot.name());
        AhScreenUtil.dumpScreen();
        return ListResult.FAILED;
    }

    /**
     * Sets the price and checks the result in the menu. Without this check a sign
     * that was not filled would create the auction at the old price, by default
     * 500 coins, and waiting a fixed time here was the start of the "stuck item"
     * chain: the menu was not back yet and the listing was given up.
     */
    private static boolean setCreatePrice(long price) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (AhScreenUtil.currentCreatePrice() == price) {
                return true;
            }
            if (inDurationMenu()) {
                clickDurationBack();
            }
            if (!waitForScreenUntil(0L, 3_000, AhScreenUtil.AhScreen.CREATE_BIN)) {
                return false;
            }
            int button = waitForButton(2_000, "item price");
            if (button < 0) {
                return false;
            }
            SignInput.set(String.valueOf(price), 700L);
            ScreenUtil.clickSlot(button, 0);
            long end = System.currentTimeMillis() + 7_000L;
            while (System.currentTimeMillis() < end) {
                if (AhScreenUtil.currentCreatePrice() == price) {
                    return true;
                }
                ScreenUtil.sleep(50);
            }
            SignInput.clear();
            System.out.println("[AH-Flipper] Preis nicht uebernommen (Versuch " + attempt
                    + "): soll=" + price + " ist=" + AhScreenUtil.currentCreatePrice());
            if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.SIGN) {
                ScreenUtil.closeCurrentScreen();
            }
            if (!waitForScreenUntil(0L, 3_000, AhScreenUtil.AhScreen.CREATE_BIN)) {
                return false;
            }
        }
        return false;
    }

    /** Last click of a listing; only the "Auction started" chat line proves success. */
    private static ListResult confirmListing(String tag, String displayName, long price) {
        int confirm = ScreenUtil.findButton("confirm");
        if (confirm < 0) {
            confirm = 11;
        }
        long clickedAt = System.currentTimeMillis();
        ScreenUtil.clickSlot(confirm, 0);
        long end = clickedAt + 7_000L;
        boolean clickedAgain = false;
        while (System.currentTimeMillis() < end) {
            if (auctionStartedAt >= clickedAt) {
                System.out.println("[AH-Flipper] Listing bestaetigt: " + tag + " @ " + price);
                message("§aGelistet: §f"
                        + (displayName == null || displayName.isBlank() ? tag : displayName)
                        + " §7für §f" + ScreenUtil.formatCoins(price));
                return ListResult.LISTED;
            }
            if (!clickedAgain && System.currentTimeMillis() - clickedAt > 2_500L
                    && AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.CONFIRM_LISTING) {
                clickedAgain = true;
                ScreenUtil.clickSlot(confirm, 0);
            }
            ScreenUtil.sleep(50);
        }
        // No line in chat: the state of the slot and the inventory decides.
        System.out.println("[AH-Flipper] Keine Listing-Bestaetigung im Chat: " + tag);
        ScreenUtil.closeAndWait(250);
        if (openCreateMenu() == ListResult.LISTED) {
            AhScreenUtil.CreateSlot slot = waitForCreateSlot(2_000);
            if (slot != null && slot.empty()
                    && AhScreenUtil.findInventorySlot(tag, displayName) < 0) {
                System.out.println("[AH-Flipper] Slot leer, Item weg - Listing war erfolgreich: " + tag);
                return ListResult.LISTED;
            }
        }
        return ListResult.FAILED;
    }

    private static int waitForButton(long timeoutMs, String text) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int slot = ScreenUtil.findButton(text);
            if (slot >= 0) {
                return slot;
            }
            if (System.currentTimeMillis() >= end) {
                return -1;
            }
            ScreenUtil.sleep(50);
        }
    }

    private static void closeOpenScreens() {
        ScreenUtil.closeAndWait(300);
    }

    private static boolean waitForNoScreen(long timeoutMs) {
        return ScreenUtil.closeAndWait(timeoutMs);
    }

    private static boolean selectDuration() {
        return selectDuration(0L);
    }

    /**
     * Sets the auction duration and only returns true once the create menu really
     * shows it. Every click waits for the menu it should lead to: with lag the
     * duration menu opened late, the fuzzy search for "48 hours" then hit
     * "Duration: 6 Hours" in the OLD menu, clicked it a second time (menu open /
     * close twitching) and the price step started inside the duration menu.
     */
    private static boolean selectDuration(long deadline) {
        String wanted = CONFIG.listDuration == null ? "2 days"
                : CONFIG.listDuration.trim().toLowerCase(java.util.Locale.ROOT);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.CREATE_BIN
                    && AhScreenUtil.durationMatches(AhScreenUtil.currentCreateDuration(), wanted)) {
                return true;
            }
            if (!inDurationMenu()) {
                if (!waitForScreenUntil(deadline, 2_000, AhScreenUtil.AhScreen.CREATE_BIN)) {
                    System.out.println("[AH-Flipper] Duration: Create-Menue nicht offen: "
                            + AhScreenUtil.currentScreenTitle());
                    return false;
                }
                int button = waitForButton(remainingMs(deadline, 2_000), "duration");
                if (button < 0) {
                    button = 33;
                }
                ScreenUtil.clickSlot(button, 0);
                if (!waitForDurationMenu(remainingMs(deadline, 3_000))) {
                    System.out.println("[AH-Flipper] Duration-Menue oeffnet nicht (Versuch "
                            + (attempt + 1) + ")");
                    continue;
                }
            }
            int option = waitForDurationOption(remainingMs(deadline, 2_000));
            if (option < 0) {
                System.out.println("[AH-Flipper] Duration-Option '" + wanted
                        + "' nicht gefunden: " + AhScreenUtil.currentScreenTitle());
                clickDurationBack();
                continue;
            }
            ScreenUtil.clickSlot(option, 0);
            if (waitForDuration(remainingMs(deadline, 3_000), wanted)) {
                return true;
            }
            System.out.println("[AH-Flipper] Duration nicht uebernommen (Versuch "
                    + (attempt + 1) + "): " + AhScreenUtil.currentCreateDuration());
        }
        if (inDurationMenu()) {
            clickDurationBack();
        }
        return AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.CREATE_BIN
                && AhScreenUtil.durationMatches(AhScreenUtil.currentCreateDuration(), wanted);
    }

    private static boolean inDurationMenu() {
        String title = AhScreenUtil.currentScreenTitle();
        return title != null && title.toLowerCase(java.util.Locale.ROOT).contains("auction duration");
    }

    private static boolean waitForDurationMenu(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (inDurationMenu()) {
                return true;
            }
            ScreenUtil.sleep(25);
        }
        return false;
    }

    /** Back in the create menu AND it shows the wanted duration. */
    private static boolean waitForDuration(long timeoutMs, String wanted) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.CREATE_BIN
                    && AhScreenUtil.durationMatches(AhScreenUtil.currentCreateDuration(), wanted)) {
                return true;
            }
            ScreenUtil.sleep(25);
        }
        return false;
    }

    private static void clickDurationBack() {
        if (!inDurationMenu()) {
            return;
        }
        int back = AhScreenUtil.findSlotNamedExactly("Go Back");
        ScreenUtil.clickSlot(back >= 0 ? back : 31, 0);
        waitForScreenUntil(0L, 2_000, AhScreenUtil.AhScreen.CREATE_BIN);
    }

    /** The option button, only inside the duration menu and only by its exact name. */
    private static int waitForDurationOption(long timeoutMs) {
        String wanted = CONFIG.listDuration == null ? "2 Days" : CONFIG.listDuration.trim();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (inDurationMenu()) {
                int option = AhScreenUtil.findSlotNamedExactly(wanted);
                if (option < 0 && AhScreenUtil.durationMatches(wanted, "2 days")) {
                    option = AhScreenUtil.findSlotNamedExactly("2 Days", "48 Hours");
                }
                if (option >= 0) {
                    return option;
                }
            }
            ScreenUtil.sleep(50);
        }
        return -1;
    }

    private static long waitForBuyPrice(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen == AhScreenUtil.AhScreen.BIN_VIEW) {
                long price = AhScreenUtil.currentPriceAt(31);
                if (price > 0) {
                    return price;
                }
            } else if (screen == AhScreenUtil.AhScreen.CONFIRM_PURCHASE) {
                long price = AhScreenUtil.currentPriceAt(11);
                if (price > 0) {
                    return price;
                }
            }
            if (AhScreenUtil.currentAuctionUnavailable()) {
                return -1;
            }
            ScreenUtil.sleep(10);
        }
        return -1;
    }

    private static boolean buyButtonReady() {
        return findBuyButtonSlot() >= 0;
    }

    private static int findBuyButtonSlot() {
        int slot = ScreenUtil.findButton("buy item right now");
        if (slot < 0) {
            slot = ScreenUtil.findButton("click to purchase");
        }
        if (slot < 0 && (AhScreenUtil.currentSlotHasText(31, "click to purchase")
                || AhScreenUtil.currentSlotHasText(31, "buy item right now"))) {
            slot = 31;
        }
        return slot;
    }

    private static int findBuyCooldownSlot() {
        String[] markers = {"can be bought", "can buy in", "available in"};
        for (String marker : markers) {
            Integer slot = AhScreenUtil.findSlotWithText(marker, 27, 35);
            if (slot != null && slot >= 0) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean waitForBuyButton(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.BIN_VIEW
                    && buyButtonReady()) {
                return true;
            }
            ScreenUtil.sleep(10);
        }
        return false;
    }

    private static int findConfirmPurchaseSlot() {
        Integer slot = AhScreenUtil.findSlotWithText("confirm purchase", 0, 53);
        if (slot != null && slot >= 0) {
            return slot;
        }
        return AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.CONFIRM_PURCHASE ? 11 : -1;
    }

    private static boolean isConfirmPurchaseScreen() {
        return findConfirmPurchaseSlot() >= 0;
    }

    private static boolean spamBuyButton() {
        long start = System.currentTimeMillis();
        long deadline = start + 25_000;
        long lastUnavailableCheck = start;
        boolean loggedCooldown = false;
        while (running && System.currentTimeMillis() <= deadline) {
            if (isConfirmPurchaseScreen()) {
                return true;
            }
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen != AhScreenUtil.AhScreen.BIN_VIEW) {
                // When the grace period ends the BIN view is replaced by
                // "Confirm Purchase"; for a moment neither is open. Giving up
                // here cancelled purchases right when the button became active.
                return waitForConfirmPurchase(2_000L);
            }
            long now = System.currentTimeMillis();
            if (now - lastUnavailableCheck >= 500) {
                lastUnavailableCheck = now;
                if (AhScreenUtil.currentAuctionUnavailable()) {
                    System.out.println("[AH-Flipper] Kauf abgebrochen: "
                            + "Auktion nicht mehr verf\u00fcgbar");
                    return false;
                }
            }
            int buySlot = findBuyButtonSlot();
            boolean cooldown = false;
            if (buySlot < 0) {
                buySlot = findBuyCooldownSlot();
                cooldown = buySlot >= 0;
            }
            if (buySlot < 0) {
                buySlot = 31;
                cooldown = true;
            }
            if (cooldown && !loggedCooldown) {
                deadline = Math.max(deadline, start + 25_000);
                System.out.println("[AH-Flipper] Buy-Cooldown: spame Slot " + buySlot
                        + " mit 30-35 CPS fuer 25s");
                loggedCooldown = true;
            }
            ScreenUtil.clickSlot(buySlot, 0);
            ScreenUtil.sleep(cooldown ? cooldownClickDelay() : clickDelay());
        }
        return AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.CONFIRM_PURCHASE;
    }

    private static boolean waitForConfirmPurchase(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (isConfirmPurchaseScreen()
                    || AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.CONFIRM_PURCHASE) {
                return true;
            }
            ScreenUtil.sleep(50);
        }
        return false;
    }

    private static boolean spamConfirmButton() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (running && System.currentTimeMillis() <= deadline) {
            int confirm = findConfirmPurchaseSlot();
            if (confirm < 0) {
                return true;
            }
            ScreenUtil.clickSlot(confirm, 0);
            // The first click decides; a second one is only useful if the first
            // came too early, and each extra one causes an error line + refund.
            ScreenUtil.sleep(Math.max(250L, clickDelay()));
        }
        return findConfirmPurchaseSlot() < 0;
    }

    private static long clickDelay() {
        int min = Math.max(1, CONFIG.buyClickMinMs);
        int max = Math.max(min, CONFIG.buyClickMaxMs);
        return max == min ? min
                : java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static long cooldownClickDelay() {
        return 29 + java.util.concurrent.ThreadLocalRandom.current().nextInt(5);
    }

    private static int streamCommand(CommandContext<FabricClientCommandSource> context) {
        CONFIG.liveFeed = !CONFIG.liveFeed;
        CONFIG.save();
        if (CONFIG.liveFeed) {
            if (running) {
                AhLiveScanner.start();
            }
            message("§6[AH-Flipper] §7Live-Feed §aan");
        } else {
            AhLiveScanner.stop();
            message("§6[AH-Flipper] §7Live-Feed §caus");
        }
        return 1;
    }

    private static int automationsCommand(CommandContext<FabricClientCommandSource> context) {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        client.gui.setScreen(com.coflnet.gui.cofl.CoflSettingsScreen.create(client.gui.screen()));
        return 1;
    }

    private static int scanCommand(CommandContext<FabricClientCommandSource> context) {
        if (AhScanner.isRunning()) {
            AhScanner.stop();
            message("§6[AH-Flipper] §7Scanner " + (running ? "§caus §7(nur Auto-Scan)" : "§caus"));
        } else {
            AhScanner.start();
            message("§6[AH-Flipper] §7Scanner §aan §7· §f" + CONFIG.scanMaxTags
                    + " §7Tags/Zyklus · Volumen ≥ §f"
                    + String.format(java.util.Locale.US, "%.0f", CONFIG.scanMinVolume)
                    + " §7· Delay §f" + CONFIG.scanRequestDelayMs + "ms");
        }
        return 1;
    }

    static void scanHit(String id, String tag, long itemValue, long price,
                        long profit, double profitPercent) {
        System.out.println("[AH-Flipper] Scan-Treffer: id=" + id + " tag=" + tag
                + " preis=" + price + " wert=" + itemValue + " profit=" + profit
                + " pct=" + String.format(java.util.Locale.US, "%.1f", profitPercent));
        message("\u00a76[Scan] \u00a7f" + tag + " \u00a77@ \u00a7f" + ScreenUtil.formatCoins(price) + " \u00a77Value \u00a7f" + ScreenUtil.formatCoins(itemValue) + " \u00a7a+" + ScreenUtil.formatCoins(profit) + " \u00a77(" + String.format(java.util.Locale.US, "%.0f", profitPercent) + "%)");
        if (!running || !autoBuy) {
            return;
        }
        synchronized (AUTO_QUEUE) {
            for (AutoCandidate candidate : AUTO_QUEUE) {
                if (candidate.id.equals(id)) {
                    return;
                }
            }
            AUTO_QUEUE.add(new AutoCandidate(id, itemValue, profit, System.currentTimeMillis()));
        }
        ensureAhPriority();
        ensureAutoWorker();
    }

    private static int buyCurrentCommand(CommandContext<FabricClientCommandSource> context) {
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.BIN_VIEW) {
            message("§cÖffne zuerst eine BIN-Auktion.");
            return 0;
        }
        long price = AhScreenUtil.currentPriceAt(31);
        if (price <= 0) {
            message("§cKein Preis erkannt.");
            return 0;
        }
        if (isBusy()) {
            message("§cEs läuft bereits ein Kauf.");
            return 0;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("buy-current", false);
            if (lease == null) {
                message("§cEs läuft bereits eine AH-Aktion.");
                return;
            }
            ensureAhPriority();
            try {
                buyFlow("current", 0, false, true);
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        }, "skycofl-ah-buy");
        thread.setDaemon(true);
        thread.start();
        message("§7Kaufe aktuelle Auktion für §f" + ScreenUtil.formatCoins(price) + "§7 ...");
        return 1;
    }

    private static int buyPlayerAuctionCommand(CommandContext<FabricClientCommandSource> context) {
        String player = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "spieler").trim();
        if (player.isBlank()) {
            message("§cSpielername fehlt.");
            return 0;
        }
        if (isBusy()) {
            message("§cEs läuft bereits ein Kauf.");
            return 0;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("buy-player", false);
            if (lease == null) {
                message("§cEs läuft bereits eine AH-Aktion.");
                return;
            }
            ensureAhPriority();
            try {
                buyPlayerAuctionFlow(player);
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        }, "skycofl-ah-buyauktion");
        thread.setDaemon(true);
        thread.start();
        message("§7Öffne Auktionen von §f" + player + "§7 ...");
        return 1;
    }

    private static void buyPlayerAuctionFlow(String player) {
        ensureAhPriority();
        try {
            System.out.println("[AH-Flipper] buyauktion: spieler=" + player);
            Trader trader = Trader.get();
            if (trader != null) {
                trader.closeBazaarGui();
            }
            ScreenUtil.sleep(10);
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeCurrentScreen();
                ScreenUtil.sleep(10);
            }
            sendCommandSilent("/ah " + player);
            int slot = waitForPlayerAuctionSlot(player, 2000);
            if (slot < 0) {
                System.out.println("[AH-Flipper] buyauktion: keine Auktion gefunden");
                message("§cKeine Auktion von §f" + player + "§c gefunden.");
                AhScreenUtil.dumpScreen();
                return;
            }
            System.out.println("[AH-Flipper] buyauktion: klicke Slot " + slot);
            ScreenUtil.clickSlot(slot, 0);
            if (!waitForTitle(1500, "bin auction view")) {
                System.out.println("[AH-Flipper] buyauktion: keine BIN-Ansicht");
                message("§cAuktion von §f" + player + "§c ist keine BIN-Auktion oder wurde nicht geöffnet.");
                AhScreenUtil.dumpScreen();
                return;
            }
            buyFlow("player:" + player, -1, false, true, true, 0);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] buyauktion Fehler: " + e);
            message("§cBuyauktion fehlgeschlagen: " + e.getMessage());
        }
    }

    private static int waitForPlayerAuctionSlot(String player, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int slot = AhScreenUtil.findPlayerAuctionSlot(player);
            if (slot >= 0) {
                return slot;
            }
            ScreenUtil.sleep(10);
        }
        return -1;
    }

    private static int buyCommand(CommandContext<FabricClientCommandSource> context) {
        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "auktion");
        if (id.equalsIgnoreCase("last")) {
            CoflCore.commands.models.FlipData flip = CoflCore.CoflCore.flipHandler.fds.GetLastFlip();
            String flipId;
            long worth;
            long ageSeconds = -1;
            if (flip != null && flip.Id != null) {
                flipId = flip.Id;
                worth = (long) flip.Worth * Math.max(1, CONFIG.coflWorthMultiplier);
                ageSeconds = lastFlipAt > 0 ? (System.currentTimeMillis() - lastFlipAt) / 1000 : -1;
            } else if (lastFlipId != null) {
                flipId = lastFlipId;
                worth = lastFlipWorth;
                ageSeconds = (System.currentTimeMillis() - lastFlipAt) / 1000;
            } else {
                message("§cKein letzter Flip vorhanden – erst §f/ahflip start §cund auf einen Flip warten.");
                return 0;
            }
            System.out.println("[AH-Flipper] buy last: id=" + flipId + " worth=" + worth
                    + " alter=" + ageSeconds + "s");
            message("§7Kaufe letzten Flip: §f" + flipId + (ageSeconds >= 0 ? " §7(" + ageSeconds + "s alt)" : ""));
            startBuy(flipId, worth, false);
        } else {
            startBuy(id, -1, false);
        }
        return 1;
    }

    private static int openCommand(CommandContext<FabricClientCommandSource> context) {
        String player = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "spieler");
        sendCommand("/ah " + player);
        return 1;
    }

    private static int viewCommand(CommandContext<FabricClientCommandSource> context) {
        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "auktion");
        sendCommand("/viewauction " + id);
        return 1;
    }

    private static void sendCommand(String command) {
        if (sendCommandSilent(command)) {
            message("§7Sende: §f" + command);
        }
    }

    static boolean sendCommandSilent(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            message("§cNicht verbunden.");
            return false;
        }
        client.getConnection().sendChat(command);
        return true;
    }

    public static void message(String text) {
        CoflModClient.displayModMessage(Component.literal(text));
    }
}



