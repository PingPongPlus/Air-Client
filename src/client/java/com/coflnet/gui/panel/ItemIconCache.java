package com.coflnet.gui.panel;

import com.coflnet.ahflipper.AhItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Render-thread-safe icon cache. Real items are learned from container slots. */
public final class ItemIconCache {
    private static final int MAX = 1000;
    private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();

    private ItemIconCache() {
    }

    public static void learn(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        try {
            AhItem item = AhItem.fromStack(stack);
            String key = cacheKey(item.tag);
            if (!key.isBlank()) {
                if (CACHE.size() >= MAX) {
                    CACHE.clear();
                }
                CACHE.putIfAbsent(key, stack.copyWithCount(1));
            }
            String nameKey = cacheKey(item.displayName);
            if (!nameKey.isBlank()) {
                if (CACHE.size() >= MAX) {
                    CACHE.clear();
                }
                CACHE.putIfAbsent(nameKey, stack.copyWithCount(1));
            }
        } catch (Exception ignored) {
        }
    }

    public static ItemStack icon(String tag) {
        return icon(tag, null);
    }

    public static ItemStack icon(String tag, String name) {
        if (tag != null && !tag.isBlank()) {
            ItemStack learned = CACHE.get(cacheKey(tag));
            if (learned != null) {
                return learned;
            }
        }
        if (name != null && !name.isBlank()) {
            ItemStack learned = CACHE.get(cacheKey(name));
            if (learned != null) {
                return learned;
            }
        }
        return fallback(tag == null || tag.isBlank() ? name : tag);
    }

    private static String cacheKey(String tag) {
        if (tag == null) {
            return "";
        }
        return tag.replace('\u00a7', ' ').replaceAll("\\s+", " ")
                .trim().toUpperCase(Locale.ROOT);
    }

    private static ItemStack fallback(String tag) {
        String upper = cacheKey(tag);
        if (upper.startsWith("ENCHANTMENT_") || upper.contains("ENCHANTED_BOOK")) {
            return Items.ENCHANTED_BOOK.getDefaultInstance();
        }
        if (upper.startsWith("ENCHANTED ")) {
            return fallback(upper.substring("ENCHANTED ".length()));
        }
        if (upper.startsWith("ENCHANTED_")) {
            return fallback(upper.substring("ENCHANTED_".length()));
        }
        if (upper.contains("WHEAT")) {
            return Items.WHEAT.getDefaultInstance();
        }
        if (upper.contains("CARROT")) {
            return Items.CARROT.getDefaultInstance();
        }
        if (upper.contains("POTATO")) {
            return Items.POTATO.getDefaultInstance();
        }
        if (upper.contains("NETHER_STALK") || upper.contains("NETHER WART")) {
            return Items.NETHER_WART.getDefaultInstance();
        }
        if (upper.contains("SUGAR")) {
            return Items.SUGAR.getDefaultInstance();
        }
        if (upper.contains("CACTUS")) {
            return Items.CACTUS.getDefaultInstance();
        }
        if (upper.contains("MUSHROOM")) {
            return Items.RED_MUSHROOM.getDefaultInstance();
        }
        if (upper.contains("LEATHER")) {
            return Items.LEATHER.getDefaultInstance();
        }
        if (upper.contains("STRING")) {
            return Items.STRING.getDefaultInstance();
        }
        if (upper.contains("FEATHER")) {
            return Items.FEATHER.getDefaultInstance();
        }
        if (upper.contains("BONE")) {
            return Items.BONE.getDefaultInstance();
        }
        if (upper.contains("GUNPOWDER")) {
            return Items.GUNPOWDER.getDefaultInstance();
        }
        if (upper.contains("GLOWSTONE")) {
            return Items.GLOWSTONE.getDefaultInstance();
        }
        if (upper.contains("ENCHANTMENT") || upper.contains("BOOK")) {
            return Items.ENCHANTED_BOOK.getDefaultInstance();
        }
        if (upper.contains("SKIN") || upper.contains("HEAD")) {
            return Items.PLAYER_HEAD.getDefaultInstance();
        }
        if (upper.contains("SKULL")) {
            return Items.PLAYER_HEAD.getDefaultInstance();
        }
        if (upper.contains("ESSENCE")) {
            return Items.GLOWSTONE_DUST.getDefaultInstance();
        }
        if (upper.contains("COOKIE")) {
            return Items.COOKIE.getDefaultInstance();
        }
        if (upper.contains("GEMSTONE") || upper.contains("GEM")) {
            return Items.EMERALD.getDefaultInstance();
        }
        if (upper.contains("TALISMAN") || upper.contains("RING")
                || upper.contains("ARTIFACT")) {
            return Items.GOLD_NUGGET.getDefaultInstance();
        }
        if (upper.contains("MINION")) {
            return Items.PLAYER_HEAD.getDefaultInstance();
        }
        if (upper.contains("DIAMOND")) {
            return Items.DIAMOND.getDefaultInstance();
        }
        if (upper.contains("EMERALD")) {
            return Items.EMERALD.getDefaultInstance();
        }
        if (upper.contains("GOLD")) {
            return Items.GOLD_INGOT.getDefaultInstance();
        }
        if (upper.contains("IRON")) {
            return Items.IRON_INGOT.getDefaultInstance();
        }
        if (upper.contains("LAPIS")) {
            return Items.LAPIS_LAZULI.getDefaultInstance();
        }
        if (upper.contains("REDSTONE")) {
            return Items.REDSTONE.getDefaultInstance();
        }
        if (upper.contains("QUARTZ")) {
            return Items.QUARTZ.getDefaultInstance();
        }
        if (upper.contains("OBSIDIAN")) {
            return Items.OBSIDIAN.getDefaultInstance();
        }
        if (upper.contains("END")) {
            return Items.ENDER_PEARL.getDefaultInstance();
        }
        if (upper.contains("BLAZE")) {
            return Items.BLAZE_ROD.getDefaultInstance();
        }
        if (upper.contains("SLIME")) {
            return Items.SLIME_BALL.getDefaultInstance();
        }
        if (upper.contains("FISH")) {
            return Items.COD.getDefaultInstance();
        }
        if (upper.contains("BOOK")) {
            return Items.BOOK.getDefaultInstance();
        }
        if (upper.contains("SWORD")) {
            return Items.IRON_SWORD.getDefaultInstance();
        }
        if (upper.contains("BOW")) {
            return Items.BOW.getDefaultInstance();
        }
        if (upper.contains("HELMET")) {
            return Items.IRON_HELMET.getDefaultInstance();
        }
        if (upper.contains("CHESTPLATE")) {
            return Items.IRON_CHESTPLATE.getDefaultInstance();
        }
        if (upper.contains("LEGGINGS")) {
            return Items.IRON_LEGGINGS.getDefaultInstance();
        }
        if (upper.contains("BOOTS")) {
            return Items.IRON_BOOTS.getDefaultInstance();
        }
        if (upper.contains("ARMOR")) {
            return Items.IRON_CHESTPLATE.getDefaultInstance();
        }
        if (upper.contains("POTION")) {
            return Items.POTION.getDefaultInstance();
        }
        if (upper.startsWith("PET_")) {
            return Items.PLAYER_HEAD.getDefaultInstance();
        }
        return Items.PAPER.getDefaultInstance();
    }
}


