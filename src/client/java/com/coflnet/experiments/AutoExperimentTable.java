package com.coflnet.experiments;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays the Hypixel SkyBlock Experimentation Table on its own once its menu is opened:
 * Chronomatron, then Ultrasequencer, then Superpairs, once per remaining Superpairs charge.
 * Runs on the client tick, so every screen access happens on the render thread.
 */
public final class AutoExperimentTable {
    public static final ExperimentConfig CONFIG = ExperimentConfig.load();

    private static final String PREFIX = "§5[Experiments] §7";
    /** A click that changes nothing is tried this often, then the step is skipped. */
    private static final int MAX_CLICK_ATTEMPTS = 3;
    /** Freshly opened menus get their items a moment after the open packet. */
    private static final long SETTLE_MS = 450;
    /** Wrong screen for this long while a menu is expected: give up. */
    private static final long SCREEN_TIMEOUT_MS = 8_000;
    /** Nothing at all happened for this long: stop instead of clicking blindly. */
    private static final long WATCHDOG_MS = 30_000;
    /** At most one Titanic Experience Bottle per Superpairs charge, so a bad read never drains coins. */
    private static final int MAX_BOTTLES_PER_CHARGE = 1;
    private static final int MAX_REOPEN_ATTEMPTS = 3;
    private static final Pattern CHARGES = Pattern.compile("charges:\\s*(\\d+)");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private enum Game {
        CHRONOMATRON("Chronomatron"), ULTRASEQUENCER("Ultrasequencer"), SUPERPAIRS("Superpairs");

        final String label;
        final String key;

        Game(String label) {
            this.label = label;
            this.key = label.toLowerCase(Locale.ROOT);
        }
    }

    private enum Stage {
        IDLE, TABLE, STAKES, GAME, CLAIM_WAIT, CLAIM, CLOSE_ALL, REOPEN, BOTTLE_TABLE, BOTTLES, BOTTLES_BACK
    }

    private enum Phase { UNKNOWN, SHOW, INPUT }

    /** One click that has to change the menu; retried, then skipped. */
    private static final class PendingClick {
        final int slot;
        final BooleanSupplier success;
        final long fixedWaitMs;
        final Runnable ok;
        final Runnable skip;
        /** Only this slot range counts as a change (game boards have ticking timers outside). */
        boolean boardOnly;
        String titleBefore;
        int attempts;
        long clickedAt;
        int containerBefore;
        Phase phaseBefore;
        String signatureBefore;

        PendingClick(int slot, BooleanSupplier success, long fixedWaitMs, Runnable ok, Runnable skip) {
            this.slot = slot;
            this.success = success;
            this.fixedWaitMs = fixedWaitMs;
            this.ok = ok;
            this.skip = skip;
        }
    }

    private static Stage stage = Stage.IDLE;
    private static Game game = Game.CHRONOMATRON;
    private static long stageSince;
    private static long waitUntil;
    private static long lastProgressAt;
    private static long lastClickAt;
    private static PendingClick pending;
    /** Finished or stopped while a menu was open: wait for it to close before arming again. */
    private static boolean ignoreUntilClosed;

    private static int lastContainerId = Integer.MIN_VALUE;
    private static long screenOpenedAt;
    private static long screenClosedAt;
    private static String lastSignature = "";

    /** A run that stopped (e.g. table did not reopen) continues here when the table is opened again. */
    private static final long RESUME_MS = 120_000;
    /** After a menu closed, wait this long before right-clicking the table again. */
    private static final long REOPEN_DELAY_MS = 1_000;
    private static Game resumeGame;
    private static int resumeCharges = -1;
    private static long resumeUntil;

    private static int charges = -1;
    private static int bottlesBought;
    private static int closeCount;
    private static int reopenAttempts;
    private static long nextReopenAt;

    // Chronomatron / Ultrasequencer
    private static Phase lastPhase = Phase.UNKNOWN;
    private static final List<String> recordedColors = new ArrayList<>();
    private static String lastLitColor;
    private static final TreeMap<Integer, Integer> recordedNumbers = new TreeMap<>();
    private static final List<Integer> playSlots = new ArrayList<>();
    private static final List<String> playColors = new ArrayList<>();
    private static int inputIndex;
    private static long chronoInputSince;
    private static int completedRounds;

    // Superpairs
    private static final Set<Integer> board = new HashSet<>();
    private static final Map<Integer, String> known = new HashMap<>();
    private static final Set<Integer> matched = new HashSet<>();
    private static final Set<Integer> dead = new HashSet<>();
    private static int firstSlot = -1;
    private static String firstKey;
    private static int plannedSecond = -1;

