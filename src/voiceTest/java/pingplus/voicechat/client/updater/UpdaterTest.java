package pingplus.voicechat.client.updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.Version;
import pingplus.voicechat.updater.UpdateInstaller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** No live GitHub writes or Minecraft installation: all replacements happen in temporary directories. */
public final class UpdaterTest {
    private static final String REPOSITORY = "AeroAlpine/Air-Client";
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("air-client-updater-test-").toRealPath();
        try {
            releaseSelection();
            boundedResponses();
            jarValidation(root);
            installation(root);
            installerProcess(root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: GitHub release selection, compatibility, bounded downloads, checksums, replacement, backup, locking and real process-exit installation");
    }

    private static void releaseSelection() throws Exception {
        var release = release("v0.1.4", "Air-Client-0.1.4.jar");
        check(GitHubReleases.parse(release.toString(), REPOSITORY, "0.1.3").orElseThrow().version().equals("0.1.4"), "New stable release not found");
        check(GitHubReleases.parse(release.toString(), REPOSITORY, "0.1.4").isEmpty(), "Current version must not update");
        check(GitHubReleases.parse(release.toString(), REPOSITORY, "0.1.5").isEmpty(), "Never downgrade");
        check(GitHubReleases.parse(release("v1.10.0", "v1.10.0.jar").toString(), REPOSITORY, "1.9.0").isPresent(), "Version comparison must be numeric");
        check(GitHubReleases.parse(release("v.0.1.4", "v.0.1.4.jar").toString(), REPOSITORY, "0.1.3").isPresent(), "Legacy v. tags must work");
        check(GitHubReleases.parse(release("v0.1.3", "v0.1.3.jar").toString(), REPOSITORY, "0.1.2").isPresent(), "Current GitHub asset convention must work");
        release.addProperty("prerelease", true);
        check(GitHubReleases.parse(release.toString(), REPOSITORY, "0.1.3").isEmpty(), "Skip prereleases");
        release = release("v0.1.4-beta.1", "v0.1.4-beta.1.jar");
        check(GitHubReleases.parse(release.toString(), REPOSITORY, "0.1.3").isEmpty(), "Skip prerelease tags even if mislabelled");
        release = release("v0.1.4", "Air-Client-0.1.4-sources.jar");
        var sourceArchive = release;
        expectFailure(() -> GitHubReleases.parse(sourceArchive.toString(), REPOSITORY, "0.1.3"));
        release = release("v0.1.4", "Air-Client-0.1.4.jar");
        release.getAsJsonArray("assets").get(0).getAsJsonObject().remove("digest");
        var missingDigest = release;
        expectFailure(() -> GitHubReleases.parse(missingDigest.toString(), REPOSITORY, "0.1.3"));
        release = release("v0.1.4", "Air-Client-0.1.4.jar");
        release.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("browser_download_url", "https://github.com/other/repo/releases/download/v0.1.4/Air-Client-0.1.4.jar");
        var wrongRepo = release;
        expectFailure(() -> GitHubReleases.parse(wrongRepo.toString(), REPOSITORY, "0.1.3"));
        release = release("v0.1.4", "Air-Client-0.1.4.jar");
        release.getAsJsonArray("assets").add(release.getAsJsonArray("assets").get(0).deepCopy());
        var ambiguous = release;
        expectFailure(() -> GitHubReleases.parse(ambiguous.toString(), REPOSITORY, "0.1.3"));
        for (String url : List.of("http://github.com/a.jar", "https://evil.example/a.jar", "https://github.com@evil.example/a.jar", "https://github.com:8080/a.jar"))
            expectFailure(() -> GitHubReleases.requireDownloadUrl(URI.create(url)));
        GitHubReleases.requireDownloadUrl(URI.create("https://release-assets.githubusercontent.com/asset"));
    }

    private static JsonObject release(String tag, String assetName) {
        var release = new JsonObject();
        release.addProperty("draft", false); release.addProperty("prerelease", false); release.addProperty("tag_name", tag);
        var asset = new JsonObject();
        asset.addProperty("name", assetName); asset.addProperty("size", 1024);
        asset.addProperty("digest", "sha256:" + "a".repeat(64));
        asset.addProperty("browser_download_url", "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + assetName);
        var assets = new JsonArray(); assets.add(asset); release.add("assets", assets);
        return release;
    }

    private static void boundedResponses() throws Exception {
        boolean[] cancelled = {false};
        var receiver = new GitHubReleases.BoundedSubscriber<>(HttpResponse.BodySubscribers.ofByteArray(), 3, n -> { });
        receiver.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { cancelled[0] = true; }
        });
        receiver.onNext(List.of(ByteBuffer.wrap(new byte[4])));
        check(cancelled[0], "Oversized responses must cancel the HTTP subscription");
        expectFailure(() -> receiver.getBody().toCompletableFuture().get());
    }

