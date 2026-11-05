package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AhItemValueTooltip {

    private static final Map<String, CachedValue> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_COMPUTE = new ConcurrentHashMap<>();
    private static final java.util.IdentityHashMap<ItemStack, CachedItem> STACK_ITEMS =
            new java.util.IdentityHashMap<>();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "skycofl-ah-tooltip");
        thread.setDaemon(true);
        return thread;
    });

    private record CachedValue(AhValuation.Estimate estimate, long at) {
    }

    private record CachedItem(Object data, Object enchants, Object name, int count, AhItem item) {
    }

    private AhItemValueTooltip() {
    }

    public static void append(ItemStack stack, List<Component> lines) {
        if (!AhFlipperMod.CONFIG.tooltipValue || stack == null || stack.isEmpty()) {
            return;
        }
        if (!AhItem.hasSkyBlockId(stack)) {
            return;
        }
        AhItem item = cachedItem(stack);
        List<String> rawLines = new ArrayList<>();
        for (Component line : lines) {
            rawLines.add(line.getString());
        }
        AhItem.CoflValues values = AhItem.parseCoflValues(rawLines);
        values.merge(AhItem.coflValuesFromDescription(stack));
        item.applyCoflValues(values);
        if (item.coflValue() <= 0) {
            AhScreenUtil.loadCoflDescriptions();
            lines.add(Component.literal("\u00a77[AH-Flipper] \u00a7eCofl-Median wird geladen..."));
            return;
        }
        String key = AhValuation.fingerprint(item, "");
        if (key.isBlank()) {
            return;
        }
        CachedValue cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at < 2 * 60_000L) {
            appendLines(lines, cached.estimate);
            return;
        }
        AhValuation.Estimate hot = AhValuation.peek(item, "");
        if (hot != null) {
            CACHE.put(key, new CachedValue(hot, System.currentTimeMillis()));
            appendLines(lines, hot);
            return;
        }
        lines.add(Component.literal("\u00a77[AH-Flipper] \u00a7eWert wird berechnet..."));
        maybeCompute(key, item);
    }

    private static AhItem cachedItem(ItemStack stack) {
        Object data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        Object enchants = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
        Object name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        int count = stack.getCount();
        synchronized (STACK_ITEMS) {
            CachedItem cached = STACK_ITEMS.get(stack);
            if (cached != null && cached.data() == data && cached.enchants() == enchants
                    && cached.name() == name && cached.count() == count) {
                return cached.item();
            }
        }
        AhItem item = AhItem.fromStack(stack);
        synchronized (STACK_ITEMS) {
            if (STACK_ITEMS.size() > 256) {
                STACK_ITEMS.clear();
            }
            STACK_ITEMS.put(stack, new CachedItem(data, enchants, name, count, item));
        }
        return item;
    }

    private static void appendLines(List<Component> lines, AhValuation.Estimate est) {
        if (est.estimated <= 1 && est.reference <= 0
                && est.comparableAverage <= 0 && !est.comparableAttempted) {
            lines.add(Component.literal("\u00a76[AH-Flipper] \u00a77Wert: \u00a7c" + est.source));
            return;
        }
        lines.add(Component.literal("\u00a76[AH-Flipper] \u00a77Wert: \u00a7f"
                + ScreenUtil.formatCoins(est.estimated) + " \u00a77(" + est.source + ")"));
        long coflChosen = est.coflChosen > 0
                ? est.coflChosen
                : (est.coflMed > 0 && est.coflLbin > 0
                ? Math.min(est.coflMed, est.coflLbin)
                : Math.max(est.coflMed, est.coflLbin));
        if (coflChosen > 0) {
            String pick = est.coflPick == null || est.coflPick.isBlank()
                    ? "Med" : est.coflPick;
            lines.add(Component.literal("\u00a77  Cofl genommen: \u00a7f"
                    + ScreenUtil.formatCoins(coflChosen) + " \u00a77(" + pick + ")"));
        }
        if (est.coflMed > 0) {
            lines.add(Component.literal("\u00a77  Cofl-Median: \u00a7f"
                    + ScreenUtil.formatCoins(est.coflMed)));
        }
        if (est.coflLbin > 0) {
            lines.add(Component.literal("\u00a77  Cofl-LBin: \u00a7f"
                    + ScreenUtil.formatCoins(est.coflLbin)));
        }
        if (est.coflLbin > 0 && est.coflMed > 0 && est.coflLbin < est.coflMed
                && est.coflLbinCount >= 0) {
            lines.add(Component.literal("\u00a77  LBin-Check: \u00a7f"
                    + est.coflLbinCount + (est.coflLbinCount == 1 ? " Auktion" : " Auktionen")
                    + " @ " + ScreenUtil.formatCoins(est.coflLbin) + " \u00a77-> "
                    + (est.coflLbinCount > 1 ? "\u00a7aLBin" : "\u00a7eMed")));
        }
        if (!AhFlipperMod.CONFIG.tooltipDetails) {
            return;
        }
        StringBuilder info = new StringBuilder("\u00a77  Referenz: \u00a7f")
                .append(ScreenUtil.formatCoins(est.reference));
        if (est.addonValue > 0) {
            info.append(" \u00a77| Addons: \u00a7f")
                    .append(ScreenUtil.formatCoins(est.addonValue));
        }
        if (est.gemValue > 0) {
            info.append(" \u00a77| Gems: \u00a7f")
                    .append(ScreenUtil.formatCoins(est.gemValue));
        }
        if (est.slotUnlockValue > 0) {
            info.append(" \u00a77| Slots: \u00a7f")
                    .append(ScreenUtil.formatCoins(est.slotUnlockValue));
        }
        lines.add(Component.literal(info.toString()));
        if (est.comparableAverage > 0) {
            lines.add(Component.literal("\u00a77  \u00d8 Addon-Vergleich: \u00a7f"
                    + ScreenUtil.formatCoins(est.comparableAverage)
                    + " \u00a77(" + est.comparableSales + " Sales, "
                    + est.comparableExcluded + " Ausrei\u00dfer)"
                    + (est.comparableBelowReference ? " \u00a7c(unter Referenz)" : "")));
        } else if (est.comparableAttempted) {
            lines.add(Component.literal("\u00a77  \u00d8 Addon-Vergleich: \u00a7c"
                    + (est.comparableNote == null || est.comparableNote.isBlank()
                    ? "keine Daten" : est.comparableNote)));
        }
        if (est.comparableBin > 0) {
            lines.add(Component.literal("\u00a77  Lowest Addon-BIN: \u00a7f"
                    + ScreenUtil.formatCoins(est.comparableBin)));
        }
        if (est.askSafetyValue > 0) {
            lines.add(Component.literal("\u00a77  BestAsk-Sicherheit: \u00a7f"
                    + ScreenUtil.formatCoins(est.askSafetyValue)
                    + " \u00a77(50/50 mit BestBid)"));
        }
        if (est.ceiling > 0 && est.source.contains("gecappt")) {
            lines.add(Component.literal("\u00a77  Deckel: \u00a7f"
                    + ScreenUtil.formatCoins(est.ceiling)));
        }
    }

    private static void maybeCompute(String key, AhItem item) {
        long now = System.currentTimeMillis();
        Long last = LAST_COMPUTE.putIfAbsent(key, now);
        if (last != null && now - last < 5000) {
            return;
        }
        if (CACHE.size() > 600) {
            CACHE.clear();
            LAST_COMPUTE.clear();
        }
        WORKERS.submit(() -> {
            try {
                AhValuation.Estimate estimate = AhValuation.estimate(item, "");
                CACHE.put(key, new CachedValue(estimate, System.currentTimeMillis()));
            } catch (Exception ignored) {
            }
        });
    }
}
