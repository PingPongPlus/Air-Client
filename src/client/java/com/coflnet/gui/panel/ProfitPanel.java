package com.coflnet.gui.panel;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.profit.ProfitTracker;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;

public final class ProfitPanel extends FlipperPanel {
    public ProfitPanel() {
        super("profit_tracker", "Profit");
    }

    @Override protected boolean enabled() { return AhFlipperMod.CONFIG.profitPanelEnabled; }
    @Override protected boolean glassEnabled() { return AhFlipperMod.CONFIG.profitPanelGlass; }
    @Override protected boolean edgesEnabled() { return AhFlipperMod.CONFIG.profitPanelEdges; }
    @Override protected int rowLimit() { return AhFlipperMod.CONFIG.profitPanelRows; }
    @Override protected boolean footerEnabled() { return AhFlipperMod.CONFIG.profitPanelFooter; }
    @Override protected boolean active() {
        return AhFlipperMod.isRunning() || AhBidMacro.isRunning()
                || com.coflnet.flipper.FlipperMod.isRunning();
    }

    @Override protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(row("Overall", ProfitTracker.overallNet()));
        rows.add(row("Bazaar", ProfitTracker.net(ProfitTracker.BAZAAR)));
        rows.add(row("AH-Flipper", ProfitTracker.net(ProfitTracker.AH)));
        rows.add(row("Lowballing", ProfitTracker.net(ProfitTracker.LOWBALLING)));
        return rows;
    }

    private Row row(String label, long net) {
        long shown = Math.max(0L, net);
        long invested = Math.max(0L, -net);
        return new Row("", label,
                "Net",
                shown > 0 ? "+" + ScreenUtil.formatCoins(shown) : "0",
                invested > 0 ? "invested " + ScreenUtil.formatCoins(invested) : "",
                shown > 0 ? GlassStyle.STATUS : GlassStyle.MUTED, 0);
    }

    @Override protected String footerText() {
        long net = ProfitTracker.overallNet();
        if (net > 0) {
            return "Overall +" + ScreenUtil.formatCoins(net);
        }
        if (net < 0) {
            return "Overall 0 \u00b7 invested " + ScreenUtil.formatCoins(-net);
        }
        return "Overall 0";
    }
}
