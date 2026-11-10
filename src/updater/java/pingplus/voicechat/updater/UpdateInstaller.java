package pingplus.voicechat.updater;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Runs from an extracted, JDK-only JAR so the installed mod is never held open by this process. */
public final class UpdateInstaller {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected an update plan file");
        Path planFile = Path.of(args[0]).toAbsolutePath().normalize();
        try {
            Properties plan = new Properties();
            try (var in = Files.newInputStream(planFile)) { plan.load(in); }
            validatePaths(plan);
            long pid = Long.parseLong(plan.getProperty("parentPid"));
            Instant started = Instant.parse(plan.getProperty("parentStarted"));
            var parent = ProcessHandle.of(pid);
            if (parent.isPresent() && !parent.get().info().startInstant().filter(started::equals).isPresent())
                throw new IOException("The Minecraft process identity changed; update cancelled.");
            Files.writeString(planFile.resolveSibling("ready"), "ready");
            if (parent.isPresent()) parent.get().onExit().get(24, TimeUnit.HOURS);
            install(plan);
            Files.writeString(planFile.resolveSibling("result.txt"), "Installed " + plan.getProperty("version"));
        } catch (Exception e) {
            e.printStackTrace();
            try { Files.writeString(planFile.resolveSibling("result.txt"), "Update failed: " + e.getMessage()); }
            catch (IOException ignored) { }
            System.exit(1);
        }
    }

    /** Install only after the parent JVM exits. Public for filesystem/subprocess tests. */
    public static void install(Properties plan) throws IOException, InterruptedException {
        validatePaths(plan);
        Path target = Path.of(plan.getProperty("target"));
        Path staged = Path.of(plan.getProperty("staged"));
        Path backup = target.resolveSibling(target.getFileName() + ".backup");
        Path lockPath = target.resolveSibling(".air-client-update.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("The updater lock must not be a symbolic link.");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another update is using this instance.");
            String previousHash = plan.getProperty("oldSha256");
            String nextHash = plan.getProperty("newSha256");
            if (!previousHash.matches("[0-9a-f]{64}") || !nextHash.matches("[0-9a-f]{64}"))
                throw new IOException("Invalid update checksums.");
            if (!sha256(target).equals(previousHash))
                throw new IOException("The installed JAR changed after downloading; update cancelled.");
            if (!sha256(staged).equals(nextHash))
                throw new IOException("The downloaded JAR changed; update cancelled.");
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && !regular(backup))
                throw new IOException("The update backup is not a regular file.");
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(backup).equals(previousHash)) throw new IOException("Could not verify the update backup.");
            IOException last = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    try { Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException e) {
                        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (!sha256(target).equals(nextHash)) throw new IOException("The installed update failed verification.");
                    return;
                } catch (IOException e) {
                    last = e;
                    // A failed non-atomic move can leave an incomplete target. Restore before retrying.
                    if (!regular(target) || !sha256(target).equals(previousHash)) {
                        try { Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING); }
                        catch (IOException restore) { e.addSuppressed(restore); throw e; }
                    }
                    if (!regular(staged)) break;
                    Thread.sleep(250);
                }
            }
            throw new IOException("Could not replace the client JAR. The previous version was preserved.", last);
        }
    }

    private static void validatePaths(Properties plan) throws IOException {
        Path target = Path.of(plan.getProperty("target")).toAbsolutePath().normalize();
        Path staged = Path.of(plan.getProperty("staged")).toAbsolutePath().normalize();
        if (!target.toString().equals(plan.getProperty("target")) || !staged.toString().equals(plan.getProperty("staged"))
                || !target.getFileName().toString().endsWith(".jar") || !target.getParent().equals(staged.getParent())
                || !staged.getFileName().toString().equals(target.getFileName() + ".pending")
                || !regular(target) || !regular(staged)
                || !target.getParent().equals(target.getParent().toRealPath()))
            throw new IOException("Invalid updater paths. The installed JAR was left untouched.");
    }

    private static boolean regular(Path path) { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private UpdateInstaller() { }
}
