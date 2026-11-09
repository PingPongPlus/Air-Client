package com.coflnet.ahflipper;

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

    /** Start of every line this class adds (after formatting codes are stripped). */
    private static final String[] OWN_PREFIXES = {
            "[AH-Flipper]", "Cofl genommen:", "Cofl-Median:", "Cofl-LBin:", "LBin-Check:",
            "Referenz:", "Ø Addon-Vergleich:", "Lowest Addon-BIN:",
            "BestAsk-Sicherheit:", "Deckel:", "Seltene Addons:", "Candy-Abzug:"
    };

    /**
     * True for a line of OUR tooltip block. Those lines show our own (rounded)
     * numbers and must never be read back as Cofl values.
     */
    public static boolean isOwnLine(String cleanLine) {
        if (cleanLine == null) {
            return false;
        }
        String text = cleanLine.trim();
        for (String prefix : OWN_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item exactly as the tooltip values it: NBT plus the Cofl Med/LBin from
     * the tooltip lines and the Cofl description. {@code rawLines} may be null.
     */
    public static AhItem valuedItem(ItemStack stack, List<String> rawLines) {
        AhItem item = AhItem.fromStack(stack);
        applyCofl(item, stack, rawLines);
        return item;
    }

    private static void applyCofl(AhItem item, ItemStack stack, List<String> rawLines) {
        AhItem.CoflValues values = AhItem.parseCoflValues(
                rawLines == null ? List.of() : rawLines);
        values.merge(AhItem.coflValuesFromDescription(stack));
        item.applyCoflValues(values);
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
        applyCofl(item, stack, rawLines);
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

    /** Like formatCoins, but 2.75M stays 2.75M instead of becoming 2.8M. */
    static String coins(long value) {
        double abs = Math.abs((double) value);
        String unit;
        double scaled;
        if (abs >= 1_000_000_000) {
            unit = "B";
            scaled = value / 1_000_000_000.0;
        } else if (abs >= 1_000_000) {
            unit = "M";
            scaled = value / 1_000_000.0;
        } else if (abs >= 1_000) {
            unit = "K";
            scaled = value / 1_000.0;
        } else {
            return Long.toString(value);
        }
        String text = String.format(java.util.Locale.US, "%.3f", scaled);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text + unit;
    }

    private static void appendLines(List<Component> lines, AhValuation.Estimate est) {
        if (est.estimated <= 1 && est.reference <= 0
                && est.comparableAverage <= 0 && !est.comparableAttempted) {
            lines.add(Component.literal("\u00a76[AH-Flipper] \u00a77Wert: \u00a7c" + est.source));
            return;
        }
        lines.add(Component.literal("\u00a76[AH-Flipper] \u00a77Wert: \u00a7f"
                + coins(est.estimated) + " \u00a77(" + est.source + ")"));
        if (est.rareAddonDeduction > 0) {
            StringBuilder rare = new StringBuilder();
            for (AhRareAddons.Addon addon : est.rareAddons) {
                if (!rare.isEmpty()) {
                    rare.append(", ");
                }
                rare.append(addon.label()).append(" ").append(addon.sales()).append("x");
            }
            lines.add(Component.literal("\u00a77  Seltene Addons: \u00a7c-"
                    + coins(est.rareAddonDeduction) + " \u00a77(" + Math.round(
                    AhFlipperMod.CONFIG.rareAddonDeductPercent) + "% von "
                    + coins(est.rareAddonValue) + ": " + rare + ")"));
        }
        if (est.candyDiscount > 0) {
            lines.add(Component.literal("\u00a77  Candy-Abzug: \u00a7c-"
                    + coins(est.candyDiscount)));
        }
        long coflChosen = est.coflChosen > 0
                ? est.coflChosen
                : (est.coflMed > 0 && est.coflLbin > 0
                ? Math.min(est.coflMed, est.coflLbin)
                : Math.max(est.coflMed, est.coflLbin));
        if (coflChosen > 0) {
            String pick = est.coflPick == null || est.coflPick.isBlank()
                    ? "Med" : est.coflPick;
            lines.add(Component.literal("\u00a77  Cofl genommen: \u00a7f"
                    + coins(coflChosen) + " \u00a77(" + pick + ")"));
        }
        if (est.coflMed > 0) {
            lines.add(Component.literal("\u00a77  Cofl-Median: \u00a7f"
                    + coins(est.coflMed)));
        }
        if (est.coflLbin > 0) {
            lines.add(Component.literal("\u00a77  Cofl-LBin: \u00a7f"
                    + coins(est.coflLbin)));
        }
        if (est.coflLbin > 0 && est.coflMed > 0 && est.coflLbin < est.coflMed
                && est.coflLbinCount >= 0) {
            lines.add(Component.literal("\u00a77  LBin-Check: \u00a7f"
                    + est.coflLbinCount + (est.coflLbinCount == 1 ? " Auktion" : " Auktionen")
                    + " @ " + coins(est.coflLbin) + " \u00a77-> "
                    + (est.coflLbinCount > 1 ? "\u00a7aLBin" : "\u00a7eMed")));
        }
        if (!AhFlipperMod.CONFIG.tooltipDetails) {
            return;
        }
        StringBuilder info = new StringBuilder("\u00a77  Referenz: \u00a7f")
                .append(coins(est.reference));
        if (est.addonValue > 0) {
            info.append(" \u00a77| Addons: \u00a7f")
                    .append(coins(est.addonValue));
        }
        if (est.gemValue > 0) {
            info.append(" \u00a77| Gems: \u00a7f")
                    .append(coins(est.gemValue));
        }
        if (est.slotUnlockValue > 0) {
            info.append(" \u00a77| Slots: \u00a7f")
                    .append(coins(est.slotUnlockValue));
        }
        lines.add(Component.literal(info.toString()));
        if (est.comparableAverage > 0) {
            lines.add(Component.literal("\u00a77  \u00d8 Addon-Vergleich: \u00a7f"
                    + coins(est.comparableAverage)
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
                    + coins(est.comparableBin)));
        }
        if (est.askSafetyValue > 0) {
            lines.add(Component.literal("\u00a77  BestAsk-Sicherheit: \u00a7f"
                    + coins(est.askSafetyValue)
                    + " \u00a77(50/50 mit BestBid)"));
        }
        if (est.ceiling > 0 && est.source.contains("gecappt")) {
            lines.add(Component.literal("\u00a77  Deckel: \u00a7f"
                    + coins(est.ceiling)));
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
