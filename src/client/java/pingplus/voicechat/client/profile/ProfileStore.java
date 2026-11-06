package pingplus.voicechat.client.profile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Named snapshots in a single atomically replaced file. Names never become file paths. */
public final class ProfileStore {
    public static final int MAX_NAME_LENGTH = 32;
    public record Profile(String id, String name) {}
    private final Path file;
    private Properties values = new Properties();

    public ProfileStore(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file)) { values.load(reader); }
        catch (IllegalArgumentException e) { throw new IOException("Profiles file is malformed", e); }
        if (!"1".equals(values.getProperty("version"))) throw new IOException("Unsupported profiles file version");
    }

    public List<Profile> profiles() {
        return values.stringPropertyNames().stream().filter(key -> key.startsWith("profile.") && key.endsWith(".name"))
                .map(key -> new Profile(key.substring(8, key.length() - 5), values.getProperty(key)))
                .sorted(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Profile::id)).toList();
    }

    public String activeId() { return values.getProperty("active", ""); }
    public String activeName() {
        return profiles().stream().filter(p -> p.id().equals(activeId())).map(Profile::name).findFirst().orElse("None");
    }

    public Profile create(String name, Properties settings) throws IOException {
        String checked = checkName(name, "");
        Profile profile = new Profile(UUID.randomUUID().toString(), checked);
        Properties next = copy(values);
        next.setProperty(prefix(profile.id()) + "name", checked);
        putSettings(next, profile.id(), settings);
        next.setProperty("active", profile.id());
        commit(next);
        return profile;
    }

    public void save(String id, Properties settings) throws IOException {
        require(id);
        Properties next = copy(values);
        putSettings(next, id, settings);
        next.setProperty("active", id);
        commit(next);
    }

    public Properties load(String id) {
        require(id);
        Properties result = new Properties();
        String prefix = prefix(id) + "setting.";
        for (String key : values.stringPropertyNames()) if (key.startsWith(prefix))
            result.setProperty(key.substring(prefix.length()), values.getProperty(key));
        return result;
    }

    public void activate(String id) throws IOException {
        require(id);
        if (id.equals(activeId())) return;
        Properties next = copy(values);
        next.setProperty("active", id);
        commit(next);
    }

    public void rename(String id, String name) throws IOException {
        require(id);
        String checked = checkName(name, id);
        Properties next = copy(values);
        next.setProperty(prefix(id) + "name", checked);
        commit(next);
    }

    public void delete(String id) throws IOException {
        require(id);
        Properties next = copy(values);
        next.keySet().removeIf(key -> key.toString().startsWith(prefix(id)));
        if (id.equals(activeId())) next.remove("active");
        commit(next);
    }

    private String checkName(String name, String exceptId) {
        String checked = name == null ? "" : name.strip();
        if (checked.isEmpty() || checked.length() > MAX_NAME_LENGTH || checked.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a name with 1–32 characters and no control characters");
        if (profiles().stream().anyMatch(p -> !p.id().equals(exceptId) && p.name().equalsIgnoreCase(checked)))
            throw new IllegalArgumentException("A profile with that name already exists");
        return checked;
    }

    private void require(String id) {
        if (id == null || !values.containsKey(prefix(id) + "name")) throw new IllegalArgumentException("Profile no longer exists");
    }
    private static String prefix(String id) { return "profile." + id + "."; }
    private static Properties copy(Properties source) { Properties next = new Properties(); next.putAll(source); return next; }
    private static void putSettings(Properties next, String id, Properties settings) {
        String prefix = prefix(id) + "setting.";
        next.keySet().removeIf(key -> key.toString().startsWith(prefix));
        for (String key : settings.stringPropertyNames()) next.setProperty(prefix + key, settings.getProperty(key));
    }
    private void commit(Properties next) throws IOException {
        next.setProperty("version", "1");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "voicechat-profiles-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { next.store(writer, "Client settings profiles"); }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            values = next;
        } finally { Files.deleteIfExists(temporary); }
    }
}
