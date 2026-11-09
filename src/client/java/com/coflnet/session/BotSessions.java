package com.coflnet.session;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.FlipperMod;
import com.coflnet.flipper.trading.Trader;
import com.coflnet.lowballing.LowballingMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session time and breaks for the AH flipper, the bid macro, the Bazaar
 * flipper and the lowballer. A bot runs for its session time; then it is
 * stopped at a safe moment (never in the middle of a purchase or trade).
 * With "use break" it goes to the island (/is), pauses for the break time and
 * starts again with a new session; without, it simply stays stopped.
 */
public final class BotSessions {

    public enum Bot {
        AH("AH-Flipper"), BID("Bid-Macro"), BAZAAR("Bazaar-Flipper"), LOWBALL("Lowballing");

        final String label;

        Bot(String label) {
            this.label = label;
        }
    }

    /** Settings of one bot (CoflSky/sessions.json). */
    public static final class Settings {
        /** 0 = no limit. */
        public int sessionMinutes = 0;
        public boolean useBreak = false;
        public int breakMinutes = 15;
    }

    private enum Phase { IDLE, RUNNING, STOPPING, BREAK }

    private static final class State {
        Phase phase = Phase.IDLE;
        long since;
        long stopRequestedAt;
        long breakUntil;
    }

    /** A bot that is busy may run on this long after its session ended. */
    private static final long MAX_STOP_WAIT_MS = 120_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Bot, Settings> SETTINGS = new EnumMap<>(Bot.class);
    private static final Map<Bot, State> STATES = new EnumMap<>(Bot.class);
    private static boolean initialized;
    private static int tickCounter;
    private static long lastIslandAt;
    /** Stopping the AH flipper also stops the bid macro; it comes back with it. */
    private static boolean bidStoppedWithAh;

