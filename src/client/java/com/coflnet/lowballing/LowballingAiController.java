package com.coflnet.lowballing;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pauses the lowballer when a visitor writes on our island, feeds the local
 * chat brain and resumes at the exact same step. Messages written while a
 * trade GUI is open are remembered and handled right after it closes.
 */
public final class LowballingAiController {

    private static final int MAX_PENDING_LINES = 8;
    private static final int MAX_HISTORY_LINES = 16;
    private static final long LEAVE_GRACE_MS = 3_000L;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static final Map<String, Integer> COUNTERS = new HashMap<>();
    private static final Map<String, Integer> REFUSALS = new HashMap<>();
    private static final Map<String, Integer> COMPLAINTS = new HashMap<>();
    private static final Map<String, List<String>> HISTORY = new HashMap<>();
    private static final Map<String, Long> LAST_SEEN = new HashMap<>();
    private static final Map<String, Boolean> AH_ASKED = new HashMap<>();

    private static boolean paused;
    private static long pausedAt;
    private static long evaluateAt;
    private static long waitUntil;
    private static long lastPollAt;
    private static long lastScreenAt;
    private static String pendingReply;
    private static long replyDueAt;
    private static LowballingAiDecision pendingDecision;
    private static boolean pendingAhSet;
    private static String pendingSpeaker;
    private static long pendingSpeakerAt;
    private static String lastStatus = "idle";

    private LowballingAiController() {
    }

    public static void init() {
        lastPollAt = System.currentTimeMillis();
        LowballingChatClassifier.stats();
    }

    public static void onStart() {
        reset();
        lastPollAt = System.currentTimeMillis();
        lastStatus = "bereit";
    }

    public static void onStop() {
        reset();
        lastStatus = "gestoppt";
    }

    static boolean isPaused() {
        return paused;
    }

    public static String status() {
        LowballingChatClassifier.Stats stats = LowballingChatClassifier.stats();
        return "AI-Chat " + (LowballingMod.CONFIG.aiChatEnabled ? "§aan" : "§caus")
                + " §7· Modell: §f" + stats.examples() + " Sätze, "
                + stats.features() + " Features, " + stats.labels() + " Intents"
                + (stats.ready() ? "" : " §c(Regeln only)")
                + " §7· Letzte: §f" + lastStatus;
    }

    /**
     * @return true while the lowballer must not run its normal state machine
     */
    public static boolean tick(Minecraft client, long now) {
        if (!LowballingMod.CONFIG.aiChatEnabled) {
            if (paused) {
                resume(now, true);
            }
            PENDING.clear();
            lastPollAt = now;
            return false;
        }
        if (!LowballingMod.isRunning()) {
            return false;
        }

        List<LowballingChat.PlayerLine> lines = LowballingChat.linesSince(lastPollAt);
        lastPollAt = now;

        if (!LowballingMod.onIsland()) {
            if (paused) {
                resume(now, false);
            }
            PENDING.clear();
            return false;
        }

        purgeLeftPlayers(now);
        Set<String> addedKeys = new HashSet<>();
        for (LowballingChat.PlayerLine line : lines) {
            if (!isPlayerOnIsland(line.name())) {
                continue;
            }
            String key = addPending(line);
            if (key != null) {
                addedKeys.add(key);
            }
        }

        if (paused) {
            cancelForNewLines(addedKeys, now);
            return tickPaused(client, now);
        }

        if (PENDING.isEmpty()) {
            return false;
        }
        // Do not interrupt our own trade GUI. The message stays queued and is
        // processed right after the GUI closes.
        if (client.gui.screen() != null) {
            lastScreenAt = now;
            return false;
        }

        paused = true;
        pausedAt = now;
        evaluateAt = Math.max(Math.max(now, lastScreenAt + 1_200L),
                newestPendingAt() + LowballingMod.CONFIG.aiDebounceMs);
        waitUntil = 0;
        clearReply();
        LowballingMod.aiReleaseMovement();
        lastStatus = "pausiert (" + PENDING.size() + " Chat)";
        LowballingMod.debug("AI: pausiere in " + LowballingMod.snapshot().state());
        return true;
    }

