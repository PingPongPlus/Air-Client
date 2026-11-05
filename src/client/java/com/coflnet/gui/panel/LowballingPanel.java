package com.coflnet.gui.panel;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.lowballing.LowballingMod;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LowballingPanel extends FlipperPanel {
    public LowballingPanel() {
        super("lowballing", "Lowballing");
    }

    @Override protected boolean enabled() { return LowballingMod.CONFIG.panelEnabled; }
    @Override protected boolean glassEnabled() { return LowballingMod.CONFIG.panelGlass; }
    @Override protected boolean edgesEnabled() { return LowballingMod.CONFIG.panelEdges; }
    @Override protected int rowLimit() { return LowballingMod.CONFIG.panelRows; }
    @Override protected boolean footerEnabled() { return LowballingMod.CONFIG.panelFooter; }
    @Override protected boolean active() { return LowballingMod.isRunning(); }

    @Override protected List<Row> buildRows() {
        LowballingMod.Snapshot snapshot = LowballingMod.snapshot();
        String state = prettyState(snapshot.state());
        String target = snapshot.target() == null || snapshot.target().isBlank()
                ? "-" : snapshot.target();
        List<Row> rows = new ArrayList<>();
        String name = snapshot.itemName() == null ? "" : snapshot.itemName();
        if (!name.isBlank()) {
            String value = snapshot.estimatedValue() > 0
                    ? "+" + ScreenUtil.formatCoins(snapshot.estimatedValue()) : "?";
            String offer = snapshot.buyable()
                    ? "Offer " + ScreenUtil.formatCoins(snapshot.firstOffer())
                            + " \u00b7 Chat " + ScreenUtil.formatCoins(snapshot.chatOffer())
                    : "Rejected";
            rows.add(new Row(snapshot.itemTag(), name,
                    "Sequence: " + state + " \u00b7 Target: " + target,
                    value, offer,
                    snapshot.buyable() ? GlassStyle.STATUS : 0xFFF05A5A, 0));
        } else {
            rows.add(new Row("", "Waiting for trade",
                    "Sequence: " + state, "", target, GlassStyle.MUTED, 0));
        }
        for (LowballingMod.ItemView item : snapshot.items()) {
            if (rows.size() >= rowLimit()) {
                break;
            }
            rows.add(new Row(item.tag(), item.name(), "Trade item",
                    "+" + ScreenUtil.formatCoins(item.value()), "", GlassStyle.STATUS, 0));
        }
        String note = snapshot.note();
        if (note != null && !note.isBlank() && rows.size() < rowLimit()) {
            rows.add(new Row("", "Note", note, "", "", GlassStyle.MUTED, 0));
        }
        return rows;
    }

    private static String prettyState(String state) {
        if (state == null) {
            return "?";
        }
        String value = state.toLowerCase(Locale.ROOT).replace('_', ' ');
        return value.isEmpty() ? "?" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Override protected String footerText() {
        LowballingMod.Snapshot snapshot = LowballingMod.snapshot();
        String target = snapshot.target() == null || snapshot.target().isBlank()
                ? "-" : snapshot.target();
        return "Sequence " + prettyState(snapshot.state())
                + " \u00b7 Target " + target
                + " \u00b7 Queue " + snapshot.queueSize();
    }
}
