package com.coflnet.gui.panel;

import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.FlipperMod;
import com.coflnet.flipper.trading.BazaarDailyLimit;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.flipper.trading.Trader;
import com.coflnet.profit.ProfitTracker;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BazaarPanel extends FlipperPanel {
    public BazaarPanel() {
        super("bazaar_flipper", "Bazaar");
    }

    @Override protected boolean enabled() { return FlipperMod.CONFIG.bazaarPanelEnabled; }
    @Override protected boolean glassEnabled() { return FlipperMod.CONFIG.bazaarPanelGlass; }
    @Override protected boolean edgesEnabled() { return FlipperMod.CONFIG.bazaarPanelEdges; }
    @Override protected int rowLimit() { return FlipperMod.CONFIG.bazaarPanelRows; }
    @Override protected boolean footerEnabled() { return FlipperMod.CONFIG.bazaarPanelFooter; }
    @Override protected boolean active() { return FlipperMod.isRunning(); }

    @Override protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        for (Trader.OrderView order : Trader.ordersView()) {
            if (rows.size() >= 3 || rows.size() >= rowLimit()) {
                break;
            }
            String side = order.sellSide() ? "Sell" : "Buy";
            double progress = order.amount() > 0
                    ? order.filled() / (double) order.amount() : 0;
            rows.add(new Row(order.itemName(), order.itemName(),
                    side + " " + order.amount() + "x @ "
                            + ScreenUtil.formatPrice(order.unitPrice()),
                    String.format(Locale.US, "%.0f%%", progress * 100),
                    order.filled() + "/" + order.amount(),
                    GlassStyle.STATUS, progress));
        }
        for (Trader.PlanView plan : Trader.lastPlansView()) {
            if (rows.size() >= rowLimit()) {
                break;
            }
            rows.add(new Row(plan.productId(), plan.itemName(),
                    ScreenUtil.formatPrice(plan.buyPrice()) + " \u2192 "
                            + ScreenUtil.formatPrice(plan.sellPrice())
                            + " \u00b7 " + String.format(Locale.US, "%.1f%%",
                            plan.marginPercent()),
                    "+" + ScreenUtil.formatCoins((long) plan.coinsPerHour()) + "/h",
                    "+" + ScreenUtil.formatCoins(plan.expectedProfit()),
                    GlassStyle.STATUS, 0));
        }
        return rows;
    }

    @Override protected String footerText() {
        int open = Math.min(Trader.scannedOrderCount(),
                Math.max(0, FlipperMod.CONFIG.maxOrders));
        return "Orders " + open + "/" + FlipperMod.CONFIG.maxOrders
                + " \u00b7 Limit " + ScreenUtil.formatCoins(BazaarDailyLimit.sold())
                + "/" + ScreenUtil.formatCoins(BazaarDailyLimit.LIMIT_COINS)
                + " \u00b7 +" + ScreenUtil.formatCoins(
                ProfitTracker.display(ProfitTracker.BAZAAR));
    }
}