    private static boolean tickPaused(Minecraft client, long now) {
        // A GUI that opened while we were thinking belongs to the player (or is
        // our own sign). Do not write or click into it; wait until it is gone.
        if (client.gui.screen() != null) {
            lastScreenAt = now;
            if (LowballingMod.isOwnTradeGuiOpen()) {
                // Our own trade GUI is active: let the macro operate it and
                // handle the remembered chat right after it closes.
                resume(now, true);
                return false;
            }
            return true;
        }
        if (replyDueAt == 0 && waitUntil == 0 && evaluateAt == 0
                && now - pausedAt > LowballingMod.CONFIG.aiMaxPauseMs) {
            lastStatus = "Timeout - weiter";
            LowballingMod.debug("AI: Pause-Timeout - Lowballer läuft weiter.");
            resume(now, true);
            return false;
        }

        if (waitUntil > 0) {
            if (now >= waitUntil) {
                waitUntil = 0;
                resume(now, true);
                return false;
            }
            return true;
        }

        if (replyDueAt > 0) {
            if (now < replyDueAt) {
                return true;
            }
            if (client.gui.screen() != null) {
                return true;
            }
            String reply = pendingReply;
            LowballingAiDecision decision = pendingDecision;
            String speaker = pendingSpeaker;
            long speakerAt = pendingSpeakerAt;
            boolean markAh = pendingAhSet;
            clearReply();
            if (!isPlayerOnIsland(speaker)) {
                // The visitor left while the 3-8 s typing delay was running.
                removePending(lineKey(speaker), speakerAt);
                if (isTarget(speaker)) {
                    LowballingMod.aiMarkReplyHandled(speakerAt);
                }
                lastStatus = "Spieler weg - Antwort verworfen";
                resume(now, true);
                return true;
            }
            if (reply != null && !reply.isBlank()) {
                LowballingMod.aiClearChatQueues();
                LowballingMod.sendAiChat(reply);
                if (isTarget(speaker)) {
                    LowballingMod.aiMarkReplySent(System.currentTimeMillis());
                }
                LowballingMod.debug("AI antwortet: " + reply);
            }
            if (markAh) {
                AH_ASKED.put(lineKey(speaker), true);
            }
            apply(decision, speaker, speakerAt, now);
            return true;
        }

        if (evaluateAt > 0 && now >= evaluateAt) {
            evaluate(now);
        }
        return true;
    }

