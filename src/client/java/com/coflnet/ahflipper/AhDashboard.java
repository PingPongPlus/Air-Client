package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.gui.RenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

public final class AhDashboard {
    private AhDashboard() {
    }

    static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("coflnet", "ah_dashboard"),
                (context, tickCounter) -> render(context));
    }

    private static void render(GuiGraphicsExtractor context) {
        if (!AhFlipperMod.CONFIG.dashboard || !AhLiveScanner.isRunning()) {
            return;
        }
        AhLiveScanner.Snapshot snapshot = AhLiveScanner.snapshot();
        AhLiveScanner.Stats stats = snapshot.stats;
        int x = Math.max(0, AhFlipperMod.CONFIG.dashboardX);
        int y = Math.max(0, AhFlipperMod.CONFIG.dashboardY);
        int rows = Math.max(1, AhFlipperMod.CONFIG.dashboardMaxRows);
        int lineHeight = 10;
        int height = lineHeight * (3 + Math.min(rows, snapshot.candidates.size())) + 8;
        RenderUtils.drawRect(context, x - 3, y - 3, 330, height, 0x99000000);

        int line = y;
        RenderUtils.drawStringWithShadow(context,
                "§6AH-Live §7· Feed " + snapshot.feedStatus
                        + " §7· Bankroll " + fmt(snapshot.bankroll), x, line, 0xFFFFFFFF);
        line += lineHeight;
        RenderUtils.drawStringWithShadow(context,
                "§7neu §f" + stats.received
                        + " §7· <" + fmt(AhFlipperMod.CONFIG.liveMinPrice) + " §f" + stats.belowMin
                        + " §7· >Bank §f" + stats.overBankroll
                        + " §7· geprüft §f" + stats.queued
                        + " §7· §akaufbar §f" + stats.buyable, x, line, 0xFFFFFFFF);
        line += lineHeight;
        RenderUtils.drawStringWithShadow(context,
                "§7raus: clean §f" + stats.cleanRejected
                        + " §7· craft §f" + stats.craftRejected
                        + " §7· value §f" + stats.valueRejected
                        + " §7· addon §f" + stats.addonRejected
                        + " §7· err §f" + stats.errors, x, line, 0xFFFFFFFF);
        line += lineHeight;

        int shown = 0;
        for (AhLiveScanner.Candidate candidate : snapshot.candidates) {
            if (shown >= rows) {
                break;
            }
            String name = candidate.name == null || candidate.name.isBlank()
                    ? candidate.tag : candidate.name;
            if (name.length() > 22) {
                name = name.substring(0, 22) + "…";
            }
            String text;
            int color;
            if ("BUY".equals(candidate.stage)) {
                text = String.format(Locale.US,
                        "§aBUY §f%s §7@ §f%s §a+%s §7(§a%.0f%%§7) §7S §f%.0f",
                        name, fmt(candidate.price), fmt(candidate.profit),
                        candidate.profitPercent, candidate.score);
                color = 0xFFFFFFFF;
            } else if (candidate.stage.startsWith("REJECT")) {
                text = "§c" + candidate.stage.replace("REJECT:", "")
                        + " §f" + name + " §7" + candidate.reason;
                color = 0xFFFFFFFF;
            } else {
                text = "§e" + candidate.stage + " §f" + name + " §7@ " + fmt(candidate.price);
                color = 0xFFFFFFFF;
            }
            RenderUtils.drawStringWithShadow(context, text, x, line, color);
            line += lineHeight;
            shown++;
        }
    }

    private static String fmt(long coins) {
        return ScreenUtil.formatCoins(coins);
    }
}
