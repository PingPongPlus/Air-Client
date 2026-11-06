package pingplus.voicechat.client;

import pingplus.voicechat.client.profile.ProfileSettings;
import pingplus.voicechat.client.profile.ProfileStore;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/** Filesystem persistence and all-or-nothing validation without starting Minecraft. */
public final class ProfileTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("voicechat-profile-test-");
        try {
            persistence(directory);
            validation();
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("PASS: profile CRUD, restart persistence, duplicate names, write failures and complete snapshot validation");
    }
    private static void persistence(Path directory) throws Exception {
        Path file = directory.resolve("profiles.properties");
        var store = new ProfileStore(file);
        check(store.profiles().isEmpty() && store.activeId().isEmpty(), "First run must preserve existing settings");
        Properties settings = new Properties(); settings.setProperty("player.zoom", "false");
        var first = store.create("  Survival  ", settings);
        check(first.name().equals("Survival") && store.activeId().equals(first.id()), "Create must trim names and activate the snapshot");
        settings.setProperty("player.zoom", "true");
        check(store.load(first.id()).getProperty("player.zoom").equals("false"), "Snapshots must not retain callers' mutable data");
        expect(IllegalArgumentException.class, () -> store.create("survival", settings));
        expect(IllegalArgumentException.class, () -> store.create(" ", settings));
        expect(IllegalArgumentException.class, () -> store.create("x".repeat(33), settings));
        expect(IllegalArgumentException.class, () -> store.create("a\nb", settings));
        var second = store.create("../Creative", settings);
        try (var files = Files.list(directory)) {
            check(files.count() == 1, "Names must never become filesystem paths");
        }
        expect(IllegalArgumentException.class, () -> store.rename(first.id(), "../creative"));
        store.rename(second.id(), "Creative");
        store.activate(first.id());
        var restarted = new ProfileStore(file);
        check(restarted.profiles().size() == 2 && restarted.activeId().equals(first.id()), "Profiles and active selection must survive restart");
        check(restarted.activeName().equals("Survival"), "Active profile name must resolve");
        Properties loaded = store.load(first.id()); loaded.clear();
        check(!store.load(first.id()).isEmpty(), "Loading must return a detached snapshot");
        store.save(first.id(), settings);
        check(new ProfileStore(file).load(first.id()).getProperty("player.zoom").equals("true"), "Save must replace the selected snapshot");
        store.delete(second.id());
        check(store.activeId().equals(first.id()), "Deleting another profile must preserve the active selection");
        store.delete(first.id());
        check(new ProfileStore(file).profiles().isEmpty() && new ProfileStore(file).activeId().isEmpty(), "Deleting the active profile must clear its startup marker");
        ProfileStore empty = store;
        expect(IllegalArgumentException.class, () -> empty.load(first.id()));

        Files.writeString(file, "version=99\n");
        expect(IOException.class, () -> new ProfileStore(file));
        check(Files.readString(file).contains("99"), "Unsupported files must remain untouched");
        Files.writeString(file, "version=1\nbroken=\\uZZZZ\n");
        expect(IOException.class, () -> new ProfileStore(file));

        Path blocker = directory.resolve("blocker");
        var failed = new ProfileStore(blocker.resolve("profiles.properties"));
        Files.writeString(blocker, "not a directory");
        expect(IOException.class, () -> failed.create("Unsaved", settings));
        check(failed.profiles().isEmpty() && failed.activeId().isEmpty(), "Failed writes must not change in-memory state");
    }
    private static void validation() {
        var bindings = new ProfileSettings();
        boolean[] toggle = {true}; int[] count = {3}; float[] scale = {1};
        bindings.bool("toggle", () -> toggle[0], () -> toggle[0] = !toggle[0]);
        bindings.integer("count", () -> count[0], value -> count[0] = value, 1, 8);
        bindings.decimal("scale", () -> scale[0], value -> scale[0] = value, .5f, 3);
        Properties snapshot = bindings.capture();
        snapshot.setProperty("toggle", "false"); snapshot.setProperty("count", "999");
        expect(IllegalArgumentException.class, () -> bindings.prepare(snapshot));
        check(toggle[0] && count[0] == 3, "An invalid later setting must leave every live value unchanged");
        snapshot.setProperty("count", "5"); snapshot.setProperty("scale", "NaN");
        expect(IllegalArgumentException.class, () -> bindings.prepare(snapshot));
        snapshot.setProperty("scale", "Infinity");
        expect(IllegalArgumentException.class, () -> bindings.prepare(snapshot));
        snapshot.setProperty("scale", "2"); snapshot.setProperty("toggle", "typo");
        expect(IllegalArgumentException.class, () -> bindings.prepare(snapshot));
        snapshot.setProperty("toggle", "false");
        Runnable apply = bindings.prepare(snapshot);
        check(toggle[0] && count[0] == 3, "Preparing a valid profile must not mutate settings");
        apply.run();
        check(!toggle[0] && count[0] == 5 && scale[0] == 2, "A valid profile must apply all bindings");
        snapshot.clear(); snapshot.setProperty("future.setting", "ignored");
        bindings.prepare(snapshot).run();
        check(count[0] == 5 && scale[0] == 2, "Missing and unknown fields must be compatible across versions");
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Action action) {
        try { action.run(); }
        catch (Exception e) { if (type.isInstance(e)) return; throw new AssertionError("Unexpected exception", e); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
