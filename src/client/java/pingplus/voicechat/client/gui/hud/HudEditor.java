package pingplus.voicechat.client.gui.hud;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.InputWithModifiers;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Shared draw transforms and hit testing for every mod HUD widget. Chat owns edit input. */
public final class HudEditor {
    @FunctionalInterface public interface Renderer { void draw(GuiGraphicsExtractor g, int mx, int my, float dt, boolean editing); }
    public record Entry(String id, String label, int width, int height, IntBinaryOperator defaultX,
                        IntBinaryOperator defaultY, BooleanSupplier visible, Renderer renderer, Supplier<List<Button>> controls) {}
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<Button, ControlProxy> PROXIES = new IdentityHashMap<>();
    private static final HudLayout LAYOUT = new HudLayout(FabricLoader.getInstance().getConfigDir().resolve("voicechat-hud-layout.properties"));
    private static String selected;
    private static boolean resizing, dirty;
    private static HudLayout.Bounds initial;
    private static double startX, startY;
    private static int screenWidth, screenHeight;
    private HudEditor() {}
    public static void register(Entry entry) { ENTRIES.put(entry.id(), entry); }
    public static void unregister(String id) {
        Entry e = ENTRIES.remove(id);
        if (e != null) e.controls().get().forEach(PROXIES::remove);
        if (id.equals(selected)) finish();
    }
    public static HudLayout.Bounds bounds(String id, int sw, int sh) {
        Entry e = ENTRIES.get(id);
        return LAYOUT.bounds(id, sw, sh, e.width(), e.height(), e.defaultX().applyAsInt(sw, sh), e.defaultY().applyAsInt(sw, sh));
    }
    private static boolean active() {
        var client = Minecraft.getInstance(); return client.player != null && !client.gui.hud.isHidden();
    }
    public static void render(GuiGraphicsExtractor g, int mx, int my, float dt, boolean editing) {
        if (!active()) return;
        screenWidth = g.guiWidth(); screenHeight = g.guiHeight();
        for (Entry e : ENTRIES.values()) if (e.visible().getAsBoolean()) {
            var b = bounds(e.id(), screenWidth, screenHeight);
            for (Button source : e.controls().get()) source.setFocused(proxy(source, b).isFocused());
            g.pose().pushMatrix();
            g.pose().translate((float)b.x(), (float)b.y()); g.pose().scale((float)b.scale());
            e.renderer().draw(g, editing ? (int)b.localX(mx) : -100, editing ? (int)b.localY(my) : -100, dt, editing);
            g.pose().popMatrix();
            for (Button source : e.controls().get()) proxy(source, b);
            if (editing) {
                g.nextStratum();
                int x = (int)b.x(), y = (int)b.y(), r = (int)Math.ceil(b.right()), bottom = (int)Math.ceil(b.bottom());
                int color = e.id().equals(selected) || b.contains(mx, my) ? GlassStyle.STATUS : 0x889DCBBB;
                g.fill(x - 1, y - 1, r + 1, y, color); g.fill(x - 1, bottom, r + 1, bottom + 1, color);
                g.fill(x - 1, y, x, bottom, color); g.fill(r, y, r + 1, bottom, color);
                g.fill(r - 5, bottom - 5, r + 2, bottom + 2, color);
                if (b.contains(mx, my) || e.id().equals(selected)) {
                    String name = e.label() + "  " + Math.round(b.scale() * 100) + "%";
                    g.text(Minecraft.getInstance().font, GlassStyle.label(name), x, Math.max(0, y - 11), color, true);
                }
            }
        }
        if (editing) g.text(Minecraft.getInstance().font, GlassStyle.label(
                "Drag to move  |  Corner / wheel to resize  |  Right-click to reset"), 8, screenHeight - 28, GlassStyle.MUTED, true);
    }
    public static List<Button> controls() {
        if (!active()) return List.of();
        var window = Minecraft.getInstance().getWindow();
        screenWidth = window.getGuiScaledWidth(); screenHeight = window.getGuiScaledHeight();
        List<Button> result = new ArrayList<>();
        for (Entry e : ENTRIES.values()) if (e.visible().getAsBoolean()) {
            var b = bounds(e.id(), screenWidth, screenHeight);
            for (Button source : e.controls().get()) result.add(proxy(source, b));
        }
        return result;
    }
    private static ControlProxy proxy(Button source, HudLayout.Bounds b) {
        ControlProxy proxy = PROXIES.computeIfAbsent(source, ControlProxy::new);
        proxy.setX((int)Math.floor(b.x() + source.getX() * b.scale()));
        proxy.setY((int)Math.floor(b.y() + source.getY() * b.scale()));
        proxy.setWidth((int)Math.ceil(source.getWidth() * b.scale()));
        proxy.setHeight((int)Math.ceil(source.getHeight() * b.scale()));
        proxy.active = source.active; proxy.setMessage(source.getMessage());
        return proxy;
    }
    /** Native focus/narration use screen bounds; sources draw only in logical widget coordinates. */
    private static final class ControlProxy extends Button {
        private final Button source;
        ControlProxy(Button source) {
            super(0, 0, 1, 1, source.getMessage(), b -> {}, supplier -> supplier.get()); this.source = source;
        }
        @Override public void onPress(InputWithModifiers input) { if (source.active) source.onPress(input); }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float dt) {}
    }
    private static Entry hit(double mx, double my) {
        if (!active()) return null;
        var entries = new ArrayList<>(ENTRIES.values()); Collections.reverse(entries);
        for (Entry e : entries) if (e.visible().getAsBoolean()) {
            var b = bounds(e.id(), screenWidth, screenHeight);
            if (mx >= b.x() - 3 && mx <= b.right() + 4 && my >= b.y() - 3 && my <= b.bottom() + 4) return e;
        }
        return null;
    }
    public static boolean click(Screen screen, MouseButtonEvent event, boolean doubleClick) {
        screenWidth = screen.width; screenHeight = screen.height;
        Entry e = hit(event.x(), event.y());
        if (e == null) return false;
        if (event.button() == 1) { LAYOUT.reset(e.id()); save(); return true; }
        if (event.button() != 0) return true;
        var b = bounds(e.id(), screenWidth, screenHeight);
        boolean corner = event.x() >= b.right() - 9 && event.y() >= b.bottom() - 9;
        if (!corner) {
            var local = new MouseButtonEvent(b.localX(event.x()), b.localY(event.y()), event.buttonInfo());
            for (Button control : e.controls().get()) if (control.isMouseOver(local.x(), local.y())) {
                Button nativeControl = proxy(control, b);
                if (nativeControl.mouseClicked(event, doubleClick)) screen.setFocused(nativeControl);
                return true; // Disabled controls must never accidentally start a drag.
            }
        }
        selected = e.id(); resizing = corner; initial = b; startX = event.x(); startY = event.y();
        return true;
    }
    public static boolean drag(double mx, double my) {
        if (selected == null) return false;
        Entry e = ENTRIES.get(selected);
        if (resizing) {
            // Project the pointer delta onto the size diagonal: smooth proportional scaling.
            double delta = ((mx - startX) * e.width() + (my - startY) * e.height()) /
                    (e.width() * (double)e.width() + e.height() * (double)e.height());
            LAYOUT.place(selected, initial.x(), initial.y(), initial.scale() + delta, screenWidth, screenHeight, e.width(), e.height());
        } else LAYOUT.place(selected, initial.x() + mx - startX, initial.y() + my - startY, initial.scale(), screenWidth, screenHeight, e.width(), e.height());
        dirty = true; return true;
    }
    public static boolean scroll(double mx, double my, double amount) {
        Entry e = hit(mx, my); if (e == null || amount == 0) return false;
        var b = bounds(e.id(), screenWidth, screenHeight);
        LAYOUT.place(e.id(), b.x(), b.y(), b.scale() + Math.signum(amount) * .1, screenWidth, screenHeight, e.width(), e.height());
        save(); return true;
    }
    public static boolean finish() {
        boolean handled = selected != null; selected = null;
        if (dirty) { dirty = false; save(); }
        return handled;
    }
    private static void save() {
        try { LAYOUT.save(); } catch (IOException e) { pingplus.voicechat.client.VoicechatClient.LOG.warn("Could not save HUD layout", e); }
    }
}