    private BotSessions() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        load();
        for (Bot bot : Bot.values()) {
            STATES.put(bot, new State());
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter % 20 == 0) {
                tick(client, System.currentTimeMillis());
            }
        });
    }

    public static Settings settings(Bot bot) {
        return SETTINGS.computeIfAbsent(bot, ignored -> new Settings());
    }

    /** Session start of the bot, 0 if it is not running. Used by the Bazaar session cap. */
    public static long sessionStartedAt(Bot bot) {
        State state = STATES.get(bot);
        return state == null || state.phase == Phase.IDLE || state.phase == Phase.BREAK ? 0 : state.since;
    }

    /** "45 min left" / "break 10 min" / "" for the GUI. */
    public static String status(Bot bot) {
        State state = STATES.get(bot);
        Settings settings = settings(bot);
        if (state == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        return switch (state.phase) {
            case RUNNING -> settings.sessionMinutes <= 0 ? "Session: no limit"
                    : "Session: " + Math.max(0, (state.since + settings.sessionMinutes * 60_000L - now) / 60_000L) + " min left";
            case STOPPING -> "Session over, stopping...";
            case BREAK -> "Break: " + Math.max(0, (state.breakUntil - now + 59_999L) / 60_000L) + " min left";
            default -> "";
        };
    }

    private static void tick(Minecraft client, long now) {
        if (client.player == null || com.coflnet.flipper.ServerRestartHandler.isActive()) {
            return; // the restart handler pauses and resumes the bots itself
        }
        for (Bot bot : Bot.values()) {
            step(client, bot, STATES.get(bot), settings(bot), now);
        }
    }

    private static void step(Minecraft client, Bot bot, State state, Settings settings, long now) {
        boolean running = running(bot);
        switch (state.phase) {
            case IDLE -> {
                if (running) {
                    state.phase = Phase.RUNNING;
                    state.since = now;
                }
            }
            case RUNNING -> {
                if (!running) {
                    state.phase = Phase.IDLE; // stopped by the player
                } else if (settings.sessionMinutes > 0
                        && now - state.since >= settings.sessionMinutes * 60_000L) {
                    state.phase = Phase.STOPPING;
                    state.stopRequestedAt = now;
                    message("§6[" + bot.label + "] §7Session (" + settings.sessionMinutes
                            + " min) vorbei - stoppe beim naechsten sicheren Moment.");
                }
            }
            case STOPPING -> {
                if (!running) {
                    state.phase = Phase.IDLE;
                    return;
                }
                if (busy(bot) && now - state.stopRequestedAt < MAX_STOP_WAIT_MS) {
                    return;
                }
                pause(bot);
                if (settings.useBreak) {
                    goToIsland(client, bot, now);
                    state.phase = Phase.BREAK;
                    state.breakUntil = now + Math.max(1, settings.breakMinutes) * 60_000L;
                    message("§6[" + bot.label + "] §7Pause fuer " + settings.breakMinutes + " min.");
                } else {
                    state.phase = Phase.IDLE;
                    message("§6[" + bot.label + "] §7Session beendet.");
                }
            }
            case BREAK -> {
                if (running) {
                    // started again by hand: that is a new session
                    state.phase = Phase.RUNNING;
                    state.since = now;
                } else if (now >= state.breakUntil) {
                    resume(bot);
                    state.phase = Phase.RUNNING;
                    state.since = now;
                    message("§6[" + bot.label + "] §7Pause vorbei - neue Session.");
                }
            }
        }
    }

    private static boolean running(Bot bot) {
        return switch (bot) {
            case AH -> AhFlipperMod.isRunning();
            case BID -> AhBidMacro.isRunning();
            case BAZAAR -> {
                Trader trader = Trader.get();
                yield trader != null && trader.isRunning() && !trader.isPaused();
            }
            case LOWBALL -> LowballingMod.isRunning();
        };
    }

    /** Not a safe moment: a purchase, bid, listing, Bazaar order or trade is going on. */
    private static boolean busy(Bot bot) {
        return switch (bot) {
            case AH, BID -> AhFlipperMod.automationBusyExcept(null);
            case BAZAAR -> {
                Trader trader = Trader.get();
                yield trader != null && trader.isGuiOwned();
            }
            case LOWBALL -> LowballingMod.holdsGui();
        };
    }

    private static void pause(Bot bot) {
        switch (bot) {
            case AH -> {
                bidStoppedWithAh = AhBidMacro.isRunning();
                AhFlipperMod.stopForRestart();
            }
            case BID -> AhBidMacro.stop();
            case BAZAAR -> FlipperMod.pauseForRestart();
            case LOWBALL -> LowballingMod.stopForRestart();
        }
    }

    private static void resume(Bot bot) {
        switch (bot) {
            case AH -> {
                AhFlipperMod.resumeAfterRestart(true);
                State bid = STATES.get(Bot.BID);
                if (bidStoppedWithAh && (bid == null || bid.phase != Phase.BREAK)) {
                    AhBidMacro.start();
                }
                bidStoppedWithAh = false;
            }
            case BID -> AhBidMacro.start();
            case BAZAAR -> FlipperMod.resumeAfterRestart(true);
            case LOWBALL -> LowballingMod.resumeAfterRestart(true);
        }
    }

    /** Before the break: back to the island (once, even if several bots break together). */
    private static void goToIsland(Minecraft client, Bot bot, long now) {
        if (now - lastIslandAt < 30_000L || client.getConnection() == null) {
            return;
        }
        if (bot != Bot.LOWBALL && LowballingMod.isRunning()) {
            return; // the lowballer still works and decides where it stands
        }
        lastIslandAt = now;
        client.getConnection().sendChat("/is");
    }

    private static void message(String text) {
        AhFlipperMod.message(text);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("CoflSky").resolve("sessions.json");
    }

    private static void load() {
        try {
            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            Map<String, Settings> stored = GSON.fromJson(Files.readString(path),
                    new TypeToken<Map<String, Settings>>() {
                    }.getType());
            if (stored == null) {
                return;
            }
            for (Map.Entry<String, Settings> entry : stored.entrySet()) {
                try {
                    SETTINGS.put(Bot.valueOf(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception e) {
            System.out.println("[Sessions] Einstellungen nicht lesbar: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Map<String, Settings> stored = new LinkedHashMap<>();
            for (Bot bot : Bot.values()) {
                Settings settings = settings(bot);
                settings.sessionMinutes = Math.max(0, Math.min(24 * 60, settings.sessionMinutes));
                settings.breakMinutes = Math.max(1, Math.min(24 * 60, settings.breakMinutes));
                stored.put(bot.name(), settings);
            }
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(stored));
        } catch (Exception e) {
            System.out.println("[Sessions] Einstellungen nicht speicherbar: " + e.getMessage());
        }
    }
}
