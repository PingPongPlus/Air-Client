package com.coflnet.gui.panel;

import com.coflnet.ahflipper.AhFireSaleSkinTracker;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.ahflipper.AhInvestmentTracker;
import com.coflnet.flipper.trading.ScreenUtil;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InvestmentPanel extends FlipperPanel {
    public InvestmentPanel() {
        super("investment", "Investment");
    }

    @Override protected boolean enabled() { return AhFlipperMod.CONFIG.investmentPanelEnabled; }
    @Override protected boolean glassEnabled() { return AhFlipperMod.CONFIG.investmentPanelGlass; }
    @Override protected boolean edgesEnabled() { return AhFlipperMod.CONFIG.investmentPanelEdges; }
    @Override protected int rowLimit() { return AhFlipperMod.CONFIG.investmentPanelRows; }
    @Override protected boolean footerEnabled() { return AhFlipperMod.CONFIG.investmentPanelFooter; }
    @Override protected boolean active() {
        return AhInvestmentTracker.isRunning() || AhFireSaleSkinTracker.isRunning();
    }

    @Override protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        for (AhInvestmentTracker.InvestmentCandidate candidate
                : AhInvestmentTracker.snapshot().top) {
            rows.add(new Row(candidate.tag, candidate.name,
                    "Buy " + ScreenUtil.formatCoins(candidate.effectiveBuyPrice)
                            + " \u2192 " + ScreenUtil.formatCoins(candidate.sellTarget),
                    "+" + String.format(Locale.US, "%.0f%%", candidate.marginPercent),
                    "Qty " + candidate.qty + " \u00b7 ~" + candidate.holdingHours
                            + "h \u00b7 " + ScreenUtil.formatCoins(candidate.coinsPerHour) + "/h",
                    GlassStyle.STATUS, candidate.confidence));
            if (rows.size() >= rowLimit()) {
                return rows;
            }
        }
        for (AhFireSaleSkinTracker.SkinCandidate candidate
                : AhFireSaleSkinTracker.snapshot().top) {
            rows.add(new Row(candidate.tag, candidate.name,
                    "Buy " + ScreenUtil.formatCoins(candidate.buyPrice)
                            + " \u2192 " + ScreenUtil.formatCoins(candidate.target),
                    "+" + String.format(Locale.US, "%.0f%%", candidate.roiPercent),
                    "Qty " + candidate.qty + " \u00b7 ~" + candidate.holdingDays
                            + "d \u00b7 " + ScreenUtil.formatCoins(candidate.profitPerDay) + "/d",
                    GlassStyle.STATUS, candidate.confidence));
            if (rows.size() >= rowLimit()) {
                break;
            }
        }
        return rows;
    }

    @Override protected String footerText() {
        AhInvestmentTracker.Snapshot snapshot = AhInvestmentTracker.snapshot();
        if (!snapshot.top.isEmpty()) {
            return "AH-Invest \u00b7 " + snapshot.top.size() + " matches";
        }
        AhFireSaleSkinTracker.Snapshot skins = AhFireSaleSkinTracker.snapshot();
        if (!skins.top.isEmpty()) {
            return "Fire Sale \u00b7 " + skins.top.size() + " matches";
        }
        return "Investment tracker off";
    }
}