    private static void jarValidation(Path root) throws Exception {
        var installed = Map.of("minecraft", Version.parse("26.2"), "java", Version.parse("25"),
                "fabricloader", Version.parse("0.19.5"), "fabric-api", Version.parse("0.160.0+26.2"));
        Path jar = root.resolve("valid.jar");
        writeJar(jar, "voicechat", "0.1.4", "26.2");
        var asset = asset(jar);
        UpdateValidation.validate(jar, asset, installed);
        var badHash = new GitHubReleases.Release("0.1.4", asset.download(), asset.size(), "b".repeat(64));
        expectFailure(() -> UpdateValidation.validate(jar, badHash, installed));
        var badVersion = new GitHubReleases.Release("0.1.5", asset.download(), asset.size(), asset.sha256());
        expectFailure(() -> UpdateValidation.validate(jar, badVersion, installed));
        writeJar(jar, "voicechat", "0.1.4", "26.3");
        expectFailure(() -> UpdateValidation.validate(jar, asset(jar), installed));
        writeJar(jar, "other_mod", "0.1.4", "26.2");
        expectFailure(() -> UpdateValidation.validate(jar, asset(jar), installed));
        writeJar(jar, "voicechat", "0.1.4", "26.2");
        expectFailure(() -> UpdateValidation.validate(jar, asset(jar), Map.of("minecraft", Version.parse("26.2"))));
        Files.writeString(jar, "not a jar");
        expectFailure(() -> UpdateValidation.validate(jar, asset(jar), installed));
    }

    private static GitHubReleases.Release asset(Path jar) throws IOException {
        return new GitHubReleases.Release("0.1.4", URI.create("https://github.com/example"), Files.size(jar), UpdateValidation.sha256(jar));
    }

    private static void writeJar(Path path, String id, String version, String minecraft) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(("{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"depends\":{\"minecraft\":\"" + minecraft
                    + "\",\"java\":\">=25\",\"fabricloader\":\">=0.19.5\",\"fabric-api\":\"*\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void installation(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("installation"));
        Path target = dir.resolve("Air-Client-0.1.3.jar");
        Path staged = dir.resolve("Air-Client-0.1.3.jar.pending");
        Files.writeString(target, "old"); Files.writeString(staged, "new");
        var plan = plan(target, staged);
        Files.writeString(staged, "corrupted");
        expectFailure(() -> UpdateInstaller.install(plan));
        check(Files.readString(target).equals("old"), "Failed integrity must preserve installed JAR");
        Files.writeString(staged, "new");
        Files.writeString(target, "externally changed");
        expectFailure(() -> UpdateInstaller.install(plan));
        check(Files.readString(target).equals("externally changed"), "Never overwrite an externally updated JAR");
        Files.writeString(target, "old");
        try (var channel = FileChannel.open(dir.resolve(".air-client-update.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            expectFailure(() -> UpdateInstaller.install(plan));
        }
        Path backup = dir.resolve("Air-Client-0.1.3.jar.backup");
        Files.createDirectory(backup);
        expectFailure(() -> UpdateInstaller.install(plan));
        check(Files.readString(target).equals("old"), "Backup failure must preserve installed JAR");
        Files.delete(backup);
        UpdateInstaller.install(plan);
        check(Files.readString(target).equals("new") && Files.readString(backup).equals("old") && !Files.exists(staged), "Replacement and backup failed");
        Files.createSymbolicLink(staged, target);
        expectFailure(() -> UpdateInstaller.install(plan));
        Files.delete(staged);
        check(Files.readString(target).equals("new"), "Symbolic links must never be followed");
    }

    private static Properties plan(Path target, Path staged) throws IOException {
        var plan = new Properties();
        plan.setProperty("target", target.toString()); plan.setProperty("staged", staged.toString());
        plan.setProperty("oldSha256", UpdateValidation.sha256(target)); plan.setProperty("newSha256", UpdateValidation.sha256(staged));
        plan.setProperty("version", "0.1.4");
        return plan;
    }

    private static void installerProcess(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("process with spaces"));
        Path target = dir.resolve("Air-Client-0.1.3.jar"), staged = dir.resolve("Air-Client-0.1.3.jar.pending");
        Files.writeString(target, "old"); Files.writeString(staged, "new");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var parent = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"), Sleeper.class.getName()).start();
        Process helper = null;
        try {
            var plan = plan(target, staged);
            plan.setProperty("parentPid", Long.toString(parent.pid()));
            plan.setProperty("parentStarted", parent.info().startInstant().orElseThrow().toString());
            Path planFile = dir.resolve("plan.properties");
            try (var out = Files.newOutputStream(planFile)) { plan.store(out, "test"); }
            Path installer = dir.resolve("installer.jar");
            try (var in = UpdaterTest.class.getResourceAsStream("/airclient/air-client-updater.jar")) {
                check(in != null, "Installer is missing from processed resources");
                Files.copy(in, installer);
            }
            helper = new ProcessBuilder(javaExecutable.toString(), "-jar", installer.toString(), planFile.toString())
                    .redirectErrorStream(true).redirectOutput(dir.resolve("helper.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(dir.resolve("ready"))) {
                check(helper.isAlive() && System.nanoTime() < deadline, "Helper did not become ready: " + Files.readString(dir.resolve("helper.log")));
                Thread.sleep(30);
            }
            check(Files.readString(target).equals("old"), "Helper must not modify the JAR while Minecraft runs");
            parent.destroyForcibly(); parent.waitFor(5, TimeUnit.SECONDS);
            check(helper.waitFor(10, TimeUnit.SECONDS) && helper.exitValue() == 0,
                    "Installer process failed: " + Files.readString(dir.resolve("helper.log")));
            check(Files.readString(target).equals("new") && Files.readString(dir.resolve("result.txt")).contains("Installed"), "Helper did not install after parent exit");
        } finally {
            parent.destroyForcibly(); parent.waitFor(5, TimeUnit.SECONDS);
            if (helper != null) { helper.destroyForcibly(); helper.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception { Thread.sleep(30000); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void expectFailure(Action action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected operation to fail");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
