package pingplus.voicechat.client.gui.hud;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Persistent viewport-relative anchors and proportional scale, independent of Minecraft rendering. */
public final class HudLayout {
    public record Position(double x, double y, double scale) {}
    public record Bounds(double x, double y, double width, double height, double scale) {
        public double right() { return x + width; }
        public double bottom() { return y + height; }
        public boolean contains(double px, double py) { return px >= x && px <= right() && py >= y && py <= bottom(); }
        public double localX(double px) { return (px - x) / scale; }
        public double localY(double py) { return (py - y) / scale; }
    }
    private final Path file;
    private final Map<String, Position> positions = new LinkedHashMap<>();
    public HudLayout(Path file) {
        this.file = file;
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file)) { p.load(reader); }
        catch (IOException | IllegalArgumentException ignored) { return; }
        for (String key : p.stringPropertyNames()) if (key.endsWith(".scale")) {
            String id = key.substring(0, key.length() - 6);
            try {
                double x = Double.parseDouble(p.getProperty(id + ".x")), y = Double.parseDouble(p.getProperty(id + ".y"));
                double scale = Double.parseDouble(p.getProperty(key));
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(scale))
                    positions.put(id, new Position(Math.clamp(x, 0, 1), Math.clamp(y, 0, 1), Math.clamp(scale, .5, 3)));
            } catch (RuntimeException ignored) { /* One bad entry must not discard the other widgets. */ }
        }
    }
    public Bounds bounds(String id, int sw, int sh, int width, int height, double defaultX, double defaultY) {
        return bounds(id, sw, sh, width, height, defaultX, defaultY, false);
    }
    public Bounds bounds(String id, int sw, int sh, int width, int height, double defaultX, double defaultY, boolean growDown) {
        Position p = positions.get(id);
        int layoutH = growDown ? Math.min(height, 18) : height;
        double scale = fitted(p == null ? 1 : p.scale(), sw, sh, width, layoutH);
        double w = width * scale, h = height * scale;
        double roomX = Math.max(0, sw - 16 - w), roomY = Math.max(0, sh - 40 - layoutH * scale);
        double x = p == null ? Math.clamp(defaultX, 8, 8 + roomX) : 8 + p.x() * roomX;
        double y = p == null ? Math.clamp(defaultY, 8, 8 + roomY) : 8 + p.y() * roomY;
        return new Bounds(x, y, w, h, scale);
    }
    public void place(String id, double x, double y, double scale, int sw, int sh, int width, int height) {
        place(id, x, y, scale, sw, sh, width, height, false);
    }
    public void place(String id, double x, double y, double scale, int sw, int sh, int width, int height, boolean growDown) {
        double requested = Double.isFinite(scale) ? Math.clamp(scale, .5, 3) : 1;
        int layoutH = growDown ? Math.min(height, 18) : height;
        double fit = fitted(requested, sw, sh, width, layoutH);
        positions.put(id, new Position(fraction(x - 8, sw - 16 - width * fit),
                fraction(y - 8, sh - 40 - layoutH * fit), requested));
    }
    private static double fraction(double offset, double available) {
        return Double.isFinite(offset) && available > 0 ? Math.clamp(offset / available, 0, 1) : 0;
    }
    private static double fitted(double scale, int sw, int sh, int w, int h) {
        return Math.max(.05, Math.min(scale, Math.min(Math.max(1, sw - 16) / (double)w, Math.max(1, sh - 40) / (double)h)));
    }
    public void reset(String id) { positions.remove(id); }
    public Position position(String id) { return positions.get(id); }
    public void save() throws IOException {
        Properties p = new Properties();
        positions.forEach((id, value) -> {
            p.setProperty(id + ".x", Double.toString(value.x())); p.setProperty(id + ".y", Double.toString(value.y()));
            p.setProperty(id + ".scale", Double.toString(value.scale()));
        });
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temporary)) { p.store(writer, "HUD anchors and scale; edit in chat"); }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
