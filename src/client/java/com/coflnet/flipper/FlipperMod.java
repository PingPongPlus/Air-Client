package com.coflnet.flipper;

import com.coflnet.CoflModClient;
import com.coflnet.flipper.analysis.FlipAnalyzer;
import com.coflnet.flipper.data.ItemNames;
import com.coflnet.flipper.data.MarketData;
import com.coflnet.flipper.trading.BazaarChatListener;
import com.coflnet.flipper.trading.OrderLedger;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.flipper.trading.Trade;
import com.coflnet.flipper.trading.Trader;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class FlipperMod {
    public static final FlipperConfig CONFIG = FlipperConfig.load();
    private static OrderLedger ledger;
    private static KeyMapping toggleKey;

    public static void init() {
        ledger = new OrderLedger(FabricLoader.getInstance().getConfigDir()
                .resolve("CoflSky").resolve("flipper-trades.json"));
        Trader.init(CONFIG, ledger);
        ItemNames.load();
        BazaarChatListener.init();
        registerKeybinding();
        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.consumeClick()) {
                toggle();
            }
        });
    }

    public static OrderLedger ledger() {
        return ledger;
    }

    private static void registerKeybinding() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("coflnet", "flipper"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.flipper",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category));
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerFlipperCommands(dispatcher);
        });
    }

    private static void registerFlipperCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("flipper")
                .executes(FlipperMod::toggle)
                .then(ClientCommands.literal("start").executes(FlipperMod::start))
                .then(ClientCommands.literal("stop").executes(FlipperMod::stop))
                .then(ClientCommands.literal("pause").executes(FlipperMod::pause))
                .then(ClientCommands.literal("resume").executes(FlipperMod::resume))
                .then(ClientCommands.literal("status").executes(FlipperMod::status))
                .then(ClientCommands.literal("report").executes(FlipperMod::report))
                .then(ClientCommands.literal("analyze").executes(FlipperMod::analyze))
                .then(ClientCommands.literal("reload").executes(context -> {
                    CONFIG.save();
                    Trader.get().ledger().load();
                    Trader.get().clearBlacklist();
                    ItemNames.load();
                    message("§aConfig gespeichert, Ledger neu geladen & Blacklist geleert.");
                    return 1;
                })));
    }

    private static int toggle(CommandContext<FabricClientCommandSource> context) {
        return toggle();
    }

    private static int toggle() {
        if (Trader.get().isRunning()) {
            stop(null);
        } else {
            start(null);
        }
        return 1;
    }

    private static int start(CommandContext<FabricClientCommandSource> context) {
        if (Trader.get().isRunning()) {
            message("§cFlipper läuft bereits.");
            return 1;
        }
        if (!ScreenUtil.onSkyBlock()) {
            message("§cFlipper kann nur auf Hypixel SkyBlock gestartet werden.");
            return 1;
        }
        Trader.get().start();
        return 1;
    }

    private static int stop(CommandContext<FabricClientCommandSource> context) {
        if (!Trader.get().isRunning()) {
            message("§cFlipper läuft nicht.");
            return 1;
        }
        Trader.get().stop();
        return 1;
    }

    private static int pause(CommandContext<FabricClientCommandSource> context) {
        Trader.get().setPaused(true);
        message("§6[Flipper] §7pausiert (aktuelle Aktion wird beendet).");
        return 1;
    }

    private static int resume(CommandContext<FabricClientCommandSource> context) {
        Trader.get().setPaused(false);
        message("§6[Flipper] §afortgesetzt.");
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> context) {
        Trader trader = Trader.get();
        String state = trader.isRunning() ? (trader.isPaused() ? "§6PAUSIERT" : "§aAKTIV") : "§cGESTOPPT";
        message("§6[Flipper] §7Status: " + state);
        if (trader.isRunning()) {
            long purse = ScreenUtil.readPurse();
            if (purse >= 0) {
                message("§7Purse: §f" + ScreenUtil.formatCoins(purse)
                        + " §7· Reserve: §f" + ScreenUtil.formatCoins(CONFIG.reserveCoins)
                        + " §7· Bankroll: §f" + ScreenUtil.formatCoins(Math.max(0, purse - CONFIG.reserveCoins)));
            }
        }
        MarketData market = trader.marketSnapshot();
        if (market != null) {
            message("§7Marktdaten: §f" + market.products.size() + " §7Produkte");
        }
        message("§7Offene Orders: §f" + ledger.openOrderCount() + "§7/§f" + CONFIG.maxOrders
                + " §7· Geblacklistet: §f" + trader.blacklistCount());
        message("§7Realisierter Gewinn: §f" + ScreenUtil.formatCoins(ledger.realizedProfit()));
        return 1;
    }

    private static int report(CommandContext<FabricClientCommandSource> context) {
        message("§6§l=== Flipper Report ===");
        for (Trade trade : ledger.all()) {
            String profitInfo = trade.state == Trade.State.DONE
                    ? " §7Gewinn: §a+" + ScreenUtil.formatCoins(trade.revenue - trade.cost)
                    : "";
            message("§7" + trade.displayName + " §8[" + trade.state.name() + "] §7"
                    + trade.qty + "x @ " + ScreenUtil.formatPrice(trade.buyPrice)
                    + " §7Kosten: §f" + ScreenUtil.formatCoins(trade.cost)
                    + (trade.revenue > 0 ? " §7Ertrag: §f" + ScreenUtil.formatCoins(trade.revenue) : "")
                    + profitInfo);
        }
        message("§7Abgeschlossen: §f" + ledger.tradesCompleted()
                + " §7· Gewinn: §f" + ScreenUtil.formatCoins(ledger.realizedProfit()));
        return 1;
    }

    private static int analyze(CommandContext<FabricClientCommandSource> context) {
        MarketData market = Trader.get().marketSnapshot();
        if (market == null) {
            message("§7Lade Marktdaten von der Hypixel API...");
            market = com.coflnet.flipper.data.BazaarApi.fetch();
        }
        if (market == null || market.products.isEmpty()) {
            message("§cMarktdaten konnten nicht geladen werden (API-Fehler?).");
            return 1;
        }
        long purse = ScreenUtil.readPurse();
        double bankroll = purse > 0 ? Math.max(0, purse - CONFIG.reserveCoins) : 100_000_000;
        List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.compute(market, CONFIG, bankroll, java.util.Set.of(), 10);
        message("§6§l=== Top Flips §7(Bankroll: §f" + ScreenUtil.formatCoins(bankroll) + "§7) ===");
        int shown = 0;
        for (FlipAnalyzer.FlipPlan plan : plans) {
            if (shown++ >= 10) {
                break;
            }
            message("§7" + plan.displayName + " §f" + plan.qty + "x §7@ §f" + ScreenUtil.formatPrice(plan.buyPrice)
                    + " §8→ §f" + ScreenUtil.formatPrice(plan.sellPrice)
                    + " §7Marge §a" + String.format(java.util.Locale.US, "%.1f%%", plan.marginPercent)
                    + " §7Gewinn §a" + ScreenUtil.formatCoins(plan.expectedProfit()));
        }
        if (plans.isEmpty()) {
            message("§7Keine lohnenden Flips gefunden.");
        }
        return 1;
    }

    private static void message(String text) {
        CoflModClient.displayModMessage(Component.literal(text));
    }
}