    private static void evaluate(long now) {
        Pending pending = pickPending();
        if (pending == null) {
            resume(now, true);
            return;
        }
        String key = pending.key;
        String speaker = pending.name;
        long speakerAt = pending.lastAt;
        String text = combinedText(pending.lines);
        List<String> messages = new ArrayList<>();
        for (LowballingChat.PlayerLine line : pending.lines) {
            messages.add(line.text());
        }

        LowballingAiContext context = LowballingMod.aiContext(speaker, messages, now,
                COUNTERS.getOrDefault(key, 0),
                REFUSALS.getOrDefault(key, 0),
                COMPLAINTS.getOrDefault(key, 0),
                AH_ASKED.getOrDefault(key, false),
                HISTORY.getOrDefault(key, List.of()));
        LowballingChatClassification classification = LowballingChatClassifier.classify(
                text, context.expectedValue());
        if (pending.lines.size() > 1 && classification.intent() != LowballingChatIntent.AH_OFFER) {
            String latest = pending.lines.get(pending.lines.size() - 1).text();
            LowballingChatClassification newest = LowballingChatClassifier.classify(
                    latest, context.expectedValue());
            if (!newest.pricePresent() && isStrongLatestIntent(newest)) {
                // "10m" followed by "no" / "wait" / "are u crazy" changes the
                // answer; combining both lines would accept the old 10m.
                classification = newest;
            }
        }
        LowballingAiDecision decision = LowballingAiPolicy.decide(
                context, classification, LowballingMod.aiBuildRules());

        updateCounters(key, decision);
        boolean ahRequest = classification.intent() == LowballingChatIntent.AH_OFFER;
        boolean ahSet = ahRequest && decision.action() != LowballingAiDecision.Action.KICK;
        boolean movedOn = decision.action() == LowballingAiDecision.Action.KICK
                || decision.action() == LowballingAiDecision.Action.SECOND_TRADE
                || (!ahRequest && (classification.pricePresent()
                || classification.intent() == LowballingChatIntent.ACCEPT));
        if (movedOn) {
            AH_ASKED.remove(key);
        }
        log(context, classification, decision);
        lastStatus = speaker + " · " + classification.intent() + " · "
                + decision.action() + (decision.price() > 0
                ? " " + LowballingSmartChat.format(decision.price()) : "");
        LowballingMod.debug("AI: " + classification.intent() + " von " + speaker
                + " -> " + decision.action() + " (" + decision.reason() + ")");

        pendingSpeaker = speaker;
        pendingSpeakerAt = speakerAt;

        if (decision.action() == LowballingAiDecision.Action.WAIT) {
            String reply = LowballingAiReply.sanitize(decision.reply());
            if (reply != null) {
                pendingReply = reply;
                pendingDecision = LowballingAiDecision.wait(null, decision.waitMs(),
                        decision.reason());
                pendingAhSet = ahSet;
                replyDueAt = now + replyDelayMs(reply);
            } else {
                waitUntil = now + Math.max(1_000L, decision.waitMs());
                removePending(key, speakerAt);
                if (isTarget(speaker)) {
                    LowballingMod.aiMarkReplyHandled(speakerAt);
                }
            }
            return;
        }

        String reply = LowballingAiReply.sanitize(decision.reply());
        if (reply != null) {
            pendingReply = reply;
            pendingDecision = decision;
            pendingAhSet = ahSet;
            replyDueAt = now + replyDelayMs(reply);
            return;
        }

        if (ahSet) {
            AH_ASKED.put(key, true);
        }
        apply(decision, speaker, speakerAt, now);
    }

    private static void apply(LowballingAiDecision decision, String speaker,
                              long speakerAt, long now) {
        if (decision == null) {
            resume(now, true);
            return;
        }
        if (!isPlayerOnIsland(speaker)) {
            removePending(lineKey(speaker), speakerAt);
            if (isTarget(speaker)) {
                LowballingMod.aiMarkReplyHandled(speakerAt);
            }
            resume(now, true);
            return;
        }
        removePending(lineKey(speaker), speakerAt);
        if (isTarget(speaker)) {
            LowballingMod.aiMarkReplyHandled(speakerAt);
        }

        switch (decision.action()) {
            case SECOND_TRADE -> {
                LowballingMod.aiClearChatQueues();
                boolean started = LowballingMod.aiStartSecondTrade(decision.price());
                clearCounters(lineKey(speaker));
                if (!started) {
                    lastStatus = "zweites Angebot fehlgeschlagen";
                    LowballingMod.debug("AI: zweites Angebot konnte nicht gestartet werden.");
                }
                resume(now, !started);
            }
            case KICK -> {
                if (LowballingMod.aiTradeSucceeded()) {
                    // The visitor accepted while we were composing the reply.
                    lastStatus = "Trade doch erfolgreich - kein Kick";
                    resume(now, true);
                    break;
                }
                LowballingMod.aiClearChatQueues();
                LowballingMod.aiKick();
                clearCounters(lineKey(speaker));
                resume(now, false);
            }
            case WAIT -> {
                LowballingMod.aiClearChatQueues();
                waitUntil = now + Math.max(1_000L, decision.waitMs());
                // stay paused
            }
            case IGNORE, REPLY_ONLY -> {
                resume(now, true);
                if (decision.marksChatOfferSent() && LowballingMod.aiCanMarkChatOfferSent()) {
                    LowballingMod.aiMarkChatOfferSent(now);
                }
                if (decision.resetChatPhase()) {
                    LowballingMod.aiResetChatPhase(now);
                }
            }
        }
    }