    private AutoExperimentTable() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoExperimentTable::onClientTick);
    }

    public static boolean isEnabled() {
        return CONFIG.enabled;
    }

    public static void toggle() {
        CONFIG.enabled = !CONFIG.enabled;
        CONFIG.save();
        if (!CONFIG.enabled) {
            reset();
        }
        info(CONFIG.enabled
                ? "Aktiv - öffne den Experimentation Table."
                : "Aus.");
    }

    public static String status() {
        if (!CONFIG.enabled) {
            return "Off";
        }
        return stage == Stage.IDLE ? "Waiting for table" : game.label + " - " + stage.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ tick

    private static void onClientTick(Minecraft mc) {
        if (!CONFIG.enabled || mc.player == null || mc.level == null || mc.gameMode == null) {
            if (stage != Stage.IDLE) {
                reset();
            }
            return;
        }
        long now = System.currentTimeMillis();
        trackScreen(mc, now);
        if (stage == Stage.IDLE) {
            tickIdle(now);
            return;
        }
        if (now - lastProgressAt > WATCHDOG_MS) {
            stop("Keine Reaktion seit " + WATCHDOG_MS / 1000 + "s - gestoppt.");
            return;
        }
        if (pending != null) {
            tickPending(now);
            return;
        }
        if (now < waitUntil) {
            return;
        }
        switch (stage) {
            case TABLE -> tickTable(now);
            case STAKES -> tickStakes(now);
            case GAME -> tickGame(now);
            case CLAIM_WAIT -> tickClaimWait(now);
            case CLAIM -> tickClaim(now);
            case CLOSE_ALL -> tickCloseAll(now);
            case REOPEN -> tickReopen(mc, now);
            case BOTTLE_TABLE -> tickBottleTable(now);
            case BOTTLES -> tickBottles(now);
            case BOTTLES_BACK -> tickBottlesBack(now);
            default -> {
            }
        }
    }

    private static void trackScreen(Minecraft mc, long now) {
        AbstractContainerScreen<?> container = container();
        int id = container == null ? Integer.MIN_VALUE : container.getMenu().containerId;
        if (id != lastContainerId) {
            if (id == Integer.MIN_VALUE) {
                screenClosedAt = now;
            } else {
                screenOpenedAt = now;
            }
            lastContainerId = id;
            lastProgressAt = now;
        }
        String signature = container == null ? "" : signature(0, size());
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            lastProgressAt = now;
        }
    }

    private static void tickIdle(long now) {
        if (ignoreUntilClosed) {
            if (container() == null) {
                ignoreUntilClosed = false;
            }
            return;
        }
        if (!isTable()) {
            return;
        }
        bottlesBought = 0;
        if (resumeGame != null && now < resumeUntil) {
            game = resumeGame;
            charges = resumeCharges;
            info("Experimentation Table erkannt - setze bei " + game.label + " fort.");
        } else {
            charges = -1;
            game = firstGame();
            info("Experimentation Table erkannt - starte.");
        }
        resumeGame = null;
        setStage(Stage.TABLE, now);
    }

    // ------------------------------------------------------------------ menus

    private static void tickTable(long now) {
        if (!isTable()) {
            if (now - stageSince > SCREEN_TIMEOUT_MS) {
                stop("Experimentation Table ist nicht offen.");
            }
            return;
        }
        if (!settled(now)) {
            return;
        }
        if (charges < 0) {
            charges = readCharges();
            if (charges == 0) {
                finish("Keine Superpairs-Charges mehr.");
                return;
            }
        }
        if (game != Game.SUPERPAIRS) {
            int slot = findSlotByName(game.key);
            if (slot < 0 || nameAt(slot).toLowerCase(Locale.ROOT).contains("unknown experiment")) {
                info(game.label + " nicht gefunden - übersprungen.");
                game = nextGame(game);
                return;
            }
            click(slot, null, () -> setStage(Stage.STAKES, System.currentTimeMillis()), () -> {
                info(game.label + " öffnet nicht - übersprungen.");
                game = nextGame(game);
            });
            return;
        }
        int slot = findSlotByName("superpairs");
        if (slot < 0) {
            finish("Superpairs nicht gefunden.");
            return;
        }
        click(slot, null, () -> setStage(Stage.STAKES, System.currentTimeMillis()),
                () -> finish("Superpairs öffnet nicht."));
    }

    private static void tickStakes(long now) {
        String title = title();
        if (!title.contains("stakes")) {
            if (now - stageSince > SCREEN_TIMEOUT_MS) {
                stop("Stakes-Menü für " + game.label + " kam nicht.");
            }
            return;
        }
        if (!settled(now)) {
            return;
        }
        int playable = -1;
        boolean needsExperience = false;
        // Right to left, bottom row first: the highest stake that can be played.
        for (int i = size() - 1; i >= 0; i--) {
            String lore = loreText(i);
            if (lore.contains("click to play")) {
                playable = i;
                break;
            }
            if (game == Game.SUPERPAIRS && lore.contains("not enough experience")
                    && bottlesBought < MAX_BOTTLES_PER_CHARGE) {
                playable = i;
                needsExperience = true;
                break;
            }
        }
        if (playable < 0) {
            if (game == Game.SUPERPAIRS) {
                closeScreen();
                finish("Keine spielbare Superpairs-Stufe.");
                return;
            }
            info(game.label + ": keine spielbare Stufe - übersprungen.");
            leaveStakesTo(nextGame(game));
            return;
        }
        if (needsExperience) {
            info("Nicht genug XP - kaufe eine Titanic Experience Bottle.");
            int back = findBackSlot();
            if (back < 0) {
                stop("Zurück-Pfeil nicht gefunden.");
                return;
            }
            click(back, null, () -> setStage(Stage.BOTTLE_TABLE, System.currentTimeMillis()),
                    () -> stop("Zurück-Pfeil reagiert nicht."));
            return;
        }
        click(playable, null, () -> startGame(System.currentTimeMillis()), () -> {
            if (game == Game.SUPERPAIRS) {
                finish("Superpairs startet nicht.");
            } else {
                info(game.label + " startet nicht - übersprungen.");
                leaveStakesTo(nextGame(game));
            }
        });
    }

    /** Back to the table from a stakes menu, continuing with {@code next}. */
    private static void leaveStakesTo(Game next) {
        int back = findBackSlot();
        Runnable viaReopen = () -> {
            game = next;
            closeScreen();
            setStage(Stage.REOPEN, System.currentTimeMillis());
        };
        if (back < 0) {
            viaReopen.run();
            return;
        }
        click(back, null, () -> {
            game = next;
            setStage(Stage.TABLE, System.currentTimeMillis());
        }, viaReopen);
    }

    private static void tickBottleTable(long now) {
        if (!isTable()) {
            if (now - stageSince > SCREEN_TIMEOUT_MS) {
                stop("Experimentation Table kam nach dem Zurück nicht.");
            }
            return;
        }
        if (!settled(now)) {
            return;
        }
        int slot = findSlotByName("experience bottles");
        if (slot < 0) {
            info("Experience Bottles nicht gefunden.");
            bottlesBought = MAX_BOTTLES_PER_CHARGE;
            setStage(Stage.TABLE, now);
            return;
        }
        click(slot, null, () -> setStage(Stage.BOTTLES, System.currentTimeMillis()), () -> {
            bottlesBought = MAX_BOTTLES_PER_CHARGE;
            setStage(Stage.TABLE, System.currentTimeMillis());
        });
    }

    private static void tickBottles(long now) {
        if (container() == null || isTable()) {
            if (now - stageSince > SCREEN_TIMEOUT_MS) {
                stop("Bottles-Menü kam nicht.");
            }
            return;
        }
        if (!settled(now)) {
            return;
        }
        int slot = findSlotByName("titanic experience bottle");
        bottlesBought++;
        if (slot < 0) {
            info("Titanic Experience Bottle nicht gefunden.");
            bottlesBought = MAX_BOTTLES_PER_CHARGE;
            setStage(Stage.BOTTLES_BACK, now);
            return;
        }
        // The purchase does not change the menu, so it is not verified: wait five delays instead.
        long wait = Math.max(500L, CONFIG.clickDelayMs * 5L);
        pending = new PendingClick(slot, null, wait,
                () -> setStage(Stage.BOTTLES_BACK, System.currentTimeMillis()),
                () -> setStage(Stage.BOTTLES_BACK, System.currentTimeMillis()));
    }

    private static void tickBottlesBack(long now) {
        if (isTable()) {
            setStage(Stage.TABLE, now);
            return;
        }
        if (!settled(now)) {
            if (container() == null && now - stageSince > SCREEN_TIMEOUT_MS) {
                setStage(Stage.REOPEN, now);
            }
            return;
        }
        int back = findBackSlot();
        Runnable viaReopen = () -> {
            closeScreen();
            setStage(Stage.REOPEN, System.currentTimeMillis());
        };
        if (back < 0) {
            viaReopen.run();
            return;
        }
        click(back, null, () -> setStage(Stage.TABLE, System.currentTimeMillis()), viaReopen);
    }

    private static void tickClaimWait(long now) {
        if (title().contains("experiment over")) {
            setStage(Stage.CLAIM, now);
            return;
        }
        if (isTable()) {
            game = nextGame(game);
            setStage(Stage.TABLE, now);
            return;
        }
        if (container() == null && now - screenClosedAt > 2_500) {
            game = nextGame(game);
            setStage(Stage.REOPEN, now);
            return;
        }
        if (container() != null && now - stageSince > SCREEN_TIMEOUT_MS) {
            closeScreen();
            game = nextGame(game);
            setStage(Stage.REOPEN, now);
        }
    }

    private static void tickClaim(long now) {
        if (!title().contains("experiment over")) {
            setStage(Stage.CLAIM_WAIT, now);
            return;
        }
        if (!settled(now)) {
            return;
        }
        int slot = findSlotByName(game.key);
        if (slot < 0) {
            slot = findSlotByLore("click to claim");
        }
        Runnable next = () -> {
            game = nextGame(game);
            closeScreen();
            setStage(Stage.REOPEN, System.currentTimeMillis());
        };
        if (slot < 0) {
            next.run();
            return;
        }
        click(slot, null, next, next);
    }

    private static void tickCloseAll(long now) {
        if (container() != null) {
            if (closeCount < 4) {
                closeScreen();
                closeCount++;
                waitUntil = now + Math.max(400, CONFIG.clickDelayMs);
            } else if (now - stageSince > SCREEN_TIMEOUT_MS) {
                stop("Menü lässt sich nicht schließen.");
            }
            return;
        }
        if (now - screenClosedAt < 800) {
            return;
        }
        charges = charges > 0 ? charges - 1 : 0;
        if (charges <= 0) {
            finish("Alle Charges verbraucht - fertig.");
            return;
        }
        info("Noch " + charges + " Charge(s) - nächste Runde.");
        game = firstGame();
        bottlesBought = 0;
        setStage(Stage.REOPEN, now);
    }

    private static void tickReopen(Minecraft mc, long now) {
        if (isTable()) {
            setStage(Stage.TABLE, now);
            return;
        }
        if (container() != null) {
            if (now - stageSince > 1_500 && now >= nextReopenAt) {
                closeScreen();
                nextReopenAt = now + 600;
            }
            return;
        }
        if (now < nextReopenAt || now - screenClosedAt < REOPEN_DELAY_MS || mc.player.isUsingItem()) {
            return;
        }
        if (reopenAttempts >= MAX_REOPEN_ATTEMPTS) {
            stop("Experimentation Table öffnet nicht - schau den Tisch an.");
            return;
        }
        reopenAttempts++;
        rightClickTable(mc, reopenAttempts);
        lastClickAt = now;
        nextReopenAt = now + Math.max(2_000, CONFIG.clickDelayMs);
    }

    // ------------------------------------------------------------------ games

    private static void startGame(long now) {
        lastPhase = Phase.UNKNOWN;
        recordedColors.clear();
        recordedNumbers.clear();
        playSlots.clear();
        playColors.clear();
        lastLitColor = null;
        inputIndex = 0;
        completedRounds = 0;
        board.clear();
        known.clear();
        matched.clear();
        dead.clear();
        firstSlot = -1;
        firstKey = null;
        plannedSecond = -1;
        setStage(Stage.GAME, now);
    }

    private static void tickGame(long now) {
        String title = title();
        if (title.contains("experiment over")) {
            setStage(Stage.CLAIM, now);
            return;
        }
        if (container() == null) {
            if (game == Game.SUPERPAIRS) {
                closeCount = 0;
                setStage(Stage.CLOSE_ALL, now);
            } else {
                setStage(Stage.CLAIM_WAIT, now);
            }
            return;
        }
        if (!title.contains(game.key) || title.contains("stakes")) {
            if (now - stageSince > SCREEN_TIMEOUT_MS) {
                stop(game.label + "-Spiel kam nicht.");
            }
            return;
        }
        // Chronomatron shows its first note right after opening: read from the very first tick.
        if (game == Game.SUPERPAIRS ? !settled(now) : size() < 9) {
            return;
        }
        switch (game) {
            case CHRONOMATRON -> tickChronomatron(now);
            case ULTRASEQUENCER -> tickUltrasequencer(now);
            case SUPERPAIRS -> tickSuperpairs(now);
        }
    }

    private static void tickChronomatron(long now) {
        Phase phase = phase();
        if (phase == Phase.SHOW) {
            if (lastPhase != Phase.SHOW) {
                if (roundFinished()) {
                    return;
                }
                recordedColors.clear();
                lastLitColor = null;
            }
            String lit = litColor();
            if (lit != null && !lit.equals(lastLitColor)) {
                recordedColors.add(lit);
            }
            lastLitColor = lit;
            lastPhase = Phase.SHOW;
            return;
        }
        if (phase != Phase.INPUT) {
            return;
        }
        if (lastPhase != Phase.INPUT) {
            // Simon says: the sequence we already played plus the note shown last.
            // Only the newest note has to be read right; missed or doubled earlier notes do not matter.
            log("Chronomatron gesehen: " + recordedColors);
            if (!recordedColors.isEmpty()) {
                playColors.add(recordedColors.getLast());
            } else {
                log("Chronomatron: keine neue Note erkannt");
            }
            inputIndex = 0;
            lastPhase = Phase.INPUT;
            chronoInputSince = now;
            log("Chronomatron Runde " + playColors.size() + ": " + playColors);
        }
        if (inputIndex >= playColors.size()) {
            return;
        }
        // Wait until the last note went dark again: else the click may be lost, and the
        // fading note would look like the reaction to our click.
        if (litColor() != null && now - Math.max(lastClickAt, chronoInputSince) < 2_000) {
            return;
        }
        int slot = slotForColor(playColors.get(inputIndex));
        if (slot < 0) {
            log("Farbe " + playColors.get(inputIndex) + " nicht auf dem Feld");
            inputIndex++;
            return;
        }
        clickGameSlot(slot);
    }

    private static void tickUltrasequencer(long now) {
        Phase phase = phase();
        if (phase == Phase.SHOW) {
            if (lastPhase != Phase.SHOW) {
                if (roundFinished()) {
                    return;
                }
                recordedNumbers.clear();
            }
            int end = Math.min(45, size());
            for (int i = 0; i < end; i++) {
                ItemStack stack = stackAt(i);
                String id = itemId(stack);
                // The top row holds the info items; only real dyes count there.
                if (stack.isEmpty() || isUltrasequencerFiller(id) || (i < 9 && !isDye(id))) {
                    continue;
                }
                recordedNumbers.put(stack.getCount(), i);
            }
            lastPhase = Phase.SHOW;
            return;
        }
        if (phase != Phase.INPUT) {
            return;
        }
        if (lastPhase != Phase.INPUT) {
            playSlots.clear();
            playSlots.addAll(recordedNumbers.values());
            inputIndex = 0;
            lastPhase = Phase.INPUT;
            log("Ultrasequencer Runde " + playSlots.size() + ": " + playSlots);
        }
        if (inputIndex >= playSlots.size()) {
            return;
        }
        clickGameSlot(playSlots.get(inputIndex));
    }

    /** Back in the show phase: was the last input complete, and is the round target reached? */
    private static boolean roundFinished() {
        int played = game == Game.CHRONOMATRON ? playColors.size() : playSlots.size();
        if (lastPhase == Phase.INPUT && played > 0 && inputIndex >= played) {
            completedRounds = played;
            int target = game == Game.CHRONOMATRON ? CONFIG.chronomatronRounds : CONFIG.ultrasequencerRounds;
            if (completedRounds >= target) {
                info(game.label + ": " + completedRounds + " Runden geschafft.");
                closeScreen();
                setStage(Stage.CLAIM_WAIT, System.currentTimeMillis());
                return true;
            }
        }
        return false;
    }

    /** A note click counts when the board or the show/input indicator changes. */
    private static void clickGameSlot(int slot) {
        click(slot, null, () -> inputIndex++, () -> {
            log(game.label + ": Klick auf " + slot + " ohne Wirkung - übersprungen");
            inputIndex++;
        });
        pending.boardOnly = true;
    }

    private static void tickSuperpairs(long now) {
        if (board.isEmpty()) {
            for (int i = 0; i < size(); i++) {
                if (isHiddenCard(stackAt(i))) {
                    board.add(i);
                }
            }
            if (board.isEmpty()) {
                if (now - stageSince > SCREEN_TIMEOUT_MS) {
                    superpairsDone("Kein Superpairs-Feld gefunden.");
                }
                return;
            }
        }
        int clicks = remainingClicks();
        if (clicks == 0) {
            superpairsDone("Superpairs: keine Klicks mehr.");
            return;
        }
        if (hiddenSlots(-1).isEmpty()) {
            superpairsDone("Superpairs: alles aufgedeckt.");
            return;
        }
        if (firstSlot < 0) {
            rememberVisibleCards();
            int[] pair = knownPair();
            int first = pair != null ? pair[0] : firstUnknown(-1);
            if (first < 0) {
                List<Integer> hidden = hiddenSlots(-1);
                first = hidden.isEmpty() ? -1 : hidden.getFirst();
            }
            if (first < 0) {
                superpairsDone("Superpairs: nichts mehr zu klicken.");
                return;
            }
            final int slot = first;
            plannedSecond = pair != null ? pair[1] : -1;
            click(slot, () -> isRevealed(slot), () -> {
                String key = cardKey(slot);
                known.put(slot, key);
                if (isPowerUp(key)) {
                    matched.add(slot);
                    return;
                }
                firstSlot = slot;
                firstKey = key;
            }, () -> {
                dead.add(slot);
                plannedSecond = -1;
            });
            return;
        }
        int second = plannedSecond >= 0 && plannedSecond != firstSlot && canPick(plannedSecond) ? plannedSecond : -1;
        if (second < 0) {
            second = knownMatch(firstKey, firstSlot);
        }
        if (second < 0) {
            second = firstUnknown(firstSlot);
        }
        if (second < 0) {
            List<Integer> hidden = hiddenSlots(firstSlot);
            second = hidden.isEmpty() ? -1 : hidden.getFirst();
        }
        if (second < 0) {
            firstSlot = -1;
            plannedSecond = -1;
            return;
        }
        final int slot = second;
        final int first = firstSlot;
        final String wanted = firstKey;
        click(slot, () -> container() == null || isRevealed(slot), () -> {
            String key = cardKey(slot);
            known.put(slot, key);
            if (key.equals(wanted)) {
                matched.add(first);
                matched.add(slot);
                log("Superpairs Paar: " + key);
            } else if (isPowerUp(key)) {
                matched.add(slot);
            }
            firstSlot = -1;
            plannedSecond = -1;
        }, () -> {
            dead.add(slot);
            firstSlot = -1;
            plannedSecond = -1;
        });
    }

    private static void superpairsDone(String message) {
        info(message);
        closeScreen();
        closeCount = 1;
        setStage(Stage.CLOSE_ALL, System.currentTimeMillis());
        waitUntil = System.currentTimeMillis() + Math.max(400, CONFIG.clickDelayMs);
    }

    /** Cards shown outside the current turn: remember them, and pairs among them are claimed. */
    private static void rememberVisibleCards() {
        Map<String, List<Integer>> visible = new HashMap<>();
        for (int slot : board) {
            ItemStack stack = stackAt(slot);
            if (stack.isEmpty() || isHiddenCard(stack) || matched.contains(slot)) {
                continue;
            }
            String key = cardKey(slot);
            known.put(slot, key);
            if (isPowerUp(key)) {
                matched.add(slot);
                continue;
            }
            visible.computeIfAbsent(key, k -> new ArrayList<>()).add(slot);
        }
        for (List<Integer> slots : visible.values()) {
            if (slots.size() >= 2) {
                matched.addAll(slots);
            }
        }
    }

    private static int[] knownPair() {
        Map<String, Integer> seen = new HashMap<>();
        for (int slot : board) {
            String key = known.get(slot);
            if (key == null || !canPick(slot) || isPowerUp(key)) {
                continue;
            }
            Integer other = seen.putIfAbsent(key, slot);
            if (other != null) {
                return new int[]{other, slot};
            }
        }
        return null;
    }

    private static int knownMatch(String key, int except) {
        if (key == null) {
            return -1;
        }
        for (int slot : board) {
            if (slot != except && key.equals(known.get(slot)) && canPick(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstUnknown(int except) {
        for (int slot : sortedBoard()) {
            if (slot != except && !known.containsKey(slot) && canPick(slot) && isHiddenCard(stackAt(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static List<Integer> hiddenSlots(int except) {
        List<Integer> result = new ArrayList<>();
        for (int slot : sortedBoard()) {
            if (slot != except && canPick(slot) && isHiddenCard(stackAt(slot))) {
                result.add(slot);
            }
        }
        return result;
    }

    private static boolean canPick(int slot) {
        return board.contains(slot) && !matched.contains(slot) && !dead.contains(slot);
    }

    private static List<Integer> sortedBoard() {
        List<Integer> slots = new ArrayList<>(board);
        slots.sort(Integer::compare);
        return slots;
    }

    private static String cardKey(int slot) {
        ItemStack stack = stackAt(slot);
        return clean(stack.getHoverName().getString()) + "|" + itemId(stack) + "|" + String.join("/", lore(stack));
    }

    private static boolean isPowerUp(String key) {
        String name = key.substring(0, Math.max(0, key.indexOf('|'))).toLowerCase(Locale.ROOT);
        return name.contains("instant find") || name.contains("gained +") || name.matches(".*\\+\\d+ clicks?.*");
    }

    private static boolean isRevealed(int slot) {
        ItemStack stack = stackAt(slot);
        return container() == null || (!stack.isEmpty() && !isHiddenCard(stack));
    }

    private static boolean isHiddenCard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = itemId(stack);
        return id.contains("stained_glass") && !id.contains("pane");
    }

    /** Remaining Superpairs clicks from the top row (bookshelf "Remaining Clicks: N", cauldron at 0). */
    private static int remainingClicks() {
        int end = Math.min(9, size());
        for (int i = 0; i < end; i++) {
            ItemStack stack = stackAt(i);
            if (stack.isEmpty()) {
                continue;
            }
            String name = clean(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("remaining clicks")) {
                Matcher m = NUMBER.matcher(name.substring(name.indexOf("remaining clicks")));
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            if (itemId(stack).contains("cauldron")) {
                return 0;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ board reading

    /** Glowstone in the menu: sequence is shown. Clock: our turn. */
    private static Phase phase() {
        int n = size();
        for (int i = n - 1; i >= 0; i--) {
            String id = itemId(stackAt(i));
            if (id.endsWith("glowstone")) {
                return Phase.SHOW;
            }
            if (id.endsWith("clock")) {
                return Phase.INPUT;
            }
        }
        return Phase.UNKNOWN;
    }

    /** The colour whose glass block currently shows as a different block. */
    private static String litColor() {
        int end = Math.min(45, size());
        for (int i = 9; i < end; i++) {
            ItemStack stack = stackAt(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = itemId(stack);
            if (id.contains("pane")) {
                continue;
            }
            if (!id.contains("glass") || stack.hasFoil()) {
                String name = clean(stack.getHoverName().getString());
                if (!name.isBlank()) {
                    return name;
                }
            }
        }
        return null;
    }

    private static int slotForColor(String color) {
        int end = Math.min(45, size());
        int fallback = -1;
        for (int i = 9; i < end; i++) {
            ItemStack stack = stackAt(i);
            if (stack.isEmpty() || itemId(stack).contains("pane")) {
                continue;
            }
            if (clean(stack.getHoverName().getString()).equalsIgnoreCase(color)) {
                if (itemId(stack).contains("glass")) {
                    return i;
                }
                if (fallback < 0) {
                    fallback = i;
                }
            }
        }
        return fallback;
    }

    private static boolean isUltrasequencerFiller(String id) {
        return id.contains("pane") || id.contains("glass") || id.contains("bookshelf")
                || id.endsWith("glowstone") || id.endsWith("clock") || id.contains("cauldron")
                || id.contains("barrier") || id.endsWith("arrow");
    }

    private static boolean isDye(String id) {
        return id.endsWith("_dye") || id.endsWith("ink_sac") || id.endsWith("bone_meal")
                || id.endsWith("lapis_lazuli") || id.endsWith("cocoa_beans");
    }

    private static int readCharges() {
        int slot = findSlotByName("superpairs");
        if (slot < 0) {
            return 1;
        }
        Matcher m = CHARGES.matcher(loreText(slot));
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    // ------------------------------------------------------------------ clicking

    /** Queue a click; {@code success} null means: any change of the open menu. */
    private static void click(int slot, BooleanSupplier success, Runnable ok, Runnable skip) {
        pending = new PendingClick(slot, success, -1, ok, skip);
    }

    private static void tickPending(long now) {
        PendingClick p = pending;
        if (p.attempts > 0) {
            if (p.fixedWaitMs >= 0) {
                if (now - p.clickedAt >= p.fixedWaitMs) {
                    pending = null;
                    lastProgressAt = now;
                    p.ok.run();
                }
                return;
            }
            boolean changed;
            if (p.success != null) {
                changed = p.success.getAsBoolean();
            } else if (p.boardOnly) {
                changed = containerId() != p.containerBefore || phase() != p.phaseBefore
                        || !signatureFor(p).equals(p.signatureBefore);
            } else {
                // Menu navigation: only a different window or title counts.
                changed = containerId() != p.containerBefore || !title().equals(p.titleBefore);
            }
            if (changed) {
                pending = null;
                lastProgressAt = now;
                p.ok.run();
                return;
            }
            if (now - p.clickedAt < verifyWindowMs()) {
                return;
            }
            if (p.attempts >= MAX_CLICK_ATTEMPTS) {
                pending = null;
                p.skip.run();
                return;
            }
        }
        if (now - lastClickAt < CONFIG.clickDelayMs) {
            return;
        }
        if (container() == null) {
            pending = null;
            p.skip.run();
            return;
        }
        if (p.attempts == 0) {
            p.containerBefore = containerId();
            p.phaseBefore = p.boardOnly ? phase() : Phase.UNKNOWN;
            p.signatureBefore = signatureFor(p);
            p.titleBefore = title();
        }
        // Retries of menu clicks fall back to a left click in case a menu ignores middle clicks;
        // their check (new window/title) cannot be fooled by the local pickup.
        boolean leftClick = p.attempts > 0 && !p.boardOnly;
        clickRaw(p.slot, leftClick);
        p.attempts++;
        p.clickedAt = now;
        lastClickAt = now;
    }

    private static String signatureFor(PendingClick p) {
        return p.boardOnly ? signature(9, Math.min(45, size())) : signature(0, size());
    }

    private static long verifyWindowMs() {
        return Math.max(800L, CONFIG.clickDelayMs);
    }

    private static void clickRaw(int slot, boolean leftClick) {
        Minecraft mc = Minecraft.getInstance();
        AbstractContainerScreen<?> container = container();
        if (container == null || mc.player == null || mc.gameMode == null || slot < 0) {
            return;
        }
        // Middle click: survival players get no local pickup prediction, so the menu only changes
        // when Hypixel answers. A left click would move the item to the cursor at once and fake a change.
        if (leftClick) {
            mc.gameMode.handleContainerInput(container.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        } else {
            mc.gameMode.handleContainerInput(container.getMenu().containerId, slot, 2, ContainerInput.CLONE, mc.player);
        }
    }

    private static void closeScreen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen != null) {
            screen.onClose();
        }
    }

    /**
     * Right-click the table. Odd attempts press the use key exactly like the player does
     * (vanilla then interacts with whatever is under the crosshair); even attempts interact directly.
     */
    private static void rightClickTable(Minecraft mc, int attempt) {
        log("Rechtsklick #" + attempt + " auf " + describeHit(mc));
        InputConstants.Key useKey = KeyMappingHelper.getBoundKeyOf(mc.options.keyUse);
        boolean keyUsable = useKey != null && useKey != InputConstants.UNKNOWN;
        if (attempt % 2 == 1 && keyUsable) {
            KeyMapping.click(useKey);
            return;
        }
        if (mc.hitResult instanceof EntityHitResult entityHit) {
            mc.gameMode.interact(mc.player, entityHit.getEntity(), entityHit, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        BlockHitResult hit = null;
        if (mc.hitResult instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            hit = block;
        } else {
            BlockPos table = nearestTable(mc);
            if (table != null) {
                hit = new BlockHitResult(Vec3.atCenterOf(table).add(0, 0.25, 0), Direction.UP, table, false);
            }
        }
        if (hit == null) {
            if (keyUsable) {
                KeyMapping.click(useKey);
            }
            return;
        }
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private static String describeHit(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            return "Block " + BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(block.getBlockPos()).getBlock())
                    + " @ " + block.getBlockPos().toShortString();
        }
        if (hit instanceof EntityHitResult entityHit) {
            return "Entity " + entityHit.getEntity().getType();
        }
        return hit == null ? "nichts" : hit.getType().toString();
    }

    private static BlockPos nearestTable(Minecraft mc) {
        BlockPos origin = mc.player.blockPosition();
        Vec3 eyes = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDistance = 4.5 * 4.5;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-5, -3, -5), origin.offset(5, 4, 5))) {
            if (!mc.level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) {
                continue;
            }
            double distance = Vec3.atCenterOf(pos).distanceToSqr(eyes);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ menu helpers

    private static AbstractContainerScreen<?> container() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen instanceof AbstractContainerScreen<?> c ? c : null;
    }

    private static int containerId() {
        AbstractContainerScreen<?> c = container();
        return c == null ? Integer.MIN_VALUE : c.getMenu().containerId;
    }

    private static String title() {
        AbstractContainerScreen<?> c = container();
        return c == null ? "" : clean(c.getTitle().getString()).toLowerCase(Locale.ROOT);
    }

    private static boolean isTable() {
        return title().equals("experimentation table");
    }

    /** Menu open long enough for its items to have arrived. */
    private static boolean settled(long now) {
        if (container() == null || now - screenOpenedAt < Math.max(SETTLE_MS, CONFIG.clickDelayMs)) {
            return false;
        }
        for (int i = 0; i < size(); i++) {
            if (!stackAt(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Slots of the opened menu only, without the player inventory. */
    private static int size() {
        AbstractContainerScreen<?> c = container();
        if (c == null) {
            return 0;
        }
        if (c.getMenu() instanceof ChestMenu chest) {
            return chest.getContainer().getContainerSize();
        }
        return Math.max(0, c.getMenu().slots.size() - 36);
    }

    private static ItemStack stackAt(int slot) {
        AbstractContainerScreen<?> c = container();
        if (c == null || slot < 0 || slot >= size()) {
            return ItemStack.EMPTY;
        }
        if (c.getMenu() instanceof ChestMenu chest) {
            return chest.getContainer().getItem(slot);
        }
        return c.getMenu().getSlot(slot).getItem();
    }

    private static String nameAt(int slot) {
        ItemStack stack = stackAt(slot);
        return stack.isEmpty() ? "" : clean(stack.getHoverName().getString());
    }

    private static int findSlotByName(String needle) {
        for (int i = 0; i < size(); i++) {
            if (nameAt(i).toLowerCase(Locale.ROOT).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int findSlotByLore(String needle) {
        for (int i = 0; i < size(); i++) {
            if (loreText(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** The "Go Back" arrow at the bottom of Hypixel menus. */
    private static int findBackSlot() {
        int slot = findSlotByName("go back");
        if (slot >= 0) {
            return slot;
        }
        for (int i = size() - 1; i >= 0; i--) {
            if (itemId(stackAt(i)).endsWith(":arrow")) {
                return i;
            }
        }
        return -1;
    }

    private static String loreText(int slot) {
        return String.join("\n", lore(stackAt(slot))).toLowerCase(Locale.ROOT);
    }

    private static List<String> lore(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return lines;
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return lines;
        }
        for (Component line : lore.lines()) {
            lines.add(clean(line.getString()));
        }
        return lines;
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String signature(int from, int to) {
        StringBuilder sb = new StringBuilder(title()).append('#');
        for (int i = from; i < to; i++) {
            ItemStack stack = stackAt(i);
            if (stack.isEmpty()) {
                sb.append('-');
                continue;
            }
            sb.append(i).append(':').append(itemId(stack)).append('x').append(stack.getCount())
                    .append(stack.hasFoil() ? "*" : "").append('=').append(stack.getHoverName().getString()).append(';');
        }
        return sb.toString();
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = ChatFormatting.stripFormatting(raw);
        return (stripped == null ? raw : stripped).replace(' ', ' ').trim();
    }

    // ------------------------------------------------------------------ state

    private static Game firstGame() {
        if (CONFIG.chronomatronRounds > 0) {
            return Game.CHRONOMATRON;
        }
        return CONFIG.ultrasequencerRounds > 0 ? Game.ULTRASEQUENCER : Game.SUPERPAIRS;
    }

    private static Game nextGame(Game current) {
        if (current == Game.CHRONOMATRON && CONFIG.ultrasequencerRounds > 0) {
            return Game.ULTRASEQUENCER;
        }
        return Game.SUPERPAIRS;
    }

    private static void setStage(Stage next, long now) {
        if (next == Stage.REOPEN && stage != Stage.REOPEN) {
            reopenAttempts = 0;
            nextReopenAt = now + Math.max(REOPEN_DELAY_MS, CONFIG.clickDelayMs);
        }
        stage = next;
        stageSince = now;
        lastProgressAt = now;
        log("-> " + next + " (" + game.label + ")");
    }

    private static void finish(String message) {
        info(message);
        resumeGame = null;
        endRun();
    }

    private static void stop(String message) {
        info("§c" + message);
        if (stage != Stage.IDLE) {
            resumeGame = game;
            resumeCharges = charges;
            resumeUntil = System.currentTimeMillis() + RESUME_MS;
            info("Öffne den Table erneut, dann geht es bei " + game.label + " weiter.");
        }
        endRun();
    }

    private static void endRun() {
        reset();
        ignoreUntilClosed = container() != null;
    }

    private static void reset() {
        stage = Stage.IDLE;
        pending = null;
        waitUntil = 0;
        charges = -1;
        bottlesBought = 0;
        firstSlot = -1;
        plannedSecond = -1;
    }

    private static void info(String message) {
        log(ChatFormatting.stripFormatting(message));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(PREFIX + message));
        }
    }

    private static void log(String message) {
        System.out.println("[AutoExperiments] " + message);
    }
}
