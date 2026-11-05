package com.coflnet.ahflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent daily snapshots for Fire Sale skins. This is the local history
 * database that grows over time and is used for backtests and comps.
 */
public final class AhFireSaleHistoryStore {
    public static final class Snapshot {
        public long at;
        public long price;
        public long marketNow;
        public long marketMonthly;
        public double volume;
        public int listings;
        public int monthSales;
        public int weekSales;
    }

    public static final class SkinRecord {
        public String tag = "";
        public String name = "";
        public String tier = "";
        public String type = "";
        public long firstSeenAt;
        public long releaseGuessAt;
        public long firstPrice;
        public List<Snapshot> snapshots = new ArrayList<>();
    }

    private static final class Root {
        Map<String, SkinRecord> skins = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_INTERVAL_MS = 2 * 60_000L;
    private static final int MAX_SNAPSHOTS = 400;
    private static final Object LOCK = new Object();

    private static Root root;
    private static long lastSaveAt;
    private static boolean dirty;

    private AhFireSaleHistoryStore() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("CoflSky").resolve("investment").resolve("skins-history.json");
    }

    private static void ensureLoaded() {
        if (root != null) {
            return;
        }
        Root loaded = null;
        try {
            Path path = file();
            if (Files.exists(path)) {
                loaded = GSON.fromJson(Files.readString(path), Root.class);
            }
        } catch (Exception e) {
            System.out.println("[AH-Invest] Skin-Historie laden fehlgeschlagen: " + e.getMessage());
        }
        if (loaded == null || loaded.skins == null) {
            loaded = new Root();
        }
        root = loaded;
    }

    public static void record(String tag, String name, String tier, String type,
                              long releaseGuessAt, Snapshot snapshot) {
        if (tag == null || tag.isBlank() || snapshot == null || snapshot.at <= 0) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded();
            SkinRecord record = root.skins.computeIfAbsent(tag, ignored -> {
                SkinRecord created = new SkinRecord();
                created.tag = tag;
                created.name = name == null ? "" : name;
                created.tier = tier == null ? "" : tier;
                created.type = type == null ? "" : type;
                created.firstSeenAt = snapshot.at;
                created.releaseGuessAt = releaseGuessAt;
                created.firstPrice = snapshot.price;
                return created;
            });
            if (record.name == null || record.name.isBlank()) {
                record.name = name == null ? "" : name;
            }
            if (record.tier == null || record.tier.isBlank()) {
                record.tier = tier == null ? "" : tier;
            }
            if (record.type == null || record.type.isBlank()) {
                record.type = type == null ? "" : type;
            }
            if (record.releaseGuessAt <= 0) {
                record.releaseGuessAt = releaseGuessAt;
            }
            Snapshot last = record.snapshots.isEmpty() ? null
                    : record.snapshots.get(record.snapshots.size() - 1);
            if (last != null && last.at / 86_400_000L == snapshot.at / 86_400_000L) {
                record.snapshots.set(record.snapshots.size() - 1, snapshot);
            } else {
                record.snapshots.add(snapshot);
                while (record.snapshots.size() > MAX_SNAPSHOTS) {
                    record.snapshots.remove(0);
                }
            }
            dirty = true;
            saveIfDue(false);
        }
    }

    public static SkinRecord get(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        synchronized (LOCK) {
            ensureLoaded();
            return root.skins.get(tag);
        }
    }

    public static List<SkinRecord> records() {
        synchronized (LOCK) {
            ensureLoaded();
            return new ArrayList<>(root.skins.values());
        }
    }

    public static void saveIfDue(boolean force) {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!force && now - lastSaveAt < SAVE_INTERVAL_MS) {
                return;
            }
            saveLocked();
        }
    }

    private static void saveLocked() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            lastSaveAt = System.currentTimeMillis();
            dirty = false;
        } catch (Exception e) {
            System.out.println("[AH-Invest] Skin-Historie speichern fehlgeschlagen: "
                    + e.getMessage());
        }
    }

    public static long textureTimestamp(String base64Value) {
        if (base64Value == null || base64Value.isBlank()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Value);
            JsonObject json = JsonParser.parseString(
                    new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("timestamp") && !json.get("timestamp").isJsonNull()) {
                return json.get("timestamp").getAsLong();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }
}
