package com.coflnet.gui.panel;

import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.ahflipper.AhInvestmentTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import pingplus.voicechat.client.hud.editor.HudEditor;

import java.util.List;

public final class FlipperPanels {
    private static final AhFlipperPanel AH = new AhFlipperPanel();
    private static final AhBidsPanel BIDS = new AhBidsPanel();
    private static final BazaarPanel BAZAAR = new BazaarPanel();
    private static final InvestmentPanel INVESTMENT = new InvestmentPanel();
    private static final ProfitPanel PROFIT = new ProfitPanel();
    private static final LowballingPanel LOWBALLING = new LowballingPanel();
    
    private static boolean initialized;

    private FlipperPanels() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        register(AH, 8, 80);
        register(BIDS, 8, 250);
        register(BAZAAR, 240, 80);
        register(INVESTMENT, 240, 300);
        register(PROFIT, 472, 80);
        register(LOWBALLING, 472, 300);
        
        if (AhFlipperMod.CONFIG.investmentPanelEnabled
                || AhFlipperMod.CONFIG.investmentGui) {
            AhInvestmentTracker.start();
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> updateAll());
    }

    private static void register(FlipperPanel panel, int defaultX, int defaultY) {
        HudEditor.register(new HudEditor.Entry(panel.id(), panel.title(),
                panel::width, panel::height,
                (width, height) -> defaultX,
                (width, height) -> defaultY,
                panel::visible,
                (graphics, mouseX, mouseY, delta, editing) ->
                        panel.render(graphics, editing),
                List::of, true));
    }

    public static void updateAll() {
        AH.update();
        BIDS.update();
        BAZAAR.update();
        INVESTMENT.update();
        PROFIT.update();
        LOWBALLING.update();
    }
}

