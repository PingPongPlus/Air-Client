package com.coflnet.ahflipper;


import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.profit.ProfitTracker;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class AhBidMacro {
    private static final int MAX_PAGES = 32;
    private static final int MAX_TRACKED = 50;
    private static final long RECENT_TTL_MS = 10 * 60_000L;
    private static final Map<String, Tracked> TRACKED = new ConcurrentHashMap<>();
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();
    private static final Map<String, String> REBID_COMMANDS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> REBID_ATTEMPTS = new ConcurrentHashMap<>();
    private static final List<ScoredCandidate> PENDING_BIDS = new ArrayList<>();
    private static volatile boolean running;
    private static volatile Thread worker;
    private static volatile boolean rebidRequested;
    private static volatile long lastScanAt;
    private static volatile long lastOutbidAt;
    private static volatile long lastBidPlacedAt;
    private static volatile int generation;
    private static volatile long lastBusyReportAt;
    private static volatile long rebidNotBefore;
    private static volatile long lastAuctionViewClosedAt;
    private static volatile boolean pendingBidWork;
    private static final long AUCTION_VIEW_SETTLE_MS = 350L;
    private static final int MAX_REBID_ATTEMPTS = 4;

    private AhBidMacro() {
    }

    private static final class Tracked {
        final String uuid;
        final String tag;
        final String name;
        volatile long bid;
        volatile long value;
        volatile long end;
        volatile int listingTries;
        volatile boolean claimed;
        volatile boolean refunded;

        private Tracked(String uuid, String tag, String name, long bid, long value, long end) {
            this.uuid = uuid;
            this.tag = tag;
            this.name = name;
            this.bid = bid;
            this.value = value;
            this.end = end;
        }
    }

    private record Candidate(AhMarketData.ActiveAuction auction, long nextBid,
                             double profitPercent, long marketValue) {
    }

    private record ReportRow(String name, String tag, String uuid, long end, long bid,
                             long value, double profit, double score, String reason) {
    }

    private record ScoredCandidate(AhMarketData.AuctionDetails details, long nextBid,
                                   AhAnalyzer.Result result) {
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onChat(message);
            }
        });
    }

    public static boolean isRunning() {
        return running;
    }

    static boolean hasPendingWork() {
        return pendingBidWork || !REBID_COMMANDS.isEmpty();
    }

    public record BidView(String uuid, String tag, String name, long ownBid,
                          long estimatedValue, long end, String status) {
    }

    public static List<BidView> bidsView() {
        long now = System.currentTimeMillis();
        List<BidView> result = new ArrayList<>();
        for (Tracked tracked : TRACKED.values()) {
            if (tracked.end > 0 && now - tracked.end > 30_000L) {
                continue;
            }
            if (AhFlipperMod.CONFIG.isBlockedItem(tracked.tag, tracked.name)) {
                continue;
            }
            String status = tracked.end > 0 && tracked.end <= now
                    ? "ENDED"
                    : REBID_COMMANDS.containsKey(uuidKey(tracked.uuid))
                    ? "OUTBID" : "HIGHEST";
            result.add(new BidView(tracked.uuid, tracked.tag, tracked.name,
                    tracked.bid, tracked.value, tracked.end, status));
        }
        result.sort((first, second) -> Long.compare(first.end(), second.end()));
        if (result.size() > 5) {
            return new ArrayList<>(result.subList(0, 5));
        }
        return result;
    }

    public static void start() {
        if (running) {
            return;
        }
        Thread previous = worker;
        if (previous != null && previous.isAlive()) {
            previous.interrupt();
            try {
                previous.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        int gen = ++generation;
        running = true;
        lastScanAt = 0;
        REBID_COMMANDS.clear();
        PENDING_BIDS.clear();
        pendingBidWork = false;
        System.out.println("[AH-Flipper] Bid-Macro gestartet (SkyBlock="
                + ScreenUtil.onSkyBlock() + ")");
        Thread thread = new Thread(() -> loop(gen), "skycofl-ah-bid-macro");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        AhFlipperMod.message("\u00a76[AH-Flipper] \u00a7aBid macro active \u00a77(target \u00a7f"
                + String.format(Locale.US, "%.0f", AhFlipperMod.CONFIG.bidMacroMinProfitPercent)
                + "%\u00a77, \u00a7f" + AhFlipperMod.CONFIG.bidMacroMaxRemainingMinutes
                + " min\u00a77).");
    }

    public static void stop() {
        if (!running) {
            return;
        }
        running = false;
        generation++;
        rebidRequested = false;
        PENDING_BIDS.clear();
        pendingBidWork = false;
        REBID_COMMANDS.clear();
        REBID_ATTEMPTS.clear();
        AhFlipperMod.clearBidPriority();
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        AhFlipperMod.message("\u00a76[AH-Flipper] \u00a7cBid macro stopped.");
    }

    private static void loop(int gen) {
        while (running && generation == gen) {
            try {
                if (!ScreenUtil.onSkyBlock()) {
                    ScreenUtil.sleep(2000);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (AhFlipperMod.isBusy()) {
                    if (now - lastBusyReportAt > 60_000L) {
                        lastBusyReportAt = now;
                        writeBidReport("busy",
                                "Scan uebersprungen: AH-Flipper busy (Auto-Buy/Claim/Listing).\n"
                                        + "Aktive Auktionen: " + AhFlipperMod.activeAuctionCount()
                                        + "/" + AhFlipperMod.maxActiveAuctions() + "\n");
                    }
                    ScreenUtil.sleep(250);
                    continue;
                }
                int maxBids = Math.max(1, Math.min(15,
                        AhFlipperMod.CONFIG.bidMacroMaxBidsPerScan));
                if (!REBID_COMMANDS.isEmpty()) {
                    if (now < rebidNotBefore) {
                        ScreenUtil.sleep(100);
                        continue;
                    }
                    processPendingRebids(maxBids);
                    rebidRequested = false;
                    if (!REBID_COMMANDS.isEmpty()) {
                        ScreenUtil.sleep(100);
                        continue;
                    }
                }
                int pendingBids = processPendingBids(maxBids);
                if (pendingBids > 0) {
                    ScreenUtil.sleep(75);
                }
                long interval = Math.max(30, AhFlipperMod.CONFIG.bidMacroScanIntervalSeconds) * 1000L;
                if (rebidRequested || now - lastScanAt >= interval) {
                    rebidRequested = false;
                    lastScanAt = now;
                    int rebids = processTracked(maxBids);
                    if (running) {
                        scanNew(Math.max(0, maxBids - rebids));
                    }
                }
                ScreenUtil.sleep(AhFlipperMod.isBidPriorityActive()
                        || rebidRequested ? 50 : 100);
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Bid-Macro Fehler: " + e.getMessage());
                e.printStackTrace();
                ScreenUtil.sleep(2000);
            }
        }
    }

    private static void onChat(Component message) {
        if (!running || message == null) {
            return;
        }
        String lower = ScreenUtil.cleanName(message.getString()).toLowerCase(Locale.ROOT);
        if (lower.contains("bid") && (lower.contains("placed")
                || lower.contains("accepted"))) {
            lastBidPlacedAt = System.currentTimeMillis();
            AhFlipperMod.clearBidPriority();
            System.out.println("[AH-Flipper] Bid-Bestaetigung im Chat: " + message.getString());
            return;
        }
        if (!(lower.contains("outbid you for") || lower.contains("you were outbid")
                || lower.contains("you have been outbid")
                || (lower.contains("outbid") && lower.contains("click"))
                || (lower.contains("outbid") && lower.contains("your bid")))) {
            return;
        }
        long now = System.currentTimeMillis();
        lastOutbidAt = now;
        rebidRequested = true;
        rebidNotBefore = now;
        String clickCommand = findRunCommand(message);
        String clickUuid = uuidFromCommand(clickCommand);
        if (!clickUuid.isBlank()) {
            Tracked outbid = trackedFor(clickUuid);
            if (outbid != null && !outbid.refunded) {
                ProfitTracker.receive(ProfitTracker.AH, outbid.bid);
                outbid.refunded = true;
            }
            AhFlipperMod.requestBidPriority(20_000L);
            AhFlipperMod.closeGuiForBidPriority();
            REBID_COMMANDS.put(clickUuid, clickCommand);
            REBID_ATTEMPTS.remove(clickUuid);
            AhMarketData.invalidateAuction(clickUuid);
        }
        System.out.println("[AH-Flipper] Outbid-Chat erkannt: " + message.getString()
                + (clickCommand.isBlank() ? "" : " -> " + clickCommand));
    }

    private static String findRunCommand(Component component) {
        if (component == null) {
            return "";
        }
        ClickEvent event = component.getStyle().getClickEvent();
        if (event instanceof ClickEvent.RunCommand runCommand) {
            return runCommand.command();
        }
        for (Component sibling : component.getSiblings()) {
            String found = findRunCommand(sibling);
            if (!found.isBlank()) {
                return found;
            }
        }
        return "";
    }

    private static String uuidKey(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static Tracked trackedFor(String key) {
        String normalized = uuidKey(key);
        Tracked direct = TRACKED.get(dashedUuid(key));
        if (direct != null) {
            return direct;
        }
        for (Tracked tracked : TRACKED.values()) {
            if (uuidKey(tracked.uuid).equals(normalized)) {
                return tracked;
            }
        }
        return null;
    }

    private static AhMarketData.AuctionDetails chatDetails(String key) {
        String uuid = dashedUuid(key);
        if (uuid.length() != 36) {
            return null;
        }
        AhMarketData.AuctionDetails details = new AhMarketData.AuctionDetails();
        details.uuid = uuid;
        details.bin = false;
        details.rawStartingBid = 1;
        details.highestBidAmount = 1;
        details.startingBid = 1;
        return details;
    }

    private static AhMarketData.AuctionDetails syntheticDetails(Tracked tracked) {
        AhMarketData.AuctionDetails details = new AhMarketData.AuctionDetails();
        details.uuid = tracked.uuid;
        details.tag = tracked.tag;
        details.itemName = tracked.name;
        details.end = tracked.end;
        details.bin = false;
        details.rawStartingBid = Math.max(0, tracked.bid);
        details.highestBidAmount = Math.max(0, tracked.bid);
        details.startingBid = details.highestBidAmount;
        return details;
    }

    private static String dashedUuid(String uuid) {
        String key = uuidKey(uuid);
        if (key.length() != 32) {
            return uuid == null ? "" : uuid;
        }
        return key.substring(0, 8) + "-" + key.substring(8, 12) + "-"
                + key.substring(12, 16) + "-" + key.substring(16, 20) + "-"
                + key.substring(20);
    }

    private static String uuidFromCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        for (String token : command.split("\\s+")) {
            String cleaned = token.replace("-", "");
            if (cleaned.matches("[0-9a-fA-F]{32}")) {
                return cleaned.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static boolean rebidWaiting() {
        return !REBID_COMMANDS.isEmpty() || rebidRequested;
    }

    private static int processTracked(int maxBids) {
        if (TRACKED.isEmpty()) {
            return 0;
        }
        int rebids = 0;
        for (Tracked tracked : new ArrayList<>(TRACKED.values())) {
            if (!running || rebids >= maxBids || rebidWaiting()) {
                return rebids;
            }
            try {
                AhMarketData.AuctionDetails details = AhMarketData.auction(tracked.uuid);
                if (details == null) {
                    if (tracked.end > 0
                            && tracked.end <= System.currentTimeMillis()) {
                        settle(tracked);
                    }
                    continue;
                }
                if (details.end > 0) {
                    tracked.end = details.end;
                }
                tracked.value = Math.max(tracked.value, 0);
                long highest = Math.max(0, details.highestBidAmount);
                boolean bidderMissing = details.highestBidder == null
                        || details.highestBidder.isBlank();
                boolean own = AhFlipperMod.isOwnSeller(details.highestBidder, "")
                        || (bidderMissing && tracked.bid > 0 && highest <= tracked.bid);
                if (details.end > 0 && details.end <= System.currentTimeMillis()) {
                    if (own) {
                        settle(tracked);
                    } else {
                        if (!tracked.refunded) {
                            ProfitTracker.receive(ProfitTracker.AH, tracked.bid);
                            tracked.refunded = true;
                        }
                        TRACKED.remove(tracked.uuid);
                        remember(tracked.uuid);
                        System.out.println("[AH-Flipper] Bid-Macro verloren: " + tracked.name);
                    }
                    continue;
                }
                if (own) {
                    REBID_COMMANDS.remove(uuidKey(tracked.uuid));
                    continue;
                }
                if (highest <= tracked.bid) {
                    REBID_COMMANDS.remove(uuidKey(tracked.uuid));
                    continue;
                }
                if (details.end > 0
                        && details.end <= System.currentTimeMillis() + 3000L) {
                    continue;
                }
                if (tryBid(details, true, null, true)) {
                    rebids++;
                    tracked.value = Math.max(tracked.value, 0);
                } else if (recentlyRejected(tracked.uuid)) {
                    TRACKED.remove(tracked.uuid);
                }
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Bid-Macro Tracked-Fehler f\u00fcr "
                        + tracked.name + ": " + e.getMessage());
            }
        }
        return rebids;
    }

    private static int processPendingRebids(int maxBids) {
        if (REBID_COMMANDS.isEmpty()) {
            AhFlipperMod.clearBidPriority();
            return 0;
        }
        if (System.currentTimeMillis() < rebidNotBefore) {
            return 0;
        }
        int bids = 0;
        for (Map.Entry<String, String> entry : new ArrayList<>(REBID_COMMANDS.entrySet())) {
            if (!running || bids >= maxBids || AhFlipperMod.isBusy()) {
                break;
            }
            Tracked tracked = trackedFor(entry.getKey());
            AhMarketData.AuctionDetails details = tracked != null
                    ? syntheticDetails(tracked)
                    : chatDetails(entry.getKey());
            if (details == null) {
                REBID_COMMANDS.remove(entry.getKey());
                REBID_ATTEMPTS.remove(entry.getKey());
                continue;
            }
            if (details.bin || (details.end > 0 && details.end <= System.currentTimeMillis())
                    || AhFlipperMod.isOwnSeller(details.highestBidder, "")) {
                REBID_COMMANDS.remove(entry.getKey());
                REBID_ATTEMPTS.remove(entry.getKey());
                continue;
            }
            if (recentlyRejected(details.uuid) || RECENT.containsKey(entry.getKey())) {
                REBID_COMMANDS.remove(entry.getKey());
                REBID_ATTEMPTS.remove(entry.getKey());
                continue;
            }
            if (!rebidStillProfitable(details, tracked)) {
                REBID_COMMANDS.remove(entry.getKey());
                REBID_ATTEMPTS.remove(entry.getKey());
                remember(details.uuid);
                continue;
            }
            if (tryBid(details, true, null, true, true)) {
                bids++;
                REBID_ATTEMPTS.remove(entry.getKey());
            } else {
                if (recentlyRejected(details.uuid)
                        || RECENT.containsKey(entry.getKey())) {
                    REBID_COMMANDS.remove(entry.getKey());
                    REBID_ATTEMPTS.remove(entry.getKey());
                    System.out.println("[AH-Flipper] Outbid verworfen (nicht profitabel/blockiert): "
                            + entry.getKey());
                    continue;
                }
                int attempts = REBID_ATTEMPTS.merge(entry.getKey(), 1, Integer::sum);
                if (attempts >= MAX_REBID_ATTEMPTS) {
                    REBID_COMMANDS.remove(entry.getKey());
                    REBID_ATTEMPTS.remove(entry.getKey());
                    remember(details.uuid);
                    System.out.println("[AH-Flipper] Outbid-Retry aufgegeben nach "
                            + attempts + " Versuchen: " + entry.getKey());
                } else {
                    rebidNotBefore = System.currentTimeMillis() + 750L;
                    AhFlipperMod.requestBidPriority(15_000L);
                    System.out.println("[AH-Flipper] Outbid-Retry " + attempts
                            + "/" + MAX_REBID_ATTEMPTS + " in 0.8s: " + entry.getKey());
                }
            }
        }
        if (REBID_COMMANDS.isEmpty()) {
            AhFlipperMod.clearBidPriority();
        }
        return bids;
    }

    private static void settle(Tracked tracked) {
        if (!AhFlipperMod.beginBidAction()) {
            return;
        }
        try {
            if (!tracked.claimed) {
                if (!AhFlipperMod.claimWonAuction(tracked.tag, tracked.name)) {
                    TRACKED.remove(tracked.uuid);
                    AhFlipperMod.message("\u00a7cClaim failed - purchase not received: \u00a7f"
                            + tracked.name);
                    System.out.println("[AH-Flipper] Claim(2x, 3s) fehlgeschlagen, "
                            + "Kauf wird als nicht gewonnen betrachtet: " + tracked.name);
                    return;
                }
                tracked.claimed = true;
                AhFlipperMod.message("\u00a7aBid macro won: \u00a7f" + tracked.name);
            }
            if (!AhFlipperMod.CONFIG.bidMacroAutoList) {
                TRACKED.remove(tracked.uuid);
                return;
            }
            if (AhFlipperMod.listPurchased(tracked.tag, tracked.name,
                    tracked.value, tracked.bid, tracked.uuid)) {
                TRACKED.remove(tracked.uuid);
                return;
            }
            tracked.listingTries++;
            if (tracked.listingTries >= 5) {
                TRACKED.remove(tracked.uuid);
                AhFlipperMod.message("\u00a7cListing failed: \u00a7f"
                        + tracked.name);
            } else {
                AhFlipperMod.message("\u00a7eRetrying listing: \u00a7f"
                        + tracked.name);
            }
        } finally {
            AhFlipperMod.endBidAction();
        }
    }

    private static void scanNew(int budget) {
        if (budget <= 0 || AhFlipperMod.isBusy() || !running || rebidWaiting()) {
            return;
        }
        long now = System.currentTimeMillis();
        long horizon = now + Math.max(1,
                AhFlipperMod.CONFIG.bidMacroMaxRemainingMinutes) * 60_000L;
        RECENT.entrySet().removeIf(entry -> now - entry.getValue() > RECENT_TTL_MS);
        List<AhMarketData.ActiveAuction> auctions =
                AhMarketData.activeNonBinEndingSoon(MAX_PAGES, horizon,
                        AhBidMacro::rebidWaiting);
        if (rebidWaiting()) {
            return;
        }
        if (auctions.isEmpty()) {
            System.out.println("[AH-Flipper] Bid-Macro Scan: keine Auktionen im Zeitfenster");
            writeBidReport("empty", "Keine Non-BIN-Auktionen im "
                    + AhFlipperMod.CONFIG.bidMacroMaxRemainingMinutes
                    + "-Minuten-Fenster.\nAktive Auktionen: "
                    + AhFlipperMod.activeAuctionCount() + "/"
                    + AhFlipperMod.maxActiveAuctions() + "\n");
            return;
        }
        Map<String, AhMarketData.MarketEntry> market = AhMarketData.market();
        System.out.println("[AH-Flipper] Bid-Macro Scan: " + auctions.size()
                + " Auktionen, " + market.size() + " Markt-Tags");
        double target = Math.max(1.0, AhFlipperMod.CONFIG.bidMacroMinProfitPercent);
        List<Candidate> candidates = new ArrayList<>();
        int skipTracked = 0;
        int skipEnding = 0;
        int skipBlocked = 0;
        int skipNoMarket = 0;
        int skipVolume = 0;
        int skipBid = 0;
        for (AhMarketData.ActiveAuction auction : auctions) {
            if (rebidWaiting()) {
                return;
            }
            if (auction.uuid.isBlank() || TRACKED.containsKey(auction.uuid)
                    || recentlyRejected(auction.uuid)) {
                skipTracked++;
                continue;
            }
            if (auction.end <= now + 5000L) {
                skipEnding++;
                continue;
            }
            if (auction.tag.toUpperCase(Locale.ROOT).startsWith("PET_")
                    || (auction.itemName != null && auction.itemName.startsWith("[Lvl"))) {
                skipBlocked++;
                continue;
            }
            if (AhFlipperMod.CONFIG.isBlockedItem(auction.tag, auction.itemName)) {
                skipBlocked++;
                continue;
            }
            AhMarketData.MarketEntry entry = market.get(auction.tag);
            long value;
            if (entry == null) {
                int runeLevel = AhItem.runeLevel(auction.itemName);
                AhMarketData.RuneStats rune = runeLevel > 0
                        && AhItem.isRuneTag(auction.tag)
                        ? AhMarketData.runeStats(auction.tag, runeLevel) : null;
                if (rune == null || rune.sales < 2 || rune.median <= 0) {
                    skipNoMarket++;
                    continue;
                }
                value = rune.median;
            } else {
                if (entry.volume < AhFlipperMod.CONFIG.scanMinVolume) {
                    skipVolume++;
                    continue;
                }
                value = entry.value();
            }
            if (value <= 0) {
                skipNoMarket++;
                continue;
            }
            long bid = nextBid(auction.startingBid, auction.highestBidAmount);
            if (bid <= 0) {
                skipBid++;
                continue;
            }
            candidates.add(new Candidate(auction, bid, profitPercent(value, bid), value));
        }
        List<ReportRow> analyzedRows = new ArrayList<>();
        System.out.println("[AH-Flipper] Bid-Macro Filter: tracked=" + skipTracked
                + " ending=" + skipEnding + " blocked=" + skipBlocked
                + " noMarket=" + skipNoMarket + " volume=" + skipVolume
                + " bid=" + skipBid);
        candidates.sort(Comparator.comparingLong(
                (Candidate candidate) -> Math.max(0, candidate.marketValue() - candidate.nextBid()))
                .reversed()
                .thenComparing(Comparator.comparingLong(Candidate::marketValue).reversed()));
        int maxCandidates = Math.max(1, Math.min(15,
                AhFlipperMod.CONFIG.bidMacroMaxCandidatesPerScan));
        int maxBids = Math.max(1, Math.min(budget, 15));
        List<Candidate> uniqueCandidates = new ArrayList<>();
        Set<String> seenTags = new HashSet<>();
        for (Candidate candidate : candidates) {
            String tag = candidate.auction().tag == null
                    ? "" : candidate.auction().tag.toLowerCase(Locale.ROOT);
            if (seenTags.add(tag)) {
                uniqueCandidates.add(candidate);
            }
        }
        int analysisLimit = Math.min(90, Math.max(uniqueCandidates.size(),
                Math.max(maxCandidates, maxBids) * 3));
        List<ScoredCandidate> scored = new ArrayList<>();
        int analyzed = 0;
        for (Candidate candidate : uniqueCandidates) {
            if (!running || analyzed >= analysisLimit || AhFlipperMod.isBusy()
                    || rebidWaiting()) {
                break;
            }
            analyzed++;
            AhMarketData.AuctionDetails details = AhMarketData.auction(candidate.auction().uuid);
            if (details == null) {
                analyzedRows.add(new ReportRow(candidate.auction().itemName,
                        candidate.auction().tag, candidate.auction().uuid,
                        candidate.auction().end, candidate.nextBid(),
                        candidate.marketValue(), candidate.profitPercent(), 0,
                        "Details nicht abrufbar"));
                continue;
            }
            if (details.bin || details.end <= 0) {
                remember(candidate.auction().uuid);
                analyzedRows.add(new ReportRow(details.itemName, details.tag, details.uuid,
                        details.end, candidate.nextBid(), candidate.marketValue(),
                        candidate.profitPercent(), 0, "BIN oder Ende fehlt"));
                continue;
            }
            if (details.end > horizon) {
                analyzedRows.add(new ReportRow(details.itemName, details.tag, details.uuid,
                        details.end, candidate.nextBid(), candidate.marketValue(),
                        candidate.profitPercent(), 0, "Ende ausserhalb Zeitfenster"));
                continue;
            }
            if (AhFlipperMod.isOwnSeller(details.highestBidder, "")) {
                analyzedRows.add(new ReportRow(details.itemName, details.tag, details.uuid,
                        details.end, candidate.nextBid(), candidate.marketValue(),
                        candidate.profitPercent(), 0, "Eigener Highest-Bidder"));
                continue;
            }
            long bid = nextBid(details);
            AhItem apiItem = AhItem.fromAuction(details);
            if (apiItem.isPet()) {
                remember(details.uuid);
                analyzedRows.add(new ReportRow(details.itemName, details.tag, details.uuid,
                        details.end, bid, candidate.marketValue(), 0, 0,
                        "Pets deaktiviert"));
                continue;
            }
            AhAnalyzer.Result result = AhAnalyzer.analyze(apiItem, bid, 1, details.uuid, 0);
            String reason = profitable(result, target, details.itemName)
                    ? "" : rejectionReason(result, target);
            analyzedRows.add(new ReportRow(details.itemName, details.tag, details.uuid,
                    details.end, bid,
                    result == null ? 0 : result.expectedValue,
                    result == null ? 0 : result.profitPercent,
                    result == null ? 0 : result.score,
                    reason));
            if (!reason.isBlank()) {
                continue;
            }
            scored.add(new ScoredCandidate(details, bid, result));
        }
        if (rebidWaiting()) {
            return;
        }
        scored.sort(Comparator.comparingDouble(
                (ScoredCandidate candidate) -> candidate.result().score).reversed());
        System.out.println("[AH-Flipper] Bid-Macro Scan: " + auctions.size() + " aktiv, "
                + candidates.size() + " Kandidaten, " + scored.size()
                + " bewertet, max " + maxBids + " Gebote");
        queuePendingBids(scored);
        int bids = processPendingBids(maxBids);
        if (bids == 0) {
            writeScanReport(auctions.size(), market.size(), candidates,
                    uniqueCandidates.size(), analyzedRows,
                    analyzed, target, skipTracked, skipEnding, skipBlocked,
                    skipNoMarket, skipVolume, skipBid);
        }
    }

    private static String rejectionReason(AhAnalyzer.Result result, double target) {
        if (result == null) {
            return "Analyse fehlgeschlagen";
        }
        List<String> reasons = new ArrayList<>(result.reasons);
        if (!result.buyable && reasons.isEmpty()) {
            reasons.add("buyable=false");
        }
        if (result.expectedValue <= 0) {
            reasons.add("Wert <= 0");
        }
        if (result.expectedValue > 0
                && result.expectedValue < AhFlipperMod.CONFIG.bidMacroMinValueCoins) {
            reasons.add("Wert < " + (AhFlipperMod.CONFIG.bidMacroMinValueCoins / 1_000_000)
                    + "M");
        }
        if (result.cleanPrice <= 0) {
            reasons.add("cleanPrice <= 0");
        }
        if (result.profitPercent < target) {
            reasons.add(String.format(Locale.US, "Profit %.1f%% < %.1f%%",
                    result.profitPercent, target));
        }
        return String.join("; ", reasons);
    }

    private static void writeScanReport(int activeAuctions, int marketTags,
                                        List<Candidate> candidates, int uniqueTags,
                                        List<ReportRow> analyzedRows,
                                        int analyzed, double target,
                                        int skipTracked, int skipEnding, int skipBlocked,
                                        int skipNoMarket, int skipVolume, int skipBid) {
        List<ReportRow> sortedAnalyzed = new ArrayList<>(analyzedRows);
        sortedAnalyzed.sort(Comparator.comparingDouble(ReportRow::score).reversed());
        StringBuilder body = new StringBuilder();
        body.append("Aktive Auktionen: ").append(AhFlipperMod.activeAuctionCount())
                .append("/").append(AhFlipperMod.maxActiveAuctions()).append('\n');
        body.append("Auktionen im Zeitfenster: ").append(activeAuctions).append('\n');
        body.append("Markt-Tags: ").append(marketTags).append('\n');
        body.append("Roh-Kandidaten: ").append(candidates.size())
                .append(" | unique Tags: ").append(uniqueTags)
                .append(" | analysiert: ").append(analyzed)
                .append(" | Ziel-Profit: ").append(String.format(Locale.US, "%.1f%%", target))
                .append('\n');
        body.append("Filter: tracked=").append(skipTracked)
                .append(" ending=").append(skipEnding)
                .append(" blocked/pets=").append(skipBlocked)
                .append(" noMarket=").append(skipNoMarket)
                .append(" volume=").append(skipVolume)
                .append(" bid=").append(skipBid).append('\n');
        body.append("Gebote in diesem Scan: 0\n\n");

        Map<String, Integer> reasonCounts = new java.util.TreeMap<>();
        for (ReportRow row : analyzedRows) {
            String reason = row.reason() == null || row.reason().isBlank()
                    ? "BUYABLE" : row.reason();
            for (String part : reason.split(";")) {
                String key = part.trim();
                if (!key.isEmpty()) {
                    reasonCounts.merge(key, 1, Integer::sum);
                }
            }
        }
        body.append("=== ABLEHNUNGSGRUENDE (analysiert) ===\n");
        reasonCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .forEach(entry -> body.append(entry.getValue()).append("x ")
                        .append(entry.getKey()).append('\n'));
        if (reasonCounts.isEmpty()) {
            body.append("keine\n");
        }
        body.append('\n');

        body.append("=== TOP 15 ANALYSIERT (Score) ===\n");
        int analyzedLimit = Math.min(15, sortedAnalyzed.size());
        for (int i = 0; i < analyzedLimit; i++) {
            appendReportRow(body, i + 1, sortedAnalyzed.get(i));
        }
        if (sortedAnalyzed.isEmpty()) {
            body.append("keine analysierten Kandidaten\n");
        }

        body.append("\n=== TOP 15 ROH (absolutes Markt-Potenzial) ===\n");
        int rawLimit = Math.min(15, candidates.size());
        for (int i = 0; i < rawLimit; i++) {
            Candidate candidate = candidates.get(i);
            AhMarketData.ActiveAuction auction = candidate.auction();
            body.append(i + 1).append(". ").append(auction.itemName)
                    .append(" [").append(auction.tag).append("]")
                    .append(" uuid=").append(auction.uuid)
                    .append(" endIn=").append(Math.max(0,
                            (auction.end - System.currentTimeMillis()) / 60_000L)).append("m")
                    .append(" nextBid=").append(ScreenUtil.formatCoins(candidate.nextBid()))
                    .append(" market=").append(ScreenUtil.formatCoins(candidate.marketValue()))
                    .append(" potential=").append(ScreenUtil.formatCoins(
                            Math.max(0, candidate.marketValue() - candidate.nextBid())))
                    .append(" marketProfit=").append(String.format(Locale.US, "%.1f%%",
                            candidate.profitPercent()))
                    .append('\n');
        }
        writeBidReport("scan", body.toString());
    }

    private static void appendReportRow(StringBuilder body, int index, ReportRow row) {
        body.append(index).append(". ").append(row.name())
                .append(" [").append(row.tag()).append("]")
                .append(" uuid=").append(row.uuid())
                .append(" endIn=").append(Math.max(0,
                        (row.end() - System.currentTimeMillis()) / 60_000L)).append("m")
                .append(" nextBid=").append(ScreenUtil.formatCoins(row.bid()))
                .append(" value=").append(ScreenUtil.formatCoins(row.value()))
                .append(" profit=").append(String.format(Locale.US, "%.1f%%", row.profit()))
                .append(" score=").append(String.format(Locale.US, "%.0f", row.score()))
                .append(" reason=").append(row.reason().isBlank()
                        ? "kaufbar, aber kein Bid ausgefuehrt" : row.reason())
                .append('\n');
    }

    private static void writeBidReport(String tag, String body) {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir()
                    .resolve("CoflSky").resolve("bidmacro");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String content = "Bid-Macro Report " + LocalDateTime.now() + "\n"
                    + body + "\n";
            Files.write(dir.resolve("bidmacro-" + stamp + "-" + tag + ".txt"),
                    content.getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("latest-" + tag + ".txt"),
                    content.getBytes(StandardCharsets.UTF_8));
            System.out.println("[AH-Flipper] Bid-Macro Report: " + dir);
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Bid-Macro Report Fehler: " + e.getMessage());
        }
    }

    private static boolean tryBid(AhMarketData.AuctionDetails details, boolean rebid) {
        return tryBid(details, rebid, null, false);
    }

    private static boolean tryBid(AhMarketData.AuctionDetails details, boolean rebid,
                                  AhAnalyzer.Result precomputed) {
        return tryBid(details, rebid, precomputed, false);
    }

    private static boolean tryBid(AhMarketData.AuctionDetails details, boolean rebid,
                                  AhAnalyzer.Result precomputed, boolean priority) {
        return tryBid(details, rebid, precomputed, priority, false);
    }

    private static boolean tryBid(AhMarketData.AuctionDetails details, boolean rebid,
                                  AhAnalyzer.Result precomputed, boolean priority,
                                  boolean skipPreAnalysis) {
        if (details == null || details.uuid.isBlank() || details.bin) {
            return false;
        }
        if (!running || AhFlipperMod.isOwnSeller(details.highestBidder, "")) {
            return false;
        }
        long bid = nextBid(details);
        if (bid <= 0) {
            return false;
        }
        double target = Math.max(1.0, AhFlipperMod.CONFIG.bidMacroMinProfitPercent);
        AhItem apiItem = AhItem.fromAuction(details);
        if (apiItem.isPet()) {
            remember(details.uuid);
            return false;
        }
        if (AhFlipperMod.CONFIG.isBlockedItem(apiItem.tag, apiItem.displayName)) {
            remember(details.uuid);
            return false;
        }
        AhAnalyzer.Result result = precomputed;
        if (result != null && result.expectedValue > 0
                && (result.expectedValue < AhFlipperMod.CONFIG.bidMacroMinValueCoins
                || profitPercent(result.expectedValue, bid) < target)) {
            remember(details.uuid);
            return false;
        }
        if (!skipPreAnalysis && result == null) {
            result = AhAnalyzer.analyze(apiItem, bid, 1, details.uuid, 0);
            if (!profitable(result, target, details.itemName)) {
                remember(details.uuid);
                return false;
            }
        }
        long maxBid = maxBidForPurse();
        if (maxBid > 0 && bid > maxBid) {
            System.out.println("[AH-Flipper] Bid-Macro Bankroll-Gate: " + details.itemName
                    + " bid=" + bid + " max=" + maxBid);
            remember(details.uuid);
            return false;
        }
        if (details.end > 0 && details.end <= System.currentTimeMillis() + 1000L) {
            remember(details.uuid);
            return false;
        }
        long sinceClose = System.currentTimeMillis() - lastAuctionViewClosedAt;
        if (sinceClose >= 0 && sinceClose < AUCTION_VIEW_SETTLE_MS) {
            ScreenUtil.sleep(AUCTION_VIEW_SETTLE_MS - sinceClose);
        }
        if (!AhFlipperMod.beginBidAction(priority)) {
            return false;
        }
        boolean openAttempted = false;
        try {
            String openCommand = REBID_COMMANDS.get(uuidKey(details.uuid));
            if (openCommand == null || openCommand.isBlank()) {
                openCommand = "/viewauction " + details.uuid;
            }
            if (!AhFlipperMod.sendCommandSilent(openCommand)) {
                return false;
            }
            openAttempted = true;
            if (!waitForAuctionView(3000)) {
                System.out.println("[AH-Flipper] Bid-Macro: Auction-View nicht ge\u00f6ffnet: "
                        + details.itemName);
                return false;
            }
            if (!waitForBidReady(3000)) {
                System.out.println("[AH-Flipper] Bid-Macro: Bid-Screen nicht bereit: "
                        + details.itemName);
                return false;
            }
            AhItem item = AhItem.fromStackWithTooltip(AhScreenUtil.currentAuctionItem());
            if (item == null || item.tag == null || item.tag.isBlank()) {
                item = apiItem;
            }
            if (item == null || item.tag == null || item.tag.isBlank()) {
                System.out.println("[AH-Flipper] Bid-Macro: Item nicht lesbar: " + details.itemName);
                AhScreenUtil.dumpScreen();
                return false;
            }
            long required = AhScreenUtil.currentRequiredBid();
            if (required > 0) {
                bid = required;
            } else {
                long screenBid = AhScreenUtil.currentBidPrice();
                if (screenBid > details.highestBidAmount && screenBid > details.rawStartingBid) {
                    bid = Math.max(bid, nextBid(details.rawStartingBid, screenBid));
                }
            }
            result = AhAnalyzer.analyze(item, bid, 1, details.uuid, 0);
            if (!profitable(result, target, item.displayName)) {
                remember(details.uuid);
                return false;
            }
            if (maxBid > 0 && bid > maxBid) {
                System.out.println("[AH-Flipper] Bid-Macro Bankroll-Gate (Screen): "
                        + item.displayName + " bid=" + bid + " max=" + maxBid);
                remember(details.uuid);
                return false;
            }
            int slot = AhScreenUtil.findBidButton();
            if (slot < 0) {
                System.out.println("[AH-Flipper] Bid-Macro: Submit-Bid-Button fehlt: "
                        + item.displayName);
                AhScreenUtil.dumpScreen();
                return false;
            }
            if (details.end > 0 && details.end <= System.currentTimeMillis() + 1000L) {
                remember(details.uuid);
                return false;
            }
            if (!submitAndConfirmBid(slot)) {
                System.out.println("[AH-Flipper] Bid-Macro: Submit/Confirm fehlgeschlagen: "
                        + item.displayName);
                AhScreenUtil.dumpScreen();
                return false;
            }
            String rebidKey = uuidKey(details.uuid);
            REBID_COMMANDS.remove(rebidKey);
            REBID_ATTEMPTS.remove(rebidKey);
            Tracked previous = TRACKED.get(details.uuid);
            if (previous != null && !previous.refunded) {
                ProfitTracker.receive(ProfitTracker.AH, previous.bid);
            }
            Tracked tracked = new Tracked(details.uuid, item.tag, item.displayName,
                    bid, result.expectedValue, details.end);
            ProfitTracker.spend(ProfitTracker.AH, bid);
            TRACKED.put(details.uuid, tracked);
            if (TRACKED.size() > MAX_TRACKED) {
                TRACKED.entrySet().stream()
                        .min(Comparator.comparingLong(entry -> entry.getValue().end))
                        .ifPresent(entry -> TRACKED.remove(entry.getKey()));
            }
            RECENT.remove(details.uuid);
            AhFlipperMod.message((rebid ? "\u00a7eRebid" : "\u00a7aBid")
                    + "\u00a77: \u00a7f" + item.displayName + " \u00a77@ \u00a7f"
                    + ScreenUtil.formatCoins(bid) + " \u00a77(+\u00a7a"
                    + String.format(Locale.US, "%.0f", result.profitPercent) + "%\u00a77)");
            return true;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Bid-Macro Fehler: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            if (openAttempted) {
                closeAuctionScreens();
                lastAuctionViewClosedAt = System.currentTimeMillis();
            }
            if (priority) {
                AhFlipperMod.clearBidPriority();
            }
            AhFlipperMod.endBidAction();
        }
    }

    private static boolean submitAndConfirmBid(int slot) {
        if (slot < 0) {
            return false;
        }
        lastBidPlacedAt = 0;
        ScreenUtil.clickSlot(slot, 0);
        long deadline = System.currentTimeMillis() + 4000;
        boolean resubmitted = false;
        while (running && System.currentTimeMillis() < deadline) {
            if (bidPlacedRecently(4000L)) {
                return true;
            }
            int confirm = AhScreenUtil.findConfirmBidButton();
            if (confirm >= 0) {
                ScreenUtil.clickSlot(confirm, 0);
                long confirmDeadline = System.currentTimeMillis() + 1200;
                while (running && System.currentTimeMillis() < confirmDeadline) {
                    if (bidPlacedRecently(4000L)) {
                        return true;
                    }
                    if (!AhScreenUtil.isContainerOpen()) {
                        return true;
                    }
                    if (AhScreenUtil.findConfirmBidButton() < 0) {
                        return true;
                    }
                    ScreenUtil.sleep(100);
                }
                return false;
            }
            if (!AhScreenUtil.isContainerOpen()) {
                return true;
            }
            if (AhScreenUtil.currentIsHighestBidder()) {
                return true;
            }
            if (!resubmitted && System.currentTimeMillis() >= deadline - 2500) {
                int again = AhScreenUtil.findBidButton();
                if (again >= 0) {
                    ScreenUtil.clickSlot(again, 0);
                    resubmitted = true;
                }
            }
            ScreenUtil.sleep(100);
        }
        return false;
    }

    private static boolean bidPlacedRecently(long windowMs) {
        long at = lastBidPlacedAt;
        return at > 0 && System.currentTimeMillis() - at <= Math.max(100L, windowMs);
    }

    private static boolean profitable(AhAnalyzer.Result result, double target, String name) {
        if (result == null || !result.buyable || result.expectedValue <= 0
                || result.expectedValue < AhFlipperMod.CONFIG.bidMacroMinValueCoins
                || result.cleanPrice <= 0 || result.profitPercent < target) {
            System.out.println("[AH-Flipper] Bid-Macro: " + name
                    + " nicht profitabel (Wert="
                    + (result == null ? "?" : String.format(Locale.US, "%.2fM",
                    result.expectedValue / 1_000_000.0))
                    + ", "
                    + String.format(Locale.US, "%.1f", result == null ? -1 : result.profitPercent)
                    + "% < " + String.format(Locale.US, "%.1f", target) + "%) "
                    + (result == null ? "" : result.reasons));
            return false;
        }
        return true;
    }

    private static long maxBidForPurse() {
        long purse = ScreenUtil.readPurse();
        long bankroll = purse > 0 ? purse - AhFlipperMod.CONFIG.reserveCoins : -1;
        if (bankroll > 0 && AhFlipperMod.CONFIG.maxBankrollCoins > 0) {
            bankroll = Math.min(bankroll, AhFlipperMod.CONFIG.maxBankrollCoins);
        }
        return bankroll > 0
                ? Math.round(bankroll * AhFlipperMod.CONFIG.bidMacroMaxBankrollFraction)
                : -1;
    }

    private static void queuePendingBids(List<ScoredCandidate> scored) {
        for (ScoredCandidate candidate : scored) {
            boolean duplicate = false;
            for (ScoredCandidate pending : PENDING_BIDS) {
                if (pending.details().uuid.equalsIgnoreCase(candidate.details().uuid)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                PENDING_BIDS.add(candidate);
            }
        }
        PENDING_BIDS.sort(Comparator.comparingDouble(
                (ScoredCandidate candidate) -> candidate.result().score).reversed());
        while (PENDING_BIDS.size() > 50) {
            PENDING_BIDS.remove(PENDING_BIDS.size() - 1);
        }
        pendingBidWork = !PENDING_BIDS.isEmpty();
    }

    private static int processPendingBids(int maxBids) {
        int bids = 0;
        while (running && !PENDING_BIDS.isEmpty() && bids < maxBids) {
            if (AhFlipperMod.isBusy()) {
                break;
            }
            ScoredCandidate candidate = PENDING_BIDS.get(0);
            if (candidate.details().end > 0
                    && candidate.details().end <= System.currentTimeMillis() + 1000L) {
                PENDING_BIDS.remove(0);
                continue;
            }
            try {
                if (tryBid(candidate.details(), false, candidate.result(), false)) {
                    PENDING_BIDS.remove(0);
                    bids++;
                } else if (!AhFlipperMod.isBusy()) {
                    PENDING_BIDS.remove(0);
                }
            } catch (Exception e) {
                System.out.println("[AH-Flipper] Bid-Macro Pending-Fehler: " + e.getMessage());
                PENDING_BIDS.remove(0);
            }
        }
        pendingBidWork = !PENDING_BIDS.isEmpty();
        AhFlipperMod.releaseAhPriorityIfIdle();
        return bids;
    }

    private static void closeAuctionScreens() {
        ScreenUtil.closeAndWait(250);
    }

    private static boolean waitForBidReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
        while (running && System.currentTimeMillis() < deadline) {
            if (AhScreenUtil.findBidButton() >= 0) {
                return true;
            }
            if (AhScreenUtil.currentAuctionUnavailable()) {
                return false;
            }
            ScreenUtil.sleep(50);
        }
        return false;
    }

    private static boolean waitForAuctionView(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
        while (running && System.currentTimeMillis() < deadline) {
            String title = AhScreenUtil.currentScreenTitle().toLowerCase(Locale.ROOT);
            if (title.contains("auction") && !title.contains("house")
                    && !title.contains("browser") && !title.contains("manage")
                    && !title.contains("create") && !title.contains("confirm")) {
                return true;
            }
            if (AhScreenUtil.findBidButton() >= 0) {
                return true;
            }
            ScreenUtil.sleep(50);
        }
        return false;
    }

    private static long nextBid(AhMarketData.AuctionDetails details) {
        return nextBid(details.rawStartingBid, details.highestBidAmount);
    }

    private static long nextBid(long startingBid, long highestBid) {
        long start = Math.max(0, startingBid);
        long high = Math.max(0, highestBid);
        if (high <= 0) {
            return start;
        }
        long next = (long) Math.ceil(high * 1.025);
        return Math.max(high + 1, next);
    }

    private static double profitPercent(long value, long price) {
        if (value <= 0 || price <= 0) {
            return -1;
        }
        double fee = Math.max(0, Math.min(100, AhFlipperMod.CONFIG.ahFeePercent)) / 100.0;
        long profit = Math.round(value * (1.0 - fee)) - price;
        return profit * 100.0 / price;
    }

    private static boolean rebidStillProfitable(AhMarketData.AuctionDetails details,
                                                Tracked tracked) {
        if (tracked == null || tracked.value <= 0) {
            return true;
        }
        long bid = nextBid(details);
        if (bid <= 0) {
            return false;
        }
        if (tracked.value < AhFlipperMod.CONFIG.bidMacroMinValueCoins) {
            return false;
        }
        double target = Math.max(1.0, AhFlipperMod.CONFIG.bidMacroMinProfitPercent);
        double percent = profitPercent(tracked.value, bid);
        if (percent < target) {
            System.out.println("[AH-Flipper] Rebid verworfen: " + tracked.name
                    + " bid=" + ScreenUtil.formatCoins(bid)
                    + " Wert=" + ScreenUtil.formatCoins(tracked.value)
                    + " -> " + String.format(Locale.US, "%.1f%%", percent)
                    + " < " + String.format(Locale.US, "%.1f%%", target));
            return false;
        }
        return true;
    }

    private static boolean recentlyRejected(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long at = RECENT.get(uuid);
        if (at == null) {
            at = RECENT.get(uuidKey(uuid));
        }
        return at != null && now - at <= RECENT_TTL_MS;
    }

    private static void remember(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        RECENT.put(uuid, now);
        RECENT.put(uuidKey(uuid), now);
    }
}


