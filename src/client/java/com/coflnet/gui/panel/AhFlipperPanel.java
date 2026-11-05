package com.coflnet.gui.panel;

import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.ahflipper.AhLiveScanner;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.profit.ProfitTracker;

import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AhFlipperPanel extends FlipperPanel {
    public AhFlipperPanel() {
        super("ah_flipper", "AH-Flipper");
    }

    @Override protected boolean enabled() { return AhFlipperMod.CONFIG.ahPanelEnabled; }
    @Override protected boolean glassEnabled() { return AhFlipperMod.CONFIG.ahPanelGlass; }
    @Override protected boolean edgesEnabled() { return AhFlipperMod.CONFIG.ahPanelEdges; }
    @Override protected int rowLimit() { return AhFlipperMod.CONFIG.ahPanelRows; }
    @Override protected boolean footerEnabled() { return AhFlipperMod.CONFIG.ahPanelFooter; }
    @Override protected boolean active() { return AhFlipperMod.isRunning(); }

    @Override protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AhLiveScanner.CandidateView view : AhLiveScanner.buyableView()) {
            long age = Math.max(0, (now - view.updatedAt()) / 1000);
            rows.add(new Row(view.tag(), view.name(),
                    ScreenUtil.formatCoins(view.price()) + " \u2192 "
                            + ScreenUtil.formatCoins(view.estimatedValue()),
                    "+" + ScreenUtil.formatCoins(view.profit()),
                    String.format(Locale.US, "+%.0f%% \u00b7 %ds ago",
                            view.profitPercent(), age),
                    GlassStyle.STATUS, 0));
        }
        return rows;
    }

    @Override protected String footerText() {
        long bankroll = AhLiveScanner.snapshot().bankroll;
        return "Bankroll " + (bankroll > 0 ? ScreenUtil.formatCoins(bankroll) : "?")
                + " \u00b7 Auctions " + AhFlipperMod.activeAuctionCount()
                + "/" + AhFlipperMod.maxActiveAuctions()
                + " \u00b7 +" + ScreenUtil.formatCoins(ProfitTracker.display(ProfitTracker.AH));
    }
}

