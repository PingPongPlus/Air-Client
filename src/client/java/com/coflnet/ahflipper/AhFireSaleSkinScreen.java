package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.gui.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AhFireSaleSkinScreen extends Screen {
    public enum Mode {
        SCAN, BACKTEST, COMPS
    }

    private static final int MAX_PANEL_WIDTH = 660;
    private static final int ROW_HEIGHT = 52;
    private static final int HEADER_HEIGHT = 74;
    private static final int FOOTER_HEIGHT = 84;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private Mode mode;

    public AhFireSaleSkinScreen() {
        this(Mode.SCAN);
    }

    public AhFireSaleSkinScreen(Mode mode) {
        super(Component.literal("Fire Sale Skin Investments"));
        this.mode = mode == null ? Mode.SCAN : mode;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(460, Math.min(MAX_PANEL_WIDTH, width - 16));
        int rows = visibleRows();
        panelHeight = HEADER_HEIGHT + rows * ROW_HEIGHT + FOOTER_HEIGHT;
        panelX = Math.max(8, (width - panelWidth) / 2);
        panelY = Math.max(8, (height - panelHeight) / 2);
        int buttonY = panelY + panelHeight - 24;
        int modeY = panelY + panelHeight - 56;
        addRenderableWidget(actionButton(panelX + 8, modeY, 68, 18, "Scan",
                () -> switchMode(Mode.SCAN)));
        addRenderableWidget(actionButton(panelX + 80, modeY, 78, 18, "Backtest",
                () -> switchMode(Mode.BACKTEST)));
        addRenderableWidget(actionButton(panelX + 162, modeY, 68, 18, "Comps",
                () -> switchMode(Mode.COMPS)));
        addRenderableWidget(actionButton(panelX + 240, modeY, 98, 18, "Apply Filters",
                this::applyRecommendedFilters));
        addRenderableWidget(actionButton(panelX + 8, buttonY, 78, 18, "Refresh",
                this::refreshCurrentMode));
        addRenderableWidget(actionButton(panelX + 92, buttonY, 90, 18, "Investment",
                AhFlipperMod::openInvestmentGui));
        addRenderableWidget(actionButton(panelX + panelWidth - 86, buttonY, 78, 18,
                "Close", this::onClose));
    }

    private void switchMode(Mode next) {
        mode = next;
        if (mode == Mode.BACKTEST) {
            AhFireSaleBacktest.runAsync();
        } else if (mode == Mode.COMPS) {
            AhFireSaleComps.runAsync();
        }
    }

    private void refreshCurrentMode() {
        if (mode == Mode.BACKTEST) {
            AhFireSaleBacktest.runAsync();
        } else if (mode == Mode.COMPS) {
            AhFireSaleComps.runAsync();
        } else {
            AhFireSaleSkinTracker.requestRefresh();
        }
    }

    private void applyRecommendedFilters() {
        AhFireSaleBacktest.Recommendation recommendation =
                AhFireSaleBacktest.snapshot().recommended;
        if (recommendation == null) {
            return;
        }
        AhFlipperConfig config = AhFlipperMod.CONFIG;
        config.fireSaleMinRoiPercent = recommendation.minRoiPercent;
        config.fireSaleMinTrendPercent = recommendation.minTrendPercent;
        config.fireSaleMinSales = Math.max(1, recommendation.minSales);
        config.fireSaleMinConfidence = Math.max(0.05,
                Math.min(0.95, recommendation.minConfidence));
        config.save();
        AhFireSaleSkinTracker.requestRefresh();
    }

    private int visibleRows() {
        int maxByHeight = Math.max(1,
                (height - HEADER_HEIGHT - FOOTER_HEIGHT - 16) / ROW_HEIGHT);
        return Math.max(1, Math.min(Math.min(5,
                AhFlipperMod.CONFIG.fireSaleTopCount), maxByHeight));
    }

    private int middleX() {
        return panelX + (graphWidth() > 0 ? 330 : 300);
    }

    private int graphWidth() {
        return panelWidth >= 580 ? 140 : 0;
    }

    private int graphX() {
        return panelX + panelWidth - graphWidth() - 14;
    }

    private Button actionButton(int x, int y, int width, int height, String label,
                                Runnable action) {
        return new Button(x, y, width, height, Component.literal(label),
                button -> action.run(), supplier -> supplier.get()) {
            @Override
            protected void extractContents(GuiGraphicsExtractor graphics,
                                           int mouseX, int mouseY, float delta) {
                if (isHoveredOrFocused()) {
                    graphics.fill(getX(), getY(), getRight(), getBottom(), 0x80555555);
                }
                RenderUtils.drawString(graphics, label, getX() + 5, getY() + 5, 0xFFFFFFFF);
            }
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        RenderUtils.drawRoundedRect(context, panelX, panelY, panelWidth, panelHeight,
                6, 0xEE0B0B10);
        RenderUtils.drawRoundedRect(context, panelX + 6, panelY + 6,
                panelWidth - 12, 24, 4, 0xFF1B1B24);
        String title = switch (mode) {
            case BACKTEST -> "\u00a76Fire Sale Backtest";
            case COMPS -> "\u00a76New Fire Sale Skins \u00b7 Comps";
            default -> "\u00a76Fire Sale Skins";
        };
        RenderUtils.drawStringWithShadow(context, title, panelX + 12, panelY + 12,
                0xFFFFFFFF);
        RenderUtils.drawString(context, statusLine(), panelX + 170, panelY + 13,
                0xFFB8B8C8);
        long bankroll = AhFireSaleSkinTracker.snapshot().bankroll;
        String bankrollText = bankroll > 0 ? "Bankroll " + fmt(bankroll) : "Bankroll ?";
        RenderUtils.drawString(context, "\u00a77" + bankrollText,
                panelX + panelWidth - 110, panelY + 13, 0xFFB8B8C8);

        int tableTop = panelY + HEADER_HEIGHT - 12;
        if (mode == Mode.SCAN) {
            drawScanHeader(context, tableTop);
            drawScan(context);
        } else if (mode == Mode.BACKTEST) {
            drawBacktest(context);
        } else {
            drawCompsHeader(context, tableTop);
            drawComps(context);
        }
        drawFooter(context);
    }

    private void drawScanHeader(GuiGraphicsExtractor context, int tableTop) {
        RenderUtils.drawString(context, "\u00a78SKIN", panelX + 32, tableTop, 0xFF808080);
        RenderUtils.drawString(context, "\u00a78BUY \u2192 TARGET \u00b7 TREND",
                panelX + 158, tableTop, 0xFF808080);
        RenderUtils.drawString(context, "\u00a78NUMBERS", middleX(), tableTop, 0xFF808080);
        if (graphWidth() > 0) {
            RenderUtils.drawString(context, "\u00a78PRICE HISTORY", graphX(),
                    tableTop, 0xFF808080);
        }
    }

    private void drawScan(GuiGraphicsExtractor context) {
        AhFireSaleSkinTracker.Snapshot snapshot = AhFireSaleSkinTracker.snapshot();
        List<AhFireSaleSkinTracker.SkinCandidate> top = snapshot.top;
        int maxRows = visibleRows();
        int rowY = panelY + HEADER_HEIGHT;
        if (top.isEmpty()) {
            String message = snapshot.scanning
                    ? "\u00a77Scanning ... " + snapshot.processedSkins + "/"
                    + snapshot.totalSkins + " \u00a78(" + snapshot.currentTag + ")"
                    : "\u00a77No Fire Sale skins found. Refresh or loosen filters.";
            RenderUtils.drawString(context, message, panelX + 14, rowY + 14, 0xFFFFFFFF);
            return;
        }
        int middleX = middleX();
        for (int index = 0; index < top.size() && index < maxRows; index++) {
            AhFireSaleSkinTracker.SkinCandidate candidate = top.get(index);
            int y = rowY + index * ROW_HEIGHT;
            RenderUtils.drawRect(context, panelX + 6, y,
                    panelWidth - 12, ROW_HEIGHT - 2,
                    index % 2 == 0 ? 0x8014141B : 0x80101016);
            RenderUtils.drawRect(context, panelX + 6, y,
                    2, ROW_HEIGHT - 2, trendColor(candidate));

            RenderUtils.drawItemStack(context, Items.PLAYER_HEAD.getDefaultInstance(),
                    panelX + 12, y + 8, 1.0f);
            RenderUtils.drawStringWithShadow(context, "\u00a7f" + trim(candidate.name, 26),
                    panelX + 32, y + 4, 0xFFFFFFFF);
            RenderUtils.drawString(context,
                    "\u00a77Buy \u00a7f" + fmt(candidate.buyPrice)
                            + " \u00a77\u2192 \u00a7f" + fmt(candidate.target)
                            + " \u00a78(" + pct(candidate.roiPercent) + " ROI)",
                    panelX + 32, y + 16, 0xFFDDDDDD);
            RenderUtils.drawString(context,
                    "\u00a77Trend " + trendShort(candidate)
                            + " \u00a77\u00b7 Hold \u00a7f~" + candidate.holdingDays + "d"
                            + " \u00a77\u00b7 Conf \u00a7f" + pct(candidate.confidence * 100),
                    panelX + 32, y + 28, 0xFFBBBBBB);

            RenderUtils.drawString(context,
                    "\u00a77Qty \u00a7f" + candidate.qty
                            + " \u00a77\u00b7 Listings \u00a7f" + candidate.activeListings,
                    middleX, y + 4, 0xFFFFFFFF);
            RenderUtils.drawString(context,
                    "\u00a7a+" + fmt(candidate.totalProfit)
                            + " \u00a77\u00b7 \u00a7e" + fmt(candidate.profitPerDay) + "/d",
                    middleX, y + 16, 0xFFFFFFFF);
            RenderUtils.drawString(context,
                    "\u00a77Sales M/W \u00a7f" + candidate.monthlySales
                            + "/" + candidate.weeklySales
                            + " \u00a77\u00b7 \u00d8 \u00a7f" + fmt(candidate.historicalAverage),
                    middleX, y + 28, 0xFFAAAAAA);
            RenderUtils.drawString(context,
                    "\u00a77History \u00a7f" + candidate.historyDays + "d",
                    middleX, y + 39, 0xFF888888);

            if (graphWidth() > 0) {
                drawGraph(context, graphX(), y + 8, graphWidth(), 34,
                        candidate.graphPrices);
            }
        }
    }

    private void drawBacktest(GuiGraphicsExtractor context) {
        AhFireSaleBacktest.Snapshot snapshot = AhFireSaleBacktest.snapshot();
        int x = panelX + 16;
        int y = panelY + HEADER_HEIGHT;
        int line = 12;
        if (snapshot.running) {
            RenderUtils.drawString(context, "\u00a7eBacktest running ...", x, y,
                    0xFFFFFFFF);
            return;
        }
        if (snapshot.error != null && !snapshot.error.isBlank()) {
            RenderUtils.drawString(context, "\u00a7c" + trim(snapshot.error, 80), x, y,
                    0xFFFFFFFF);
            return;
        }
        RenderUtils.drawString(context,
                "\u00a77Tested skins: \u00a7f" + snapshot.skinCount
                        + " \u00a77\u00b7 total signals: \u00a7f" + snapshot.signalCount
                        + " \u00a77\u00b7 updated: \u00a7f" + age(snapshot.updatedAt),
                x, y, 0xFFFFFFFF);
        y += line;
        for (int horizon : AhFireSaleBacktest.HORIZONS) {
            AhFireSaleBacktest.HorizonStats stats = snapshot.horizons.get(horizon);
            if (stats == null) {
                RenderUtils.drawString(context, "\u00a77" + horizon + "d: \u00a78no data",
                        x, y, 0xFFFFFFFF);
            } else {
                RenderUtils.drawString(context, "\u00a77" + stats.summary()
                                + String.format(Locale.US,
                                " \u00a77\u00b7 target hit \u00a7f%.0f%%",
                                stats.targetHitRate * 100),
                        x, y, 0xFFFFFFFF);
            }
            y += line;
        }
        y += 4;
        AhFireSaleBacktest.Recommendation recommendation = snapshot.recommended;
        if (recommendation == null) {
            RenderUtils.drawString(context,
                    "\u00a7eNo recommendation yet (too few signals).",
                    x, y, 0xFFFFFFFF);
        } else {
            RenderUtils.drawString(context,
                    "\u00a76Recommended filters \u00a77("
                            + recommendation.calibrationHorizonDays + "d)"
                            + (recommendation.appliedDefaults
                            ? " \u00a78(default, too few signals)" : ""),
                    x, y, 0xFFFFFFFF);
            y += line;
            RenderUtils.drawString(context,
                    "\u00a77Min ROI \u00a7f" + pct(recommendation.minRoiPercent)
                            + " \u00a77\u00b7 Min Trend \u00a7f"
                            + String.format(Locale.US, "%.2f%%/d", recommendation.minTrendPercent)
                            + " \u00a77\u00b7 Min Sales \u00a7f" + recommendation.minSales
                            + " \u00a77\u00b7 Min Conf \u00a7f"
                            + pct(recommendation.minConfidence * 100),
                    x, y, 0xFFFFFFFF);
            y += line;
            RenderUtils.drawString(context,
                    "\u00a77Signale \u00a7f" + recommendation.signals
                            + " \u00a77\u00b7 Win \u00a7f" + pct(recommendation.winRate * 100)
                            + " \u00a77\u00b7 Median \u00a7f"
                            + String.format(Locale.US, "%.1f%%", recommendation.medianRoi)
                            + " \u00a77\u00b7 \u00d8 \u00a7f"
                            + String.format(Locale.US, "%.1f%%", recommendation.averageRoi),
                    x, y, 0xFFFFFFFF);
        }
        y += line + 4;
        RenderUtils.drawString(context, "\u00a7e" + snapshot.outOfSample, x, y,
                0xFFFFFFFF);
        y += line;
        RenderUtils.drawString(context, "\u00a78" + trim(snapshot.note, 100), x, y,
                0xFFFFFFFF);
    }

    private void drawCompsHeader(GuiGraphicsExtractor context, int tableTop) {
        RenderUtils.drawString(context, "\u00a78NEW SKIN", panelX + 32, tableTop,
                0xFF808080);
        RenderUtils.drawString(context, "\u00a78BUY \u2192 COMP TARGET \u00b7 ROI",
                panelX + 158, tableTop, 0xFF808080);
        RenderUtils.drawString(context, "\u00a78COMPS", middleX(), tableTop, 0xFF808080);
    }

    private void drawComps(GuiGraphicsExtractor context) {
        AhFireSaleComps.Snapshot snapshot = AhFireSaleComps.snapshot();
        int x = panelX + 16;
        int y = panelY + HEADER_HEIGHT;
        if (snapshot.running) {
            RenderUtils.drawString(context, "\u00a7eComputing comps ...", x, y,
                    0xFFFFFFFF);
            return;
        }
        if (snapshot.error != null && !snapshot.error.isBlank()) {
            RenderUtils.drawString(context, "\u00a7c" + trim(snapshot.error, 80), x, y,
                    0xFFFFFFFF);
            return;
        }
        List<AhFireSaleComps.CompRow> rows = snapshot.rows;
        if (rows.isEmpty()) {
            RenderUtils.drawString(context,
                    "\u00a77No new skins with enough comps found. Pool: "
                            + snapshot.compPool + " skins, new: " + snapshot.newSkins,
                    x, y + 10, 0xFFFFFFFF);
            return;
        }
        int middleX = middleX();
        for (int index = 0; index < rows.size() && index < visibleRows(); index++) {
            AhFireSaleComps.CompRow row = rows.get(index);
            int rowY = y + index * ROW_HEIGHT;
            RenderUtils.drawRect(context, panelX + 6, rowY,
                    panelWidth - 12, ROW_HEIGHT - 2,
                    index % 2 == 0 ? 0x8014141B : 0x80101016);
            RenderUtils.drawRect(context, panelX + 6, rowY,
                    2, ROW_HEIGHT - 2, 0xFF55AACC);
            RenderUtils.drawItemStack(context, Items.PLAYER_HEAD.getDefaultInstance(),
                    panelX + 12, rowY + 8, 1.0f);
            RenderUtils.drawStringWithShadow(context, "\u00a7f" + trim(row.name, 26),
                    panelX + 32, rowY + 4, 0xFFFFFFFF);
            RenderUtils.drawString(context,
                    "\u00a77Buy \u00a7f" + fmt(row.buyPrice)
                            + " \u00a77\u2192 \u00a7f" + fmt(row.target)
                            + " \u00a78(" + pct(row.expectedRoiPercent) + " ROI)",
                    panelX + 32, rowY + 16, 0xFFDDDDDD);
            RenderUtils.drawString(context,
                    "\u00a77" + row.compCount + " Comps \u00a77\u00b7 Hold \u00a7f~"
                            + row.holdingDays + "d \u00a77\u00b7 Conf \u00a7f"
                            + pct(row.confidence * 100)
                            + " \u00a77\u00b7 Listings \u00a7f" + row.listings,
                    panelX + 32, rowY + 28, 0xFFBBBBBB);
            RenderUtils.drawString(context,
                    "\u00a77Median \u00a7f" + pct(row.compMedianRoi)
                            + " \u00a77\u00b7 P25\u2013P75 \u00a7f"
                            + pct(row.compP25Roi) + "\u2013" + pct(row.compP75Roi),
                    middleX, rowY + 6, 0xFFFFFFFF);
            RenderUtils.drawString(context,
                    "\u00a77Tier \u00a7f" + row.tier + " \u00a77\u00b7 Typ \u00a7f" + row.type,
                    middleX, rowY + 18, 0xFFAAAAAA);
            RenderUtils.drawString(context,
                    "\u00a78" + trim(row.note, 40), middleX, rowY + 30, 0xFF888888);
        }
    }

    private void drawFooter(GuiGraphicsExtractor context) {
        int footerY = panelY + panelHeight - 76;
        String footer = switch (mode) {
            case BACKTEST -> "\u00a78Backtest uses Cofl month/week \u00b7 Fee "
                    + String.format(Locale.US, "%.1f%%", AhFlipperMod.CONFIG.ahFeePercent)
                    + " \u00b7 Apply copies the recommendation into the filters";
            case COMPS -> "\u00a78Comps = relative valuation across older skins "
                    + "\u00b7 Fee " + String.format(Locale.US, "%.1f%%",
                    AhFlipperMod.CONFIG.ahFeePercent);
            default -> "\u00a78Cofl monthly/weekly history \u00b7 Fee "
                    + String.format(Locale.US, "%.1f%%", AhFlipperMod.CONFIG.ahFeePercent)
                    + " \u00b7 Slippage " + String.format(Locale.US, "%.0f%%",
                    AhFlipperMod.CONFIG.fireSaleSlippagePercent)
                    + " \u00b7 Min ROI " + String.format(Locale.US, "%.0f%%",
                    AhFlipperMod.CONFIG.fireSaleMinRoiPercent);
        };
        RenderUtils.drawString(context, footer, panelX + 12, footerY, 0xFF888888);
        AhFireSaleSkinTracker.Snapshot snapshot = AhFireSaleSkinTracker.snapshot();
        if (mode == Mode.SCAN && snapshot.scanning && snapshot.totalSkins > 0) {
            int barWidth = panelWidth - 24;
            int filled = (int) (barWidth * (snapshot.processedSkins
                    / (double) Math.max(1, snapshot.totalSkins)));
            RenderUtils.drawRect(context, panelX + 12, panelY + panelHeight - 66,
                    barWidth, 3, 0xFF232330);
            RenderUtils.drawRect(context, panelX + 12, panelY + panelHeight - 66,
                    filled, 3, 0xFFFFAA00);
        }
    }

    private String statusLine() {
        if (mode == Mode.BACKTEST) {
            AhFireSaleBacktest.Snapshot snapshot = AhFireSaleBacktest.snapshot();
            if (snapshot.running) {
                return "\u00a7eBacktest running ...";
            }
            return "\u00a77aktualisiert " + age(snapshot.updatedAt)
                    + " \u00a77\u00b7 " + snapshot.skinCount + " Skins";
        }
        if (mode == Mode.COMPS) {
            AhFireSaleComps.Snapshot snapshot = AhFireSaleComps.snapshot();
            if (snapshot.running) {
                return "\u00a7eComps l\u00e4uft ...";
            }
            return "\u00a77aktualisiert " + age(snapshot.updatedAt)
                    + " \u00a77\u00b7 Pool " + snapshot.compPool;
        }
        AhFireSaleSkinTracker.Snapshot snapshot = AhFireSaleSkinTracker.snapshot();
        if (snapshot.scanning) {
            if (snapshot.totalSkins <= 0) {
                return "\u00a7eScan startet ...";
            }
            return "\u00a7eScan " + snapshot.processedSkins + "/" + snapshot.totalSkins
                    + (snapshot.currentTag.isBlank() ? "" : " \u00a78" + snapshot.currentTag);
        }
        if (snapshot.error != null && !snapshot.error.isBlank()) {
            return "\u00a7c" + trim(snapshot.error, 44);
        }
        if (snapshot.updatedAt > 0) {
            return "\u00a77aktualisiert " + age(snapshot.updatedAt)
                    + " \u00a77\u00b7 " + snapshot.candidateCount + " Kandidaten";
        }
        return "\u00a77warte auf Scan ...";
    }

    private static void drawGraph(GuiGraphicsExtractor context, int x, int y,
                                  int width, int height, long[] values) {
        RenderUtils.drawRect(context, x, y, width, height, 0x60000000);
        if (values == null || values.length == 0) {
            return;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long value : values) {
            if (value > 0) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        if (min == Long.MAX_VALUE) {
            return;
        }
        long range = Math.max(1, max - min);
        int base = y + height - 2;
        boolean rising = values.length > 1 && values[values.length - 1] >= values[0];
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= 0) {
                continue;
            }
            int px = x + 2 + (int) Math.round(i * (width - 4)
                    / (double) Math.max(1, values.length - 1));
            int barHeight = 3 + (int) Math.round((values[i] - min) / (double) range
                    * (height - 7));
            int color = i == values.length - 1 ? 0xFFFFCC44
                    : rising ? 0xFF55DD55 : 0xFFDD5555;
            RenderUtils.drawRect(context, px, base - barHeight, 2, barHeight, color);
        }
        RenderUtils.drawRect(context, x, y + height - 1, width, 1, 0x40FFFFFF);
    }

    private static String age(long at) {
        if (at <= 0) {
            return "never";
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        return (seconds / 3600) + "h ago";
    }

    private static String trendShort(AhFireSaleSkinTracker.SkinCandidate candidate) {
        double trend = candidate.trendPerDayPercent;
        if (trend > 0.05) {
            return "\u00a7a\u2191+" + String.format(Locale.US, "%.2f%%/d", trend);
        }
        if (trend < -0.05) {
            return "\u00a7c\u2193" + String.format(Locale.US, "%.2f%%/d", trend);
        }
        return "\u00a77\u2192";
    }

    private static int trendColor(AhFireSaleSkinTracker.SkinCandidate candidate) {
        if (candidate.trendPerDayPercent > 0.05) {
            return 0xFF55DD55;
        }
        if (candidate.trendPerDayPercent < -0.05) {
            return 0xFFDD5555;
        }
        return 0xFF8888AA;
    }

    private static String pct(double value) {
        return String.format(Locale.US, "%.0f%%", value);
    }

    private static String fmt(long coins) {
        return ScreenUtil.formatCoins(coins);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "\u2026";
    }
}
