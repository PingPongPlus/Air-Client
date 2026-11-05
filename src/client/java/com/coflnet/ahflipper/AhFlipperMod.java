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
    private static volatile String lastBoughtId = "";
    private static volatile long lastBoughtValue;
    private static volatile long lastBoughtPrice;
    private static volatile String activePurchaseTag = "";
    private static volatile String activePurchaseName = "";
    private static volatile String activePurchaseId = "";
    private static volatile long activePurchaseValue;
    private static volatile long activePurchasePrice;
    private static volatile long activePurchaseAt;
    private static final java.util.concurrent.atomic.AtomicBoolean SOLD_CLAIM_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean CHAT_HANDLING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastSoldClaimAt;
    private static volatile long lastPurchaseErrorAt;
    private static volatile boolean recoveryRunning;
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
                               String excludeUuid, long fixedPrice, long at) {
    }

    public static void init() {
        System.out.println("[AH-Flipper] BUILD CLEAN-2026-11-05 geladen (investmentGui="
                + CONFIG.investmentGui + ", fireSaleSkins=" + CONFIG.fireSaleSkins
                + ", keys=F10/F8)");
        debugAnalyze = false;
        CONFIG.debugAnalyze = false;
        registerKeybinding();
        registerSoldChatClaim();
        com.coflnet.gui.panel.FlipperPanels.init();
        AhBidMacro.init();
        AhInvestmentTracker.init();
        AhFireSaleSkinTracker.init();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                message("\u00a76[AH-Flipper] \u00a77Build \u00a7fCLEAN-2026-11-05"
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
        synchronized (LISTING_QUEUE) {
            LISTING_QUEUE.clear();
        }
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
            message("Ãƒâ€šÃ‚Â§cAH-Flipper lÃƒÆ’Ã‚Â¤uft bereits.");
            return 1;
        }
        start();
        return 1;
    }

    private static int stopCommand(CommandContext<FabricClientCommandSource> context) {
        if (!running && !recon) {
            message("Ãƒâ€šÃ‚Â§cAH-Flipper lÃƒÆ’Ã‚Â¤uft nicht.");
            return 1;
        }
        stop();
        return 1;
    }

    private static int reconCommand(CommandContext<FabricClientCommandSource> context) {
        recon = !recon;
        message("Ãƒâ€šÃ‚Â§6[AH-Flipper] Ãƒâ€šÃ‚Â§7Recon-Logging " + (recon ? "Ãƒâ€šÃ‚Â§aan" : "Ãƒâ€šÃ‚Â§caus"));
        return 1;
    }

    private static int activeAuctionsCommand(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            message("Ãƒâ€šÃ‚Â§cSpieler nicht verfÃƒÆ’Ã‚Â¼gbar.");
            return 0;
        }
        String uuid = client.player.getUUID().toString().replace("-", "");
        message("Ãƒâ€šÃ‚Â§7Lade aktive Auktionen ...");
        Thread thread = new Thread(() -> {
            int count = AhMarketData.activePlayerAuctionCount(uuid);
            if (count >= 0) {
                activeAuctionCount = count;
                message("Ãƒâ€šÃ‚Â§6[AH-Flipper] Ãƒâ€šÃ‚Â§7Aktive Auktionen: Ãƒâ€šÃ‚Â§f" + count
                        + "Ãƒâ€šÃ‚Â§7/Ãƒâ€šÃ‚Â§f" + AH_MAX_ACTIVE);
                ensureListingWorker();
            } else {
                message("Ãƒâ€šÃ‚Â§cAktive Auktionen konnten nicht geladen werden.");
            }
        }, "skycofl-ah-active-auctions");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int statusCommand(CommandContext<FabricClientCommandSource> context) {
        message("Ãƒâ€šÃ‚Â§6[Flipper] Ãƒâ€šÃ‚Â§7AH-Status: " + (running ? "Ãƒâ€šÃ‚Â§aAKTIV" : "Ãƒâ€šÃ‚Â§cGESTOPPT")
                + (recon ? " Ãƒâ€šÃ‚Â§7(Recon an)" : ""));
        message("Ãƒâ€šÃ‚Â§7Exit: Ãƒâ€šÃ‚Â§fmin(Craft-Cost, Cofl-LBIN) Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Fee: Ãƒâ€šÃ‚Â§f"
                + String.format(java.util.Locale.US, "%.1f%%", CONFIG.ahFeePercent)
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Min. Profit: Ãƒâ€šÃ‚Â§f" + String.format(java.util.Locale.US, "%.0f%%", CONFIG.minProfitPercent)
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Min. Item: Ãƒâ€šÃ‚Â§f" + ScreenUtil.formatCoins(CONFIG.minItemCoins));
        message("Ãƒâ€šÃ‚Â§7Regeln: Total-Sales > Ãƒâ€šÃ‚Â§f" + CONFIG.minTotalSales
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Clean ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â¥ Ãƒâ€šÃ‚Â§f" + CONFIG.minCleanSales + " Ãƒâ€šÃ‚Â§7nur bei Addon ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â¤ Ãƒâ€šÃ‚Â§f"
                + String.format(java.util.Locale.US, "%.0fM", CONFIG.cleanSalesAddonValueCoins / 1_000_000.0)
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· CV ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â¤ Ãƒâ€šÃ‚Â§f" + String.format(java.util.Locale.US, "%.0f%%", CONFIG.maxDeviationPercent));
        message("Ãƒâ€šÃ‚Â§7Addon: ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â¥ Ãƒâ€šÃ‚Â§f" + CONFIG.addonMinSalesPerWeek + "x/Woche"
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Addonwert ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â¥ Ãƒâ€šÃ‚Â§f" + String.format(java.util.Locale.US, "%.0fM", CONFIG.minAddonValueMillions));
        message("Ãƒâ€šÃ‚Â§7Pets: Level-1-Sales > Ãƒâ€šÃ‚Â§f" + CONFIG.petLevel1MinSales
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Level-Sales > Ãƒâ€šÃ‚Â§f" + CONFIG.petLevelMinSales + " Ãƒâ€šÃ‚Â§7(nicht clean)");
        message("Ãƒâ€šÃ‚Â§7Kaufreihenfolge: Ãƒâ€šÃ‚Â§fFIFO Ãƒâ€šÃ‚Â§7(erster passender Treffer, kein Score-Vergleich)");
        message("Ãƒâ€šÃ‚Â§7Max. Auktionen: Ãƒâ€šÃ‚Â§f" + CONFIG.maxAuctions
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Reserve: Ãƒâ€šÃ‚Â§f" + ScreenUtil.formatCoins(CONFIG.reserveCoins));
        message("Ãƒâ€šÃ‚Â§7Auto-Buy: " + (autoBuy ? "Ãƒâ€šÃ‚Â§aan" : "Ãƒâ€šÃ‚Â§caus")
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Kauf lÃƒÆ’Ã‚Â¤uft: " + (isBusy() ? "Ãƒâ€šÃ‚Â§eja" : "Ãƒâ€šÃ‚Â§7nein")
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Top-5-Analyse: " + (debugAnalyze ? "Ãƒâ€šÃ‚Â§aan" : "Ãƒâ€šÃ‚Â§caus"));
        int queued;
        synchronized (AUTO_QUEUE) {
            queued = AUTO_QUEUE.size();
        }
        long age = lastFlipAt > 0 ? (System.currentTimeMillis() - lastFlipAt) / 1000 : -1;
        message("Ãƒâ€šÃ‚Â§7Letzter Flip: Ãƒâ€šÃ‚Â§f" + (lastFlipId == null ? "-" : lastFlipId)
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Profit Ãƒâ€šÃ‚Â§f" + (lastFlipWorth > 0 ? ScreenUtil.formatCoins(lastFlipWorth) : "-")
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Alter Ãƒâ€šÃ‚Â§f" + (age >= 0 ? age + "s" : "-")
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Queue Ãƒâ€šÃ‚Â§f" + queued);
        String conn = CoflCore.CoflCore.Wrapper != null
                ? CoflCore.CoflCore.Wrapper.GetStatus() : "null";
        message("Ãƒâ€šÃ‚Â§7Cofl: Ãƒâ€šÃ‚Â§f" + conn);
        message("Ãƒâ€šÃ‚Â§7Live: " + AhLiveScanner.status());
        message("Ãƒâ€šÃ‚Â§7Scanner: " + AhScanner.status());
        message("Ãƒâ€šÃ‚Â§7Investment: " + (CONFIG.investmentGui ? "Ãƒâ€šÃ‚Â§aan" : "Ãƒâ€šÃ‚Â§caus")
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Ãƒâ€šÃ‚Â§fF10 Ãƒâ€šÃ‚Â§7oder Ãƒâ€šÃ‚Â§f/ahflip invest");
        message("Ãƒâ€šÃ‚Â§7Fire-Sale-Skins: " + (CONFIG.fireSaleSkins ? "Ãƒâ€šÃ‚Â§aan" : "Ãƒâ€šÃ‚Â§caus")
                + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Ãƒâ€šÃ‚Â§fF8 Ãƒâ€šÃ‚Â§7oder Ãƒâ€šÃ‚Â§f/ahflip skins");
        return 1;
    }

    private static int analyzeCommand(CommandContext<FabricClientCommandSource> context) {
        long price = AhScreenUtil.currentPriceAt(31);
        AhItem item = AhItem.fromStack(AhScreenUtil.currentItem(13));
        if (item.tag.isBlank()) {
            message("Ãƒâ€šÃ‚Â§cKein Item erkannt ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ ÃƒÆ’Ã‚Â¶ffne eine BIN-Auktion.");
            return 0;
        }
        message("Ãƒâ€šÃ‚Â§7Analysiere Ãƒâ€šÃ‚Â§f" + item.tag + "Ãƒâ€šÃ‚Â§7 (Preis: Ãƒâ€šÃ‚Â§f"
                + (price > 0 ? ScreenUtil.formatCoins(price) : "?") + "Ãƒâ€šÃ‚Â§7) ...");
        Thread thread = new Thread(() -> {
            try {
                AhAnalyzer.Result result = AhAnalyzer.analyze(item, Math.max(price, 0), -1);
                logAnalysis(result);
                message((result.buyable ? "Ãƒâ€šÃ‚Â§a" : "Ãƒâ€šÃ‚Â§c") + result.shortSummary());
                for (String reason : result.reasons) {
                    message("Ãƒâ€šÃ‚Â§c- " + reason);
                }
            } catch (Exception e) {
                message("Ãƒâ€šÃ‚Â§cAnalyse fehlgeschlagen: " + e.getMessage());
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
        message("Ãƒâ€šÃ‚Â§6[AH-Flipper] Ãƒâ€šÃ‚Â§f" + item.tag + " Ãƒâ€šÃ‚Â§7Reforge: Ãƒâ€šÃ‚Â§f" + (item.reforge.isBlank() ? "-" : item.reforge)
                + " Ãƒâ€šÃ‚Â§7Stars: Ãƒâ€šÃ‚Â§f" + item.stars + " Ãƒâ€šÃ‚Â§7HPB: Ãƒâ€šÃ‚Â§f" + item.hpb
                + " Ãƒâ€šÃ‚Â§7Recomb: Ãƒâ€šÃ‚Â§f" + item.recomb + " Ãƒâ€šÃ‚Â§7Enchants: Ãƒâ€šÃ‚Â§f" + item.enchants.size()
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
                    + (check.supported ? "" : " (nicht prÃƒÆ’Ã‚Â¼fbar)"));
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
            message("Ãƒâ€šÃ‚Â§cClaim geht nur auf Hypixel SkyBlock.");
            return;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("claim", false);
            if (lease == null) {
                message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits eine AH-Aktion.");
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
                onSoldAuctionChat(message);
                onPurchaseErrorChat(message);
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Chat handler failed: " + e);
            }
        });
    }

    private static void onPurchaseErrorChat(Component message) {
        if (message == null || !running) {
            return;
        }
        String raw = message.getString();
        String lower = ScreenUtil.cleanName(raw).toLowerCase(java.util.Locale.ROOT);
        // Ignore our own status messages. The chat hook also sees messages emitted
        // by this mod, which used to recursively re-trigger this handler.
        if (lower.contains("purchase error/refund detected")
                || lower.contains("[ah-flipper]")
                || lower.contains("[flipper]")
                || lower.contains("[investment]")
                || lower.contains("[live]")
                || lower.contains("[scan]")) {
            return;
        }
        if (!lower.contains("refund")) {
            return;
        }
        if (!(lower.contains("error") || lower.contains("failed")
                || lower.contains("purchase") || lower.contains("auction"))) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPurchaseErrorAt < 5000L) {
            return;
        }
        if (!CHAT_HANDLING.compareAndSet(false, true)) {
            return;
        }
        lastPurchaseErrorAt = now;
        try {
            System.out.println("[AH-Flipper] Refund/error chat detected: " + raw);
            message("\u00a76[AH-Flipper] \u00a7ePurchase error/refund detected - checking claim and listing...");
            boolean recentPurchase = activePurchaseAt > 0
                    && now - activePurchaseAt < 60_000L
                    && !activePurchaseTag.isBlank();
            if (recentPurchase) {
                scheduleBuyRecovery(activePurchaseTag, activePurchaseName,
                        activePurchaseValue, activePurchasePrice, activePurchaseId);
            } else {
                scheduleBuyRecovery(lastBoughtTag, lastBoughtName, lastBoughtValue,
                        lastBoughtPrice, lastBoughtId);
            }
        } finally {
            CHAT_HANDLING.set(false);
        }
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
                System.out.println("[AH-Flipper] Sold-Claim \u00fcbersprungen (running=false)");
                return;
            }
            AutomationGate.Lease lease = AutomationGate.tryAcquire("sold-claim", false);
            if (lease == null) {
                System.out.println("[AH-Flipper] Sold-Claim \u00fcbersprungen (busy="
                        + AutomationGate.owner() + ")");
                return;
            }
            ensureAhPriority();
            try {
                claimSoldAuctionsBlocking();
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Sold-Claim fehlgeschlagen: " + e);
            message("\u00a7cSold claim failed: " + e.getMessage());
        } finally {
            SOLD_CLAIM_PENDING.set(false);
        }
    }

    private static boolean claimSoldAuctionsBlocking() {
        int claimed = 0;
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (!running) {
                    break;
                }
                if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.BIN_VIEW) {
                    if (clickSoldCollectSlot()) {
                        claimed++;
                        ScreenUtil.sleep(250);
                        continue;
                    }
                    ScreenUtil.closeAndWait(200);
                }
                if (!openManageAuctions()) {
                    System.out.println("[AH-Flipper] Sold-Claim: Manage Auctions nicht erreichbar.");
                    break;
                }
                Integer soldSlot = waitForSoldAuctionSlot(2500);
                if (soldSlot == null || soldSlot < 0) {
                    break;
                }
                ScreenUtil.clickSlot(soldSlot, 0);
                if (!waitForTitle(2500, "bin auction view")) {
                    System.out.println("[AH-Flipper] Sold-Claim: BIN-View nicht ge\u00f6ffnet (Slot "
                            + soldSlot + ").");
                    break;
                }
                ScreenUtil.sleep(350);
                if (!clickSoldCollectSlot()) {
                    System.out.println("[AH-Flipper] Sold-Claim: Collect-Coins-Slot nicht gefunden.");
                    break;
                }
                claimed++;
                System.out.println("[AH-Flipper] Verkaufte Auktion geclaimt (Slot " + soldSlot + ").");
                ScreenUtil.sleep(900);
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Sold-Claim Fehler: " + e);
        }
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(150);
        }
        if (claimed > 0) {
            message("\u00a7aClaimed " + claimed + " sold auction(s).");
            return true;
        }
        message("\u00a77No sold auction to claim.");
        return false;
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
            if (!waitForTitle(500, "auction")) {
                return false;
            }
        }
        screen = AhScreenUtil.currentScreen();
        if (screen == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                || screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
            return true;
        }
        int manage = waitForManageAuctionsButton(2500);
        if (manage < 0) {
            System.out.println("[AH-Flipper] Sold-Claim: 'Manage Auctions/Orders' nicht gefunden.");
            AhScreenUtil.dumpScreen();
            return false;
        }
        ScreenUtil.clickSlot(manage, 0);
        if (!waitForScreenUntil(0L, 3500, AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                AhScreenUtil.AhScreen.MANAGE_ORDERS)) {
            return false;
        }
        return true;
    }

    private static int waitForManageAuctionsButton(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int manage = findManageAuctionsButton();
            if (manage >= 0) {
                return manage;
            }
            ScreenUtil.sleep(50);
        }
        return -1;
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

    private static Integer waitForSoldAuctionSlot(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Integer slot = AhScreenUtil.findSoldAuctionSlot();
            if (slot != null && slot >= 0) {
                return slot;
            }
            ScreenUtil.sleep(50);
        }
        return -1;
    }

    private static boolean clickSoldCollectSlot() {
        if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.BIN_VIEW) {
            return false;
        }
        Integer slot = AhScreenUtil.findSlotWithLore("click to collect coins");
        if (slot == null || slot < 0) {
            slot = AhScreenUtil.findSlotWithLore("click to collect");
        }
        if (slot == null || slot < 0) {
            Integer soldMarker = AhScreenUtil.findSlotWithLore("status: sold");
            if (soldMarker != null && soldMarker >= 0) {
                slot = AhScreenUtil.findSlotByName("Collect Auction");
            }
        }
        if (slot == null || slot < 0) {
            return false;
        }
        ProfitTracker.startPurseWatch(ProfitTracker.AH);
        ScreenUtil.clickSlot(slot, 0);
        System.out.println("[AH-Flipper] Sold-Claim: Collect-Coins geklickt (Slot " + slot + ").");
        ScreenUtil.sleep(900);
        ProfitTracker.finishPurseWatch(ProfitTracker.AH);
        return true;
    }

    static boolean claimWonAuction(String tag, String name) {
        if (AhScreenUtil.findInventorySlot(tag, name) >= 0) {
            return true;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt > 1) {
                ScreenUtil.sleep(3000);
            }
            if (claimWonAuctionOnce(tag, name)) {
                return true;
            }
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeAndWait(250);
            }
        }
        return false;
    }

    private static boolean claimWonAuctionOnce(String tag, String name) {
        try {
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeAndWait(250);
            }
            sendCommandSilent("/ah");
            if (!waitForTitle(3500, "auction")) {
                return false;
            }
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen != AhScreenUtil.AhScreen.YOUR_BIDS
                    && screen != AhScreenUtil.AhScreen.MANAGE_ORDERS) {
                int entry = waitForClaimEntryButton(2500);
                if (entry < 0 && openManageAuctions()) {
                    entry = waitForClaimEntryButton(1500);
                }
                if (entry < 0) {
                    return waitForInventoryItem(tag, name, 500);
                }
                ScreenUtil.clickSlot(entry, 0);
                if (!waitForClaimScreen(3500)) {
                    return false;
                }
            }
            ScreenUtil.sleep(500);
            int slot = -1;
            if (tag != null && !tag.isBlank()) {
                slot = AhScreenUtil.findSlotByTag(tag);
            }
            if (slot < 0) {
                slot = waitForSlotByName(name, 1500);
            }
            if (slot < 0) {
                return waitForInventoryItem(tag, name, 1000);
            }
            ScreenUtil.clickSlot(slot, 0);
            ScreenUtil.sleep(800);
            if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.BIN_VIEW) {
                Integer collect = AhScreenUtil.findSlotWithLore("click to pick up item");
                if (collect == null || collect < 0) {
                    collect = AhScreenUtil.findSlotByName("Collect Auction");
                }
                if (collect != null && collect >= 0) {
                    ScreenUtil.clickSlot(collect, 0);
                    ScreenUtil.sleep(700);
                }
            }
            return waitForInventoryItem(tag, name, 2500);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Claim(2x) Fehler: " + e.getMessage());
            return false;
        }
    }

    private static boolean claimBlocking() {
        return claimBlocking("");
    }

    static boolean claimBlocking(String wantedName) {
        return claimBlocking("", wantedName);
    }

    static boolean claimBlocking(String wantedTag, String wantedName) {
        claimActionPerformed = false;
        if ((wantedTag != null && !wantedTag.isBlank())
                || (wantedName != null && !wantedName.isBlank())) {
            if (AhScreenUtil.findInventorySlot(wantedTag, wantedName) >= 0) {
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
                if (bids < 0 && openManageAuctions()) {
                    bids = waitForClaimEntryButton(1500);
                }
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
                    System.out.println("[AH-Flipper] Claim: kein Slot fÃƒÆ’Ã‚Â¼r " + wantedName);
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

    private static int findManageAuctionsButton() {
        return findButtonOrText("manage auctions", "auctions management", "manage orders");
    }

    private static int findCreateAuctionButton() {
        return findButtonOrText("create auction", "create bin auction");
    }

    private static boolean waitForAhContainer(long deadline, long timeoutMs) {
        long end = System.currentTimeMillis() + remainingMs(deadline, timeoutMs);
        while (System.currentTimeMillis() < end) {
            AhScreenUtil.AhScreen current = AhScreenUtil.currentScreen();
            if (current == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                    || current == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
                return true;
            }
            if (AhScreenUtil.isContainerOpen()) {
                String title = AhScreenUtil.currentScreenTitle()
                        .toLowerCase(java.util.Locale.ROOT);
                if (title.contains("auction") || title.contains("manage")
                        || title.contains("orders")) {
                    return true;
                }
            }
            ScreenUtil.sleep(50);
        }
        return false;
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

    static void releaseAhPriorityIfIdle() {
        boolean idle;
        synchronized (AUTO_QUEUE) {
            idle = AUTO_QUEUE.isEmpty();
        }
        synchronized (LISTING_QUEUE) {
            idle = idle && LISTING_QUEUE.isEmpty();
        }
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
            LISTING_QUEUE.add(new ListingTask(tag, name, value, boughtPrice,
                    excludeUuid == null ? "" : excludeUuid, fixedPrice,
                    System.currentTimeMillis()));
            if (LISTING_QUEUE.size() > 50) {
                LISTING_QUEUE.removeFirst();
            }
        }
        if (activeAuctionCount >= AH_MAX_ACTIVE) {
            message("\u00a7eAH full (" + activeAuctionCount + "/" + AH_MAX_ACTIVE + ") - item queued: \u00a7f" + (name.isBlank() ? tag : name));
        }
        ensureListingWorker();
    }

    private static void ensureListingWorker() {
        synchronized (AhFlipperMod.class) {
            if (listingWorkerRunning || !running) {
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
            while (running) {
                if (isBidPriorityActive() || activeAuctionCount >= AH_MAX_ACTIVE) {
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
            boolean pending;
            synchronized (LISTING_QUEUE) {
                pending = !LISTING_QUEUE.isEmpty();
            }
            if (pending && running) {
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

    private static ListingTask takeListing() {
        synchronized (LISTING_QUEUE) {
            return LISTING_QUEUE.poll();
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
                System.out.println("[AH-Flipper] Top-" + rank + ": Auktion nicht geÃƒÆ’Ã‚Â¶ffnet");
                message("\u00a7c#" + rank + " auction did not open: " + candidate.id);
                return;
            }
            if (AhScreenUtil.currentAuctionUnavailable()) {
                System.out.println("[AH-Flipper] Top-" + rank
                        + ": Auktion nicht mehr verfÃƒÆ’Ã‚Â¼gbar (Collect/Someone else) - schlieÃƒÆ’Ã…Â¸e sofort");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            long price = waitForBuyPrice(4000);
            if (price <= 0 && AhScreenUtil.currentAuctionUnavailable()) {
                System.out.println("[AH-Flipper] Top-" + rank
                        + ": Auktion nicht mehr verfÃƒÆ’Ã‚Â¼gbar - schlieÃƒÆ’Ã…Â¸e sofort");
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
        if (!CONFIG.autoList || isBidPriorityActive()
                || activeAuctionCount >= AH_MAX_ACTIVE) {
            return false;
        }
        ListingTask task = takeListing();
        if (task == null) {
            return false;
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
        boolean listed = false;
        try {
            listed = listPurchased(task.tag, task.name, task.value,
                    task.boughtPrice, task.excludeUuid, task.fixedPrice);
        } finally {
            ACTIVE_LISTINGS.remove(key);
            if (listed) {
                markListed(key);
            }
            AutomationGate.release(lease);
            releaseAhPriorityIfIdle();
        }
        return true;
    }

    static void listPurchased(String tag, String name, long value, long boughtPrice) {
        listPurchased(tag, name, value, boughtPrice, "");
    }

    static boolean listPurchased(String tag, String name, long value, long boughtPrice,
                                 String excludeUuid) {
        return listPurchased(tag, name, value, boughtPrice, excludeUuid, 0L);
    }

    public static boolean listLowballedFixed(String tag, String name, long price) {
        if (tag == null || tag.isBlank() || price <= 0) {
            return false;
        }
        AutomationGate.Lease lease = AutomationGate.acquire("lowball-list", false, 10_000L);
        if (lease == null) {
            return false;
        }
        ensureAhPriority();
        try {
            return listPurchased(tag, name, price, 0, "", price);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Lowball-Listing Fehler: " + e.getMessage());
            return false;
        } finally {
            AutomationGate.release(lease);
            releaseAhPriorityIfIdle();
        }
    }

    private static boolean listPurchased(String tag, String name, long value, long boughtPrice,
                                         String excludeUuid, long fixedPrice) {
        boolean inventoryFound = AhScreenUtil.findInventorySlot(tag, name) >= 0
                || waitForInventoryItem(tag, name, 10_000);
        if (!inventoryFound) {
            ;
            return false;
        }
        long price = fixedPrice > 0
                ? fixedPrice
                : listingPrice(tag, name, value, boughtPrice, excludeUuid);
        if (price <= 0) {
            message("\u00a7cNo safe listing price - not listing: " + tag);
            return false;
        }
        boolean listed = false;
        for (int attempt = 1; attempt <= 2; attempt++) {
            boolean secondAttempt = attempt == 2;
            if (secondAttempt) {
                message("Listing failed - retry 2/2...");
                System.out.println("[AH-Flipper] Listing-Versuch 2 fuer " + tag
                        + " (identisch zu Versuch 1, Item wird nicht angefasst)");
            }
            if (listFlow(tag, name, price, secondAttempt, 0L)) {
                listed = true;
                break;
            }
            System.out.println("[AH-Flipper] Listing-Versuch " + attempt + "/2 fehlgeschlagen: "
                    + tag);
            if (attempt < 2) {
                message("Listing failed - attempt " + (attempt + 1) + "/2...");
                if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                    ScreenUtil.closeCurrentScreen();
                }
                ScreenUtil.sleep(500);
            }
        }
        if (!listed) {
            message("\u00a7cListing failed after 2 attempts: " + tag);
            closeOpenScreens();
        } else {
            markListed(listingKey(tag, name, excludeUuid));
        }
        return listed;
    }

    private static long listingPrice(String tag, String name, long value, long boughtPrice,
                                     String excludeUuid) {
        AhItem item = AhScreenUtil.findInventoryItem(tag, name);
        if (item != null && !item.tag.equalsIgnoreCase(tag)) {
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
            expected = fresh > 0 ? Math.min(fresh, value) : value;
            if (minNetPrice > 0 && expected < minNetPrice) {
                System.out.println("[AH-Flipper] Listing verworfen: " + tag + " Wert="
                        + expected + " < Kaufpreis-Netto=" + minNetPrice
                        + " (Kauf " + boughtPrice + ")");
                return 0;
            }
        } else {
            expected = fresh;
            if (minNetPrice > 0 && expected < minNetPrice) {
                System.out.println("[AH-Flipper] Listing verworfen: " + tag + " Referenz="
                        + expected + " < Kaufpreis-Netto=" + minNetPrice
                        + " (Kauf " + boughtPrice + ")");
                return 0;
            }
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
            message("Ãƒâ€šÃ‚Â§cKeine Auktions-ID.");
            return;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("buy", false);
            if (lease == null) {
                if (!auto) {
                    message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits ein Kauf.");
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
                System.out.println("[AH-Flipper] Auktion nicht mehr verfÃƒÆ’Ã‚Â¼gbar (Collect/Someone else) - schlieÃƒÆ’Ã…Â¸e sofort: "
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
                    System.out.println("[AH-Flipper] Auktion nicht mehr verfÃƒÆ’Ã‚Â¼gbar - schlieÃƒÆ’Ã…Â¸e sofort: " + id);
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
            if (item.isPet()) {
                message("\u00a7cPets are disabled in AH-Flipper.");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            if (item.coflValue() <= 0) {
                System.out.println("[AH-Flipper] Cofl-Median/LBin nicht geladen: " + item.tag);
                message("\u00a7cCofl tooltip value not loaded - no purchase.");
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
                System.out.println("[AH-Flipper] Tooltip-Wert fehlt fÃƒÆ’Ã‚Â¼r "
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
            activePurchaseTag = item.tag;
            activePurchaseName = item.displayName;
            activePurchaseValue = resale;
            activePurchasePrice = price;
            activePurchaseId = id;
            activePurchaseAt = System.currentTimeMillis();
            purchaseAttempted = true;
            if (!spamBuyButton()) {
                System.out.println("[AH-Flipper] Kaufbutton nicht aktiv");
                message("\u00a7cBuy button not active - cancelled.");
                AhScreenUtil.dumpScreen();
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
                activePurchasePrice = price;
            }
            if (!spamConfirmButton()) {
                System.out.println("[AH-Flipper] Confirm Purchase nicht bestÃƒÆ’Ã‚Â¤tigt");
                message("\u00a7cConfirmation did not go through.");
                AhScreenUtil.dumpScreen();
                return;
            }
            purchaseSucceeded = true;
            lastBuyOk = true;
            lastBoughtTag = item.tag;
            lastBoughtName = item.displayName;
            lastBoughtValue = resale;
            lastBoughtPrice = price;
            lastBoughtId = id;
            AhFlipperConfig.blockTemporarily(item.tag, item.displayName);
            ScreenUtil.sleep(prevalidated || auto ? 50 : 100);
            long purseAfter = ScreenUtil.readPurse();
            System.out.println("[AH-Flipper] Kauf ausgelÃƒÆ’Ã‚Â¶st: Purse " + purseBefore + " -> " + purseAfter);
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
                scheduleBuyRecovery(recoveryTag, recoveryName, recoveryValue,
                        recoveryPrice, id);
            }
            purchaseInProgress = false;
            releaseAhPriorityIfIdle();
        }
    }

    private static void scheduleBuyRecovery(String tag, String name, long value,
                                            long price, String excludeUuid) {
        String recoveryKey = listingKey(tag, name, excludeUuid);
        if (recentlyListed(recoveryKey) || ACTIVE_LISTINGS.contains(recoveryKey)) {
            return;
        }
        synchronized (AhFlipperMod.class) {
            if (recoveryRunning) {
                ensureListingWorker();
                return;
            }
            recoveryRunning = true;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.acquire("recovery", false, 5000L);
            try {
                if (!running || lease == null) {
                    return;
                }
                ensureAhPriority();
                ScreenUtil.closeAndWait(250);
                claimBlocking(name);
                if (tag != null && !tag.isBlank() && name != null && !name.isBlank()
                        && AhScreenUtil.findInventorySlot(tag, name) >= 0) {
                    enqueueListing(tag, name, value, price, excludeUuid);
                }
                ensureListingWorker();
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Kauf-Recovery Fehler: " + e.getMessage());
            } finally {
                AutomationGate.release(lease);
                releaseAhPriorityIfIdle();
                recoveryRunning = false;
            }
        }, "skycofl-ah-buy-recover");
        thread.setDaemon(true);
        thread.start();
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
            message("Ãƒâ€šÃ‚Â§cKein Item bekannt ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ erst kaufen oder BIN-View ÃƒÆ’Ã‚Â¶ffnen.");
            return 0;
        }
        if (price <= 0) {
            message("Ãƒâ€šÃ‚Â§cKein Preis ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ nutze /ahflip sell <preis>.");
            return 0;
        }
        if (isBusy()) {
            message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits ein Kauf/Listing.");
            return 0;
        }
        String finalTag = tag;
        String finalName = name;
        long finalPrice = price;
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("sell", false);
            if (lease == null) {
                message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits eine AH-Aktion.");
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

    private static boolean listFlow(String tag, String displayName, long price) {
        return listFlow(tag, displayName, price, false, 0L);
    }

    private static boolean listFlow(String tag, String displayName, long price,
                                    boolean skipItemClick, long deadline) {
        try {
            System.out.println("[AH-Flipper] listFlow: tag=" + tag + " name=" + displayName
                    + " preis=" + price + " skipItemClick=" + skipItemClick);
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeAndWait(250);
            }
            sendCommandSilent("/ah");
            if (!waitForScreenUntil(deadline, 4000, AhScreenUtil.AhScreen.AH_MAIN,
                    AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                    AhScreenUtil.AhScreen.MANAGE_ORDERS,
                    AhScreenUtil.AhScreen.CREATE_BIN)) {
                if (!waitForTitleUntil(deadline, 500, "auction")) {
                    message("\u00a7cAH-Men\u00fc nicht ge\u00f6ffnet.");
                    return false;
                }
            }
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen == AhScreenUtil.AhScreen.AH_MAIN
                    || screen == AhScreenUtil.AhScreen.OTHER) {
                int mgmt = waitForManageAuctionsButton(
                        Math.max(500L, remainingMs(deadline, 2500)));
                if (mgmt < 0) {
                    message("\u00a7c'Manage Auctions' nicht gefunden.");
                    AhScreenUtil.dumpScreen();
                    return false;
                }
                ScreenUtil.clickSlot(mgmt, 0);
                if (!waitForScreenUntil(deadline, 3500,
                        AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                        AhScreenUtil.AhScreen.MANAGE_ORDERS)) {
                    if (!waitForAnyTitleUntil(deadline, 500,
                            "create bin auction", "create auction")) {
                        message("\u00a7c'Manage Auctions' nicht erreicht.");
                        AhScreenUtil.dumpScreen();
                        return false;
                    }
                }
                screen = AhScreenUtil.currentScreen();
            }
            if (screen == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                    || screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
                int create = findCreateAuctionButton();
                if (create < 0) {
                    message("\u00a7c'Create Auction' nicht gefunden.");
                    AhScreenUtil.dumpScreen();
                    return false;
                }
                ScreenUtil.clickSlot(create, 0);
                sleepUntil(deadline, 700);
            }
            String title = AhScreenUtil.currentScreenTitle().toLowerCase(java.util.Locale.ROOT);
            if (title.contains("create") && !title.contains("bin")) {
                int switchBin = ScreenUtil.findButton("switch to bin");
                if (switchBin >= 0) {
                    ScreenUtil.clickSlot(switchBin, 0);
                    sleepUntil(deadline, 600);
                }
            }
            if (!waitForAnyTitleUntil(deadline, 4000, "create bin auction", "create auction")) {
                message("\u00a7c'Create Auction' nicht erreicht.");
                AhScreenUtil.dumpScreen();
                return false;
            }
            if (skipItemClick) {
                System.out.println("[AH-Flipper] Listing-Fallback: Item wird nicht angefasst, "
                        + "nur Duration, Preis und Erstellen.");
            } else {
                boolean previewOk = previewMatchesTag(tag);
                if (previewOk) {
                    System.out.println("[AH-Flipper] Listing: Item bereits im Create-Slot, "
                            + "Rechtsklick wird \u00fcbersprungen.");
                } else {
                    int slot = AhScreenUtil.findInventorySlot(tag, displayName);
                    if (slot < 0) {
                        message("\u00a7cItem im Inventar nicht gefunden: " + tag);
                        AhScreenUtil.dumpScreen();
                        return false;
                    }
                    ScreenUtil.clickSlot(slot, 1);
                    sleepUntil(deadline, 500);
                    long clickEnd = deadline > 0
                            ? Math.min(deadline, System.currentTimeMillis() + 2000)
                            : System.currentTimeMillis() + 2000;
                    while (System.currentTimeMillis() < clickEnd
                            && !previewMatchesTag(tag)) {
                        ScreenUtil.sleep(100);
                    }
                    previewOk = previewMatchesTag(tag);
                    if (!previewOk) {
                        message("\u00a7cItem wurde nicht in den Verkaufs-Slot gelegt.");
                        AhScreenUtil.dumpScreen();
                        return false;
                    }
                }
            }
            if (!selectDuration(deadline)) {
                message("\u00a7cDuration-Auswahl fehlgeschlagen.");
                AhScreenUtil.dumpScreen();
                return false;
            }
            sleepUntil(deadline, 300);
            int priceButton = ScreenUtil.findButton("item price");
            if (priceButton < 0) {
                priceButton = ScreenUtil.findButton("price");
            }
            if (priceButton < 0 && AhScreenUtil.currentSlotHasText(31, "price")) {
                priceButton = 31;
            }
            if (priceButton < 0) {
                message("\u00a7c'Item Price' nicht gefunden.");
                AhScreenUtil.dumpScreen();
                return false;
            }
            SignInput.set(String.valueOf(price), 700L);
            ScreenUtil.clickSlot(priceButton, 0);
            sleepUntil(deadline, 1300);
            int create = ScreenUtil.findButton("create bin auction");
            if (create < 0) {
                create = ScreenUtil.findButton("create auction");
            }
            if (create < 0) {
                message("\u00a7c'Create Auction' nicht gefunden.");
                AhScreenUtil.dumpScreen();
                return false;
            }
            ScreenUtil.clickSlot(create, 0);
            if (!waitForAnyTitleUntil(deadline, 4000, "confirm bin auction", "confirm auction")) {
                message("\u00a7c'Confirm Auction' nicht erreicht.");
                AhScreenUtil.dumpScreen();
                return false;
            }
            sleepUntil(deadline, 300);
            int confirm = ScreenUtil.findButton("confirm");
            if (confirm < 0) {
                confirm = 11;
            }
            ScreenUtil.clickSlot(confirm, 0);
            sleepUntil(deadline, 900);
            System.out.println("[AH-Flipper] Listing ausgel\u00f6st: " + tag + " @ " + price);
            message("\u00a7aGelistet: \u00a7f" + (displayName.isBlank() ? tag : displayName)
                    + " \u00a77f\u00fcr \u00a7f" + ScreenUtil.formatCoins(price));
            return true;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] listFlow Fehler: " + e);
            message("\u00a7cListing fehlgeschlagen: " + e.getMessage());
            return false;
        } finally {
            closeOpenScreens();
        }
    }

    private static void closeOpenScreens() {
        ScreenUtil.closeAndWait(300);
    }

    private static boolean waitForNoScreen(long timeoutMs) {
        return ScreenUtil.closeAndWait(timeoutMs);
    }

    private static boolean previewMatchesTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        AhItem preview = AhItem.fromStack(AhScreenUtil.currentItem(13));
        return tag.equalsIgnoreCase(preview.tag);
    }

    private static boolean selectDuration() {
        return selectDuration(0L);
    }

    private static boolean selectDuration(long deadline) {
        int duration = ScreenUtil.findButton("duration");
        if (duration < 0) {
            duration = 33;
        }
        ScreenUtil.clickSlot(duration, 0);
        sleepUntil(deadline, 250);
        int option = waitForDurationOption(remainingMs(deadline, 2000));
        if (option < 0) {
            System.out.println("[AH-Flipper] Duration-Option nicht gefunden: "
                    + AhScreenUtil.currentScreenTitle());
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            ScreenUtil.clickSlot(option, 0);
            if (waitForAnyTitle(remainingMs(deadline, 2000),
                    "create bin auction", "create auction")) {
                return true;
            }
            option = waitForDurationOption(remainingMs(deadline, 500));
            if (option < 0) {
                break;
            }
        }
        int back = ScreenUtil.findButton("go back");
        if (back < 0 && AhScreenUtil.currentSlotHasText(31, "go back")) {
            back = 31;
        }
        if (back >= 0) {
            ScreenUtil.clickSlot(back, 0);
        }
        return waitForAnyTitle(remainingMs(deadline, 2500),
                "create bin auction", "create auction");
    }

    private static int waitForDurationOption(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int option = ScreenUtil.findButton(CONFIG.listDuration);
            if (option < 0) {
                option = ScreenUtil.findButton("2 days");
            }
            if (option < 0) {
                option = ScreenUtil.findButton("48 hours");
            }
            if (option < 0) {
                option = ScreenUtil.findButton("2d");
            }
            if (option >= 0) {
                return option;
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
                return false;
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

    private static boolean spamConfirmButton() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (running && System.currentTimeMillis() <= deadline) {
            int confirm = findConfirmPurchaseSlot();
            if (confirm < 0) {
                return true;
            }
            ScreenUtil.clickSlot(confirm, 0);
            ScreenUtil.sleep(clickDelay());
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
            message("Ãƒâ€šÃ‚Â§6[AH-Flipper] Ãƒâ€šÃ‚Â§7Live-Feed Ãƒâ€šÃ‚Â§aan");
        } else {
            AhLiveScanner.stop();
            message("Ãƒâ€šÃ‚Â§6[AH-Flipper] Ãƒâ€šÃ‚Â§7Live-Feed Ãƒâ€šÃ‚Â§caus");
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
            message("Ãƒâ€šÃ‚Â§6[AH-Flipper] Ãƒâ€šÃ‚Â§7Scanner " + (running ? "Ãƒâ€šÃ‚Â§caus Ãƒâ€šÃ‚Â§7(nur Auto-Scan)" : "Ãƒâ€šÃ‚Â§caus"));
        } else {
            AhScanner.start();
            message("Ãƒâ€šÃ‚Â§6[AH-Flipper] Ãƒâ€šÃ‚Â§7Scanner Ãƒâ€šÃ‚Â§aan Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Ãƒâ€šÃ‚Â§f" + CONFIG.scanMaxTags
                    + " Ãƒâ€šÃ‚Â§7Tags/Zyklus Ãƒâ€šÃ‚Â· Volumen ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â¥ Ãƒâ€šÃ‚Â§f"
                    + String.format(java.util.Locale.US, "%.0f", CONFIG.scanMinVolume)
                    + " Ãƒâ€šÃ‚Â§7Ãƒâ€šÃ‚Â· Delay Ãƒâ€šÃ‚Â§f" + CONFIG.scanRequestDelayMs + "ms");
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
            message("Ãƒâ€šÃ‚Â§cÃƒÆ’Ã¢â‚¬â€œffne zuerst eine BIN-Auktion.");
            return 0;
        }
        long price = AhScreenUtil.currentPriceAt(31);
        if (price <= 0) {
            message("Ãƒâ€šÃ‚Â§cKein Preis erkannt.");
            return 0;
        }
        if (isBusy()) {
            message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits ein Kauf.");
            return 0;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("buy-current", false);
            if (lease == null) {
                message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits eine AH-Aktion.");
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
        message("Ãƒâ€šÃ‚Â§7Kaufe aktuelle Auktion fÃƒÆ’Ã‚Â¼r Ãƒâ€šÃ‚Â§f" + ScreenUtil.formatCoins(price) + "Ãƒâ€šÃ‚Â§7 ...");
        return 1;
    }

    private static int buyPlayerAuctionCommand(CommandContext<FabricClientCommandSource> context) {
        String player = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "spieler").trim();
        if (player.isBlank()) {
            message("Ãƒâ€šÃ‚Â§cSpielername fehlt.");
            return 0;
        }
        if (isBusy()) {
            message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits ein Kauf.");
            return 0;
        }
        Thread thread = new Thread(() -> {
            AutomationGate.Lease lease = AutomationGate.tryAcquire("buy-player", false);
            if (lease == null) {
                message("Ãƒâ€šÃ‚Â§cEs lÃƒÆ’Ã‚Â¤uft bereits eine AH-Aktion.");
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
        message("Ãƒâ€šÃ‚Â§7ÃƒÆ’Ã¢â‚¬â€œffne Auktionen von Ãƒâ€šÃ‚Â§f" + player + "Ãƒâ€šÃ‚Â§7 ...");
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
                message("Ãƒâ€šÃ‚Â§cKeine Auktion von Ãƒâ€šÃ‚Â§f" + player + "Ãƒâ€šÃ‚Â§c gefunden.");
                AhScreenUtil.dumpScreen();
                return;
            }
            System.out.println("[AH-Flipper] buyauktion: klicke Slot " + slot);
            ScreenUtil.clickSlot(slot, 0);
            if (!waitForTitle(1500, "bin auction view")) {
                System.out.println("[AH-Flipper] buyauktion: keine BIN-Ansicht");
                message("Ãƒâ€šÃ‚Â§cAuktion von Ãƒâ€šÃ‚Â§f" + player + "Ãƒâ€šÃ‚Â§c ist keine BIN-Auktion oder wurde nicht geÃƒÆ’Ã‚Â¶ffnet.");
                AhScreenUtil.dumpScreen();
                return;
            }
            buyFlow("player:" + player, -1, false, true, true, 0);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] buyauktion Fehler: " + e);
            message("Ãƒâ€šÃ‚Â§cBuyauktion fehlgeschlagen: " + e.getMessage());
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
                message("Ãƒâ€šÃ‚Â§cKein letzter Flip vorhanden ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ erst Ãƒâ€šÃ‚Â§f/ahflip start Ãƒâ€šÃ‚Â§cund auf einen Flip warten.");
                return 0;
            }
            System.out.println("[AH-Flipper] buy last: id=" + flipId + " worth=" + worth
                    + " alter=" + ageSeconds + "s");
            message("Ãƒâ€šÃ‚Â§7Kaufe letzten Flip: Ãƒâ€šÃ‚Â§f" + flipId + (ageSeconds >= 0 ? " Ãƒâ€šÃ‚Â§7(" + ageSeconds + "s alt)" : ""));
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
            message("Ãƒâ€šÃ‚Â§7Sende: Ãƒâ€šÃ‚Â§f" + command);
        }
    }

    static boolean sendCommandSilent(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            message("Ãƒâ€šÃ‚Â§cNicht verbunden.");
            return false;
        }
        client.getConnection().sendChat(command);
        return true;
    }

    public static void message(String text) {
        CoflModClient.displayModMessage(Component.literal(text));
    }
}



