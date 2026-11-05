package com.coflnet.gui.panel;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.profit.ProfitTracker;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AhBidsPanel extends FlipperPanel {
    public AhBidsPanel() {
        super("ah_bids", "Bids");
    }

    @Override protected boolean enabled() { return AhFlipperMod.CONFIG.bidsPanelEnabled; }
    @Override protected boolean glassEnabled() { return AhFlipperMod.CONFIG.bidsPanelGlass; }
    @Override protected boolean edgesEnabled() { return AhFlipperMod.CONFIG.bidsPanelEdges; }
    @Override protected int rowLimit() { return AhFlipperMod.CONFIG.bidsPanelRows; }
    @Override protected boolean footerEnabled() { return AhFlipperMod.CONFIG.bidsPanelFooter; }
    @Override protected boolean active() { return AhBidMacro.isRunning(); }

    @Override protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AhBidMacro.BidView view : AhBidMacro.bidsView()) {
            String status;
            int color;
            if ("ENDED".equals(view.status())) {
                status = "Ended";
                color = GlassStyle.MUTED;
            } else if ("OUTBID".equals(view.status())) {
                status = "Outbid";
                color = 0xFFF08A8A;
            } else {
                status = "Highest bidder";
                color = GlassStyle.STATUS;
            }
            long remaining = view.end() > 0 ? view.end() - now : 0;
            String time;
            if (view.end() <= 0) {
                time = "End ?";
            } else if (remaining <= 0) {
                time = "ended";
            } else if (remaining < 60_000L) {
                time = "ends in " + Math.max(1, remaining / 1000) + "s";
                color = 0xFFF0D08A;
            } else {
                time = String.format(Locale.US, "ends %d:%02d",
                        (remaining / 60_000L) % 60, (remaining / 1000) % 60);
            }
            rows.add(new Row(view.tag(), view.name(),
                    "Bid " + ScreenUtil.formatCoins(view.ownBid())
                            + " \u00b7 Value " + ScreenUtil.formatCoins(view.estimatedValue()),
                    status, time, color, 0));
        }
        return rows;
    }

    @Override protected String footerText() {
        long bound = 0;
        int active = 0;
        long now = System.currentTimeMillis();
        for (AhBidMacro.BidView view : AhBidMacro.bidsView()) {
            if (view.end() > now) {
                active++;
                bound += Math.max(0, view.ownBid());
            }
        }
        return "Active bids " + active + " \u00b7 locked "
                + ScreenUtil.formatCoins(bound)
                + " \u00b7 +" + ScreenUtil.formatCoins(
                ProfitTracker.display(ProfitTracker.AH));
    }
}
