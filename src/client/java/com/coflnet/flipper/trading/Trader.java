package com.coflnet.flipper.trading;

import com.coflnet.CoflModClient;
import com.coflnet.flipper.FlipperConfig;
import com.coflnet.flipper.analysis.FlipAnalyzer;
import com.coflnet.flipper.data.BazaarApi;
import com.coflnet.flipper.data.BazaarPriceHistory;
import com.coflnet.flipper.data.ItemNames;
import com.coflnet.flipper.data.MarketData;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Trader {

    private static final long CHAT_MIN_INTERVAL_MS = 1300L;
    private static final long CLICK_MIN_INTERVAL_MS = 500L;
    private static final long RECONCILE_INTERVAL_MS = 2 * 60 * 1000L;
    private static final long STUCK_LIMIT_MS = 90_000L;
    private static final long SCAN_MIN_INTERVAL_MS = 15_000L;
    private static final long CLAIM_LIST_RETRY_MS = 30_000L;

    private static volatile Trader instance;

    private final FlipperConfig cfg;
    private final OrderLedger ledger;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean ahPriorityActive;
    private volatile long ahPriorityUntil;
    private volatile boolean stopRequested;
    private Thread thread;
    private volatile MarketData market;
    private volatile long marketFetchedAt;
    private volatile long lastReconciliationAt;
    private volatile long lastChatAt;
    private volatile long lastClickAt;
    private volatile boolean traderOwnsGui;
    private volatile long lastBazaarFailAt;
    private volatile long lastOrdersFailAt;
    private volatile long lastLowBankrollAt;
    private volatile boolean manualClaimFlow;
    private volatile long flowStartAt;
    private volatile boolean lastScanHadOrders;
    private volatile long lastScannedSnapshot;
    private volatile long lastFullScanAt;
    private volatile long lastOrdersScanAt;
    private volatile boolean ordersBusy;
    private volatile boolean recentFillChat;
    private volatile boolean orderLimitReached;
    private volatile int lastScannedOrderCount;
    private volatile long heartbeat = System.currentTimeMillis();
    private Thread supervisor;
    private final List<ScreenUtil.GuiOrder> lastScannedBuyOrders = new ArrayList<>();
    private volatile List<ScreenUtil.GuiOrder> lastScannedOrders = List.of();
    private final Map<String, MarketData.Product> productByName = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> scannedOrderProductIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Long> itemBlacklist = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> blacklistCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> sellRepriceCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    private Trader(FlipperConfig cfg, OrderLedger ledger) {
        this.cfg = cfg;
        this.ledger = ledger;
    }

    public static void init(FlipperConfig cfg, OrderLedger ledger) {
        instance = new Trader(cfg, ledger);
    }

    public static Trader get() {
        return instance;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        paused = false;
        stopRequested = false;
        heartbeat = System.currentTimeMillis();
        thread = Thread.ofVirtual().name("skycofl-flipper").start(this::run);
        supervisor = Thread.ofVirtual().name("skycofl-flipper-supervisor").start(this::supervise);
    }

    private void supervise() {
        while (!stopRequested) {
            ScreenUtil.sleep(10_000);
            if (stopRequested) return;
            long idle = System.currentTimeMillis() - heartbeat;
            if (idle > 45_000L) {
                log("SUPERVISOR: Trader seit " + idle + "ms ohne Herzschlag. screen="
                        + ScreenUtil.currentScreen() + " market=" + (market != null ? market.products.size() : -1)
                        + " threadAlive=" + (thread != null && thread.isAlive()));
                ScreenUtil.closeCurrentScreen();
                traderOwnsGui = false;
                SignInput.clear();
                flowStartAt = 0;
                heartbeat = System.currentTimeMillis();
                if (thread == null || !thread.isAlive()) {
                    log("SUPERVISOR: Trader-Thread tot - starte neu");
                    stopRequested = false;
                    running = true;
                    thread = Thread.ofVirtual().name("skycofl-flipper").start(this::run);
                }
            }
        }
    }

    public synchronized void stop() {
        stopRequested = true;
        ahPriorityActive = false;
        ahPriorityUntil = 0;
        // Stop requests also come from the GUI; never sleep on the render thread.
        if (thread != null) thread.interrupt();
        if (supervisor != null) supervisor.interrupt();
    }

    public void setPaused(boolean value) {
        paused = value;
    }

    public boolean isAhPriority() {
        if (!ahPriorityActive) {
            return false;
        }
        if (System.currentTimeMillis() < ahPriorityUntil) {
            return true;
        }
        ahPriorityActive = false;
        return false;
    }

    public void yieldToAhFlipper() {
        boolean wasActive = isAhPriority();
        ahPriorityActive = true;
        ahPriorityUntil = System.currentTimeMillis() + 60_000L;
        traderOwnsGui = false;
        SignInput.clear();
        flowStartAt = 0;
        closeBazaarGui();
        if (!wasActive) {
            log("AH-Priority aktiv - Bazaar-GUI geschlossen und pausiert");
        }
    }

    public void releaseAhPriority() {
        if (!isAhPriority()) {
            ahPriorityActive = false;
            ahPriorityUntil = 0;
            return;
        }
        ahPriorityActive = false;
        ahPriorityUntil = 0;
        log("AH-Priority beendet - Bazaar darf fortsetzen");
    }

    public boolean hasBazaarGui() {
        return isBazaarScreen(ScreenUtil.currentScreen());
    }

    public void closeBazaarGui() {
        for (int i = 0; i < 3; i++) {
            ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
            if (!isBazaarScreen(screen)) {
                return;
            }
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(10);
        }
    }

    private static boolean isBazaarScreen(ScreenUtil.BzScreen screen) {
        return screen == ScreenUtil.BzScreen.BAZAAR
                || screen == ScreenUtil.BzScreen.SEARCH
                || screen == ScreenUtil.BzScreen.PRODUCT
                || screen == ScreenUtil.BzScreen.BUY_AMOUNT
                || screen == ScreenUtil.BzScreen.BUY_PRICE
                || screen == ScreenUtil.BzScreen.BUY_CONFIRM
                || screen == ScreenUtil.BzScreen.SELL_AMOUNT
                || screen == ScreenUtil.BzScreen.SELL_PRICE
                || screen == ScreenUtil.BzScreen.SELL_CONFIRM
                || screen == ScreenUtil.BzScreen.ORDERS
                || screen == ScreenUtil.BzScreen.ORDER_OPTIONS
                || screen == ScreenUtil.BzScreen.SIGN;
    }

    public MarketData marketSnapshot() {
        return market;
    }

    public boolean isBlacklisted(String productId) {
        Long expiry = itemBlacklist.get(productId);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            itemBlacklist.remove(productId);
            return false;
        }
        return true;
    }

    public void clearBlacklist() {
        itemBlacklist.clear();
        blacklistCounts.clear();
    }

    private void blacklistProduct(String productId, String displayName) {
        long blacklistMs = (long) Math.max(1.0, cfg.blacklistMinutes * 60_000.0);
        itemBlacklist.put(productId, System.currentTimeMillis() + blacklistMs);
        int count = blacklistCounts.merge(productId, 1, Integer::sum);
        if (count >= 3 && !cfg.blacklist.contains(productId)) {
            cfg.blacklist.add(productId);
            cfg.save();
            chat("§6[Flipper] §cItem dauerhaft ausgeschlossen (3 Fehlschläge in Folge): §f" + displayName);
        }
    }

    private void markProductSuccess(String productId) {
        if (productId != null) {
            blacklistCounts.remove(productId);
        }
    }

    public int blacklistCount() {
        return itemBlacklist.size();
    }

    public OrderLedger ledger() {
        return ledger;
    }

    private void chat(String message) {
        CoflModClient.displayModMessage(Component.literal(message));
    }

    private void log(String message) {
        System.out.println("[Flipper] " + message);
    }

    private void beat() {
        heartbeat = System.currentTimeMillis();
    }

    private void run() {
        chat("§6[Flipper] §aBazaar Flipper gestartet. §7Reserve: §f" + ScreenUtil.formatCoins(cfg.reserveCoins)
                + " §7· Max. Orders: §f" + cfg.maxOrders
                + " §7· Steuer: §f" + cfg.taxPercent + "%");
        long lastCycleAt = 0;
        long lastHeartbeatAt = System.currentTimeMillis();
        int watchdogStrikes = 0;
        long sessionStartAt = System.currentTimeMillis();
        long sessionMs = cfg.sessionMinutes * 60_000L;
        long pauseMs = cfg.pauseMinutes * 60_000L;
        while (!stopRequested) {
            try {
                heartbeat = System.currentTimeMillis();
                long heartbeatNow = System.currentTimeMillis();
                if (heartbeatNow - lastHeartbeatAt > 30_000L) {
                    lastHeartbeatAt = heartbeatNow;
                    watchdogStrikes++;
                    log("WATCHDOG: screen=" + ScreenUtil.currentScreen() + " paused=" + paused
                            + " onSkyBlock=" + ScreenUtil.onSkyBlock() + " combat=" + ScreenUtil.inCombatOrDead()
                            + " market=" + (market != null ? market.products.size() : -1)
                            + " ownsGui=" + traderOwnsGui + " stop=" + stopRequested);
                    if (watchdogStrikes >= 3) {
                        log("WATCHDOG: Selbstheilung - schließe GUI und setze Flags zurück");
                        ScreenUtil.closeCurrentScreen();
                        traderOwnsGui = false;
                        SignInput.clear();
                        flowStartAt = 0;
                        watchdogStrikes = 0;
                    }
                }
                if (sessionMs > 0 && System.currentTimeMillis() - sessionStartAt >= sessionMs) {
                    cleanupGui();
                    chat("§6[Flipper] §7Session beendet - Pause für §f" + cfg.pauseMinutes + " §7Min");
                    pauseInterruptibly(pauseMs);
                    if (stopRequested) {
                        break;
                    }
                    sessionStartAt = System.currentTimeMillis();
                    chat("§6[Flipper] §aNeue Session gestartet");
                    continue;
                }
                if (!waitUntilSafe()) {
                    continue;
                }
                refreshMarketIfStale();
                MarketData data = market;
                if (data == null) {
                    ScreenUtil.sleep(5000);
                    continue;
                }
                long now = System.currentTimeMillis();
                boolean urgent = recentFillChat
                        || !ledger.inState(Trade.State.FILLED).isEmpty()
                        || !ledger.inState(Trade.State.SOLD).isEmpty()
                        || !ledger.inState(Trade.State.CLAIMED).isEmpty();
                if (now - lastCycleAt >= cfg.cycleIntervalSeconds * 1000L || urgent) {
                    lastCycleAt = now;
                    runTradingCycle(data);
                }
                watchdogStrikes = 0;
                lastHeartbeatAt = System.currentTimeMillis();
                ScreenUtil.jitterSleep(2500, 2500);
            } catch (InterruptedException e) {
                stopRequested = true;
            } catch (Exception e) {
                System.out.println("[Flipper] cycle error: " + e.getMessage());
                e.printStackTrace();
                failPause("Unerwarteter Fehler: " + e.getMessage());
            }
        }
        running = false;
        cleanupGui();
        double profit = ledger.realizedProfit();
        chat("§6[Flipper] §cGestoppt. §7Abgeschlossene Trades: §f" + ledger.tradesCompleted()
                + " §7· Realisierter Gewinn: §f" + (profit >= 0 ? "§a+" : "§c") + ScreenUtil.formatCoins(profit));
    }

    private void runTradingCycle(MarketData data) {
        beat();
        if (isAhPriority()) {
            closeBazaarGui();
            return;
        }
        long purse = ScreenUtil.readPurse();
        for (int attempt = 0; attempt < 3 && purse < 0; attempt++) {
            ScreenUtil.sleep(400);
            purse = ScreenUtil.readPurse();
        }
        if (purse < 0) {
            log("Purse nicht lesbar - Zyklus übersprungen");
            return;
        }
        log("Zyklus: Purse=" + purse + " Bankroll=" + (purse - cfg.reserveCoins)
                + " offeneOrders=" + lastScannedOrderCount
                + " (Ledger: " + ledger.openOrderCount() + ")");
        boolean ordersBusy = prioritizeExistingOrders(data);
        beat();
        if (stopRequested) {
            return;
        }
        if (!ordersBusy) {
            boolean placed = placeNewBuyOrders(data, purse);
            if (!placed) {
                replaceWorstOrderIfWorthwhile(data, purse);
            }
        } else {
            log("Orders noch nicht up-to-date - keine neuen Käufe");
        }
        maybeReconcile();
        beat();
        cleanupGui();
    }

    private void cleanupGui() {
        if (traderOwnsGui) {
            traderOwnsGui = false;
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(600);
        }
        SignInput.clear();
        flowStartAt = 0;
    }

    private boolean stuck() {
        return flowStartAt > 0 && System.currentTimeMillis() - flowStartAt > STUCK_LIMIT_MS;
    }

    private void pauseInterruptibly(long totalMs) {
        long deadline = System.currentTimeMillis() + totalMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            ScreenUtil.sleep(500);
        }
    }

    private void failPause(String reason) {
        log("Fehler - 5s Pause: " + reason);
        cleanupGui();
        ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
        if (current == ScreenUtil.BzScreen.BAZAAR || current == ScreenUtil.BzScreen.PRODUCT
                || current == ScreenUtil.BzScreen.SEARCH || current == ScreenUtil.BzScreen.BUY_AMOUNT
                || current == ScreenUtil.BzScreen.BUY_PRICE || current == ScreenUtil.BzScreen.BUY_CONFIRM
                || current == ScreenUtil.BzScreen.SELL_AMOUNT || current == ScreenUtil.BzScreen.SELL_PRICE
                || current == ScreenUtil.BzScreen.SELL_CONFIRM || current == ScreenUtil.BzScreen.ORDERS
                || current == ScreenUtil.BzScreen.ORDER_OPTIONS || current == ScreenUtil.BzScreen.SIGN) {
            ScreenUtil.closeCurrentScreen();
        }
        pauseInterruptibly(ScreenUtil.jitterAround(5000, 2000));
    }

    private boolean waitUntilSafe() throws InterruptedException {
        long lastHeartbeatAt = System.currentTimeMillis();
        int watchdogStrikes = 0;
        while (!stopRequested) {
            heartbeat = System.currentTimeMillis();
            long heartbeatNow = System.currentTimeMillis();
            if (heartbeatNow - lastHeartbeatAt > 30_000L) {
                lastHeartbeatAt = heartbeatNow;
                watchdogStrikes++;
                log("WATCHDOG(wait): screen=" + ScreenUtil.currentScreen() + " paused=" + paused
                        + " onSkyBlock=" + ScreenUtil.onSkyBlock() + " combat=" + ScreenUtil.inCombatOrDead()
                        + " market=" + (market != null ? market.products.size() : -1)
                        + " ownsGui=" + traderOwnsGui + " stop=" + stopRequested);
                if (watchdogStrikes >= 3) {
                    log("WATCHDOG: Selbstheilung - schließe GUI und setze Flags zurück");
                    ScreenUtil.closeCurrentScreen();
                    traderOwnsGui = false;
                    SignInput.clear();
                    flowStartAt = 0;
                    watchdogStrikes = 0;
                }
            }
            if (isAhPriority()) {
                closeBazaarGui();
                ScreenUtil.sleep(100);
                continue;
            }
            if (paused) {
                ScreenUtil.sleep(2000);
                continue;
            }
            if (!ScreenUtil.onSkyBlock()) {
                ScreenUtil.sleep(2000);
                continue;
            }
            if (ScreenUtil.inCombatOrDead()) {
                ScreenUtil.sleep(2000);
                continue;
            }
            ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
            if (traderOwnsGui) {
                traderOwnsGui = false;
                ScreenUtil.closeCurrentScreen();
                ScreenUtil.sleep(800);
                continue;
            }
            if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                    || screen == ScreenUtil.BzScreen.SEARCH || screen == ScreenUtil.BzScreen.ORDERS
                    || screen == ScreenUtil.BzScreen.ORDER_OPTIONS || screen == ScreenUtil.BzScreen.BUY_AMOUNT
                    || screen == ScreenUtil.BzScreen.BUY_PRICE || screen == ScreenUtil.BzScreen.BUY_CONFIRM
                    || screen == ScreenUtil.BzScreen.SELL_AMOUNT || screen == ScreenUtil.BzScreen.SELL_PRICE
                    || screen == ScreenUtil.BzScreen.SELL_CONFIRM || screen == ScreenUtil.BzScreen.SIGN) {
                log("Übrig gebliebene Bazaar-GUI geschlossen: " + screen);
                ScreenUtil.closeCurrentScreen();
                ScreenUtil.jitterSleep(600, 300);
                continue;
            }
            if (screen == ScreenUtil.BzScreen.NONE) {
                return true;
            }
            ScreenUtil.sleep(1500);
        }
        return false;
    }

    private void refreshMarketIfStale() {
        long age = System.currentTimeMillis() - marketFetchedAt;
        if (market != null && age < cfg.apiRefreshSeconds * 1000L) {
            return;
        }
        beat();
        MarketData fresh = BazaarApi.fetch();
        beat();
        if (fresh != null && !fresh.products.isEmpty()) {
            market = fresh;
            marketFetchedAt = System.currentTimeMillis();
            com.coflnet.flipper.data.UndercutTracker.update(fresh);
        }
    }

    private void sendCommand(String command) {
        if (isAhPriority()) {
            return;
        }
        long now = System.currentTimeMillis();
        long wait = lastChatAt + CHAT_MIN_INTERVAL_MS - now;
        if (wait > 0) {
            ScreenUtil.sleep(wait + 150);
        }
        ScreenUtil.onClient(() -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client.player != null && client.getConnection() != null) {
                client.getConnection().sendCommand(command);
            }
        });
        lastChatAt = System.currentTimeMillis();
    }

    private void click(int slot, int button) {
        if (isAhPriority()) {
            return;
        }
        if (stuck()) {
            log("Stuck erkannt - erzwinge GUI-Close (Esc)");
            cleanupGui();
            return;
        }
        long now = System.currentTimeMillis();
        long wait = lastClickAt + CLICK_MIN_INTERVAL_MS - now;
        if (wait > 0) {
            ScreenUtil.sleep(wait + 100);
        }
        String name = ScreenUtil.slotName(slot);
        log("Klick Slot " + slot + " (button " + button + ") Item: " + name);
        ScreenUtil.clickSlot(slot, button);
        lastClickAt = System.currentTimeMillis();
    }

    private void clickDelay() {
        long now = System.currentTimeMillis();
        long wait = lastClickAt + CLICK_MIN_INTERVAL_MS - now;
        if (wait > 0) {
            ScreenUtil.jitterSleep(wait + 100, 150);
        }
        lastClickAt = System.currentTimeMillis();
    }

    private boolean screenGone() {
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen == ScreenUtil.BzScreen.NONE) {
            traderOwnsGui = false;
            log("GUI wurde extern geschlossen - breche Schritt ab");
            return true;
        }
        return false;
    }

    private boolean clickByName(String text, int button, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickIfNameContains(text, button)) {
                log("Klick verifiziert: " + what);
                return true;
            }
            ScreenUtil.sleep(400);
        }
        log("Klick-Ziel nicht gefunden: " + what);
        return false;
    }

    private boolean clickExactName(String text, int button, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickIfNameExact(text, button)) {
                log("Klick verifiziert (exakt): " + what);
                return true;
            }
            ScreenUtil.sleep(400);
        }
        log("Exaktes Klick-Ziel nicht gefunden: " + what);
        return false;
    }

    private boolean clickSign(long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickSignSlot(0)) {
                log("Klick verifiziert: " + what);
                return true;
            }
            ScreenUtil.sleep(400);
        }
        log("Sign nicht gefunden: " + what);
        return false;
    }

    private boolean clickOrder(String displayName, boolean sellSide, int button, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickOrderEntry(displayName, sellSide, button)) {
                log("Klick verifiziert: " + what);
                return true;
            }
            ScreenUtil.sleep(400);
        }
        log("Order-Eintrag nicht gefunden: " + what);
        return false;
    }

    private boolean clickConfirmAtomic(String acceptContains, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickConfirmItem(acceptContains)) {
                log("Klick verifiziert: Confirm (" + acceptContains + ")");
                return true;
            }
            ScreenUtil.sleep(400);
        }
        log("Confirm-Button nicht gefunden: " + acceptContains);
        return false;
    }

    private boolean openBazaar() {
        if (isAhPriority()) {
            return false;
        }
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen == ScreenUtil.BzScreen.NONE) {
            long now = System.currentTimeMillis();
            if (now - lastBazaarFailAt < 15_000L) {
                return false;
            }
            sendCommand("bz");
            if (ScreenUtil.waitForScreen(ScreenUtil.BzScreen.BAZAAR, 10_000)) {
                traderOwnsGui = true;
                flowStartAt = System.currentTimeMillis();
                ScreenUtil.sleep(400);
                return true;
            }
            if (ScreenUtil.waitForScreen(ScreenUtil.BzScreen.PRODUCT, 3000)) {
                traderOwnsGui = true;
                flowStartAt = System.currentTimeMillis();
                ScreenUtil.sleep(400);
                return true;
            }
            lastBazaarFailAt = now;
            chat("§6[Flipper] §cBazaar konnte nicht geöffnet werden (Booster Cookie aktiv?).");
            return false;
        }
        if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                || screen == ScreenUtil.BzScreen.SEARCH) {
            traderOwnsGui = true;
            return true;
        }
        if (screen == ScreenUtil.BzScreen.ORDERS || screen == ScreenUtil.BzScreen.ORDER_OPTIONS) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(600);
            return openBazaar();
        }
        log("Fremder Screen offen - warte auf Schließen: " + screen);
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            ScreenUtil.sleep(1000);
            screen = ScreenUtil.currentScreen();
            if (screen == ScreenUtil.BzScreen.NONE) {
                return openBazaar();
            }
            if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                    || screen == ScreenUtil.BzScreen.SEARCH) {
                traderOwnsGui = true;
                return true;
            }
            if (screen == ScreenUtil.BzScreen.ORDERS || screen == ScreenUtil.BzScreen.ORDER_OPTIONS) {
                ScreenUtil.closeCurrentScreen();
                ScreenUtil.sleep(600);
            }
        }
        log("Fremder Screen blieb offen");
        return false;
    }

    private boolean openOrdersGui() {
        if (isAhPriority()) {
            return false;
        }
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen == ScreenUtil.BzScreen.NONE) {
            long now = System.currentTimeMillis();
            if (now - lastOrdersFailAt < 15_000L) {
                return false;
            }
            sendCommand("managebazaarorders");
            if (ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDERS, 12_000)) {
                lastOrdersFailAt = 0;
                traderOwnsGui = true;
                flowStartAt = System.currentTimeMillis();
                ScreenUtil.sleep(400);
                return true;
            }
            lastOrdersFailAt = now;
            chat("§6[Flipper] §cOrders-GUI konnte nicht geöffnet werden.");
            return false;
        }
        if (screen == ScreenUtil.BzScreen.ORDERS) {
            traderOwnsGui = true;
            return true;
        }
        if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                || screen == ScreenUtil.BzScreen.SEARCH || screen == ScreenUtil.BzScreen.ORDER_OPTIONS) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(600);
            return openOrdersGui();
        }
        log("Fremder Screen offen - warte auf Schließen: " + screen);
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            ScreenUtil.sleep(1000);
            screen = ScreenUtil.currentScreen();
            if (screen == ScreenUtil.BzScreen.NONE) {
                return openOrdersGui();
            }
            if (screen == ScreenUtil.BzScreen.ORDERS) {
                traderOwnsGui = true;
                return true;
            }
        }
        log("Fremder Screen blieb offen");
        return false;
    }

    private boolean searchProduct(String displayName, String productId) {
        String remembered = cfg.searchNameOverrides.get(displayName);
        if (remembered != null) {
            log("Gespeicherter Suchname: " + remembered);
            if (searchProductOnce(remembered, productId)) {
                return true;
            }
        }
        if (searchProductOnce(displayName, productId)) {
            return true;
        }
        String cleaned = displayName.replaceAll("[^\\p{L}\\p{N}\\s]", "").trim();
        if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase(displayName)) {
            log("2. Such-Versuch ohne Sonderzeichen: " + cleaned);
            if (searchProductOnce(cleaned, productId)) {
                cfg.searchNameOverrides.put(displayName, cleaned);
                cfg.save();
                log("Suchname gemerkt: " + displayName + " -> " + cleaned);
                return true;
            }
        }
        return false;
    }

    private boolean productPageMatches(String productId, String fallbackName) {
        String actual = ScreenUtil.productName();
        if (actual == null || actual.isBlank()) {
            log("Produktname nicht lesbar - breche sicher ab");
            return false;
        }
        String expected = productId != null ? ItemNames.displayName(productId) : fallbackName;
        if (!ScreenUtil.sameName(actual, expected)) {
            log("Falsche Produktseite: erwartet='" + expected + "' tatsächlich='" + actual + "'");
            return false;
        }
        return true;
    }

    private boolean actualProductPriceSane(String productId, double buyPrice, double sellPrice) {
        BazaarPriceHistory.Snapshot history = BazaarPriceHistory.lookup(productId);
        if (cfg.requireHistoricalPrice) {
            if (history == null || history.volume < cfg.minHistoryVolume) {
                log("Weekly-Avg-Check: keine ausreichende Historie fuer " + productId
                        + " volume=" + (history != null ? history.volume : -1));
                return false;
            }
            double reference = history.referencePrice();
            if (reference <= 0) {
                log("Weekly-Avg-Check: ungueltige Referenz fuer " + productId);
                return false;
            }
            double buyDeviation = Math.abs(buyPrice - reference) / reference * 100.0;
            double sellDeviation = Math.abs(sellPrice - reference) / reference * 100.0;
            if (buyDeviation > cfg.maxHistoricalDeviationPercent
                    || sellDeviation > cfg.maxHistoricalDeviationPercent) {
                log(String.format(java.util.Locale.US,
                        "Weekly-Avg-Check FAIL %s: ref=%.1f buy=%.1f(%.1f%%) sell=%.1f(%.1f%%) max=%.1f%%",
                        productId, reference, buyPrice, buyDeviation, sellPrice, sellDeviation,
                        cfg.maxHistoricalDeviationPercent));
                return false;
            }
            log(String.format(java.util.Locale.US,
                    "Weekly-Avg-Check PASS %s: ref=%.1f buy=%.1f(%.1f%%) sell=%.1f(%.1f%%)",
                    productId, reference, buyPrice, buyDeviation, sellPrice, sellDeviation));
        }

        MarketData current = market;
        MarketData.Product actual = current != null ? current.products.get(productId) : null;
        if (actual == null) {
            log("Live-Preis-Check: Produkt fehlt: " + productId);
            return false;
        }
        double liveBuyReference = actual.avgSellPrice > 0 ? actual.avgSellPrice : actual.bestAsk;
        double liveSellReference = actual.avgBuyPrice > 0 ? actual.avgBuyPrice : actual.bestBid;
        if (liveBuyReference > 0) {
            double deviation = Math.abs(buyPrice - liveBuyReference) / liveBuyReference * 100.0;
            if (deviation > cfg.maxHistoricalDeviationPercent) {
                log(String.format(java.util.Locale.US,
                        "Live-Preis-Check FAIL %s buy: ref=%.1f buy=%.1f (%.1f%%)",
                        productId, liveBuyReference, buyPrice, deviation));
                return false;
            }
        }
        if (liveSellReference > 0) {
            double deviation = Math.abs(sellPrice - liveSellReference) / liveSellReference * 100.0;
            if (deviation > cfg.maxHistoricalDeviationPercent) {
                log(String.format(java.util.Locale.US,
                        "Live-Preis-Check FAIL %s sell: ref=%.1f sell=%.1f (%.1f%%)",
                        productId, liveSellReference, sellPrice, deviation));
                return false;
            }
        }
        return true;
    }

    private boolean searchProductOnce(String displayName, String productId) {
        boolean signSeen = false;
        for (int attempt = 0; attempt < 2 && !signSeen && !stopRequested; attempt++) {
            SignInput.set(displayName);
            if (!clickByName("Search", 0, 4000, "Search-Button")) {
                log("Search-Slot nicht gefunden. " + ScreenUtil.screenDebugInfo());
                return false;
            }
            long signDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < signDeadline) {
                ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
                if (current == ScreenUtil.BzScreen.PRODUCT) {
                    if (productPageMatches(productId, displayName)) {
                        return true;
                    }
                    cleanupGui();
                    return false;
                }
                if (current == ScreenUtil.BzScreen.SIGN) {
                    signSeen = true;
                    break;
                }
                ScreenUtil.sleep(120);
            }
        }
        if (!signSeen) {
            log("Such-Sign öffnete sich nicht. " + ScreenUtil.screenDebugInfo());
            return false;
        }
        long deadline = System.currentTimeMillis() + 3500;
        int clicks = 0;
        while (System.currentTimeMillis() < deadline) {
            ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
            if (current == ScreenUtil.BzScreen.PRODUCT) {
                if (productPageMatches(productId, displayName)) {
                    return true;
                }
                cleanupGui();
                return false;
            }
            if (stuck()) {
                cleanupGui();
                return false;
            }
            if (clicks < 2 && current != ScreenUtil.BzScreen.NONE
                    && current != ScreenUtil.BzScreen.SIGN
                    && current != ScreenUtil.BzScreen.UNKNOWN) {
                long clickStart = System.currentTimeMillis();
                if (clickExactName(displayName, 0, 2500, "Such-Ergebnis: " + displayName)) {
                    clicks++;
                    ScreenUtil.sleep(700);
                    for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(clickStart)) {
                        if (entry.text.toLowerCase().contains("mutation")) {
                            if (productId != null && !cfg.blacklist.contains(productId)) {
                                cfg.blacklist.add(productId);
                                cfg.save();
                            }
                            chat("§6[Flipper] §cKein Bazaar-Item (Mutation) - permanent ausgeschlossen: §f"
                                    + displayName);
                            cleanupGui();
                            return false;
                        }
                    }
                    ScreenUtil.sleep(600);
                    continue;
                }
            }
            ScreenUtil.sleep(200);
        }
        log("Produktseite nach Suche nicht erreicht. " + ScreenUtil.screenDebugInfo());
        return false;
    }

    private boolean placeBuyOrder(Trade trade, FlipAnalyzer.FlipPlan plan) {
        beat();
        log("Platziere Buy Order: " + plan.qty + "x " + plan.displayName + " @ " + plan.buyPrice);
        if (!openBazaar()) {
            log("openBazaar fehlgeschlagen");
            return false;
        }
        if (!searchProduct(plan.displayName, plan.productId)) {
            log("searchProduct fehlgeschlagen: " + plan.displayName);
            chat("§6[Flipper] §cItem nicht gefunden: §f" + plan.displayName);
            cleanupGui();
            return false;
        }
        if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.PRODUCT) {
            log("Nicht auf Produktseite vor Buy-Button. " + ScreenUtil.screenDebugInfo());
            cleanupGui();
            return false;
        }
        if (!productPageMatches(plan.productId, plan.displayName)) {
            chat("��6[Flipper] ��cFalsches Produkt erkannt - Kauf abgebrochen: ��f" + plan.displayName);
            blacklistProduct(plan.productId, plan.displayName);
            cleanupGui();
            return false;
        }
        String actualId = ItemNames.productId(ScreenUtil.productName());
        if (!actualProductPriceSane(actualId, plan.buyPrice, plan.sellPrice)) {
            chat("��6[Flipper] ��cWeekly-Avg-Check fehlgeschlagen - Kauf abgebrochen: ��f" + plan.displayName);
            blacklistProduct(plan.productId, plan.displayName);
            cleanupGui();
            return false;
        }
        if (!clickByName("Create Buy Order", 0, 5000, "Create Buy Order")) {
            log("Create Buy Order Button nicht gefunden");
            cleanupGui();
            return false;
        }
        if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.BUY_AMOUNT, 6000)) {
            log("BUY_AMOUNT Screen nicht erreicht");
            cleanupGui();
            return false;
        }
        SignInput.set(String.valueOf(plan.qty));
        if (!clickSign(5000, "Mengen-Sign")) {
            log("Mengen-Sign nicht gefunden");
            cleanupGui();
            return false;
        }
        if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.BUY_PRICE, 6000)) {
            log("BUY_PRICE Screen nicht erreicht");
            cleanupGui();
            return false;
        }
        ScreenUtil.sleep(ScreenUtil.jitter(400));
        SignInput.set(ScreenUtil.formatPrice(plan.buyPrice));
        if (!clickSign(5000, "Preis-Sign")) {
            log("Preis-Sign nicht gefunden");
            cleanupGui();
            return false;
        }
        if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.BUY_CONFIRM, 6000)) {
            log("BUY_CONFIRM Screen nicht erreicht");
            cleanupGui();
            return false;
        }
        ScreenUtil.sleep(ScreenUtil.jitter(400));
        if (!clickConfirmAtomic("Buy Order", 8000)) {
            log("Confirm-Button nicht gefunden");
            cleanupGui();
            return false;
        }
        if (!BazaarChatListener.waitForChat("Buy Order Setup!", 12_000)) {
            log("Keine 'Buy Order Setup!' Bestätigung im Chat");
            cleanupGui();
            return false;
        }
        trade.cost = plan.qty * plan.buyPrice;
        trade.buyPrice = plan.buyPrice;
        trade.sellPrice = plan.sellPrice;
        beat();
        cleanupGui();
        return true;
    }

    private boolean placeNewBuyOrders(MarketData data, long purse) {
        if (orderLimitReached) {
            log("Order-Limit erreicht - warte auf Claim/Cancel, keine neuen Käufe");
            return false;
        }
        double bankroll = Math.max(0, purse - cfg.reserveCoins);
        if (bankroll <= 0) {
            long now = System.currentTimeMillis();
            if (now - lastLowBankrollAt > 10 * 60 * 1000L) {
                lastLowBankrollAt = now;
                chat("§6[Flipper] §cZu wenig Geld: §fPurse " + ScreenUtil.formatCoins(purse)
                        + " §7· Reserve " + ScreenUtil.formatCoins(cfg.reserveCoins)
                        + " §7(Reserve in config/CoflSky/flipper.json änderbar)");
            }
            return false;
        }
        int open = lastScannedOrderCount;
        int availableSlots = Math.max(0, cfg.maxOrders - open);
        if (availableSlots <= 0) {
            return false;
        }
        Set<String> existing = new HashSet<>();
        for (String id : itemBlacklist.keySet()) {
            if (isBlacklisted(id)) {
                existing.add(id);
            }
        }
        existing.addAll(scannedOrderProductIds);
        List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.compute(data, cfg, bankroll, existing, availableSlots);
        log("Neue Käufe: " + plans.size() + " Kandidaten, Bankroll=" + bankroll + ", Slots=" + availableSlots);
        int placed = 0;
        for (FlipAnalyzer.FlipPlan plan : plans) {
            if (placed >= cfg.maxNewOrdersPerCycle || stopRequested) {
                break;
            }
            Trade trade = new Trade();
            trade.productId = plan.productId;
            trade.displayName = plan.displayName;
            trade.qty = plan.qty;
            trade.buyPrice = plan.buyPrice;
            trade.sellPrice = plan.sellPrice;
            trade.createdAt = System.currentTimeMillis();
            ledger.add(trade);
            if (placeBuyOrder(trade, plan)) {
                trade.state = Trade.State.BUY_OPEN;
                ledger.update(trade);
                chat("§6[Flipper] §7Buy Order: §f" + plan.qty + "x " + plan.displayName
                        + " §7@ §f" + ScreenUtil.formatPrice(plan.buyPrice)
                        + " §7(§f" + ScreenUtil.formatCoins(trade.cost) + "§7)"
                        + " §7→ erwarteter Gewinn: §a" + ScreenUtil.formatCoins(plan.expectedProfit())
                        + " §7· Coins/h: §a" + ScreenUtil.formatCoins(plan.coinsPerHour)
                        + " §7· Volumen: §f" + formatHourly(plan.hourlyVolume) + "/h");
                log(String.format(java.util.Locale.US,
                        "Formel %s: B=%.1f S=%.1f m=%.2f v=%.2f/h u=%.2f/%.2f c=%.3f q=%d "
                                + "P=%.0f r=%.2f e=%.4f Lim/h=%.0f λ=%.4f Score=%.0f",
                        plan.displayName, plan.buyPrice, plan.sellPrice, plan.profitPerUnit,
                        plan.hourlyVolume, plan.uBuy, plan.uSell, plan.c, plan.qty,
                        plan.coinsPerHour, plan.r, plan.e, plan.limitPerHour, plan.lambda, plan.score));
                placed++;
            } else {
                trade.state = Trade.State.ABANDONED;
                ledger.update(trade);
                if (orderLimitReached) {
                    chat("§6[Flipper] §eOrder-Limit erreicht – keine neuen Orders bis Claim/Cancel.");
                    break;
                }
                blacklistProduct(plan.productId, plan.displayName);
                chat("§6[Flipper] §cBuy Order fehlgeschlagen: §f" + plan.displayName
                        + " §7– Item " + (int) Math.max(1.0, cfg.blacklistMinutes) + " Min. geblacklistet");
                failPause("Buy Order fehlgeschlagen: " + plan.displayName);
                break;
            }
        }
        return placed > 0;
    }

    private static String formatHourly(double perHour) {
        if (perHour >= 1000) {
            return ScreenUtil.formatCoins(perHour);
        }
        return String.format(java.util.Locale.US, "%.1f", perHour);
    }

    private double buyOrderExpectedProfit(ScreenUtil.GuiOrder order) {
        MarketData.Product product = productFor(order.itemName);
        if (product == null || product.bestAsk <= 0 || order.unitPrice <= 0) {
            return -1;
        }
        double sellPrice = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
        double perUnit = sellPrice * (1.0 - cfg.taxPercent / 100.0) - order.unitPrice;
        long remaining = Math.max(1, order.amount - order.filled);
        return perUnit * remaining;
    }

    private void replaceWorstOrderIfWorthwhile(MarketData data, long purse) {
        int open = lastScannedOrderCount;
        int availableSlots = Math.max(0, cfg.maxOrders - open);
        if (availableSlots > 0 || orderLimitReached || lastScannedBuyOrders.isEmpty()) {
            return;
        }
        ScreenUtil.GuiOrder worst = null;
        double worstScore = Double.MAX_VALUE;
        for (ScreenUtil.GuiOrder order : lastScannedBuyOrders) {
            double score = buyOrderExpectedProfit(order);
            if (score < worstScore) {
                worstScore = score;
                worst = order;
            }
        }
        if (worst == null) {
            return;
        }
        double bankroll = Math.max(0, purse - cfg.reserveCoins);
        if (bankroll <= 0) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (String id : itemBlacklist.keySet()) {
            if (isBlacklisted(id)) {
                existing.add(id);
            }
        }
        existing.addAll(scannedOrderProductIds);
        List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.compute(data, cfg, bankroll, existing, 1);
        if (plans.isEmpty()) {
            return;
        }
        FlipAnalyzer.FlipPlan best = plans.get(0);
        double newScore = best.profitPerUnit * best.qty;
        boolean worthIt = worstScore <= 0 ? newScore > 0 : newScore >= worstScore * 1.2;
        if (!worthIt) {
            log("Ersatz nicht lohnend: neu=" + ScreenUtil.formatCoins(newScore)
                    + " alt=" + ScreenUtil.formatCoins(worstScore) + " (" + worst.itemName + ")");
            return;
        }
        log("Ersetze schwächste Buy Order: " + worst.itemName + " (" + ScreenUtil.formatCoins(worstScore)
                + ") durch " + best.displayName + " (" + ScreenUtil.formatCoins(newScore) + ")");
        Trade base = ensureTradeFor(worst);
        if (!cancelBuyOrder(base, "Ersatz", data)) {
            failPause("Ersatz-Cancel fehlgeschlagen: " + worst.itemName);
            return;
        }
        chat("§6[Flipper] §cBuy Order ersetzt: §f" + worst.itemName
                + " §8(" + ScreenUtil.formatCoins(worstScore) + "§8)");
        Trade trade = new Trade();
        trade.productId = best.productId;
        trade.displayName = best.displayName;
        trade.qty = best.qty;
        trade.buyPrice = best.buyPrice;
        trade.sellPrice = best.sellPrice;
        trade.createdAt = System.currentTimeMillis();
        ledger.add(trade);
        if (placeBuyOrder(trade, best)) {
            trade.state = Trade.State.BUY_OPEN;
            ledger.update(trade);
            chat("§6[Flipper] §7Neue Buy Order: §f" + best.qty + "x " + best.displayName
                    + " §7@ §f" + ScreenUtil.formatPrice(best.buyPrice)
                    + " §7→ erwarteter Gewinn: §a" + ScreenUtil.formatCoins(best.expectedProfit()));
        } else {
            trade.state = Trade.State.ABANDONED;
            ledger.update(trade);
            if (orderLimitReached) {
                chat("§6[Flipper] §eOrder-Limit erreicht – Ersatz-Kauf abgebrochen.");
                return;
            }
            blacklistProduct(best.productId, best.displayName);
            chat("§6[Flipper] §cErsatz-Kauf fehlgeschlagen: §f" + best.displayName
                    + " §7– " + (int) Math.max(1.0, cfg.blacklistMinutes) + " Min. geblacklistet");
        }
    }

    private boolean prioritizeExistingOrders(MarketData data) {
        long now = System.currentTimeMillis();
        boolean force = now - lastFullScanAt >= 60_000L;
        if (!force && now - lastOrdersScanAt < SCAN_MIN_INTERVAL_MS) {
            return ordersBusy;
        }
        if (!force && !needsOrderScan(data)) {
            return ordersBusy;
        }
        recentFillChat = false;
        if (!openOrdersGui()) {
            return true;
        }
        waitForOrderGuiStable(4000);
        List<ScreenUtil.GuiOrder> orders = ScreenUtil.scanOrders();
        lastScannedOrders = List.copyOf(orders);
        cleanupGui();
        beat();
        lastOrdersScanAt = System.currentTimeMillis();
        lastFullScanAt = System.currentTimeMillis();
        lastScannedSnapshot = data.lastUpdated;
        lastScanHadOrders = !orders.isEmpty();
        scannedOrderProductIds.clear();
        if (orders.isEmpty()) {
            lastScannedOrderCount = 0;
            if (orderLimitReached) {
                log("Order-Limit aufgehoben: Scan zeigt keine Orders");
                orderLimitReached = false;
            }
            ordersBusy = false;
            return false;
        }
        log("Order-Scan: " + orders.size() + " Orders gefunden");
        lastScannedOrderCount = orders.size();
        if (orderLimitReached && orders.size() < cfg.maxOrders) {
            log("Order-Limit aufgehoben: Scan zeigt nur " + orders.size() + " Orders");
            orderLimitReached = false;
        }
        lastScannedBuyOrders.clear();
        for (ScreenUtil.GuiOrder order : orders) {
            if (!order.sellSide) {
                lastScannedBuyOrders.add(order);
            }
        }
        productByName.clear();
        for (MarketData.Product product : data.products.values()) {
            productByName.putIfAbsent(ItemNames.nameKey(ItemNames.displayName(product.productId)), product);
        }

        boolean busy = false;
        Map<String, List<ScreenUtil.GuiOrder>> sellGroups = new java.util.LinkedHashMap<>();
        for (ScreenUtil.GuiOrder order : orders) {
            if (order.sellSide) {
                sellGroups.computeIfAbsent(order.itemName.toLowerCase(), k -> new ArrayList<>()).add(order);
            }
        }
        for (List<ScreenUtil.GuiOrder> group : sellGroups.values()) {
            String itemName = group.get(0).itemName;
            if (group.size() > 1) {
                log("Mehrere Sell Offers für " + itemName + " (" + group.size() + ") - zusammenführen");
                busy |= !mergeSellOrders(group, data);
            } else {
                ScreenUtil.GuiOrder order = group.get(0);
                MarketData.Product product = productFor(order.itemName);
                boolean outdated = product != null && product.bestAsk > 0
                        && product.bestAsk < order.unitPrice - 0.05;
                boolean coins = order.coinsClaimable && order.claimable > 0;
                log("Sell-Check: " + order.itemName + " outdated=" + outdated + " coins=" + coins
                        + " bestAsk=" + (product != null ? product.bestAsk : -1)
                        + " unit=" + order.unitPrice);
                if (coins) {
                    busy |= !processSellEntry(order, data);
                } else if (outdated) {
                    String key = order.itemName.toLowerCase();
                    Long last = sellRepriceCooldown.get(key);
                    long nowMs = System.currentTimeMillis();
                    if (last != null && nowMs - last < 5_000L) {
                        log("Reprice-Cooldown (busy): " + order.itemName);
                        busy = true;
                    } else {
                        busy |= !processSellEntry(order, data);
                    }
                }
            }
            if (stopRequested) {
                ordersBusy = true;
                return true;
            }
        }
        Map<String, List<ScreenUtil.GuiOrder>> buyGroups = new java.util.LinkedHashMap<>();
        List<ScreenUtil.GuiOrder> buyOrders = new ArrayList<>();
        for (ScreenUtil.GuiOrder order : orders) {
            if (!order.sellSide) {
                buyOrders.add(order);
                buyGroups.computeIfAbsent(ScreenUtil.cleanSpecials(order.itemName).toLowerCase(),
                        k -> new ArrayList<>()).add(order);
            }
        }
        List<Trade> batchCancel = new ArrayList<>();
        Set<ScreenUtil.GuiOrder> handled = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<ScreenUtil.GuiOrder> group : buyGroups.values()) {
            if (group.size() <= 1) {
                continue;
            }
            group.sort(Comparator.comparingDouble(o -> o.unitPrice));
            log("Mehrere Buy Orders für " + group.get(0).itemName + " (" + group.size()
                    + ") - behalte beste, cancel Rest");
            for (int i = 1; i < group.size(); i++) {
                ScreenUtil.GuiOrder extra = group.get(i);
                handled.add(extra);
                Trade trade = ensureTradeFor(extra);
                batchCancel.add(trade);
            }
        }
        for (ScreenUtil.GuiOrder order : buyOrders) {
            String productId = ItemNames.productId(order.itemName);
            scannedOrderProductIds.add(productId);
            if (handled.contains(order)) {
                continue;
            }
            MarketData.Product product = productFor(order.itemName);
            boolean outdated = product != null && product.bestBid > order.unitPrice + 0.05;
            boolean unprofitable = isBuyOrderUnprofitable(order);
            boolean fullyFilled = order.filled >= order.amount && order.amount > 0;
            boolean claimable = order.claimable > 0;
            if ((outdated || unprofitable) && !claimable && !fullyFilled) {
                handled.add(order);
                Trade trade = ensureTradeFor(order);
                batchCancel.add(trade);
            } else if (fullyFilled || claimable || outdated || unprofitable) {
                busy |= !processBuyEntry(order, data);
            }
            if (stopRequested) {
                ordersBusy = true;
                return true;
            }
        }
        if (!batchCancel.isEmpty() && !stopRequested) {
            int cancelled = cancelBuyOrdersBatch(batchCancel, "Batch", data);
            if (cancelled < batchCancel.size()) {
                busy = true;
            }
        }
        ordersBusy = busy;
        return busy;
    }

    private boolean isBuyOrderUnprofitable(ScreenUtil.GuiOrder order) {
        MarketData.Product product = productFor(order.itemName);
        if (product == null || product.bestAsk <= 0 || order.unitPrice <= 0) {
            return false;
        }
        double sellPrice = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
        double profitPerUnit = sellPrice * (1.0 - cfg.taxPercent / 100.0) - order.unitPrice;
        double margin = profitPerUnit / order.unitPrice * 100.0;
        if (margin < cfg.minBuyOrderMarginPercent) {
            return true;
        }
        long remaining = Math.max(1, order.amount - order.filled);
        return profitPerUnit * remaining < 200_000;
    }

    private int cancelBuyOrdersBatch(List<Trade> trades, String reason, MarketData data) {
        if (trades.isEmpty()) {
            return 0;
        }
        if (!openOrdersGui()) {
            return 0;
        }
        waitForOrderGuiStable(2500);
        int cancelled = 0;
        for (Trade trade : trades) {
            if (stopRequested) {
                break;
            }
            if (ScreenUtil.freeInventorySlots() < 4) {
                log("Inventar fast voll - verlasse GUI, Rest später");
                break;
            }
            int slot = findOrderSlotRetry(trade.displayName, false, 2500);
            if (slot < 0) {
                continue;
            }
            trade.notFoundChecks = 0;
            ledger.update(trade);
            manualClaimFlow = true;
            if (!clickOrder(trade.displayName, false, 0, 4000, "Batch-Cancel (Claim-Check): " + trade.displayName)) {
                manualClaimFlow = false;
                continue;
            }
            long claimedNow = waitForClaimedItems(2500);
            manualClaimFlow = false;
            if (claimedNow > 0) {
                Trade batch = new Trade();
                batch.productId = trade.productId;
                batch.displayName = trade.displayName;
                batch.qty = claimedNow;
                batch.buyPrice = trade.buyPrice;
                batch.sellPrice = trade.sellPrice;
                batch.cost = claimedNow * trade.buyPrice;
                batch.state = Trade.State.CLAIMED;
                batch.createdAt = System.currentTimeMillis();
                ledger.add(batch);
                trade.qty = Math.max(0, trade.qty - claimedNow);
                trade.cost = trade.qty * trade.buyPrice;
                ledger.update(trade);
                chat("§6[Flipper] §7Geclaimt: §f" + claimedNow + "x " + trade.displayName);
                if (ScreenUtil.freeInventorySlots() < 4) {
                    log("Inventar voll nach Claim - verlasse GUI");
                    cleanupGui();
                    return cancelled;
                }
            }
            if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.ORDER_OPTIONS
                    && !ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDER_OPTIONS, 2000)) {
                if (!clickOrder(trade.displayName, false, 1, 4000, "Batch-Cancel (Rechtsklick): " + trade.displayName)) {
                    continue;
                }
                if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDER_OPTIONS, 2500)) {
                    continue;
                }
            }
            boolean done = false;
            for (int attempt = 0; attempt < 2 && !done && !stopRequested; attempt++) {
                if (!clickByName("Cancel", 0, 3000, "Cancel-Button (Batch)")) {
                    break;
                }
                done = waitForCancelled(3000);
            }
            if (done) {
                trade.state = Trade.State.CANCELLED;
                ledger.update(trade);
                cancelled++;
                chat("§6[Flipper] §cBuy Order gecancelt (§7" + reason + "§c): §f" + trade.displayName);
            }
            ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
            if (screen != ScreenUtil.BzScreen.ORDERS) {
                ScreenUtil.closeCurrentScreen();
                ScreenUtil.jitterSleep(400, 200);
            }
        }
        cleanupGui();
        return cancelled;
    }

    private boolean mergeSellOrders(List<ScreenUtil.GuiOrder> group, MarketData data) {
        String itemName = group.get(0).itemName;
        for (ScreenUtil.GuiOrder order : group) {
            if (!order.coinsClaimable || order.claimable <= 0) {
                continue;
            }
            if (!openOrdersGui()) {
                return false;
            }
            waitForOrderGuiStable(2500);
            List<ScreenUtil.GuiOrder> fresh = ScreenUtil.scanOrders();
            ScreenUtil.GuiOrder target = null;
            for (ScreenUtil.GuiOrder entry : fresh) {
                if (entry.sellSide && entry.coinsClaimable && entry.claimable > 0
                        && ScreenUtil.fuzzyItemMatch(entry.itemName, itemName)) {
                    target = entry;
                    break;
                }
            }
            if (target != null) {
                ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, true, 0);
                if (BazaarChatListener.waitForChat("Claimed", 3000)) {
                    Trade trade = findLedgerTrade(itemName, true, order.amount);
                    if (trade != null) {
                        trade.state = Trade.State.DONE;
                        markProductSuccess(trade.productId);
                        ledger.update(trade);
                        double profit = trade.revenue - trade.cost;
                        chat("§6[Flipper] §aTrade abgeschlossen: §f" + trade.displayName
                                + " §7· Gewinn: §a+" + ScreenUtil.formatCoins(profit));
                    }
                }
            }
            cleanupGui();
            if (stopRequested) {
                return false;
            }
        }
        List<Trade> cancelled = new ArrayList<>();
        for (ScreenUtil.GuiOrder order : group) {
            if (order.coinsClaimable && order.claimable > 0) {
                continue;
            }
            Trade base = ensureTradeFor(order);
            cancelSellOffer(base);
            if (base.state == Trade.State.CLAIMED) {
                cancelled.add(base);
            }
            if (stopRequested) {
                return false;
            }
        }
        if (cancelled.isEmpty()) {
            return true;
        }
        refreshMarketIfStale();
        MarketData fresh = market != null ? market : data;
        Trade merged = cancelled.get(0);
        merged.qty = ScreenUtil.countItemsInInventory(merged.displayName);
        if (merged.qty <= 0) {
            log("Merge: keine Items im Inventar: " + merged.displayName);
            return false;
        }
        merged.cost = merged.qty * merged.buyPrice;
        merged.state = Trade.State.CLAIMED;
        ledger.update(merged);
        for (int i = 1; i < cancelled.size(); i++) {
            Trade other = cancelled.get(i);
            other.qty = 0;
            other.cost = 0;
            other.state = Trade.State.ABANDONED;
            ledger.update(other);
        }
        boolean listed = createSellOfferFromInventory(merged, fresh);
        if (listed) {
            sellRepriceCooldown.put(merged.displayName.toLowerCase(), System.currentTimeMillis());
            chat("§6[Flipper] §7Sell Offers zusammengeführt: §f" + merged.qty + "x " + merged.displayName
                    + " §7@ §f" + ScreenUtil.formatPrice(merged.sellPrice));
            return true;
        }
        return false;
    }

    private boolean processSellEntry(ScreenUtil.GuiOrder order, MarketData data) {
        if (!openOrdersGui()) {
            return false;
        }
        waitForOrderGuiStable(2500);
        List<ScreenUtil.GuiOrder> freshOrders = ScreenUtil.scanOrders();
        ScreenUtil.GuiOrder target = null;
        for (ScreenUtil.GuiOrder entry : freshOrders) {
            if (entry.sellSide && ScreenUtil.fuzzyItemMatch(entry.itemName, order.itemName)
                    && entry.coinsClaimable && entry.claimable > 0) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            for (ScreenUtil.GuiOrder entry : freshOrders) {
                if (entry.sellSide && ScreenUtil.fuzzyItemMatch(entry.itemName, order.itemName)) {
                    target = entry;
                    break;
                }
            }
        }
        boolean clicked;
        if (target != null) {
            clicked = ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, true, 0);
            log(clicked ? "Klick Slot " + target.slot + " (gezielt): " + target.itemName
                    : "Gezielter Klick fehlgeschlagen: " + target.itemName);
        } else {
            clicked = clickOrder(order.itemName, true, 0, 5000, "Coin-Check: " + order.itemName);
        }
        if (!clicked) {
            cleanupGui();
            return false;
        }
        boolean claimed = BazaarChatListener.waitForChat("Claimed", 3000);
        if (claimed) {
            Trade trade = findLedgerTrade(order.itemName, true, order.amount);
            if (trade != null) {
                trade.state = Trade.State.DONE;
                markProductSuccess(trade.productId);
                ledger.update(trade);
                double profit = trade.revenue - trade.cost;
                chat("§6[Flipper] §aTrade abgeschlossen: §f" + trade.displayName
                        + " §7· Gewinn: §a+" + ScreenUtil.formatCoins(profit));
            } else {
                chat("§6[Flipper] §aCoins geclaimt: §f" + order.itemName);
            }
            cleanupGui();
            return true;
        }
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen != ScreenUtil.BzScreen.ORDERS) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.jitterSleep(400, 200);
        }
        MarketData.Product product = productFor(order.itemName);
        boolean outdated = product != null && product.bestAsk > 0 && product.bestAsk < order.unitPrice - 0.05;
        if (!outdated) {
            cleanupGui();
            return true;
        }
        log("Sell Offer outdated - stelle neu ein: " + order.itemName + " @ " + order.unitPrice);
        Trade base = ensureTradeFor(order);
        cancelSellOffer(base);
        if (base.state == Trade.State.CLAIMED && !stopRequested) {
            refreshMarketIfStale();
            MarketData fresh = market;
            boolean listed = false;
            if (fresh != null) {
                listed = createSellOfferFromInventory(base, fresh);
            }
            if (!listed) {
                log("Re-Listing fehlgeschlagen - Items bleiben im Inventar: " + order.itemName);
                return false;
            }
            base.outbidChecks = 0;
            base.lastRelistedAt = System.currentTimeMillis();
            ledger.update(base);
            sellRepriceCooldown.put(order.itemName.toLowerCase(), System.currentTimeMillis());
            chat("§6[Flipper] §7Sell Offer aktualisiert: §f" + base.displayName
                    + " §7@ §f" + ScreenUtil.formatPrice(base.sellPrice));
            return true;
        }
        return false;
    }

    private boolean claimEntryItems(ScreenUtil.GuiOrder target, ScreenUtil.GuiOrder order, MarketData data) {
        manualClaimFlow = true;
        boolean clicked = ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, false, 0);
        log(clicked ? "Klick Slot " + target.slot + " (gezielt Claim): " + target.itemName
                : "Gezielter Claim-Klick fehlgeschlagen: " + target.itemName);
        manualClaimFlow = false;
        if (!clicked) {
            return false;
        }
        long claimed = waitForClaimedItems(3000);
        if (claimed <= 0) {
            return false;
        }
        Trade batch = new Trade();
        batch.productId = ItemNames.productId(order.itemName);
        batch.displayName = order.itemName;
        batch.qty = claimed;
        batch.buyPrice = order.unitPrice;
        batch.sellPrice = 0;
        batch.cost = claimed * order.unitPrice;
        batch.state = Trade.State.CLAIMED;
        batch.createdAt = System.currentTimeMillis();
        ledger.add(batch);
        chat("§6[Flipper] §7Geclaimt: §f" + claimed + "x " + order.itemName);
        if (data != null) {
            createSellOfferFromInventory(batch, data);
        }
        return true;
    }

    private boolean processBuyEntry(ScreenUtil.GuiOrder order, MarketData data) {
        if (!openOrdersGui()) {
            return false;
        }
        waitForOrderGuiStable(2500);
        long start = System.currentTimeMillis();
        List<ScreenUtil.GuiOrder> fresh = ScreenUtil.scanOrders();
        ScreenUtil.GuiOrder target = null;
        for (ScreenUtil.GuiOrder entry : fresh) {
            if (!entry.sellSide && ScreenUtil.fuzzyItemMatch(entry.itemName, order.itemName)) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            log("Buy Order nicht mehr vorhanden - überspringe: " + order.itemName);
            cleanupGui();
            return true;
        }
        MarketData.Product product = productFor(order.itemName);
        boolean outdated = product != null && product.bestBid > order.unitPrice + 0.05;
        boolean unprofitable = isBuyOrderUnprofitable(order);
        boolean fullyFilled = target.filled >= target.amount && target.amount > 0;
        boolean partiallyFilled = target.filled > 0 && !fullyFilled;
        boolean claimable = target.claimable > 0;
        if (!fullyFilled && !partiallyFilled && !outdated && !unprofitable && !claimable) {
            log("Buy Order gesund - nichts zu tun: " + order.itemName);
            cleanupGui();
            return true;
        }
        if (!fullyFilled && !outdated && !unprofitable && (claimable || partiallyFilled)) {
            log("Buy Order teilgefüllt - claime gefüllten Teil: " + order.itemName);
            claimEntryItems(target, order, data);
            cleanupGui();
            return true;
        }
        if (fullyFilled && !outdated && !unprofitable) {
            manualClaimFlow = true;
            boolean clicked = ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, false, 1);
            log(clicked ? "Klick Slot " + target.slot + " (gezielt Flip): " + target.itemName
                    : "Gezielter Flip-Klick fehlgeschlagen: " + target.itemName);
            if (!clicked) {
                manualClaimFlow = false;
                cleanupGui();
                return false;
            }
            manualClaimFlow = false;
            long claimedByClick = claimedItemsSince(start);
            if (claimedByClick > 0) {
                log("Klick hat " + claimedByClick + "x geclaimt - Items im Inventar: " + order.itemName);
                cleanupGui();
                Trade batch = new Trade();
                batch.productId = ItemNames.productId(order.itemName);
                batch.displayName = order.itemName;
                batch.qty = claimedByClick;
                batch.buyPrice = order.unitPrice;
                batch.sellPrice = 0;
                batch.cost = claimedByClick * order.unitPrice;
                batch.state = Trade.State.CLAIMED;
                batch.createdAt = System.currentTimeMillis();
                ledger.add(batch);
                createSellOfferFromInventory(batch, data);
                return true;
            }
            if (ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
                if (clickByName("Flip Order", 0, 3000, "Flip Order")) {
                    double targetSell = 0;
                    if (product != null && product.bestAsk > 0) {
                        targetSell = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
                    }
                    if (targetSell <= 0) {
                        cleanupGui();
                        return false;
                    }
                    SignInput.set(ScreenUtil.formatPrice(targetSell));
                    boolean flipped = waitForFlipChat(12_000);
                    if (flipped) {
                        Trade trade = ensureTradeFor(order);
                        trade.sellPrice = targetSell;
                        trade.revenue = trade.qty * targetSell * (1.0 - cfg.taxPercent / 100.0);
                        trade.state = Trade.State.SELL_OPEN;
                        trade.listedAt = System.currentTimeMillis();
                        ledger.update(trade);
                        chat("§6[Flipper] §7Sell Offer erstellt (Flip): §f" + order.itemName
                                + " §7@ §f" + ScreenUtil.formatPrice(targetSell));
                        cleanupGui();
                        return true;
                    }
                    failPause("Flip fehlgeschlagen: " + order.itemName);
                    cleanupGui();
                    return false;
                }
                ScreenUtil.closeCurrentScreen();
                ScreenUtil.jitterSleep(400, 200);
            }
        }
        String reason = outdated ? "überboten" : "nicht profitabel";
        Trade base = ensureTradeFor(order);
        boolean cancelled = cancelBuyOrder(base, reason, data);
        if (cancelled) {
            chat("§6[Flipper] §cBuy Order gecancelt (§7" + reason + "§c): §f" + order.itemName);
            return true;
        }
        return false;
    }

    private void waitForOrderGuiStable(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String previous = ScreenUtil.containerSignature();
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            ScreenUtil.jitterSleep(300, 200);
            String current = ScreenUtil.containerSignature();
            if (current != null && current.equals(previous)) {
                return;
            }
            previous = current;
        }
    }

    private boolean needsOrderScan(MarketData data) {
        if (recentFillChat) {
            return true;
        }
        if (!ledger.inState(Trade.State.CLAIMED).isEmpty() || !ledger.inState(Trade.State.FILLED).isEmpty()
                || !ledger.inState(Trade.State.SOLD).isEmpty() || !ledger.inState(Trade.State.SELL_OPEN).isEmpty()) {
            return true;
        }
        if (lastScanHadOrders) {
            return true;
        }
        return data.lastUpdated != lastScannedSnapshot;
    }

    private MarketData.Product productFor(String itemName) {
        if (market != null) {
            MarketData.Product direct = market.products.get(ItemNames.productId(itemName));
            if (direct != null) {
                return direct;
            }
        }
        String key = ItemNames.nameKey(itemName);
        return productByName.get(key);
    }

    private Trade findLedgerTrade(String itemName, boolean sellSide, long amount) {
        Trade best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Trade trade : ledger.all()) {
            if (!trade.displayName.equalsIgnoreCase(itemName)) {
                continue;
            }
            boolean tradeIsSell = trade.state == Trade.State.SELL_OPEN
                    || trade.state == Trade.State.SOLD || trade.state == Trade.State.CLAIMED;
            if (tradeIsSell != sellSide) {
                continue;
            }
            long diff = amount <= 0 ? 0 : Math.abs(trade.qty - amount);
            if (best == null || diff < bestDiff) {
                best = trade;
                bestDiff = diff;
            }
        }
        return best;
    }

    private Trade ensureTradeFor(ScreenUtil.GuiOrder order) {
        Trade matched = findLedgerTrade(order.itemName, order.sellSide, order.amount);
        if (matched != null) {
            return matched;
        }
        Trade trade = new Trade();
        trade.productId = ItemNames.productId(order.itemName);
        trade.displayName = order.itemName;
        trade.qty = order.amount;
        trade.buyPrice = order.sellSide ? 0 : order.unitPrice;
        trade.sellPrice = order.sellSide ? order.unitPrice : 0;
        trade.cost = order.sellSide ? 0 : order.amount * order.unitPrice;
        trade.createdAt = System.currentTimeMillis();
        trade.state = order.sellSide ? Trade.State.SELL_OPEN : Trade.State.FILLED;
        ledger.add(trade);
        log("Neuer Trade aus Order-Scan: " + order.itemName + " (" + trade.state + ")");
        return trade;
    }

    private int findOrderSlotRetry(String displayName, boolean sellSide, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            int slot = ScreenUtil.findOrderSlot(displayName, sellSide);
            if (slot >= 0) {
                return slot;
            }
            ScreenUtil.sleep(400);
        }
        return -1;
    }

    private long waitForClaimedItems(long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        int noneStreak = 0;
        while (System.currentTimeMillis() < deadline) {
            beat();
            for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
                if (entry.event != null && entry.event.type == BazaarChatEvent.Type.CLAIMED_ITEMS
                        && entry.event.qty > 0) {
                    return entry.event.qty;
                }
            }
            if (stuck()) {
                return 0;
            }
            ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
            if (current == ScreenUtil.BzScreen.NONE) {
                noneStreak++;
                if (noneStreak > 18) {
                    traderOwnsGui = false;
                    log("GUI extern geschlossen während Claim-Warten");
                    return 0;
                }
            } else {
                noneStreak = 0;
                if (current != ScreenUtil.BzScreen.ORDERS && current != ScreenUtil.BzScreen.ORDER_OPTIONS
                        && current != ScreenUtil.BzScreen.SIGN) {
                    log("Screen verließ Orders-GUI während Claim-Warten: " + current
                            + " " + ScreenUtil.screenDebugInfo());
                    return 0;
                }
            }
            ScreenUtil.sleep(150);
        }
        return 0;
    }

    private long claimedItemsSince(long start) {
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (entry.event != null && entry.event.type == BazaarChatEvent.Type.CLAIMED_ITEMS
                    && entry.event.qty > 0) {
                return entry.event.qty;
            }
        }
        return 0;
    }

    private boolean inventoryFullSince(long start) {
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (entry.event != null && entry.event.type == BazaarChatEvent.Type.INVENTORY_FULL) {
                return true;
            }
        }
        return false;
    }


    private boolean waitForFlipChat(long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
                if (entry.text.contains("Order Flipped!") || entry.text.contains("Sell Offer Setup!")) {
                    return true;
                }
            }
            if (stuck()) {
                return false;
            }
            if (ScreenUtil.currentScreen() == ScreenUtil.BzScreen.SELL_CONFIRM) {
                if (clickConfirmAtomic("Sell Offer", 4000)) {
                    continue;
                }
            }
            ScreenUtil.sleep(150);
        }
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (entry.text.contains("Order Flipped!") || entry.text.contains("Sell Offer Setup!")) {
                return true;
            }
        }
        return false;
    }


    private void handleMissingOrder(Trade trade, MarketData data) {
        trade.notFoundChecks++;
        ledger.update(trade);
        if (trade.notFoundChecks >= 5) {
            ledger.remove(trade);
            log("Order existiert nicht mehr - Trade gelöscht: " + trade.displayName);
        }
        cleanupGui();
    }

    private boolean waitForCancelled(long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        long lastConfirmClick = 0;
        int foreignStreak = 0;
        while (System.currentTimeMillis() < deadline) {
            beat();
            for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
                if (entry.text.toLowerCase().contains("cancelled")) {
                    return true;
                }
            }
            if (stuck()) {
                return false;
            }
            ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
            if (current == ScreenUtil.BzScreen.BAZAAR || current == ScreenUtil.BzScreen.PRODUCT
                    || current == ScreenUtil.BzScreen.SEARCH || current == ScreenUtil.BzScreen.BUY_AMOUNT
                    || current == ScreenUtil.BzScreen.BUY_PRICE || current == ScreenUtil.BzScreen.BUY_CONFIRM
                    || current == ScreenUtil.BzScreen.SELL_AMOUNT || current == ScreenUtil.BzScreen.SELL_PRICE
                    || current == ScreenUtil.BzScreen.SELL_CONFIRM || current == ScreenUtil.BzScreen.SIGN) {
                foreignStreak++;
                if (foreignStreak > 20) {
                    log("Screen verließ Orders-Flow während Cancel-Warten");
                    return false;
                }
            } else {
                foreignStreak = 0;
            }
            String title = ScreenUtil.screenTitle();
            if (title != null && title.toLowerCase().contains("confirm")) {
                long now = System.currentTimeMillis();
                if (now - lastConfirmClick > 1500) {
                    int confirm = ScreenUtil.findButton("Confirm");
                    if (confirm >= 0) {
                        click(confirm, 0);
                        lastConfirmClick = now;
                    }
                }
            }
            ScreenUtil.sleep(150);
        }
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (entry.text.toLowerCase().contains("cancelled")) {
                return true;
            }
        }
        return false;
    }

    private boolean cancelBuyOrder(Trade trade, String reason, MarketData data) {
        for (int round = 0; round < 4 && !stopRequested; round++) {
            if (!openOrdersGui()) {
                return false;
            }
            int slot = findOrderSlotRetry(trade.displayName, false, 4000);
            if (slot < 0) {
                handleMissingOrder(trade, data);
                return false;
            }
            trade.notFoundChecks = 0;
            ledger.update(trade);
            manualClaimFlow = true;
            if (!clickOrder(trade.displayName, false, 0, 5000, "Order-Eintrag (Cancel): " + trade.displayName)) {
                manualClaimFlow = false;
                break;
            }
            long claimedNow = waitForClaimedItems(3000);
            manualClaimFlow = false;
            if (claimedNow > 0) {
                log("Cancel-Flow: " + claimedNow + "x geclaimt: " + trade.displayName);
                Trade batch = new Trade();
                batch.productId = trade.productId;
                batch.displayName = trade.displayName;
                batch.qty = claimedNow;
                batch.buyPrice = trade.buyPrice;
                batch.sellPrice = trade.sellPrice;
                batch.cost = claimedNow * trade.buyPrice;
                batch.state = Trade.State.CLAIMED;
                batch.createdAt = System.currentTimeMillis();
                ledger.add(batch);
                trade.qty = Math.max(0, trade.qty - claimedNow);
                trade.cost = trade.qty * trade.buyPrice;
                ledger.update(trade);
                chat("§6[Flipper] §7Geclaimt: §f" + claimedNow + "x " + trade.displayName);
                cleanupGui();
                if (data != null) {
                    createSellOfferFromInventory(batch, data);
                }
                continue;
            }
            if (ScreenUtil.currentScreen() == ScreenUtil.BzScreen.ORDER_OPTIONS
                    || ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDER_OPTIONS, 2000)) {
                return confirmCancel(trade, reason);
            }
            if (!clickOrder(trade.displayName, false, 1, 5000, "Order-Eintrag Rechtsklick (Cancel): " + trade.displayName)) {
                break;
            }
            if (waitForClaimedItems(3000) > 0) {
                continue;
            }
            if (ScreenUtil.currentScreen() == ScreenUtil.BzScreen.ORDER_OPTIONS
                    || ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
                return confirmCancel(trade, reason);
            }
            break;
        }
        failPause("Cancel-Flow unklar: " + trade.displayName);
        cleanupGui();
        return false;
    }

    private boolean confirmCancel(Trade trade, String reason) {
        boolean cancelled = false;
        for (int attempt = 0; attempt < 3 && !cancelled && !stopRequested; attempt++) {
            if (!clickByName("Cancel", 0, 4000, "Cancel-Button")) {
                break;
            }
            cancelled = waitForCancelled(3000);
            if (!cancelled) {
                log("Cancel-Klick ggf. nicht durchgegangen - neuer Versuch (" + attempt + ")");
            }
        }
        if (cancelled) {
            trade.state = Trade.State.CANCELLED;
            ledger.update(trade);
            chat("§6[Flipper] §cBuy Order gecancelt (§7" + reason + "§c): §f" + trade.displayName
                    + " §7· §f" + ScreenUtil.formatCoins(trade.cost) + " §7zurück");
            cleanupGui();
            return true;
        }
        failPause("Cancel nicht bestätigt: " + trade.displayName);
        cleanupGui();
        return false;
    }

    private void cancelSellOffer(Trade trade) {
        if (!openOrdersGui()) {
            return;
        }
        waitForOrderGuiStable(2500);
        int slot = findOrderSlotRetry(trade.displayName, true, 4000);
        if (slot < 0) {
            handleMissingOrder(trade, null);
            return;
        }
        trade.notFoundChecks = 0;
        ledger.update(trade);
        if (!clickOrder(trade.displayName, true, 0, 5000, "Sell-Eintrag (Cancel): " + trade.displayName)) {
            failPause("Order-Eintrag nicht klickbar (Sell-Cancel): " + trade.displayName);
            return;
        }
        if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
            if (!clickOrder(trade.displayName, true, 1, 5000, "Sell-Eintrag Rechtsklick: " + trade.displayName)) {
                failPause("Order-Options nicht erreichbar (Sell-Cancel): " + trade.displayName);
                return;
            }
            if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
                failPause("Order-Options nicht erreichbar (Sell-Cancel): " + trade.displayName);
                return;
            }
        }
        boolean cancelled = false;
        for (int attempt = 0; attempt < 3 && !cancelled && !stopRequested; attempt++) {
            if (!clickByName("Cancel", 0, 4000, "Cancel-Button (Sell)")) {
                break;
            }
            cancelled = waitForCancelled(3000);
        }
        if (cancelled) {
            trade.state = Trade.State.CLAIMED;
            ledger.update(trade);
            chat("§6[Flipper] §cSell Offer gecancelt (abgelaufen): §f" + trade.displayName);
        } else {
            failPause("Sell-Cancel nicht bestätigt: " + trade.displayName);
        }
        cleanupGui();
    }

    private boolean createSellOfferFromInventory(Trade trade, MarketData data) {
        refreshMarketIfStale();
        MarketData fresh = market != null ? market : data;
        if (!openBazaar()) {
            return false;
        }
        if (!searchProduct(trade.displayName, trade.productId)) {
            chat("§6[Flipper] §cItem nicht gefunden: §f" + trade.displayName);
            failPause("Item nicht gefunden (Sell): " + trade.displayName);
            return false;
        }
        if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.PRODUCT) {
            failPause("Nicht auf Produktseite (Sell): " + trade.displayName);
            return false;
        }
        log("Produktseite (Sell): " + trade.displayName + " " + ScreenUtil.screenDebugInfo());
        boolean sellClicked = clickByName("Create Sell Offer", 0, 5000, "Create Sell Offer");
        if (!sellClicked) {
            sellClicked = clickByName("Sell Offer", 0, 3000, "Sell Offer");
        }
        if (!sellClicked) {
            sellClicked = clickByName("Create Offer", 0, 3000, "Create Offer");
        }
        if (!sellClicked) {
            sellClicked = clickByName("Create Sell", 0, 3000, "Create Sell");
        }
        if (!sellClicked) {
            String slot16 = ScreenUtil.slotName(16);
            if (slot16 != null && !slot16.toLowerCase().contains("search")
                    && !slot16.toLowerCase().contains("close") && !slot16.toLowerCase().contains("go back")
                    && !slot16.toLowerCase().contains("instantly")
                    && !slot16.toLowerCase().contains("create buy")) {
                log("Fallback: Klick Slot 16 (Sell) für " + trade.displayName + " Item: " + slot16);
                clickDelay();
                if (!isAhPriority()) {
                    ScreenUtil.clickSlot(16, 0);
                    sellClicked = true;
                }
            }
        }
        if (!sellClicked) {
            failPause("Sell-Button nicht gefunden: " + trade.displayName);
            return false;
        }
        long inInventory = ScreenUtil.countItemsInInventory(trade.displayName);
        if (inInventory <= 0) {
            log("Keine Items im Inventar für " + trade.displayName + ". Inventar-Namen: "
                    + ScreenUtil.inventoryItemNames());
            failPause("Keine Items im Inventar: " + trade.displayName);
            return false;
        }
        long qty = trade.qty > 0 ? Math.min(trade.qty, inInventory) : inInventory;
        trade.qty = qty;
        trade.cost = qty * trade.buyPrice;
        ledger.update(trade);
        if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.SELL_PRICE, 4000)) {
            if (ScreenUtil.waitForScreen(ScreenUtil.BzScreen.SELL_AMOUNT, 4000)) {
                SignInput.set(String.valueOf(qty));
                if (!clickSign(5000, "Mengen-Sign (Sell)")) {
                    failPause("Mengen-Sign nicht gefunden (Sell): " + trade.displayName);
                    return false;
                }
                if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.SELL_PRICE, 6000)) {
                    failPause("SELL_PRICE nicht erreicht: " + trade.displayName);
                    return false;
                }
            } else {
                failPause("SELL-Preis-Seite nicht erreicht: " + trade.displayName);
                return false;
            }
        }
        ScreenUtil.sleep(ScreenUtil.jitter(400));
        log("SELL_PRICE Seite für: " + trade.displayName + " " + ScreenUtil.screenDebugInfo());
        double targetSell = trade.sellPrice;
        MarketData.Product product = fresh.products.get(trade.productId);
        if (product == null) {
            product = productFor(trade.displayName);
        }
        if (product != null && product.bestAsk > 0) {
            double freshPrice = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
            if (freshPrice > 0) {
                targetSell = freshPrice;
            }
        }
        boolean usedUndercut = clickByName("Best Offer -0.1", 0, 3000, "Best Offer -0.1");
        if (!usedUndercut) {
            usedUndercut = clickByName("Below", 0, 3000, "Below");
        }
        if (!usedUndercut) {
            usedUndercut = clickByName("0.1 Coins Less", 0, 3000, "0.1 Coins Less");
        }
        if (usedUndercut) {
            log("Nutze -0.1-Preset für: " + trade.displayName);
        } else {
            boolean usedPreset = clickByName("Best Offer", 0, 3000, "Best Offer");
            if (!usedPreset) {
                usedPreset = clickByName("Top Offer", 0, 3000, "Top Offer");
            }
            if (!usedPreset) {
                usedPreset = clickByName("Match", 0, 3000, "Match");
            }
            if (!usedPreset) {
                usedPreset = clickByName("Best", 0, 3000, "Best");
            }
            if (usedPreset) {
                log("Nutze Preis-Preset für: " + trade.displayName);
                targetSell = Math.round((targetSell + 0.1) * 10.0) / 10.0;
            } else {
                log("Kein Preis-Preset gefunden - nutze Sign: " + trade.displayName);
                SignInput.set(ScreenUtil.formatPrice(targetSell));
                if (!clickSign(5000, "Preis-Sign (Sell)")) {
                    failPause("Preis-Sign nicht gefunden (Sell): " + trade.displayName);
                    return false;
                }
            }
        }
        if (!ScreenUtil.waitForScreen(ScreenUtil.BzScreen.SELL_CONFIRM, 6000)) {
            failPause("SELL_CONFIRM nicht erreicht: " + trade.displayName);
            return false;
        }
        ScreenUtil.sleep(ScreenUtil.jitter(400));
        if (!clickConfirmAtomic("Sell Offer", 8000)) {
            failPause("Sell-Confirm-Button nicht gefunden: " + trade.displayName);
            return false;
        }
        if (!BazaarChatListener.waitForChat("Sell Offer Setup!", 12_000)) {
            failPause("Keine 'Sell Offer Setup!' Bestätigung: " + trade.displayName);
            return false;
        }
        trade.sellPrice = targetSell;
        trade.revenue = trade.qty * targetSell * (1.0 - cfg.taxPercent / 100.0);
        trade.state = Trade.State.SELL_OPEN;
        trade.listedAt = System.currentTimeMillis();
        ledger.update(trade);
        chat("§6[Flipper] §7Sell Offer erstellt: §f" + trade.qty + "x " + trade.displayName
                + " §7@ §f" + ScreenUtil.formatPrice(targetSell));
        cleanupGui();
        return true;
    }

    private void maybeReconcile() {
        List<Trade> sellable = ledger.inState(Trade.State.CLAIMED);
        if (!sellable.isEmpty() && !stopRequested) {
            refreshMarketIfStale();
            MarketData data = market;
            for (Trade trade : sellable) {
                beat();
                long nowMs = System.currentTimeMillis();
                if (nowMs - trade.lastRelistedAt < CLAIM_LIST_RETRY_MS) {
                    continue;
                }
                trade.lastRelistedAt = nowMs;
                ledger.update(trade);
                if (ScreenUtil.countItemsInInventory(trade.displayName) <= 0) {
                    chat("§6[Flipper] §cKeine Items im Inventar für §f" + trade.displayName
                            + " §7(möglicherweise im Stash) – Trade übersprungen.");
                    trade.state = Trade.State.ABANDONED;
                    ledger.update(trade);
                    continue;
                }
                if (data != null) {
                    createSellOfferFromInventory(trade, data);
                }
                if (stopRequested) {
                    return;
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastReconciliationAt < RECONCILE_INTERVAL_MS) {
            return;
        }
        List<ScreenUtil.GuiOrder> orders = lastScannedOrders;
        if (now - lastOrdersScanAt > 30_000L) {
            if (!openOrdersGui()) {
                return;
            }
            waitForOrderGuiStable(2500);
            orders = ScreenUtil.scanOrders();
            lastScannedOrders = List.copyOf(orders);
            lastOrdersScanAt = System.currentTimeMillis();
            cleanupGui();
        }
        if (orders == null) {
            orders = List.of();
        }
        Set<ScreenUtil.GuiOrder> used = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Trade trade : ledger.all()) {
            beat();
            if (!trade.isOpen() || trade.state == Trade.State.PLANNED || trade.state == Trade.State.CLAIMED) {
                continue;
            }
            if (trade.createdAt > lastOrdersScanAt) {
                continue;
            }
            boolean sellSide = trade.state == Trade.State.SELL_OPEN || trade.state == Trade.State.SOLD;
            ScreenUtil.GuiOrder match = findMatchingOrder(orders, trade.displayName, sellSide, used);
            if (match == null) {
                trade.notFoundChecks++;
                ledger.update(trade);
                if (trade.notFoundChecks >= 5) {
                    ledger.remove(trade);
                    chat("§6[Flipper] §cOrder existiert nicht mehr - Trade gelöscht: §f" + trade.displayName);
                }
                continue;
            }
            used.add(match);
            trade.notFoundChecks = 0;
            trade.filledQty = match.filled;
            if (trade.state == Trade.State.BUY_OPEN && match.filled >= match.amount && match.amount > 0) {
                trade.state = Trade.State.FILLED;
                trade.filledAt = System.currentTimeMillis();
                chat("§6[Flipper] §aBuy Order gefüllt: §f" + trade.qty + "x " + trade.displayName);
            }
            if (trade.state == Trade.State.SELL_OPEN && match.filled >= match.amount && match.amount > 0) {
                trade.state = Trade.State.SOLD;
                trade.soldAt = System.currentTimeMillis();
                markProductSuccess(trade.productId);
                chat("§6[Flipper] §aSell Offer verkauft: §f" + trade.displayName
                        + " §7· Umsatz: §f" + ScreenUtil.formatCoins(trade.revenue));
            }
            ledger.update(trade);
        }
        cleanupGui();
        lastReconciliationAt = System.currentTimeMillis();
    }

    private ScreenUtil.GuiOrder findMatchingOrder(List<ScreenUtil.GuiOrder> orders, String displayName,
            boolean sellSide, Set<ScreenUtil.GuiOrder> used) {
        for (ScreenUtil.GuiOrder order : orders) {
            if (used.contains(order) || order.sellSide != sellSide || order.itemName == null) {
                continue;
            }
            if (order.itemName.equalsIgnoreCase(displayName)
                    || ScreenUtil.fuzzyItemMatch(order.itemName, displayName)) {
                return order;
            }
        }
        return null;
    }

    public synchronized void onChatEvent(BazaarChatEvent event) {
        if (event == null) {
            return;
        }
        try {
            switch (event.type) {
                case BUY_SETUP -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.PLANNED);
                    if (trade != null) {
                        trade.state = Trade.State.BUY_OPEN;
                        trade.createdAt = System.currentTimeMillis();
                        if (event.price > 0) {
                            trade.cost = event.price;
                        }
                        ledger.update(trade);
                    }
                }
                case SELL_SETUP, FLIPPED -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.FILLED, Trade.State.CLAIMED);
                    if (trade != null) {
                        trade.state = Trade.State.SELL_OPEN;
                        trade.listedAt = System.currentTimeMillis();
                        if (event.price > 0) {
                            trade.revenue = event.price * (1.0 - cfg.taxPercent / 100.0);
                        }
                        ledger.update(trade);
                    }
                }
                case BUY_FILLED -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.BUY_OPEN);
                    if (trade != null) {
                        trade.state = Trade.State.FILLED;
                        trade.filledAt = System.currentTimeMillis();
                        if (event.qty > 0) {
                            trade.filledQty = event.qty;
                        }
                        ledger.update(trade);
                        chat("§6[Flipper] §aBuy Order gefüllt: §f" + trade.filledQty + "x " + trade.displayName);
                    } else {
                        recentFillChat = true;
                        log("Gefüllte Order erkannt (kein Ledger-Match): " + event.item);
                    }
                }
                case SELL_FILLED -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.SELL_OPEN);
                    if (trade != null) {
                        trade.state = Trade.State.SOLD;
                        trade.soldAt = System.currentTimeMillis();
                        markProductSuccess(trade.productId);
                        ledger.update(trade);
                        chat("§6[Flipper] §aSell Offer verkauft: §f" + trade.displayName
                                + " §7· Umsatz: §f" + ScreenUtil.formatCoins(trade.revenue));
                    } else {
                        recentFillChat = true;
                        log("Verkaufte Order erkannt (kein Ledger-Match): " + event.item);
                    }
                }
                case CLAIMED_COINS -> {
                    orderLimitReached = false;
                    Trade trade = null;
                    if (event.item != null && !event.item.isBlank()) {
                        trade = ledger.firstMatching(event.item, Trade.State.SOLD);
                    }
                    if (trade == null) {
                        List<Trade> sold = ledger.inState(Trade.State.SOLD);
                        if (!sold.isEmpty()) {
                            trade = sold.get(0);
                        }
                    }
                    if (trade != null) {
                        trade.state = Trade.State.DONE;
                        markProductSuccess(trade.productId);
                        ledger.update(trade);
                        double profit = trade.revenue - trade.cost;
                        chat("§6[Flipper] §aTrade abgeschlossen: §f" + trade.displayName
                                + " §7· Gewinn: §a+" + ScreenUtil.formatCoins(profit));
                    }
                }
                case CLAIMED_ITEMS -> {
                    orderLimitReached = false;
                    if (manualClaimFlow) {
                        break;
                    }
                    Trade trade = ledger.firstMatching(event.item, Trade.State.FILLED);
                    if (trade != null) {
                        trade.state = Trade.State.CLAIMED;
                        ledger.update(trade);
                    }
                }
                case CANCELLED, EXPIRED -> {
                    orderLimitReached = false;
                    Trade trade = ledger.firstMatching(event.item, Trade.State.BUY_OPEN, Trade.State.SELL_OPEN, Trade.State.FILLED);
                    if (trade != null) {
                        boolean wasBuy = trade.state == Trade.State.BUY_OPEN;
                        trade.state = wasBuy ? Trade.State.CANCELLED : Trade.State.CLAIMED;
                        ledger.update(trade);
                        if (wasBuy) {
                            chat("§6[Flipper] §cBuy Order gecancelt: §f" + trade.displayName);
                        }
                    }
                }
                case ORDER_LIMIT -> {
                    orderLimitReached = true;
                    lastScannedOrderCount = Math.max(lastScannedOrderCount, cfg.maxOrders);
                    log("Order-Limit erreicht (max. " + cfg.maxOrders + " Orders) - warte auf Claim/Cancel");
                }
                default -> {
                }
            }
        } catch (Exception e) {
            System.out.println("[Flipper] chat event error: " + e.getMessage());
        }
    }
}