    private static void cancelForNewLines(Set<String> addedKeys, long now) {
        if (addedKeys.isEmpty()) {
            return;
        }
        String current = lineKey(pendingSpeaker);
        String target = lineKey(LowballingMod.aiTargetName());
        boolean targetSpoke = !target.isEmpty() && addedKeys.contains(target);
        boolean currentSpoke = !current.isEmpty() && addedKeys.contains(current);
        boolean cancel = pendingSpeaker == null || targetSpoke || currentSpoke;
        if (!cancel) {
            // Another visitor wrote while the current speaker's reply is being
            // prepared. Finish that reply first; the other line stays queued.
            return;
        }
        clearReply();
        waitUntil = 0;
        evaluateAt = now + LowballingMod.CONFIG.aiDebounceMs;
        lastStatus = "warte auf Tipp-Ende";
    }

    private static void resume(long now, boolean shiftTimers) {
        long elapsed = pausedAt > 0 ? Math.max(0, now - pausedAt) : 0;
        paused = false;
        pausedAt = 0;
        evaluateAt = 0;
        waitUntil = 0;
        clearReply();
        if (shiftTimers) {
            LowballingMod.aiShiftTimers(elapsed);
        }
        if (elapsed > 0) {
            LowballingMod.debug("AI: weiter nach " + (elapsed / 1000) + "s.");
        }
    }

    private static String addPending(LowballingChat.PlayerLine line) {
        String key = lineKey(line.name());
        if (key.isEmpty()) {
            return null;
        }
        Pending pending = PENDING.computeIfAbsent(key,
                ignored -> new Pending(key, line.name()));
        pending.name = line.name();
        pending.lines.add(line);
        while (pending.lines.size() > MAX_PENDING_LINES) {
            pending.lines.remove(0);
        }
        pending.lastAt = Math.max(pending.lastAt, line.at());
        List<String> history = HISTORY.computeIfAbsent(key, ignored -> new ArrayList<>());
        history.add(line.text());
        while (history.size() > MAX_HISTORY_LINES) {
            history.remove(0);
        }
        LAST_SEEN.put(key, Math.max(LAST_SEEN.getOrDefault(key, 0L), line.at()));
        return key;
    }

    private static void removePending(String key, long upToAt) {
        Pending pending = PENDING.get(key);
        if (pending == null) {
            return;
        }
        pending.lines.removeIf(line -> line.at() <= upToAt);
        if (pending.lines.isEmpty()) {
            PENDING.remove(key);
        } else {
            pending.lastAt = pending.lines.get(pending.lines.size() - 1).at();
        }
    }

    private static Pending pickPending() {
        String target = lineKey(LowballingMod.aiTargetName());
        Pending targetPending = target.isEmpty() ? null : PENDING.get(target);
        if (targetPending != null && !targetPending.lines.isEmpty()) {
            return targetPending;
        }
        return PENDING.values().stream()
                .filter(pending -> !pending.lines.isEmpty())
                .max(Comparator.comparingLong(pending -> pending.lastAt))
                .orElse(null);
    }

    private static long newestPendingAt() {
        long newest = 0;
        for (Pending pending : PENDING.values()) {
            newest = Math.max(newest, pending.lastAt);
        }
        return newest;
    }

