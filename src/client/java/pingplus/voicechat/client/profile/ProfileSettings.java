package pingplus.voicechat.client.profile;

import java.util.*;
import java.util.function.*;

/** Explicit setting bindings validate the entire snapshot before any live value changes. */
public final class ProfileSettings {
    private record Setting(Supplier<String> read, Function<String, Runnable> prepare) {}
    private final Map<String, Setting> settings = new LinkedHashMap<>();

    public <T> void bind(String key, Supplier<T> read, Consumer<T> write, Function<String, T> parse) {
        bind(key, read, write, parse, Object::toString);
    }
    public <T> void bind(String key, Supplier<T> read, Consumer<T> write, Function<String, T> parse, Function<T, String> format) {
        if (settings.putIfAbsent(key, new Setting(() -> format.apply(read.get()), value -> {
            T parsed = parse.apply(value);
            return () -> write.accept(parsed);
        })) != null) throw new IllegalArgumentException("Duplicate setting: " + key);
    }
    public void bool(String key, BooleanSupplier read, Runnable toggle) {
        bind(key, read::getAsBoolean, value -> { if (read.getAsBoolean() != value) toggle.run(); }, ProfileSettings::parseBoolean);
    }
    public void integer(String key, IntSupplier read, IntConsumer write, int min, int max) {
        bind(key, read::getAsInt, write::accept, text -> {
            int value = Integer.parseInt(text);
            if (value < min || value > max) throw new IllegalArgumentException("Out of range");
            return value;
        });
    }
    public void decimal(String key, Supplier<Float> read, Consumer<Float> write, float min, float max) {
        bind(key, read, write, text -> {
            float value = Float.parseFloat(text);
            if (!Float.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Out of range");
            return value;
        });
    }
    public Properties capture() {
        Properties snapshot = new Properties();
        settings.forEach((key, setting) -> snapshot.setProperty(key, setting.read().get()));
        return snapshot;
    }
    public Runnable prepare(Properties snapshot) {
        List<Runnable> changes = new ArrayList<>();
        settings.forEach((key, setting) -> {
            if (!snapshot.containsKey(key)) return; // Older profiles keep defaults for newly added settings.
            try { changes.add(setting.prepare().apply(snapshot.getProperty(key))); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid profile setting: " + key, e); }
        });
        return () -> changes.forEach(Runnable::run);
    }
    public static boolean parseBoolean(String text) {
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) throw new IllegalArgumentException("Invalid boolean");
        return Boolean.parseBoolean(text);
    }
}
