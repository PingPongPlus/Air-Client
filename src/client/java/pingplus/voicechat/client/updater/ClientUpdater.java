package pingplus.voicechat.client.updater;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Network/file work stays off the client thread; screens read an immutable volatile snapshot. */
public final class ClientUpdater {
    private static final Logger LOG = LoggerFactory.getLogger("air-client-updater");
    private static final ClientUpdater INSTANCE = new ClientUpdater();
    public enum State { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
    public record Snapshot(State state, String message, int progress, GitHubReleases.Release release) {
        public boolean showButton() { return release != null; }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Air Client updater"); thread.setDaemon(true); return thread;
    });
    private final String version;
    private final String repository;
    private final GitHubReleases releases;
    private final Map<String, Version> installed;
    private final Path target;
    private final String installUnavailable;
    private volatile Snapshot snapshot = new Snapshot(State.IDLE, "Checking for updates...", 0, null);
    // Held until process exit after successful staging; the helper acquires the same lock afterwards.
    private FileChannel lockChannel;
    private FileLock updateLock;

    private ClientUpdater() {
        var loader = FabricLoader.getInstance();
        var mod = loader.getModContainer("voicechat").orElseThrow();
        version = mod.getMetadata().getVersion().getFriendlyString();
        repository = mod.getMetadata().getCustomValue("airclient:updateRepository").getAsString();
        releases = new GitHubReleases(repository);
        var versions = new HashMap<String, Version>();
        loader.getAllMods().forEach(item -> versions.put(item.getMetadata().getId(), item.getMetadata().getVersion()));
        installed = Map.copyOf(versions);
        Path found = null;
        String unavailable = "";
        try {
            if (loader.isDevelopmentEnvironment()) throw new IOException("Updates cannot be installed in a development run.");
            var origin = mod.getOrigin();
            if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1)
                throw new IOException("Install Air Client as a standalone JAR in this instance's mods folder to update it.");
            Path originPath = origin.getPaths().getFirst().toAbsolutePath().normalize();
            if (!Files.isRegularFile(originPath, LinkOption.NOFOLLOW_LINKS)
                    || !originPath.getFileName().toString().endsWith(".jar"))
                throw new IOException("The installed client is not a regular mod JAR.");
            Path real = originPath.toRealPath();
            Path mods = loader.getGameDir().resolve("mods").toRealPath();
            if (!real.getParent().equals(mods)) throw new IOException("The client JAR is outside this instance's mods folder.");
            found = real;
        } catch (IOException e) { unavailable = e.getMessage(); }
        target = found;
        installUnavailable = unavailable;
    }

    public static ClientUpdater get() { return INSTANCE; }
    public String version() { return version; }
    public String repository() { return repository; }
    public Snapshot snapshot() { return snapshot; }
    public boolean canInstall() { return target != null; }
    public String installUnavailable() { return installUnavailable; }

    public synchronized void check() {
        if (snapshot.state() == State.CHECKING || snapshot.state() == State.DOWNLOADING || snapshot.state() == State.READY) return;
        if (Boolean.getBoolean("airclient.updater.disable")) return;
        snapshot = new Snapshot(State.CHECKING, "Checking for updates...", 0, null);
        worker.execute(() -> {
            try {
                var latest = releases.latest(version);
                snapshot = latest.map(release -> new Snapshot(State.AVAILABLE, "Air Client " + release.version() + " is available.", 0, release))
                        .orElseGet(() -> new Snapshot(State.CURRENT, "You are up to date.", 0, null));
            } catch (Exception e) { fail("Could not check for updates. Try again later.", e, null); }
        });
    }

    public synchronized void download() {
        var release = snapshot.release();
        if (!canInstall() || release == null || (snapshot.state() != State.AVAILABLE && snapshot.state() != State.ERROR)) return;
        snapshot = new Snapshot(State.DOWNLOADING, "Downloading update...", 0, release);
        worker.execute(() -> stage(release));
    }

    private void stage(GitHubReleases.Release release) {
        Path staged = target.resolveSibling(target.getFileName() + ".pending");
        Process helper = null;
        boolean createdStaging = false;
        try {
            Path lockPath = target.resolveSibling(".air-client-update.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("The updater lock is not a regular file.");
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            updateLock = lockChannel.tryLock();
            if (updateLock == null) throw new IOException("Another client is already updating this instance.");
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("A pending update already exists. Restart Minecraft or remove " + staged.getFileName() + " before retrying.");
            String oldHash = UpdateValidation.sha256(target);
            Files.createFile(staged);
            createdStaging = true;
            releases.download(release, staged, percent -> snapshot = new Snapshot(State.DOWNLOADING,
                    "Downloading update... " + percent + "%", percent, release));
            snapshot = new Snapshot(State.DOWNLOADING, "Verifying update...", 100, release);
            UpdateValidation.validate(staged, release, installed);
            Path stateDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolve(".air-client-updater");
            Files.createDirectories(stateDir);
            Path job = Files.createTempDirectory(stateDir, "update-");
            Path installer = job.resolve("installer.jar");
            try (var in = ClientUpdater.class.getResourceAsStream("/airclient/air-client-updater.jar")) {
                if (in == null) throw new IOException("The build is missing its update installer. Please install a release JAR.");
                Files.copy(in, installer, StandardCopyOption.REPLACE_EXISTING);
            }
            var plan = new Properties();
            plan.setProperty("target", target.toString());
            plan.setProperty("staged", staged.toString());
            plan.setProperty("oldSha256", oldHash);
            plan.setProperty("newSha256", release.sha256());
            plan.setProperty("version", release.version());
            plan.setProperty("parentPid", Long.toString(ProcessHandle.current().pid()));
            plan.setProperty("parentStarted", ProcessHandle.current().info().startInstant()
                    .orElseThrow(() -> new IOException("Could not identify the Minecraft process.")).toString());
            Path planFile = job.resolve("plan.properties");
            try (var out = Files.newOutputStream(planFile)) { plan.store(out, "Air Client update"); }
            Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            helper = new ProcessBuilder(javaExecutable.toString(), "-jar", installer.toString(), planFile.toString())
                    .redirectErrorStream(true).redirectOutput(job.resolve("installer.log").toFile()).start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(job.resolve("ready"))) {
                if (!helper.isAlive() || System.nanoTime() >= deadline)
                    throw new IOException("Could not start the update installer. See " + job.resolve("installer.log"));
                Thread.sleep(50);
            }
            snapshot = new Snapshot(State.READY, "Update downloaded. Restart Minecraft to apply it.", 100, release);
        } catch (Exception e) {
            if (helper != null) {
                helper.destroyForcibly();
                try { helper.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            // Only delete a file this attempt created; never follow or remove somebody else's pending file.
            if (createdStaging)
                try { Files.deleteIfExists(staged); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            releaseLock();
            fail(e.getMessage() == null ? "Could not download the update. Please retry." : e.getMessage(), e, release);
        }
    }

    private void releaseLock() {
        try { if (updateLock != null) updateLock.close(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
        updateLock = null; lockChannel = null;
    }

    private void fail(String message, Exception error, GitHubReleases.Release release) {
        LOG.warn(message, error);
        snapshot = new Snapshot(State.ERROR, message, 0, release);
    }
}
