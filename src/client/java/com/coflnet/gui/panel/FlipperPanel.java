package com.coflnet.gui.panel;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.gui.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;

/** Base class for the draggable/scalable flipper HUD panels. */
public abstract class FlipperPanel {
    public record Row(String tag, String name, String subtitle, String main,
                      String sub, int mainColor, double progress) {
    }

    private static final int HEADER = 18;
    private static final int ROW = 24;
    private static final int FOOTER = 14;
    private static final int WIDTH = 224;
    private static final long UPDATE_MS = 500L;

    private final String id;
    private final String title;
    private volatile List<Row> rows = List.of();
    private volatile long lastUpdate;

    protected FlipperPanel(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    protected abstract boolean enabled();

    protected abstract boolean glassEnabled();

    protected abstract boolean edgesEnabled();

    protected abstract int rowLimit();

    protected abstract boolean footerEnabled();

    protected boolean active() {
        return enabled();
    }

    protected abstract List<Row> buildRows();

    protected abstract String footerText();

    public int width() {
        return WIDTH;
    }

    public int height() {
        int count = Math.max(1, Math.min(5, rowLimit()));
        return HEADER + 1 + count * ROW + (footerEnabled() ? FOOTER : 0) + 6;
    }

    public boolean visible() {
        return enabled() && ScreenUtil.onSkyBlock();
    }

    public void update() {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < UPDATE_MS) {
            return;
        }
        lastUpdate = now;
        try {
            rows = List.copyOf(buildRows());
        } catch (Exception ignored) {
            rows = List.of();
        }
    }

    public void render(GuiGraphicsExtractor g, boolean editing) {
        if (!enabled()) {
            return;
        }
        if (!editing && !ScreenUtil.onSkyBlock()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        int limit = Math.max(1, Math.min(5, rowLimit()));
        int h = HEADER + 1 + limit * ROW + (footerEnabled() ? FOOTER : 0) + 6;
        if (glassEnabled()) {
            g.nextStratum();
            GlassStyle.surface(g, 0, 0, WIDTH, h, .88f, .08f, edgesEnabled());
            g.nextStratum();
        }
        int textColor = glassEnabled() ? GlassStyle.TEXT : 0xFFFFFFFF;
        int muted = glassEnabled() ? GlassStyle.MUTED : 0xFFD0D0D0;
        g.text(client.font, GlassStyle.label(title), 8, 4, textColor, !glassEnabled());
        int dotX = WIDTH - 18;
        int dotY = 6;
        boolean active = active();
        if (active) {
            float pulse = .62f + .38f * (0.5f + 0.5f * (float) Math.sin(
                    System.nanoTime() / 380_000_000.0));
            int glow = GlassStyle.alpha(GlassStyle.STATUS, pulse * .28f);
            int dot = GlassStyle.alpha(GlassStyle.STATUS, pulse);
            g.fill(dotX - 1, dotY - 1, dotX + 6, dotY + 6, glow);
            g.fill(dotX, dotY, dotX + 5, dotY + 5, dot);
        } else {
            g.fill(dotX - 1, dotY - 1, dotX + 6, dotY + 6,
                    GlassStyle.alpha(0xFFF05A5A, .22f));
            g.fill(dotX, dotY, dotX + 5, dotY + 5, 0xFFF05A5A);
        }
        String counter = rows.size() + "/" + limit;
        g.text(client.font, GlassStyle.label(counter),
                WIDTH - 24 - client.font.width(counter), 4, muted, false);
        g.fill(8, HEADER - 1, WIDTH - 8, HEADER,
                GlassStyle.alpha(0xFFFFFFFF, .12f));

        List<Row> shown = rows;
        if (editing && shown.isEmpty()) {
            shown = exampleRows();
        }
        if (shown.isEmpty()) {
            String empty = "No data";
            g.text(client.font, GlassStyle.label(empty),
                    (WIDTH - client.font.width(empty)) / 2, HEADER + 8, muted, false);
        } else {
            for (int index = 0; index < shown.size() && index < limit; index++) {
                renderRow(g, client, shown.get(index), HEADER + index * ROW, muted);
            }
        }
        if (footerEnabled()) {
            String footer = footerText();
            if (footer == null) {
                footer = "";
            }
            int footerY = HEADER + 1 + limit * ROW + 2;
            GlassStyle.line(g, footer, 8, footerY, WIDTH - 16, muted);
        }
    }

    private void renderRow(GuiGraphicsExtractor g, Minecraft client, Row row,
                           int y, int muted) {
        RenderUtils.drawItemStack(g, ItemIconCache.icon(row.tag(), row.name()), 8, y + 4, 1.0f);
        int rightX = WIDTH - 8;
        int mainWidth = client.font.width(row.main() == null ? "" : row.main());
        int subWidth = client.font.width(row.sub() == null ? "" : row.sub());
        int nameMax = WIDTH - 30 - Math.max(mainWidth, subWidth) - 8;
        GlassStyle.line(g, row.name() == null ? "" : row.name(),
                30, y + 3, Math.max(20, nameMax),
                glassEnabled() ? GlassStyle.TEXT : 0xFFFFFFFF);
        if (row.subtitle() != null && !row.subtitle().isBlank()) {
            GlassStyle.line(g, row.subtitle(), 30, y + 13,
                    Math.max(20, WIDTH - 30 - 8), muted);
        }
        if (row.main() != null && !row.main().isBlank()) {
            g.text(client.font, GlassStyle.label(row.main()),
                    rightX - mainWidth, y + 3, row.mainColor(), false);
        }
        if (row.sub() != null && !row.sub().isBlank()) {
            g.text(client.font, GlassStyle.label(row.sub()),
                    rightX - subWidth, y + 13, muted, false);
        }
        if (row.progress() > 0) {
            int barY = y + 21;
            int barWidth = WIDTH - 30 - 8;
            int filled = (int) Math.round(barWidth * Math.min(1.0, row.progress()));
            g.fill(30, barY, 30 + barWidth, barY + 2,
                    GlassStyle.alpha(GlassStyle.STATUS, .15f));
            g.fill(30, barY, 30 + filled, barY + 2, GlassStyle.STATUS);
        }
    }

    private List<Row> exampleRows() {
        List<Row> examples = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            examples.add(new Row("", "Example item " + (i + 1),
                    "12.4M \u2192 15.1M", "+2.1M", "+17% \u00b7 8s ago",
                    GlassStyle.STATUS, i == 0 ? .72 : 0));
        }
        return examples;
    }
}
