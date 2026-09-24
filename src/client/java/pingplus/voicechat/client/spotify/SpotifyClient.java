package pingplus.voicechat.client.spotify;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;

/** Local-only media transport. One hidden helper is owned by the enabled in-world HUD. */
public final class SpotifyClient {
    public static final SpotifyClient INSTANCE = new SpotifyClient();
    public record State(String message, SpotifyPlayback playback) {}
    private volatile State state = new State("Starting local Spotify connection...", null);
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Spotify local media"); t.setDaemon(true); return t;
    });
    private volatile Process process;
    private volatile long lastUpdate;
    private volatile boolean wanted;
    private long retryAt;
    private BufferedWriter commands;
    private SpotifyClient() { worker.scheduleWithFixedDelay(this::maintain, 0, 1, TimeUnit.SECONDS); }
    public State state() { return state; }
    public void active(boolean active) { wanted = active; }
    private void maintain() {
        try {
            if (!wanted) { stop(); return; }
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                state = new State("Local Spotify currently requires Windows 10/11", null); return;
            }
            if (process != null && (!process.isAlive() || System.currentTimeMillis() - lastUpdate > 20_000)) {
                stop(); state = new State("Local Spotify unavailable. Retrying...", null); retryAt = System.currentTimeMillis() + 15_000;
            }
            if (process == null && System.currentTimeMillis() >= retryAt) start();
        } catch (Exception ignored) {
            stop(); state = new State("Cannot access Windows media controls. Retrying...", null);
            retryAt = System.currentTimeMillis() + 30_000;
        }
    }
    private void start() throws IOException {
        String script;
        try (var source = SpotifyClient.class.getResourceAsStream("/spotify/windows-media.ps1")) {
            if (source == null) throw new IOException("Missing media bridge");
            script = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
        String windows = System.getenv("SystemRoot");
        if (windows == null) throw new IOException("Windows directory unavailable");
        String executable = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process launched = new ProcessBuilder(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-EncodedCommand", encoded).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        synchronized (this) {
            if (!wanted || worker.isShutdown()) { launched.destroyForcibly(); return; }
            process = launched;
            commands = new BufferedWriter(new OutputStreamWriter(launched.getOutputStream(), StandardCharsets.UTF_8));
            lastUpdate = System.currentTimeMillis(); state = new State("Finding Spotify on this computer...", null);
        }
        Thread.ofPlatform().daemon().name("Spotify media snapshots").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && process == launched) {
                    if (line.length() > 3_000_000) continue;
                    try {
                        var json = JsonParser.parseString(line).getAsJsonObject();
                        SpotifyPlayback playback = SpotifyPlayback.parse(json, System.nanoTime());
                        State update = new State(SpotifyPlayback.text(json, "message"), playback);
                        synchronized (this) {
                            if (process != launched) return;
                            state = update; lastUpdate = System.currentTimeMillis();
                        }
                    } catch (RuntimeException ignored) { /* Invalid records cannot replace valid snapshots. */ }
                }
            } catch (IOException ignored) { /* The watchdog restarts failed helpers. */ }
        });
    }
    public void command(String action) {
        if (!Set.of("previous", "next", "play", "pause").contains(action)) return;
        worker.execute(() -> {
            var track = state.playback();
            if (!wanted || process == null || track == null) return;
            boolean enabled = switch (action) { case "previous" -> track.previous(); case "next" -> track.next(); default -> track.toggle(); };
            if (!enabled) return;
            BufferedWriter output = commands;
            if (output == null) return;
            try { output.write(action); output.newLine(); output.flush(); }
            catch (IOException ignored) { stop(); }
        });
    }
    private synchronized void stop() {
        Process old = process; process = null;
        if (old != null) {
            try { old.getOutputStream().close(); } catch (IOException ignored) {}
            old.destroy(); if (old.isAlive()) old.destroyForcibly();
        }
        commands = null;
    }
    public void close() { wanted = false; worker.shutdownNow(); stop(); }
}
