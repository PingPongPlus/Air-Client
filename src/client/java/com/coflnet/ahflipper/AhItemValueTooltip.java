package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AhItemValueTooltip {

    private static final Map<String, CachedValue> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_COMPUTE = new ConcurrentHashMap<>();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "skycofl-ah-tooltip");
        thread.setDaemon(true);
        return thread;
    });

    private record CachedValue(AhValuation.Estimate estimate, long at) {
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
        AhItem item = AhItem.fromStack(stack);
        String key = fingerprint(item);
        if (key.isBlank()) {
            return;
        }
        CachedValue cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at < 2 * 60_000L) {
            appendLines(lines, cached.estimate);
            return;
        }
        lines.add(Component.literal("\u00a77[AH-Flipper] \u00a7eWert wird berechnet..."));
        maybeCompute(key, item);
    }

    private static void appendLines(List<Component> lines, AhValuation.Estimate est) {
        if (est.reference <= 0) {
            lines.add(Component.literal("\u00a76[AH-Flipper] \u00a77Wert: \u00a7c" + est.source));
            return;
        }
        lines.add(Component.literal("\u00a76[AH-Flipper] \u00a77Wert: \u00a7f"
                + ScreenUtil.formatCoins(est.estimated) + " \u00a77(" + est.source + ")"));
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
                    + est.comparableExcluded + " Ausrei\u00dfer)"));
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
                if (estimate.reference <= 0) {
                    estimate.source = "keine Daten";
                }
                CACHE.put(key, new CachedValue(estimate, System.currentTimeMillis()));
            } catch (Exception ignored) {
            }
        });
    }

    private static String fingerprint(AhItem item) {
        StringBuilder builder = new StringBuilder(item.tag);
        for (Map.Entry<String, Integer> enchant : item.enchants.entrySet()) {
            builder.append('|').append(enchant.getKey()).append(':').append(enchant.getValue());
        }
        builder.append("|hpb:").append(item.hpb)
                .append("|recomb:").append(item.recomb)
                .append("|stars:").append(item.stars)
                .append("|aow:").append(item.artOfWar)
                .append("|wood:").append(item.woodSingularity)
                .append("|ethermerge:").append(item.ethermerge)
                .append("|tuned:").append(item.tunedTransmission)
                .append("|gems:").append(item.gemstones)
                .append("|slots:").append(item.unlockedGemSlots)
                .append("|ref:").append(item.reforge.toLowerCase(Locale.ROOT))
                .append("|pet:").append(item.petLevel).append(':')
                .append(item.petRarity.toLowerCase(Locale.ROOT));
        return builder.toString();
    }
}
