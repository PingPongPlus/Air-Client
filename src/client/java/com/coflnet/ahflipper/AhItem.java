package com.coflnet.ahflipper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AhItem {
    public static final String ASPECT_OF_THE_VOID = "ASPECT_OF_THE_VOID";
    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl\\s+(\\d+)\\]");
    private static final Pattern GEM_KEY = Pattern.compile("^([A-Za-z]+)_(\\d+)$");
    private static final Set<String> CATEGORY_GEM_SLOTS = Set.of(
            "COMBAT", "OFFENSIVE", "DEFENSIVE", "MINING", "UNIVERSAL", "CHISEL");

    public String tag = "";
    public String displayName = "";
    public int count = 1;
    public final Map<String, Integer> enchants = new LinkedHashMap<>();
    public final Set<String> abilityScrolls = new LinkedHashSet<>();
    public String reforge = "";
    public int stars;
    public int hpb;
    public boolean recomb;
    public int artOfWar;
    public int woodSingularity;
    public String petItem = "";
    public int petLevel;
    public int petCandies;
    public String petRarity = "";
    public String killKey = "";
    public int kills;
    public boolean ethermerge;
    public int tunedTransmission;
    public final Map<String, Integer> gemstones = new LinkedHashMap<>();
    public final Set<String> unlockedGemSlots = new LinkedHashSet<>();

    public static AhItem fromStack(ItemStack stack) {
        AhItem item = new AhItem();
        if (stack == null || stack.isEmpty()) {
            return item;
        }
        item.displayName = stack.getHoverName().getString();
        item.count = stack.getCount();
        CompoundTag extra = extraAttributes(stack);
        if (extra != null) {
            item.tag = extra.getString("id").orElse("").toUpperCase(Locale.ROOT);
            item.reforge = extra.getString("modifier").orElse("");
            item.hpb = extra.getInt("hot_potato_count").orElse(0);
            item.recomb = extra.getInt("rarity_upgrades").orElse(0) > 0;
            item.stars = Math.max(extra.getInt("upgrade_level").orElse(0),
                    extra.getInt("dungeon_item_level").orElse(0));
            item.artOfWar = extra.getInt("art_of_war_count").orElse(0);
            item.woodSingularity = extra.getInt("wood_singularity_count").orElse(0);
            item.ethermerge = nbtFlag(extra, "ethermerge");
            item.tunedTransmission = extra.getInt("tuned_transmission").orElse(0);
            CompoundTag gems = extra.getCompound("gems").orElse(null);
            if (gems != null) {
                parseGemsTag(gems, item.gemstones, item.unlockedGemSlots);
            }

            CompoundTag enchantments = extra.getCompound("enchantments").orElse(null);
            if (enchantments != null) {
                for (String key : enchantments.keySet()) {
                    item.enchants.merge(key.toLowerCase(Locale.ROOT),
                            enchantments.getInt(key).orElse(0), Math::max);
                }
            }
            item.petLevel = petLevel(item.displayName);
            String petTier = extra.getString("tier").orElse("");
            String heldItem = extra.getString("heldItem").orElse("");
            CompoundTag petInfo = extra.getCompound("petInfo").orElse(null);
            if (petInfo != null) {
                petTier = petInfo.getString("tier").orElse(petTier);
                heldItem = petInfo.getString("heldItem").orElse(heldItem);
            }
            String petInfoRaw = extra.getString("petInfo").orElse("");
            if (!petInfoRaw.isBlank()) {
                try {
                    JsonObject json = JsonParser.parseString(petInfoRaw).getAsJsonObject();
                    petTier = jsonString(json, "tier", petTier);
                    heldItem = jsonString(json, "heldItem", heldItem);
                } catch (Exception ignored) {
                }
            }
            item.petRarity = petTier.toUpperCase(Locale.ROOT);
            item.petItem = heldItem;
            String petInfoJson = extra.getString("petInfo").orElse("");
            if (!petInfoJson.isBlank()) {
                try {
                    JsonObject petJson = JsonParser.parseString(petInfoJson).getAsJsonObject();
                    item.petCandies = jsonInt(petJson, "candyUsed");
                } catch (Exception ignored) {
                }
            }
            ListTag scrolls = extra.getList("ability_scroll").orElse(null);
            if (scrolls != null) {
                for (int i = 0; i < scrolls.size(); i++) {
                    scrolls.getString(i).ifPresent(item.abilityScrolls::add);
                }
            }
            for (String key : extra.keySet()) {
                if (key.endsWith("_kills")) {
                    item.killKey = key;
                    item.kills = extra.getInt(key).orElse(0);
                    break;
                }
            }
        }

        ItemEnchantments componentEnchants = stack.get(DataComponents.ENCHANTMENTS);
        if (componentEnchants != null) {
            for (Map.Entry<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>, Integer> entry
                    : componentEnchants.entrySet()) {
                String key = entry.getKey().unwrapKey()
                        .map(k -> k.identifier().getPath())
                        .orElse("");
                if (!key.isBlank()) {
                    item.enchants.merge(key, entry.getValue(), Math::max);
                }
            }
        }

        if (item.tag.isBlank()) {
            item.tag = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toUpperCase(Locale.ROOT);
        }
        return item;
    }

    public static AhItem fromAuction(AhMarketData.AuctionDetails details) {
        AhItem item = new AhItem();
        if (details == null) {
            return item;
        }
        item.tag = details.tag == null ? "" : details.tag.toUpperCase(Locale.ROOT);
        item.displayName = details.itemName == null ? "" : details.itemName;
        item.count = Math.max(1, details.count);
        item.reforge = details.reforge == null || details.reforge.equalsIgnoreCase("none")
                ? "" : details.reforge;
        item.enchants.putAll(details.enchants);
        item.hpb = nbtInt(details, "hpc", "hot_potato_count");
        item.recomb = nbtInt(details, "rarity_upgrades") > 0;
        item.stars = Math.max(nbtInt(details, "upgrade_level"),
                nbtInt(details, "dungeon_item_level"));
        item.artOfWar = nbtInt(details, "art_of_war_count");
        item.woodSingularity = nbtInt(details, "wood_singularity_count");
        item.ethermerge = nbtFlag(details, "ethermerge");
        item.tunedTransmission = nbtInt(details, "tuned_transmission");
        parseFlatGems(details.flatNbt, item.gemstones, item.unlockedGemSlots);
        item.petLevel = petLevel(details.itemName);
        item.petRarity = petRarity(details);
        item.petItem = nbtText(details, "heldItem");
        String petInfoRaw = nbtText(details, "petInfo");
        if (!petInfoRaw.isBlank()) {
            try {
                JsonObject petJson = JsonParser.parseString(petInfoRaw).getAsJsonObject();
                item.petCandies = jsonInt(petJson, "candyUsed");
                if (item.petItem.isBlank()) {
                    item.petItem = jsonString(petJson, "heldItem", "");
                }
            } catch (Exception ignored) {
            }
        }
        String scrolls = nbtText(details, "ability_scroll");
        if (!scrolls.isBlank()) {
            for (String scroll : scrolls.split("\\s+")) {
                if (!scroll.isBlank()) {
                    item.abilityScrolls.add(scroll);
                }
            }
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : details.flatNbt.entrySet()) {
            if (entry.getKey().endsWith("_kills") && entry.getValue() != null
                    && !entry.getValue().isJsonNull()) {
                item.killKey = entry.getKey();
                try {
                    item.kills = (int) Double.parseDouble(entry.getValue().getAsString());
                } catch (Exception ignored) {
                }
                break;
            }
        }
        return item;
    }

    public boolean isPet() {
        return (tag != null && tag.toUpperCase(Locale.ROOT).startsWith("PET_")) || petLevel > 0;
    }

    public boolean isAspectOfTheVoid() {
        return tag != null && ASPECT_OF_THE_VOID.equalsIgnoreCase(tag.trim());
    }

    public String petFilter() {
        if (petLevel <= 0 || petRarity == null || petRarity.isBlank()) {
            return "";
        }
        return "&PetLevel=" + petLevel + "&Rarity=" + petRarity;
    }

    public static int petLevel(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return 0;
        }
        Matcher matcher = PET_LEVEL.matcher(itemName);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static String petRarity(AhMarketData.AuctionDetails details) {
        if (details == null) {
            return "";
        }
        String rarity = details.tier == null ? "" : details.tier;
        if (rarity.isBlank()) {
            rarity = nbtText(details, "tier");
        }
        return rarity.toUpperCase(Locale.ROOT);
    }

    private static String nbtText(AhMarketData.AuctionDetails details, String key) {
        if (details.flatNbt.has(key) && !details.flatNbt.get(key).isJsonNull()) {
            try {
                com.google.gson.JsonElement element = details.flatNbt.get(key);
                if (element.isJsonArray()) {
                    StringBuilder builder = new StringBuilder();
                    for (com.google.gson.JsonElement part : element.getAsJsonArray()) {
                        if (part != null && !part.isJsonNull()) {
                            if (builder.length() > 0) {
                                builder.append(' ');
                            }
                            builder.append(part.getAsString());
                        }
                    }
                    return builder.toString();
                }
                return element.getAsString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String jsonString(JsonObject json, String key, String fallback) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            try {
                return json.get(key).getAsString();
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static int jsonInt(JsonObject json, String key) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            try {
                return json.get(key).getAsInt();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static int nbtInt(AhMarketData.AuctionDetails details, String... keys) {
        for (String key : keys) {
            if (details.flatNbt.has(key) && !details.flatNbt.get(key).isJsonNull()) {
                try {
                    return (int) Double.parseDouble(details.flatNbt.get(key).getAsString());
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private static boolean nbtFlag(AhMarketData.AuctionDetails details, String key) {
        if (nbtInt(details, key) > 0) {
            return true;
        }
        String value = nbtText(details, key);
        return !value.isBlank() && !"0".equals(value)
                && !"false".equalsIgnoreCase(value);
    }

    private static boolean nbtFlag(CompoundTag tag, String key) {
        if (tag.getInt(key).orElse(0) > 0) {
            return true;
        }
        return tag.getString(key)
                .map(value -> !value.isBlank() && !"0".equals(value)
                        && !"false".equalsIgnoreCase(value))
                .orElse(false);
    }

    private static void parseGemsTag(CompoundTag gems, Map<String, Integer> out,
                                     Set<String> unlocked) {
        for (String key : gems.keySet()) {
            Matcher matcher = GEM_KEY.matcher(key);
            if (!matcher.matches()) {
                continue;
            }
            String slotType = matcher.group(1).toUpperCase(Locale.ROOT);
            String tier = gems.getString(key).orElse("");
            if (tier.isBlank()) {
                CompoundTag obj = gems.getCompound(key).orElse(null);
                if (obj != null) {
                    tier = obj.getString("quality").orElse("");
                }
            }
            if (tier.isBlank()) {
                continue;
            }
            String gemType = CATEGORY_GEM_SLOTS.contains(slotType)
                    ? gems.getString(key + "_gem").orElse("") : slotType;
            if (gemType.isBlank()) {
                gemType = slotType;
            }
            out.merge(tier.toUpperCase(Locale.ROOT) + "_"
                    + gemType.toUpperCase(Locale.ROOT) + "_GEM", 1, Integer::sum);
        }
        ListTag slots = gems.getList("unlocked_slots").orElse(null);
        if (slots != null) {
            for (int i = 0; i < slots.size(); i++) {
                slots.getString(i).ifPresent(slot -> {
                    if (!slot.isBlank()) {
                        unlocked.add(slot.trim());
                    }
                });
            }
        }
    }

    public static void parseFlatGems(JsonObject nbt, Map<String, Integer> out,
                                     Set<String> unlocked) {
        if (nbt == null) {
            return;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : nbt.entrySet()) {
            String key = entry.getKey();
            com.google.gson.JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            Matcher matcher = GEM_KEY.matcher(key);
            if (matcher.matches()) {
                String tier = value.getAsString().trim();
                if (tier.isBlank()) {
                    continue;
                }
                String slotType = matcher.group(1).toUpperCase(Locale.ROOT);
                String gemType = CATEGORY_GEM_SLOTS.contains(slotType)
                        ? jsonElementString(nbt, key + "_gem") : slotType;
                if (gemType == null || gemType.isBlank()) {
                    gemType = slotType;
                }
                if (out != null) {
                    out.merge(tier.toUpperCase(Locale.ROOT) + "_"
                            + gemType.toUpperCase(Locale.ROOT) + "_GEM", 1, Integer::sum);
                }
            } else if ("unlocked_slots".equalsIgnoreCase(key) && unlocked != null) {
                for (String token : value.getAsString().split("[,\\s]+")) {
                    if (!token.isBlank()) {
                        unlocked.add(token.trim());
                    }
                }
            }
        }
    }

    private static String jsonElementString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String rawCustomData(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return "<none>";
        }
        String raw = customData.copyTag().toString();
        return raw.length() > 2000 ? raw.substring(0, 2000) + "..." : raw;
    }

    private static CompoundTag extraAttributes(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return null;
        }
        CompoundTag root = customData.copyTag();
        CompoundTag extra = root.getCompound("ExtraAttributes").orElse(null);
        if (extra != null) {
            return extra;
        }
        CompoundTag components = root.getCompound("components").orElse(null);
        if (components != null) {
            CompoundTag custom = components.getCompound("minecraft:custom_data").orElse(null);
            if (custom != null) {
                extra = custom.getCompound("ExtraAttributes").orElse(null);
                if (extra != null) {
                    return extra;
                }
            }
        }
        return root;
    }

    public static boolean hasSkyBlockId(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return false;
        }
        CompoundTag root = customData.copyTag();
        CompoundTag extra = root.getCompound("ExtraAttributes").orElse(null);
        if (extra != null && extra.getString("id").isPresent()
                && !extra.getString("id").orElse("").isBlank()) {
            return true;
        }
        CompoundTag components = root.getCompound("components").orElse(null);
        if (components != null) {
            CompoundTag custom = components.getCompound("minecraft:custom_data").orElse(null);
            if (custom != null) {
                CompoundTag extra2 = custom.getCompound("ExtraAttributes").orElse(null);
                if (extra2 != null && extra2.getString("id").isPresent()
                        && !extra2.getString("id").orElse("").isBlank()) {
                    return true;
                }
            }
        }
        return root.getString("id").isPresent() && !root.getString("id").orElse("").isBlank();
    }
}
