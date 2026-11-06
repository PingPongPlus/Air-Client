package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.gui.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

public final class AhInvestmentScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int ROW_HEIGHT = 46;
    private static final int HEADER_HEIGHT = 74;
    private static final int FOOTER_HEIGHT = 56;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public AhInvestmentScreen() {
        super(Component.literal("Investment Tracker"));
    }

    @Override
    protected void init() {
        panelWidth = Math.max(420, Math.min(MAX_PANEL_WIDTH, width - 16));
        int rows = visibleRows();
        panelHeight = HEADER_HEIGHT + rows * ROW_HEIGHT + FOOTER_HEIGHT;
        panelX = Math.max(8, (width - panelWidth) / 2);
        panelY = Math.max(8, (height - panelHeight) / 2);
        int footerY = panelY + panelHeight - 24;
        addRenderableWidget(actionButton(panelX + 8, footerY, 78, 18, "Refresh",
                AhInvestmentTracker::requestRefresh));
        addRenderableWidget(actionButton(panelX + 92, footerY, 90, 18, "Fire Sale",
                AhFlipperMod::openFireSaleGui));
        addRenderableWidget(actionButton(panelX + panelWidth - 86, footerY, 78, 18,
                "Close", this::onClose));
    }

    private int visibleRows() {
        int maxByHeight = Math.max(1,
                (height - HEADER_HEIGHT - FOOTER_HEIGHT - 16) / ROW_HEIGHT);
        return Math.max(1, Math.min(Math.min(10,
                AhFlipperMod.CONFIG.investmentTopCount), maxByHeight));
    }

    private int middleX() {
        return panelX + (sparkWidth() > 0 ? 320 : 300);
    }

    private int sparkWidth() {
        return panelWidth >= 540 ? 112 : 0;
    }

    private int sparkX() {
        return panelX + panelWidth - sparkWidth() - 14;
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
        AhInvestmentTracker.Snapshot snapshot = AhInvestmentTracker.snapshot();
        RenderUtils.drawRoundedRect(context, panelX, panelY, panelWidth, panelHeight,
                6, 0xEE0B0B10);
        RenderUtils.drawRoundedRect(context, panelX + 6, panelY + 6,
                panelWidth - 12, 24, 4, 0xFF1B1B24);
        RenderUtils.drawStringWithShadow(context, "\u00a76Investment Tracker",
                panelX + 12, panelY + 12, 0xFFFFFFFF);
        String status = statusLine(snapshot);
        RenderUtils.drawString(context, status, panelX + 132, panelY + 13, 0xFFB8B8C8);
        String bankroll = snapshot.bankroll > 0
                ? "Bankroll " + fmt(snapshot.bankroll) : "Bankroll ?";
        RenderUtils.drawString(context, "\u00a77" + bankroll,
                panelX + panelWidth - 110, panelY + 13, 0xFFB8B8C8);

        int tableTop = panelY + HEADER_HEIGHT - 12;
        RenderUtils.drawString(context, "\u00a78ITEM", panelX + 32, tableTop, 0xFF808080);
        RenderUtils.drawString(context, "\u00a78BUY \u2192 SELL \u00b7 TIME WINDOW",
                panelX + 158, tableTop, 0xFF808080);
        RenderUtils.drawString(context, "\u00a78NUMBERS", middleX(),
                tableTop, 0xFF808080);
        int sparkWidthPx = sparkWidth();
        if (sparkWidthPx > 0) {
            RenderUtils.drawString(context, "\u00a7824H PROFILE", sparkX(), tableTop, 0xFF808080);
        }

        List<AhInvestmentTracker.InvestmentCandidate> top = snapshot.top;
        int maxRows = visibleRows();
        int rowY = panelY + HEADER_HEIGHT;
        if (top.isEmpty()) {
            String message = snapshot.scanning
                    ? "\u00a77Scanning ... " + snapshot.processedTags + "/"
                    + snapshot.totalTags + " \u00a78(" + snapshot.currentTag + ")"
                    : "\u00a77No investments found. Press Refresh or loosen the filters.";
            RenderUtils.drawString(context, message, panelX + 14, rowY + 12, 0xFFFFFFFF);
        } else {
            int middleX = middleX();
            for (int index = 0; index < top.size() && index < maxRows; index++) {
                AhInvestmentTracker.InvestmentCandidate candidate = top.get(index);
                int y = rowY + index * ROW_HEIGHT;
                RenderUtils.drawRect(context, panelX + 6, y,
                        panelWidth - 12, ROW_HEIGHT - 2,
                        index % 2 == 0 ? 0x8014141B : 0x80101016);
                RenderUtils.drawRect(context, panelX + 6, y,
                        2, ROW_HEIGHT - 2, trendColor(candidate));

                RenderUtils.drawItemStack(context, iconFor(candidate.tag),
                        panelX + 12, y + 6, 1.0f);
                RenderUtils.drawStringWithShadow(context, "\u00a7f" + trim(candidate.name, 24),
                        panelX + 32, y + 4, 0xFFFFFFFF);
                RenderUtils.drawString(context,
                        "\u00a77Buy \u00a7f" + fmt(candidate.effectiveBuyPrice)
                                + " \u00a77\u2192 \u00a7f" + fmt(candidate.sellTarget)
                                + " \u00a78(" + pct(candidate.marginPercent) + ")",
                        panelX + 32, y + 16, 0xFFDDDDDD);
                String windowText = candidate.patternValidated
                        ? "\u00a77B \u00a7f"
                                + hourWindow(candidate.buyHourStart, candidate.buyHourEnd)
                                + " \u00a77\u00b7 S \u00a7f"
                                + hourWindow(candidate.sellHourStart, candidate.sellHourEnd)
                                + " \u00a78(" + candidate.patternHits + "/"
                                + candidate.patternDays + "d)"
                        : "\u00a77Clearing \u00a7f" + fmt(candidate.clearingPrice)
                                + (candidate.nextBinPrice > 0
                                ? " \u00a77\u00b7 next BIN \u00a7f" + fmt(candidate.nextBinPrice)
                                : "");
                RenderUtils.drawString(context,
                        windowText + " \u00a77\u00b7 " + trendShort(candidate),
                        panelX + 32, y + 27, 0xFFBBBBBB);

                RenderUtils.drawString(context,
                        "\u00a77Qty \u00a7f" + candidate.qty
                                + " \u00a77\u00b7 Hold \u00a7f~" + candidate.holdingHours + "h",
                        middleX, y + 4, 0xFFFFFFFF);
                RenderUtils.drawString(context,
                        "\u00a7a+" + fmt(candidate.totalProfit)
                                + " \u00a77\u00b7 \u00a7e" + fmt(candidate.coinsPerHour) + "/h",
                        middleX, y + 16, 0xFFFFFFFF);
                RenderUtils.drawString(context,
                        "\u00a77" + (candidate.confidence >= 0.6 ? "\u00a7a" : candidate.confidence >= 0.4
                                ? "\u00a7e" : "\u00a7c") + "Conf " + pct(candidate.confidence * 100)
                                + " \u00a77\u00b7 Book \u00a7f" + candidate.bookDepth,
                        middleX, y + 27, 0xFFAAAAAA);

                if (sparkWidth() > 0) {
                    drawSparkline(context, sparkX(), y + 7, sparkWidth(), 32,
                            candidate.hourlyMedians, candidate.buyHourStart, candidate.sellHourStart);
                }
            }
        }

        int footerY = panelY + panelHeight - 50;
        String footer = "\u00a78Prices per item \u00b7 Fee "
                + String.format(Locale.US, "%.1f%%", AhFlipperMod.CONFIG.ahFeePercent)
                + " \u00b7 Slippage " + String.format(Locale.US, "%.0f%%",
                AhFlipperMod.CONFIG.investmentSlippagePercent)
                + " \u00b7 max Buy " + fmt(AhFlipperMod.CONFIG.investmentMaxBuyPrice)
                + (sparkWidth() > 0 ? " \u00b7 \u00a7aBuy \u00a7cSell \u00a78= Stundenprofil" : "");
        RenderUtils.drawString(context, footer, panelX + 12, footerY, 0xFF888888);
        if (snapshot.scanning && snapshot.totalTags > 0) {
            int barWidth = panelWidth - 24;
            int filled = (int) (barWidth * (snapshot.processedTags
                    / (double) Math.max(1, snapshot.totalTags)));
            RenderUtils.drawRect(context, panelX + 12, panelY + panelHeight - 36,
                    barWidth, 3, 0xFF232330);
            RenderUtils.drawRect(context, panelX + 12, panelY + panelHeight - 36,
                    filled, 3, 0xFFFFAA00);
        }
    }

    private static void drawSparkline(GuiGraphicsExtractor context, int x, int y,
                                      int width, int height, long[] values,
                                      int buyHour, int sellHour) {
        RenderUtils.drawRect(context, x, y, width, height, 0x60000000);
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
        for (int hour = 0; hour < values.length && hour < 24; hour++) {
            long value = values[hour];
            if (value <= 0) {
                continue;
            }
            int px = x + 2 + (int) Math.round(hour * (width - 4) / 23.0);
            int barHeight = 3 + (int) Math.round((value - min) / (double) range
                    * (height - 7));
            int color = hour == buyHour ? 0xFF55DD55
                    : hour == sellHour ? 0xFFDD5555 : 0xFF5599CC;
            RenderUtils.drawRect(context, px, base - barHeight, 2, barHeight, color);
        }
        RenderUtils.drawRect(context, x, y + height - 1, width, 1, 0x40FFFFFF);
    }

    private static String statusLine(AhInvestmentTracker.Snapshot snapshot) {
        if (snapshot.scanning) {
            if (snapshot.totalTags <= 0) {
                return "\u00a7eScan startet ...";
            }
            return "\u00a7eScan " + snapshot.processedTags + "/" + snapshot.totalTags
                    + (snapshot.currentTag.isBlank() ? "" : " \u00a78" + snapshot.currentTag);
        }
        if (snapshot.error != null && !snapshot.error.isBlank()) {
            return "\u00a7c" + trim(snapshot.error, 42);
        }
        if (snapshot.updatedAt > 0) {
            return "\u00a77updated " + age(snapshot.updatedAt)
                    + " \u00a77\u00b7 " + snapshot.candidateCount + " candidates"
                    + " \u00a77\u00b7 Top " + snapshot.top.size();
        }
        return "\u00a77waiting for scan ...";
    }

    private static String age(long at) {
        long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        return (seconds / 3600) + "h ago";
    }

    private static String hourWindow(int start, int end) {
        if (start == end) {
            return String.format(Locale.US, "%02d", start);
        }
        return String.format(Locale.US, "%02d\u2013%02d", start, end);
    }

    /** 48h vs. rest of the week. */
    private static String trendShort(AhInvestmentTracker.InvestmentCandidate candidate) {
        double trend = candidate.momentumPercent;
        if (trend > 2.0) {
            return "\u00a7a\u2191+" + String.format(Locale.US, "%.0f%%", trend);
        }
        if (trend < -2.0) {
            return "\u00a7c\u2193" + String.format(Locale.US, "%.0f%%", trend);
        }
        return "\u00a77\u2192";
    }

    private static int trendColor(AhInvestmentTracker.InvestmentCandidate candidate) {
        if (candidate.momentumPercent > 2.0) {
            return 0xFF55DD55;
        }
        if (candidate.momentumPercent < -2.0) {
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

    private static ItemStack iconFor(String tag) {
        String upper = tag == null ? "" : tag.toUpperCase(Locale.ROOT);
        if (upper.contains("DIAMOND")) {
            return Items.DIAMOND.getDefaultInstance();
        }
        if (upper.contains("EMERALD")) {
            return Items.EMERALD.getDefaultInstance();
        }
        if (upper.contains("GOLD")) {
            return Items.GOLD_INGOT.getDefaultInstance();
        }
        if (upper.contains("IRON")) {
            return Items.IRON_INGOT.getDefaultInstance();
        }
        if (upper.contains("LAPIS")) {
            return Items.LAPIS_LAZULI.getDefaultInstance();
        }
        if (upper.contains("REDSTONE")) {
            return Items.REDSTONE.getDefaultInstance();
        }
        if (upper.contains("QUARTZ")) {
            return Items.QUARTZ.getDefaultInstance();
        }
        if (upper.contains("OBSIDIAN")) {
            return Items.OBSIDIAN.getDefaultInstance();
        }
        if (upper.contains("END")) {
            return Items.ENDER_PEARL.getDefaultInstance();
        }
        if (upper.contains("BLAZE")) {
            return Items.BLAZE_ROD.getDefaultInstance();
        }
        if (upper.contains("SLIME")) {
            return Items.SLIME_BALL.getDefaultInstance();
        }
        if (upper.contains("FISH")) {
            return Items.COD.getDefaultInstance();
        }
        if (upper.contains("BOOK")) {
            return Items.BOOK.getDefaultInstance();
        }
        if (upper.contains("SWORD")) {
            return Items.IRON_SWORD.getDefaultInstance();
        }
        if (upper.contains("BOW")) {
            return Items.BOW.getDefaultInstance();
        }
        if (upper.contains("HELMET") || upper.contains("CHESTPLATE")
                || upper.contains("LEGGINGS") || upper.contains("BOOTS")
                || upper.contains("ARMOR")) {
            return Items.IRON_CHESTPLATE.getDefaultInstance();
        }
        if (upper.contains("POTION")) {
            return Items.POTION.getDefaultInstance();
        }
        return Items.PAPER.getDefaultInstance();
    }
}