    private static String combinedText(List<LowballingChat.PlayerLine> lines) {
        StringBuilder builder = new StringBuilder();
        for (LowballingChat.PlayerLine line : lines) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(line.text());
            if (builder.length() >= 300) {
                break;
            }
        }
        return builder.toString();
    }

    /**
     * A final line like "no", "wait", "why so low" or "are u crazy" overrides
     * an older price in the same multi-line message. A bare "?" does not.
     */
    private static boolean isStrongLatestIntent(LowballingChatClassification c) {
        if (c.intent() == LowballingChatIntent.WAIT
                || c.intent() == LowballingChatIntent.REFUSE
                || c.intent() == LowballingChatIntent.INSULT
                || c.intent() == LowballingChatIntent.COMPLAINT) {
            return true;
        }
        return c.intent() == LowballingChatIntent.QUESTION
                && c.text() != null && !c.text().replace("?", "").isBlank();
    }

    private static void updateCounters(String key, LowballingAiDecision decision) {
        if (decision.counter()) {
            COUNTERS.merge(key, 1, Integer::sum);
        }
        if (decision.refusal()) {
            REFUSALS.merge(key, 1, Integer::sum);
        }
        if (decision.complaint()) {
            COMPLAINTS.merge(key, 1, Integer::sum);
        }
    }

    private static void clearCounters(String key) {
        COUNTERS.remove(key);
        REFUSALS.remove(key);
        COMPLAINTS.remove(key);
    }

    /** Clears a player's whole conversation only after they left the island. */
    private static void purgeLeftPlayers(long now) {
        Set<String> keys = new HashSet<>();
        keys.addAll(HISTORY.keySet());
        keys.addAll(PENDING.keySet());
        keys.addAll(COUNTERS.keySet());
        keys.addAll(REFUSALS.keySet());
        keys.addAll(COMPLAINTS.keySet());
        keys.addAll(AH_ASKED.keySet());
        for (String key : keys) {
            if (isPlayerOnIsland(key)) {
                LAST_SEEN.put(key, now);
                continue;
            }
            long seenAt = LAST_SEEN.getOrDefault(key, 0L);
            if (now - seenAt > LEAVE_GRACE_MS) {
                dropPlayer(key);
            }
        }
    }

    private static void dropPlayer(String key) {
        PENDING.remove(key);
        HISTORY.remove(key);
        LAST_SEEN.remove(key);
        clearCounters(key);
        AH_ASKED.remove(key);
    }

    private static void clearReply() {
        pendingReply = null;
        replyDueAt = 0;
        pendingDecision = null;
        pendingAhSet = false;
        pendingSpeaker = null;
        pendingSpeakerAt = 0;
    }

    private static void reset() {
        PENDING.clear();
        COUNTERS.clear();
        REFUSALS.clear();
        COMPLAINTS.clear();
        HISTORY.clear();
        LAST_SEEN.clear();
        AH_ASKED.clear();
        paused = false;
        pausedAt = 0;
        evaluateAt = 0;
        waitUntil = 0;
        lastScreenAt = 0;
        clearReply();
    }

    private static String lineKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isTarget(String name) {
        return name != null && !name.isBlank()
                && name.equalsIgnoreCase(LowballingMod.aiTargetName());
    }

    private static boolean isPlayerOnIsland(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || name == null || name.isBlank()) {
            return false;
        }
        for (Player player : client.level.players()) {
            if (player == client.player || player.getUUID().version() != 4) {
                continue;
            }
            if (player.getName().getString().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static long replyDelayMs(String text) {
        long delay = LowballingSmartChat.typingDelayMs(text,
                ThreadLocalRandom.current().nextDouble());
        long min = LowballingMod.CONFIG.aiMinReplyDelayMs;
        long max = LowballingMod.CONFIG.aiMaxReplyDelayMs;
        return Math.max(min, Math.min(max, delay));
    }

    private static void log(LowballingAiContext context,
                            LowballingChatClassification classification,
                            LowballingAiDecision decision) {
        if (!LowballingMod.CONFIG.aiLogDecisions) {
            return;
        }
        try {
            Path dir = FabricLoader.getInstance().getConfigDir()
                    .resolve("CoflSky").resolve("lowballing");
            Files.createDirectories(dir);
            String line = LocalDateTime.now().format(STAMP)
                    + " | speaker=" + context.speaker()
                    + " | target=" + context.targetName()
                    + " | state=" + context.state()
                    + " | stage=" + context.stage()
                    + " | intent=" + classification.intent()
                    + " | conf=" + String.format(Locale.US, "%.2f", classification.confidence())
                    + " | ask=" + classification.price()
                    + " | first=" + context.firstOffer()
                    + " | last=" + context.lastOffer()
                    + " | ah=" + context.ahAsked()
                    + " | msg=" + context.message()
                    + " | action=" + decision.action()
                    + " | price=" + decision.price()
                    + " | reply=" + (decision.reply() == null ? "" : decision.reply())
                    + " | reason=" + decision.reason()
                    + System.lineSeparator();
            synchronized (LowballingAiController.class) {
                Files.writeString(dir.resolve("ai.log"), line,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            LowballingMod.debug("AI-Log Fehler: " + e.getMessage());
        }
    }

    private static final class Pending {
        final String key;
        String name;
        final List<LowballingChat.PlayerLine> lines = new ArrayList<>();
        long lastAt;

        Pending(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }
}
