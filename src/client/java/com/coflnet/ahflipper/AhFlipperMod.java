package com.coflnet.ahflipper;

import com.coflnet.CoflModClient;
import com.coflnet.flipper.trading.ScreenUtil;
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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class AhFlipperMod {
    public static final AhFlipperConfig CONFIG = AhFlipperConfig.load();
    private static KeyMapping toggleKey;
    private static volatile boolean running;
    private static volatile boolean recon;
    private static volatile boolean autoBuy;
    private static volatile boolean busy;
    private static volatile boolean lastBuyOk;
    private static volatile boolean autoWorkerRunning;
    private static volatile boolean debugAnalyze;
    private static volatile boolean debugWorkerRunning;
    private static final java.util.List<AutoCandidate> AUTO_QUEUE = new java.util.ArrayList<>();
    private static final java.util.List<AutoCandidate> DEBUG_QUEUE = new java.util.ArrayList<>();
    private static final java.util.ArrayDeque<ListingTask> LISTING_QUEUE = new java.util.ArrayDeque<>();
    private static volatile String lastFlipId;
    private static volatile long lastFlipWorth;
    private static volatile long lastFlipAt;
    private static volatile String lastBoughtTag = "";
    private static volatile String lastBoughtName = "";
    private static volatile long lastBoughtValue;
    private static volatile long lastBoughtPrice;
    private static final java.util.concurrent.atomic.AtomicBoolean SOLD_CLAIM_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastSoldClaimAt;
    private static String lastScreenKey = "";

    private record AutoCandidate(String id, long value, long profit, long at, boolean prevalidated) {
        private AutoCandidate(String id, long value, long profit, long at) {
            this(id, value, profit, at, false);
        }
    }

    private record ListingTask(String tag, String name, long value, long boughtPrice, long at) {
    }

    public static void init() {
        registerKeybinding();
        registerCommands();
        registerSoldChatClaim();
        AhDashboard.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.consumeClick()) {
                toggle();
            }
            if (recon || running) {
                trackScreens(client);
            }
        });
    }

    public static KeyMapping toggleKey() {
        return toggleKey;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean wantsFlips() {
        return running || debugAnalyze;
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
                .then(ClientCommands.literal("autobuy").executes(AhFlipperMod::autoBuyCommand))
                .then(ClientCommands.literal("scan").executes(AhFlipperMod::scanCommand))
                .then(ClientCommands.literal("stream").executes(AhFlipperMod::streamCommand))
                .then(ClientCommands.literal("feed").executes(AhFlipperMod::streamCommand))
                .then(ClientCommands.literal("tooltip").executes(AhFlipperMod::tooltipCommand))
                .then(ClientCommands.literal("automations").executes(AhFlipperMod::automationsCommand))
                .then(ClientCommands.literal("buy")
                        .then(ClientCommands.argument("auktion", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(AhFlipperMod::buyCommand)))
                .then(ClientCommands.literal("buycurrent").executes(AhFlipperMod::buyCurrentCommand))
                .then(ClientCommands.literal("buyauktion")
                        .then(ClientCommands.argument("spieler", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(AhFlipperMod::buyPlayerAuctionCommand)))
                .then(ClientCommands.literal("analyze5").executes(AhFlipperMod::analyze5Command))
                .then(ClientCommands.literal("dump").executes(context -> {
                    AhScreenUtil.dumpScreen();
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(AhFlipperMod::statusCommand))
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
            message("§cAH-Flipper kann nur auf Hypixel SkyBlock gestartet werden.");
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
        message("§6[AH-Flipper] §aaktiv §7(Live "
                + (CONFIG.liveFeed ? "§aan" : "§caus") + "§7, Scanner "
                + (CONFIG.autoScan ? "§aan" : "§caus") + "§7, Auto-Buy "
                + (autoBuy ? "§aan" : "§caus") + "§7).");
    }

    private static void stop() {
        running = false;
        recon = false;
        AhLiveScanner.stop();
        CoflFlipStream.stop();
        AhScanner.stop();
        synchronized (AUTO_QUEUE) {
            AUTO_QUEUE.clear();
        }
        Trader trader = Trader.get();
        if (trader != null && !busy) {
            trader.releaseAhPriority();
        }
        message("§6[AH-Flipper] §cgestoppt.");
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

    private static int toggleCommand(CommandContext<FabricClientCommandSource> context) {
        toggle();
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

    private static int statusCommand(CommandContext<FabricClientCommandSource> context) {
        message("§6[Flipper] §7AH-Status: " + (running ? "§aAKTIV" : "§cGESTOPPT")
                + (recon ? " §7(Recon an)" : ""));
        message("§7Exit: §fmin(Craft-Cost, Cofl-LBIN) §7· Fee: §f"
                + String.format(java.util.Locale.US, "%.1f%%", CONFIG.ahFeePercent)
                + " §7· Min. Profit: §f" + String.format(java.util.Locale.US, "%.0f%%", CONFIG.minProfitPercent)
                + " §7· Min. Item: §f" + ScreenUtil.formatCoins(CONFIG.minItemCoins));
        message("§7Regeln: Total-Sales > §f" + CONFIG.minTotalSales
                + " §7· Clean ≥ §f" + CONFIG.minCleanSales + " §7nur bei Addon ≤ §f"
                + String.format(java.util.Locale.US, "%.0fM", CONFIG.cleanSalesAddonValueCoins / 1_000_000.0)
                + " §7· CV ≤ §f" + String.format(java.util.Locale.US, "%.0f%%", CONFIG.maxDeviationPercent));
        message("§7Addon: ≥ §f" + CONFIG.addonMinSalesPerWeek + "x/Woche"
                + " §7· Addonwert ≥ §f" + String.format(java.util.Locale.US, "%.0fM", CONFIG.minAddonValueMillions));
        message("§7Pets: Level-1-Sales > §f" + CONFIG.petLevel1MinSales
                + " §7· Level-Sales > §f" + CONFIG.petLevelMinSales + " §7(nicht clean)");
        message("§7Kaufreihenfolge: §fFIFO §7(erster passender Treffer, kein Score-Vergleich)");
        message("§7Max. Auktionen: §f" + CONFIG.maxAuctions
                + " §7· Reserve: §f" + ScreenUtil.formatCoins(CONFIG.reserveCoins));
        message("§7Auto-Buy: " + (autoBuy ? "§aan" : "§caus")
                + " §7· Kauf läuft: " + (busy ? "§eja" : "§7nein")
                + " §7· Top-5-Analyse: " + (debugAnalyze ? "§aan" : "§caus"));
        int queued;
        synchronized (AUTO_QUEUE) {
            queued = AUTO_QUEUE.size();
        }
        long age = lastFlipAt > 0 ? (System.currentTimeMillis() - lastFlipAt) / 1000 : -1;
        message("§7Letzter Flip: §f" + (lastFlipId == null ? "-" : lastFlipId)
                + " §7· Profit §f" + (lastFlipWorth > 0 ? ScreenUtil.formatCoins(lastFlipWorth) : "-")
                + " §7· Alter §f" + (age >= 0 ? age + "s" : "-")
                + " §7· Queue §f" + queued);
        String conn = CoflCore.CoflCore.Wrapper != null
                ? CoflCore.CoflCore.Wrapper.GetStatus() : "null";
        message("§7Cofl: §f" + conn);
        message("§7Live: " + AhLiveScanner.status());
        message("§7Cofl-Stream: " + CoflFlipStream.status());
        message("§7Scanner: " + AhScanner.status());
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
        Thread thread = new Thread(AhFlipperMod::claimBlocking, "skycofl-ah-claim");
        thread.setDaemon(true);
        thread.start();
    }

    private static void registerSoldChatClaim() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onSoldAuctionChat(message);
            }
        });
    }

    private static void onSoldAuctionChat(Component message) {
        if (message == null || !running) {
            return;
        }
        String lower = ScreenUtil.cleanName(message.getString()).toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("auction") || !lower.contains("coins") || !lower.contains("click")) {
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
        message("\u00a77Verkaufte Auktion erkannt \u2013 claim l\u00e4uft ...");
        Thread thread = new Thread(AhFlipperMod::soldClaimWorker, "skycofl-ah-sold-claim");
        thread.setDaemon(true);
        thread.start();
    }

    private static void soldClaimWorker() {
        try {
            long deadline = System.currentTimeMillis() + 30000;
            while (busy && running && System.currentTimeMillis() < deadline) {
                ScreenUtil.sleep(100);
            }
            if (!running || busy) {
                System.out.println("[AH-Flipper] Sold-Claim \u00fcbersprungen (busy=" + busy
                        + " running=" + running + ")");
                return;
            }
            busy = true;
            ensureAhPriority();
            try {
                claimSoldAuctionsBlocking();
            } finally {
                busy = false;
                releaseAhPriorityIfIdle();
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Sold-Claim fehlgeschlagen: " + e);
            message("\u00a7cSold-Claim fehlgeschlagen: " + e.getMessage());
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
                        ScreenUtil.sleep(900);
                        continue;
                    }
                    ScreenUtil.closeCurrentScreen();
                    ScreenUtil.sleep(250);
                }
                if (!openManageAuctions()) {
                    System.out.println("[AH-Flipper] Sold-Claim: Manage Auctions nicht erreichbar.");
                    break;
                }
                Integer soldSlot = AhScreenUtil.findSoldAuctionSlot();
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
            message("\u00a7a" + claimed + " verkaufte Auktion(en) geclaimt.");
            return true;
        }
        message("\u00a77Keine verkaufte Auktion zum Claimen gefunden.");
        return false;
    }

    private static boolean openManageAuctions() {
        AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
        if (screen == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                || screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
            return true;
        }
        if (screen != AhScreenUtil.AhScreen.NONE) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(250);
        }
        sendCommandSilent("/ah");
        if (!waitForScreenUntil(0L, 3500, AhScreenUtil.AhScreen.AH_MAIN,
                AhScreenUtil.AhScreen.MANAGE_AUCTIONS,
                AhScreenUtil.AhScreen.MANAGE_ORDERS)) {
            if (!waitForTitle(500, "auction")) {
                return false;
            }
        }
        ScreenUtil.sleep(350);
        screen = AhScreenUtil.currentScreen();
        if (screen == AhScreenUtil.AhScreen.MANAGE_AUCTIONS
                || screen == AhScreenUtil.AhScreen.MANAGE_ORDERS) {
            return true;
        }
        int manage = findManageAuctionsButton();
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
        ScreenUtil.sleep(300);
        return true;
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
        ScreenUtil.clickSlot(slot, 0);
        System.out.println("[AH-Flipper] Sold-Claim: Collect-Coins geklickt (Slot " + slot + ").");
        ScreenUtil.sleep(700);
        return true;
    }

    private static boolean claimBlocking() {
        return claimBlocking("");
    }

    private static boolean claimBlocking(String wantedName) {
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
                    message("§cAH-Menü nicht erkannt.");
                    return false;
                }
                ScreenUtil.sleep(400);
                int bids = ScreenUtil.findButton("manage bids");
                if (bids < 0) {
                    bids = ScreenUtil.findButton("your bids");
                }
                if (bids < 0) {
                    bids = ScreenUtil.findButton("manage orders");
                }
                if (bids < 0 && openManageAuctions()) {
                    bids = ScreenUtil.findButton("manage bids");
                    if (bids < 0) {
                        bids = ScreenUtil.findButton("your bids");
                    }
                    if (bids < 0) {
                        bids = ScreenUtil.findButton("manage orders");
                    }
                }
                if (bids < 0) {
                    message("§c'Manage Bids/Orders' nicht gefunden.");
                    AhScreenUtil.dumpScreen();
                    return false;
                }
                ScreenUtil.clickSlot(bids, 0);
                if (!waitForClaimScreen(3500)) {
                    message("§c'Bids/Orders'-Menü hat nicht geöffnet.");
                    AhScreenUtil.dumpScreen();
                    return false;
                }
            }
            ScreenUtil.sleep(450);
            if (wantedName != null && !wantedName.isBlank()) {
                int itemSlot = waitForSlotByName(wantedName, 1500);
                if (itemSlot >= 0) {
                    ScreenUtil.clickSlot(itemSlot, 0);
                    ScreenUtil.sleep(700);
                    if (collectOpenAuction(wantedName)) {
                        return true;
                    }
                    if (waitForInventoryItem("", wantedName, 1500)) {
                        message("§aItem geclaimt: §f" + wantedName);
                        return true;
                    }
                    System.out.println("[AH-Flipper] Claim: Collect-Auction-Slot nach Item-Klick nicht gefunden.");
                } else {
                    System.out.println("[AH-Flipper] Claim: kein Slot für " + wantedName);
                }
            }
            int claimed = clickClaimableItems();
            if (claimed > 0) {
                message("§a" + claimed + " Item(s) geclaimt.");
                return true;
            }
            int claimAll = ScreenUtil.findButton("claim all");
            if (claimAll >= 0) {
                ScreenUtil.clickSlot(claimAll, 0);
                ScreenUtil.sleep(600);
                message("§aClaim All geklickt.");
                return true;
            }
            message("§7Nichts zu claimen (kein Item-Slot/'Click to claim').");
            AhScreenUtil.dumpScreen();
            return false;
        } catch (Exception e) {
            message("§cClaim fehlgeschlagen: " + e.getMessage());
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
        System.out.println("[AH-Flipper] Collect Auction geklickt: Slot " + slot);
        if (wantedName == null || wantedName.isBlank()) {
            message("§aCollect Auction geklickt.");
            return true;
        }
        if (waitForInventoryItem("", wantedName, 2500)) {
            message("§aItem geclaimt: §f" + wantedName);
        } else {
            message("§eCollect Auction geklickt - prüfe Inventar ...");
        }
        return true;
    }

    private static int clickClaimableItems() {
        int clicked = 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            Integer slot = AhScreenUtil.findSlotWithLore("click to claim");
            if (slot == null || slot < 0) {
                slot = AhScreenUtil.findSlotWithLore("click to pick up item");
            }
            if (slot == null || slot < 0) {
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


    static boolean isAutoBuyOn() {
        return autoBuy;
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
        if (isOwnPlayer(playerName)) {
            return true;
        }
        String ownId = ownPlayerId();
        return !ownId.isBlank() && seller != null
                && seller.replace("-", "").equalsIgnoreCase(ownId);
    }

    private static void ensureAhPriority() {
        Trader trader = Trader.get();
        if (trader != null && trader.isRunning()) {
            trader.yieldToAhFlipper();
        }
    }

    private static void releaseAhPriorityIfIdle() {
        boolean idle;
        synchronized (AUTO_QUEUE) {
            idle = AUTO_QUEUE.isEmpty();
        }
        synchronized (LISTING_QUEUE) {
            idle = idle && LISTING_QUEUE.isEmpty();
        }
        if (!idle || busy) {
            return;
        }
        Trader trader = Trader.get();
        if (trader != null) {
            trader.releaseAhPriority();
        }
    }

    static void onStreamFlip(String id, String tag, String name, long cost,
                             long lowestBin, long profit) {
        if (id == null || id.isBlank() || profit <= 0) {
            return;
        }
        if (debugAnalyze) {
            synchronized (DEBUG_QUEUE) {
                DEBUG_QUEUE.add(new AutoCandidate(id, lowestBin, profit,
                        System.currentTimeMillis()));
            }
            ensureDebugWorker();
        }
        if (!running || !autoBuy) {
            return;
        }
        if (cost < CONFIG.minItemCoins) {
            System.out.println("[AH-Flipper] Stream-Gate: " + tag + " zu billig (" + cost
                    + " < " + CONFIG.minItemCoins + ")");
            return;
        }
        message("§6[Stream] §f" + tag + " §7@ §f" + ScreenUtil.formatCoins(cost)
                + " §7→ §a+" + ScreenUtil.formatCoins(profit));
        if (enqueueAuto(id, lowestBin, profit)) {
            ensureAutoWorker();
        }
    }

    private static boolean enqueueAuto(String id, long value, long profit) {
        synchronized (AUTO_QUEUE) {
            for (AutoCandidate candidate : AUTO_QUEUE) {
                if (candidate.id.equals(id)) {
                    return false;
                }
            }
            AUTO_QUEUE.add(new AutoCandidate(id, value, profit, System.currentTimeMillis()));
        }
        ensureAhPriority();
        return true;
    }

    private static void enqueueListing(String tag, String name, long value, long boughtPrice) {
        synchronized (LISTING_QUEUE) {
            LISTING_QUEUE.add(new ListingTask(tag, name, value, boughtPrice,
                    System.currentTimeMillis()));
            if (LISTING_QUEUE.size() > 50) {
                LISTING_QUEUE.removeFirst();
            }
        }
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
        ensureAhPriority();
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
        message("§6[Live] §f" + id + " §7in Kauf-Queue");
        ensureAutoWorker();
        return true;
    }

    static boolean isBusy() {
        return busy;
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
        if (autoBuy && flip.Id != null) {
            if (enqueueAuto(flip.Id, profit, profit)) {
                ensureAutoWorker();
            }
        }
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
                if (best == null || candidate.at < best.at) {
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
                while (busy && debugAnalyze) {
                    ScreenUtil.sleep(200);
                }
                if (!debugAnalyze) {
                    break;
                }
                busy = true;
                try {
                    int rank = 1;
                    for (AutoCandidate candidate : batch) {
                        if (!debugAnalyze) {
                            break;
                        }
                        analyzeCandidate(candidate, rank);
                        rank++;
                        ScreenUtil.sleep(400);
                    }
                } finally {
                    busy = false;
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
                ScreenUtil.closeCurrentScreen();
                ScreenUtil.sleep(250);
            }
            sendCommandSilent("/viewauction " + candidate.id);
            if (!waitForTitle(4000, "bin auction view")) {
                System.out.println("[AH-Flipper] Top-" + rank + ": Auktion nicht geöffnet");
                message("§c#" + rank + " Auktion nicht geöffnet: " + candidate.id);
                return;
            }
            long price = waitForBuyPrice(4000);
            AhItem item = AhItem.fromStack(AhScreenUtil.currentItem(13));
            if (item.tag.isBlank()) {
                System.out.println("[AH-Flipper] Top-" + rank + ": kein Item-Tag");
                message("§c#" + rank + " Item-Tag nicht erkannt.");
                AhScreenUtil.dumpScreen();
                ScreenUtil.closeCurrentScreen();
                return;
            }
            AhAnalyzer.Result result = AhAnalyzer.analyze(item, Math.max(price, 0), candidate.profit);
            System.out.println("[AH-Flipper] TOP" + rank + " " + result.shortSummary());
            logAnalysis(result);
            message((result.buyable ? "§a" : "§c") + "#" + rank + " " + result.shortSummary());
            for (String reason : result.reasons) {
                message("§c   - " + reason);
            }
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(250);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Top-" + rank + " Fehler: " + e);
            message("§c#" + rank + " Analyse fehlgeschlagen: " + e.getMessage());
        }
    }

    private static void autoWorkerLoop() {
        try {
            while (running && autoBuy) {
                AutoCandidate best = pickBestCandidate();
                if (best == null) {
                    if (processNextListing()) {
                        continue;
                    }
                    ScreenUtil.sleep(10);
                    continue;
                }
                while (busy && running) {
                    ScreenUtil.sleep(10);
                }
                if (!running || !autoBuy) {
                    break;
                }
                System.out.println("[AH-Flipper] Auto-Buy Kandidat: id=" + best.id
                        + " value=" + best.value + " profit=" + best.profit + " alter="
                        + (System.currentTimeMillis() - best.at) / 1000 + "s");
                busy = true;
                lastBuyOk = false;
                try {
                    buyFlow(best.id, best.profit, true, false, best.prevalidated(), best.value);
                } finally {
                    busy = false;
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
        if (!CONFIG.autoList) {
            return false;
        }
        ListingTask task = takeListing();
        if (task == null) {
            return false;
        }
        if (busy) {
            synchronized (LISTING_QUEUE) {
                LISTING_QUEUE.addFirst(task);
            }
            return false;
        }
        ensureAhPriority();
        busy = true;
        try {
            listPurchased(task.tag, task.name, task.value, task.boughtPrice);
        } finally {
            busy = false;
            releaseAhPriorityIfIdle();
        }
        return true;
    }

    private static void listPurchased(String tag, String name, long value, long boughtPrice) {
        if (AhScreenUtil.findInventorySlot(tag, name) < 0
                && !waitForInventoryItem(tag, name, 5000)) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                message("Item nicht im Inventar - Claim-Versuch " + attempt + "/2 ...");
                claimBlocking(name);
                if (waitForInventoryItem(tag, name, 3000)) {
                    break;
                }
                ScreenUtil.sleep(400);
            }
        }
        long price = listingPrice(tag, name, value, boughtPrice);
        if (price <= 0) {
            message("\u00a7cKein sicherer Listenpreis - Item wird nicht gelistet: " + tag);
            return;
        }
        boolean listed = false;
        for (int attempt = 1; attempt <= 2; attempt++) {
            boolean fallback = attempt == 2;
            long deadline = fallback ? System.currentTimeMillis() + 35_000L : 0L;
            if (fallback) {
                message("Listing fehlgeschlagen - Fallback /ah -> Manage Auctions -> Create Auction -> Item ...");
                System.out.println("[AH-Flipper] Listing-Fallback fuer " + tag
                        + " (max. 35s, Manage Auctions -> Create Auction)");
            }
            if (listFlow(tag, name, price, false, deadline)) {
                listed = true;
                break;
            }
            System.out.println("[AH-Flipper] Listing-Versuch " + attempt + "/2 fehlgeschlagen: "
                    + tag);
            if (attempt < 2) {
                message("Listing fehlgeschlagen - Versuch " + (attempt + 1) + "/2 ...");
                if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                    ScreenUtil.closeCurrentScreen();
                }
                ScreenUtil.sleep(500);
            }
        }
        if (!listed) {
            message("\u00a7cListing nach 2 Versuchen fehlgeschlagen: " + tag);
            closeOpenScreens();
        }
    }

    private static long listingPrice(String tag, String name, long value, long boughtPrice) {
        AhItem item = AhScreenUtil.findInventoryItem(tag, name);
        if (item != null && !item.tag.equalsIgnoreCase(tag)) {
            item = null;
        }
        AhValuation.Estimate estimate = AhValuation.estimate(item, "");
        double feeFraction = AhFlipperMod.CONFIG.ahFeePercent / 100.0;
        long minNetPrice = boughtPrice > 0
                ? Math.round(boughtPrice / Math.max(0.01, 1.0 - feeFraction)) : 0;
        long fresh = estimate.estimated;
        long expected;
        if (value > 0) {
            if (fresh > 0) {
                expected = Math.min(fresh, value);
                if (expected < minNetPrice) {
                    expected = Math.max(value, minNetPrice);
                    System.out.println("[AH-Flipper] Listing: " + tag + " frische Bewertung "
                            + fresh + " unter Netto " + minNetPrice
                            + " - nutze Kauf-Bewertung " + value);
                }
            } else {
                expected = Math.max(value, minNetPrice);
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
                + estimate.comparableExcluded + " Ausrei\u00dfer) AskSafety="
                + estimate.askSafetyValue
                + " Ceiling=" + estimate.ceiling
                + " Frisch=" + fresh + " Kaufwert=" + value + " Netto=" + minNetPrice
                + " -> " + expected
                + (boughtPrice > 0 ? " (Kauf " + boughtPrice + ")" : ""));
        return Math.max(1, expected);
    }

    private static synchronized void startBuy(String id, long expectedProfit, boolean auto) {
        if (busy) {
            if (!auto) {
                message("§cEs läuft bereits ein Kauf.");
            }
            return;
        }
        if (id == null || id.isEmpty()) {
            message("§cKeine Auktions-ID.");
            return;
        }
        busy = true;
        Thread thread = new Thread(() -> buyFlow(id, expectedProfit, auto), "skycofl-ah-buy");
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
        try {
            System.out.println("[AH-Flipper] buyFlow: id=" + id + " erwartet=" + expectedProfit
                    + " auto=" + auto + " offen=" + alreadyOpen);
            long settle = prevalidated ? 10 : (auto ? 50 : 250);
            ScreenUtil.sleep(settle);
            if (!alreadyOpen) {
                Trader trader = Trader.get();
                if (trader != null) {
                    trader.closeBazaarGui();
                }
                AhScreenUtil.AhScreen open = AhScreenUtil.currentScreen();
                if (open != AhScreenUtil.AhScreen.NONE) {
                    ScreenUtil.closeCurrentScreen();
                    ScreenUtil.sleep(settle);
                }
            }
            long purseBefore = ScreenUtil.readPurse();
            if (!alreadyOpen) {
                sendCommandSilent("/viewauction " + id);
                if (!waitForTitle(1500, "bin auction view")) {
                    System.out.println("[AH-Flipper] BUYABLE-Kauf abgebrochen (Auktion evtl. vorbei): id="
                            + id + " erwartet=" + expectedProfit);
                    message((auto ? "§eAuto-Buy: " : "§c") + "Auktion nicht geöffnet: " + id);
                    return;
                }
            } else if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.BIN_VIEW) {
                message("§cKeine BIN-Auktion geöffnet.");
                return;
            }
            System.out.println("[AH-Flipper] BIN-View offen, lese Preis");
            if (AhScreenUtil.isOwnAuctionCurrent()) {
                message("§cEigene Auktion – übersprungen.");
                return;
            }
            long price = waitForBuyPrice(800);
            if (price <= 0) {
                System.out.println("[AH-Flipper] Kein Preis erkannt");
                message("§cKein Preis erkannt – übersprungen.");
                AhScreenUtil.dumpScreen();
                return;
            }
            System.out.println("[AH-Flipper] Preis=" + price);
            long bankroll = purseBefore > 0 ? purseBefore - CONFIG.reserveCoins : -1;
            long maxPrice = bankroll > 0
                    ? Math.round(bankroll * CONFIG.maxBankFractionPerOrder) : -1;
            if (maxPrice > 0 && price > maxPrice) {
                System.out.println("[AH-Flipper] Bankroll-Gate: Preis=" + price
                        + " max=" + maxPrice + " Bankroll=" + bankroll);
                message("§cPreis über " + Math.round(CONFIG.maxBankFractionPerOrder * 100)
                        + "% der Bankroll (§f" + ScreenUtil.formatCoins(maxPrice)
                        + "§c) – übersprungen.");
                return;
            }
            AhItem item = AhItem.fromStack(AhScreenUtil.currentItem(13));
            if (item.tag.isBlank()) {
                message("§cItem-Tag nicht erkannt – abgebrochen.");
                AhScreenUtil.dumpScreen();
                return;
            }
            AhAnalyzer.Result analysis = AhAnalyzer.analyze(item, price, expectedProfit, id);
            logAnalysis(analysis);
            message("§7Analyse: §f" + analysis.tag
                    + " §7· Gewinn §f" + String.format(java.util.Locale.US, "%.0f%%", analysis.profitPercent)
                    + " §7· Clean §f" + String.format(java.util.Locale.US, "%.0f%%", analysis.cleanScore * 100)
                    + " §7· Score §f" + String.format(java.util.Locale.US, "%.0f", analysis.score)
                    + " §7(Q §f" + String.format(java.util.Locale.US, "%.2f", analysis.quality) + "§7)");
            if (auto && analysis.profitPercent < CONFIG.minProfitPercent) {
                message("§c- Gewinn " + String.format(java.util.Locale.US, "%.0f%%",
                        analysis.profitPercent) + " (< " + CONFIG.minProfitPercent + "%)");
                message("§cKein Kauf.");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            if (!analysis.buyable) {
                for (String reason : analysis.reasons) {
                    message("§c- " + reason);
                }
                message("§cKein Kauf.");
                ScreenUtil.closeCurrentScreen();
                return;
            }
            if (!spamBuyButton()) {
                System.out.println("[AH-Flipper] Kaufbutton nicht aktiv");
                message("§cKaufbutton nicht aktiv – abgebrochen.");
                AhScreenUtil.dumpScreen();
                return;
            }
            if (!spamConfirmButton()) {
                System.out.println("[AH-Flipper] Confirm Purchase nicht bestätigt");
                message("§cBestätigung nicht durchgegangen.");
                AhScreenUtil.dumpScreen();
                return;
            }
            lastBuyOk = true;
            ScreenUtil.sleep(prevalidated || auto ? 50 : 250);
            long purseAfter = ScreenUtil.readPurse();
            System.out.println("[AH-Flipper] Kauf ausgelöst: Purse " + purseBefore + " -> " + purseAfter);
            if (purseBefore > 0 && purseAfter > 0 && purseAfter < purseBefore) {
                long purseDiff = purseBefore - purseAfter;
                boolean plausible = price <= 0
                        || (purseDiff <= price * 1.5 && purseDiff >= price * 0.5);
                if (plausible) {
                    message("\u00a7aGekauft! \u00a77Bezahlt: \u00a7f"
                            + ScreenUtil.formatCoins(purseDiff)
                            + (expectedProfit > 0 ? " \u00a77\u00bb Erwarteter Gewinn: \u00a7f"
                            + ScreenUtil.formatCoins(expectedProfit) : ""));
                } else {
                    message("\u00a7aGekauft! \u00a77Screen-Preis: \u00a7f"
                            + ScreenUtil.formatCoins(price)
                            + " \u00a77(Purse-Diff \u00a7f" + ScreenUtil.formatCoins(purseDiff)
                            + "\u00a77 weicht ab - Bazaar-Escrow?)"
                            + (expectedProfit > 0 ? " \u00a77\u00bb Erwarteter Gewinn: \u00a7f"
                            + ScreenUtil.formatCoins(expectedProfit) : ""));
                }
            } else {
                message("§eKauf ausgelöst – Purse: "
                        + (purseBefore > 0 ? ScreenUtil.formatCoins(purseBefore) : "?")
                        + " → " + (purseAfter > 0 ? ScreenUtil.formatCoins(purseAfter) : "?"));
            }
            ScreenUtil.closeCurrentScreen();
            lastBoughtTag = analysis.tag;
            lastBoughtName = item.displayName;
            lastBoughtValue = analysis.expectedValue;
            lastBoughtPrice = price;
            if (CONFIG.autoList && analysis.expectedValue > 0) {
                if (auto) {
                    enqueueListing(analysis.tag, item.displayName, analysis.expectedValue, price);
                } else {
                    listPurchased(analysis.tag, item.displayName, analysis.expectedValue, price);
                }
            }
        } catch (Exception e) {
            System.out.println("[AH-Flipper] buyFlow Fehler: " + e);
            message("§cKauf fehlgeschlagen: " + e.getMessage());
        } finally {
            busy = false;
            releaseAhPriorityIfIdle();
        }
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
        if (busy) {
            message("§cEs läuft bereits ein Kauf/Listing.");
            return 0;
        }
        busy = true;
        String finalTag = tag;
        String finalName = name;
        long finalPrice = price;
        Thread thread = new Thread(() -> {
            try {
                listFlow(finalTag, finalName, finalPrice);
            } finally {
                busy = false;
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
            ScreenUtil.sleep(300);
            if (AhScreenUtil.currentScreen() != AhScreenUtil.AhScreen.NONE) {
                ScreenUtil.closeCurrentScreen();
                sleepUntil(deadline, 300);
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
            sleepUntil(deadline, 450);
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen == AhScreenUtil.AhScreen.AH_MAIN
                    || screen == AhScreenUtil.AhScreen.OTHER) {
                int mgmt = findManageAuctionsButton();
                if (mgmt < 0) {
                    message("\u00a7c'Manage Auctions' nicht gefunden.");
                    AhScreenUtil.dumpScreen();
                    return false;
                }
                ScreenUtil.clickSlot(mgmt, 0);
                sleepUntil(deadline, 700);
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
            boolean previewOk = previewMatchesTag(tag);
            if (previewOk) {
                System.out.println("[AH-Flipper] Listing: Item bereits im Create-Slot, "
                        + "Rechtsklick wird \u00fcbersprungen.");
            } else if (skipItemClick) {
                System.out.println("[AH-Flipper] Listing-Fallback: Item nicht im Create-Slot "
                        + "erkannt - fahre ohne Inventar-Rechtsklick fort.");
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
                while (System.currentTimeMillis() < clickEnd && !previewMatchesTag(tag)) {
                    ScreenUtil.sleep(100);
                }
                previewOk = previewMatchesTag(tag);
                if (!previewOk) {
                    message("\u00a7cItem wurde nicht in den Verkaufs-Slot gelegt.");
                    AhScreenUtil.dumpScreen();
                    return false;
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
            SignInput.set(String.valueOf(price));
            ScreenUtil.clickSlot(priceButton, 0);
            sleepUntil(deadline, 800);
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
        for (int attempt = 0; attempt < 3; attempt++) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(150);
            if (AhScreenUtil.currentScreen() == AhScreenUtil.AhScreen.NONE) {
                return;
            }
        }
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
        boolean loggedCooldown = false;
        while (running && System.currentTimeMillis() <= deadline) {
            if (isConfirmPurchaseScreen()) {
                return true;
            }
            AhScreenUtil.AhScreen screen = AhScreenUtil.currentScreen();
            if (screen != AhScreenUtil.AhScreen.BIN_VIEW) {
                return false;
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

    private static int autoBuyCommand(CommandContext<FabricClientCommandSource> context) {
        autoBuy = !autoBuy;
        if (!autoBuy) {
            synchronized (AUTO_QUEUE) {
                AUTO_QUEUE.clear();
            }
            releaseAhPriorityIfIdle();
        }
        message("§6[AH-Flipper] §7Auto-Buy " + (autoBuy ? "§aan" : "§caus")
                + " §7(Profit-Gate: §f" + String.format(java.util.Locale.US, "%.0f%%", CONFIG.minProfitPercent) + "§7)");
        return 1;
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

    private static int tooltipCommand(CommandContext<FabricClientCommandSource> context) {
        CONFIG.tooltipValue = !CONFIG.tooltipValue;
        CONFIG.save();
        message("\u00a76[AH-Flipper] \u00a77Item-Wert-Tooltip "
                + (CONFIG.tooltipValue ? "\u00a7aan" : "\u00a7caus"));
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
        message("§6[Scan] §f" + tag + " §7@ §f" + ScreenUtil.formatCoins(price)
                + " §7· Wert §f" + ScreenUtil.formatCoins(itemValue)
                + " §a+" + ScreenUtil.formatCoins(profit)
                + " §7(" + String.format(java.util.Locale.US, "%.0f", profitPercent) + "%)");
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

    private static int analyze5Command(CommandContext<FabricClientCommandSource> context) {
        debugAnalyze = !debugAnalyze;
        if (!debugAnalyze) {
            synchronized (DEBUG_QUEUE) {
                DEBUG_QUEUE.clear();
            }
        }
        message("§6[AH-Flipper] §7Top-5-Analyse " + (debugAnalyze ? "§aan" : "§caus")
                + " §7(kauft nichts, loggt nur).");
        return 1;
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
        if (busy) {
            message("§cEs läuft bereits ein Kauf.");
            return 0;
        }
        busy = true;
        Thread thread = new Thread(() -> {
            try {
                buyFlow("current", 0, false, true);
            } finally {
                busy = false;
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
        if (busy) {
            message("§cEs läuft bereits ein Kauf.");
            return 0;
        }
        busy = true;
        Thread thread = new Thread(() -> {
            try {
                buyPlayerAuctionFlow(player);
            } finally {
                busy = false;
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

    private static int openCommand(CommandContext<FabricClientCommandSource> context) {        String player = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "spieler");
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

    private static boolean sendCommandSilent(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            message("§cNicht verbunden.");
            return false;
        }
        client.getConnection().sendChat(command);
        return true;
    }

    static void message(String text) {
        CoflModClient.displayModMessage(Component.literal(text));
    }
}
