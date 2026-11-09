package com.coflnet.ahflipper;

import CoflCore.handlers.DescriptionHandler;
import com.coflnet.CoflModClient;
import com.coflnet.flipper.trading.ScreenUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AhItem {
    public static final String ASPECT_OF_THE_VOID = "ASPECT_OF_THE_VOID";
    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl\\s+(\\d+)\\]");
    private static final Pattern RUNE_ROMAN = Pattern.compile(
            "(?i)\\b(I|II|III|IV|V|VI|VII|VIII|IX|X)\\s*$");
    private static final Pattern RUNE_ARABIC = Pattern.compile("\\b(\\d{1,2})\\s*$");
    private static final Pattern COFL_MED = Pattern.compile(
            "(?i)\\b(?:cofl\\s*)?med(?:ian)?\\s*:?\\s*[~\\u2248]?\\s*([0-9][0-9.,]*\\s*[kmbt]?)");
    private static final Pattern COFL_ESTIMATED = Pattern.compile(
            "(?i)\\bestimated\\s+value\\s*:?\\s*[~\\u2248]?\\s*([0-9][0-9.,]*\\s*[kmbt]?)");
    private static final Pattern COFL_LBIN = Pattern.compile(
            "(?i)\\b(?:lbin|lowest\\s+(?:addon[\\s-]*)?bin)\\b\\s*:?\\s*[~\\u2248]?\\s*([0-9][0-9.,]*\\s*[kmbt]?)");
    private static final Pattern GEM_KEY = Pattern.compile("^([A-Za-z]+)_(\\d+)$");
    private static final Set<String> CATEGORY_GEM_SLOTS = Set.of(
            "COMBAT", "OFFENSIVE", "DEFENSIVE", "MINING", "UNIVERSAL", "CHISEL");

    public static final class CoflValues {
        public long med;
        public long estimated;
        public long lbin;

        public long median() {
            return med > 0 ? med : estimated;
        }

        public long best() {
            long median = median();
            if (median > 0 && lbin > 0) {
                return Math.min(median, lbin);
            }
            return median > 0 ? median : lbin;
        }

        public String pick() {
            long median = median();
            if (median <= 0 && lbin <= 0) {
                return "";
            }
            if (median <= 0) {
                return "LBin";
            }
            if (lbin <= 0) {
                return "Med";
            }
            if (lbin < median) {
                return "LBin";
            }
            if (median < lbin) {
                return "Med";
            }
            return "Med/LBin";
        }

        public void merge(CoflValues other) {
            if (other == null) {
                return;
            }
            med = Math.max(med, other.med);
            estimated = Math.max(estimated, other.estimated);
            lbin = Math.max(lbin, other.lbin);
        }
    }

    public String tag = "";
    public String displayName = "";
    public int count = 1;
    public final Map<String, Integer> enchants = new LinkedHashMap<>();
    public final Set<String> abilityScrolls = new LinkedHashSet<>();
    public String reforge = "";
    public int stars;
    public int hpb;
    public int fumingHpb;
    public boolean recomb;
    public int artOfWar;
    public int woodSingularity;
    public String dyeItem = "";
    public String skin = "";
    public String petSkin = "";
    public String petItem = "";
    public int petLevel;
    public int runeLevel;
    public int petCandies;
    public String petRarity = "";
    public long coinValue;
    public long coflMed;
    public long coflLbin;
    public String coflPick = "";
    public int coflLbinCount = -1;
    /** LBin exactly as Cofl showed it (coflLbin may be replaced by the real BIN price). */
    public long coflLbinShown;
    /** How the count was checked: true = exact price (addon comparison), false = +-search. */
    public boolean coflLbinExact;
    public String killKey = "";
    public int kills;
    public boolean ethermerge;
    public int tunedTransmission;
    public final Map<String, Integer> gemstones = new LinkedHashMap<>();
    public final Set<String> unlockedGemSlots = new LinkedHashSet<>();

    public boolean needsLbinCountCheck() {
        return coflMed > 0 && coflLbin > 0 && coflLbin < coflMed && coflLbinCount < 0;
    }

    public long coflValue() {
        long median = coflMed;
        if (median > 0 && coflLbin > 0) {
            if (coflLbin < median) {
                int required = Math.max(1, AhFlipperMod.CONFIG.coflLbinMinCount);
                return coflLbinCount >= required ? coflLbin : median;
            }
            return Math.min(median, coflLbin);
        }
        return median > 0 ? median : coflLbin;
    }

    public void applyCoflValues(CoflValues values) {
        if (values == null) {
            return;
        }
        coflMed = values.median();
        coflLbin = values.lbin;
        coflLbinShown = values.lbin;
        coflPick = values.pick();
        coflLbinCount = -1;
    }

    public void applyLbinCount(int count) {
        coflLbinCount = count;
        if (coflMed > 0 && coflLbin > 0 && coflLbin < coflMed) {
            int required = Math.max(1, AhFlipperMod.CONFIG.coflLbinMinCount);
            coflPick = count >= required ? "LBin" : "Med";
        }
    }

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
            int potatoes = extra.getInt("hot_potato_count").orElse(0);
            item.hpb = Math.min(10, potatoes);
            item.fumingHpb = Math.max(0, potatoes - 10);
            item.fumingHpb = Math.max(item.fumingHpb,
                    extra.getInt("fuming_potato_count").orElse(0));
            item.dyeItem = extra.getString("dye_item").orElse("")
                    .trim().toUpperCase(Locale.ROOT);
            item.skin = extra.getString("skin").orElse("")
                    .trim().toUpperCase(Locale.ROOT);
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
            item.coinValue = coinValueFromNbt(extra);
            item.runeLevel = runeLevelFromNbt(extra);
            if (item.tag.isBlank()) {
                for (String key : extra.keySet()) {
                    if (isRuneNbtKey(key)) {
                        item.tag = key.toUpperCase(Locale.ROOT);
                        break;
                    }
                }
            }
            if (item.runeLevel <= 0) {
                item.runeLevel = runeLevel(item.displayName);
            }
            item.petLevel = petLevel(item.displayName);
            String petTier = extra.getString("tier").orElse("");
            String heldItem = extra.getString("heldItem").orElse("");
            String petSkin = "";
            String petType = "";
            CompoundTag petInfo = extra.getCompound("petInfo").orElse(null);
            if (petInfo != null) {
                petTier = petInfo.getString("tier").orElse(petTier);
                heldItem = petInfo.getString("heldItem").orElse(heldItem);
                petSkin = petInfo.getString("skin").orElse(petSkin);
                petType = petInfo.getString("type").orElse(petType);
            }
            String petInfoRaw = extra.getString("petInfo").orElse("");
            if (!petInfoRaw.isBlank()) {
                try {
                    JsonObject json = JsonParser.parseString(petInfoRaw).getAsJsonObject();
                    petTier = jsonString(json, "tier", petTier);
                    heldItem = jsonString(json, "heldItem", heldItem);
                    petSkin = jsonString(json, "skin", petSkin);
                    petType = jsonString(json, "type", petType);
                } catch (Exception ignored) {
                }
            }
            item.tag = marketTag(item.tag, extra, petType);
            if (item.runeLevel <= 0) {
                item.runeLevel = runeLevelFromRunes(extra);
            }
            item.petRarity = petTier.toUpperCase(Locale.ROOT);
            item.petItem = heldItem;
            item.petSkin = petSkin.trim().toUpperCase(Locale.ROOT);
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
        if (item.runeLevel <= 0) {
            item.runeLevel = runeLevel(item.displayName);
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

    public static AhItem fromStackWithTooltip(ItemStack stack) {
        AhItem item = fromStack(stack);
        if (stack == null || stack.isEmpty()) {
            return item;
        }
        List<String> lines = ScreenUtil.sync(() -> {
            List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.EMPTY,
                    Minecraft.getInstance().player, TooltipFlag.NORMAL);
            ItemTooltipCallback.EVENT.invoker().getTooltip(stack,
                    Item.TooltipContext.EMPTY, TooltipFlag.NORMAL, tooltip);
            List<String> result = new ArrayList<>();
            for (Component line : tooltip) {
                result.add(line.getString());
            }
            return result;
        });
        CoflValues values = parseCoflValues(lines);
        values.merge(coflValuesFromDescription(stack));
        item.applyCoflValues(values);
        return item;
    }

    public static AhItem resolveCofl(ItemStack stack, long timeoutMs) {
        AhItem item = fromStack(stack);
        if (stack == null || stack.isEmpty()) {
            return item;
        }
        AhScreenUtil.loadCoflDescriptions();
        long end = System.currentTimeMillis() + Math.max(250, timeoutMs);
        while (System.currentTimeMillis() < end) {
            item = fromStackWithTooltip(stack);
            if (item.coflValue() > 0) {
                // the LBin check runs inside AhValuation: only there it is known
                // whether the item gets an addon comparison (exact price check)
                return item;
            }
            ScreenUtil.sleep(100);
        }
        return item;
    }

    public static long resolveCoflMed(ItemStack stack, long timeoutMs) {
        return resolveCofl(stack, timeoutMs).coflValue();
    }

    public static CoflValues coflValuesFromDescription(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new CoflValues();
        }
        try {
            String id = CoflModClient.getIdFromStack(stack);
            String lookupId = CoflModClient.uuidToOriginalUuid
                    .getOrDefault(id, id);
            DescriptionHandler.DescModification[] modifications =
                    DescriptionHandler.getTooltipData(lookupId);
            if (modifications == null && !lookupId.equals(id)) {
                modifications = DescriptionHandler.getTooltipData(id);
            }
            if (modifications == null) {
                return new CoflValues();
            }
            CoflValues values = new CoflValues();
            for (DescriptionHandler.DescModification modification : modifications) {
                if (modification == null || modification.value == null) {
                    continue;
                }
                values.merge(parseCoflValues(List.of(modification.value)));
            }
            return values;
        } catch (Exception ignored) {
        }
        return new CoflValues();
    }

    public static long coflMedFromDescription(ItemStack stack) {
        return coflValuesFromDescription(stack).best();
    }

    public static CoflValues parseCoflValues(List<String> lines) {
        CoflValues values = new CoflValues();
        if (lines == null || lines.isEmpty()) {
            return values;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String clean = ScreenUtil.cleanName(line);
            // Our own value lines ("Value-LBin: 2.8M", "Lowest Addon-BIN: ...") show
            // ROUNDED numbers. They used to be parsed as market values here and,
            // via Math.max in merge(), replaced e.g. the real 2.75M LBin by 2.8M,
            // so the LBin check found no auction and fell back to the median.
            if (AhItemValueTooltip.isOwnLine(clean)) {
                continue;
            }
            Matcher estimated = COFL_ESTIMATED.matcher(clean);
            if (estimated.find()) {
                values.estimated = Math.max(values.estimated,
                        parseCompactCoins(estimated.group(1)));
            }
            Matcher median = COFL_MED.matcher(clean);
            if (median.find()) {
                values.med = Math.max(values.med,
                        parseCompactCoins(median.group(1)));
            }
            Matcher lbin = COFL_LBIN.matcher(clean);
            if (lbin.find()) {
                values.lbin = Math.max(values.lbin,
                        parseCompactCoins(lbin.group(1)));
            }
        }
        return values;
    }

    public static long parseCoflMed(List<String> lines) {
        return parseCoflValues(lines).best();
    }

    private static long parseCompactCoins(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double multiplier = 1.0;
        char last = text.charAt(text.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b' || last == 't') {
            multiplier = switch (last) {
                case 'k' -> 1_000.0;
                case 'm' -> 1_000_000.0;
                case 'b' -> 1_000_000_000.0;
                default -> 1_000_000_000_000.0;
            };
            text = text.substring(0, text.length() - 1);
        } else {
            text = text.replace(".", "");
        }
        try {
            return Math.max(0, Math.round(Double.parseDouble(text) * multiplier));
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
        int potatoes = Math.max(nbtInt(details, "hpc"),
                nbtInt(details, "hot_potato_count"));
        item.hpb = Math.min(10, potatoes);
        item.fumingHpb = Math.max(0, potatoes - 10);
        item.fumingHpb = Math.max(item.fumingHpb,
                nbtInt(details, "fuming_potato_count"));
        item.dyeItem = nbtText(details, "dye_item").trim().toUpperCase(Locale.ROOT);
        item.skin = nbtText(details, "skin").trim().toUpperCase(Locale.ROOT);
        item.recomb = nbtInt(details, "rarity_upgrades") > 0;
        item.stars = Math.max(nbtInt(details, "upgrade_level"),
                nbtInt(details, "dungeon_item_level"));
        item.artOfWar = nbtInt(details, "art_of_war_count");
        item.woodSingularity = nbtInt(details, "wood_singularity_count");
        item.ethermerge = nbtFlag(details, "ethermerge");
        item.tunedTransmission = nbtInt(details, "tuned_transmission");
        parseFlatGems(details.flatNbt, item.gemstones, item.unlockedGemSlots);
        item.coinValue = coinValueFromFlat(details.flatNbt);
        item.runeLevel = runeLevelFromFlat(details.flatNbt);
        if (item.runeLevel <= 0) {
            item.runeLevel = runeLevel(details.itemName);
        }
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
                item.petSkin = jsonString(petJson, "skin", item.petSkin)
                        .trim().toUpperCase(Locale.ROOT);
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
        return isPetTag(tag) || petLevel > 0;
    }

    /**
     * PET_LION is a pet; PET_SKIN_* and PET_ITEM_* are normal items that
     * only share the prefix. They used to be treated as pets (and rejected).
     */
    public static boolean isPetTag(String tag) {
        if (tag == null) {
            return false;
        }
        String upper = tag.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("PET_") && !upper.startsWith("PET_SKIN_")
                && !upper.startsWith("PET_ITEM_");
    }

    /**
     * Hypixel stores every pet as id "PET" and every rune as id "RUNE" /
     * "UNIQUE_RUNE"; the price API only knows PET_LION, RUNE_MUSIC ... With
     * the raw id every price, sales and LBin lookup returned nothing, so all
     * pets/runes from a stack (lowballing, tooltip) were rejected.
     */
    private static String marketTag(String id, CompoundTag extra, String petType) {
        if (id == null || id.isBlank()) {
            return "";
        }
        if (id.equals("PET") && petType != null && !petType.isBlank()) {
            return "PET_" + petType.trim().toUpperCase(Locale.ROOT);
        }
        if ((id.equals("RUNE") || id.equals("UNIQUE_RUNE")) && extra != null) {
            CompoundTag runes = extra.getCompound("runes").orElse(null);
            if (runes != null) {
                for (String key : runes.keySet()) {
                    if (!key.isBlank()) {
                        return id + "_" + key.toUpperCase(Locale.ROOT);
                    }
                }
            }
        }
        return id;
    }

    private static int runeLevelFromRunes(CompoundTag extra) {
        if (extra == null) {
            return 0;
        }
        CompoundTag runes = extra.getCompound("runes").orElse(null);
        if (runes == null) {
            return 0;
        }
        for (String key : runes.keySet()) {
            int level = runes.getInt(key).orElse(0);
            if (level > 0) {
                return level;
            }
        }
        return 0;
    }

    public boolean isRune() {
        return runeLevel > 0 || isRuneTag(tag);
    }

    /**
     * Same item tag. A remembered purchase from before the PET_x / RUNE_x fix
     * may still carry the bare "PET" / "RUNE" id: that matches every pet/rune.
     */
    public static boolean sameTag(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.trim().toUpperCase(Locale.ROOT);
        String y = b.trim().toUpperCase(Locale.ROOT);
        if (x.equals(y)) {
            return true;
        }
        for (String generic : new String[]{"PET", "RUNE", "UNIQUE_RUNE"}) {
            if ((x.equals(generic) && y.startsWith(generic + "_"))
                    || (y.equals(generic) && x.startsWith(generic + "_"))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRuneTag(String tag) {
        String upper = tag == null ? "" : tag.toUpperCase(Locale.ROOT);
        return upper.equals("RUNE") || upper.startsWith("RUNE_")
                || upper.startsWith("UNIQUE_RUNE_");
    }

    private static final String[] COIN_KEYS = {
            "coins", "collected_coins", "stored_coins", "crown_coins",
            "avarice_coins", "coin_count", "winning_bid", "gilded_gifted_coins"
    };

    private static long coinValueFromNbt(CompoundTag tag) {
        if (tag == null) {
            return 0;
        }
        long total = 0;
        for (String key : COIN_KEYS) {
            if (!tag.contains(key)) {
                continue;
            }
            long value = tag.getLong(key).orElse(0L);
            if (value <= 0) {
                try {
                    value = Long.parseLong(tag.getString(key).orElse("0").trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (value > 0) {
                total += value;
            }
        }
        return total;
    }

    private static long coinValueFromFlat(JsonObject nbt) {
        if (nbt == null) {
            return 0;
        }
        long total = 0;
        for (String key : COIN_KEYS) {
            com.google.gson.JsonElement element = nbt.get(key);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                long value = element.getAsLong();
                if (value > 0) {
                    total += value;
                }
            } catch (Exception ignored) {
            }
        }
        return total;
    }

    public static String runeMarketTag(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return "";
        }
        String clean = itemName.replaceAll("\u00a7.", "").trim();
        Matcher matcher = Pattern.compile(
                "(?i)^(.+?)\\s+Rune\\s+(I|II|III|IV|V|VI|VII|VIII|IX|X|\\d{1,2})\\s*$")
                .matcher(clean);
        if (!matcher.find()) {
            return "";
        }
        String base = matcher.group(1).trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return base.isBlank() ? "" : "RUNE_" + base;
    }

    public static int runeLevel(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return 0;
        }
        String clean = itemName.replaceAll("\u00a7.", "").trim().toUpperCase(Locale.ROOT);
        if (!clean.contains("RUNE")) {
            return 0;
        }
        Matcher roman = RUNE_ROMAN.matcher(clean);
        if (roman.find()) {
            return switch (roman.group(1).toUpperCase(Locale.ROOT)) {
                case "I" -> 1;
                case "II" -> 2;
                case "III" -> 3;
                case "IV" -> 4;
                case "V" -> 5;
                case "VI" -> 6;
                case "VII" -> 7;
                case "VIII" -> 8;
                case "IX" -> 9;
                case "X" -> 10;
                default -> 0;
            };
        }
        Matcher arabic = RUNE_ARABIC.matcher(clean);
        if (arabic.find()) {
            try {
                return Integer.parseInt(arabic.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int runeLevelFromNbt(CompoundTag extra) {
        if (extra == null) {
            return 0;
        }
        for (String key : extra.keySet()) {
            if (!isRuneNbtKey(key)) {
                continue;
            }
            int level = extra.getInt(key).orElse(0);
            if (level <= 0) {
                try {
                    level = Integer.parseInt(extra.getString(key).orElse("0").trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (level > 0) {
                return level;
            }
        }
        return 0;
    }

    private static int runeLevelFromFlat(JsonObject nbt) {
        if (nbt == null) {
            return 0;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : nbt.entrySet()) {
            if (!isRuneNbtKey(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().isJsonNull()) {
                continue;
            }
            try {
                int level = (int) Double.parseDouble(entry.getValue().getAsString());
                if (level > 0) {
                    return level;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static boolean isRuneNbtKey(String key) {
        if (key == null) {
            return false;
        }
        String upper = key.toUpperCase(Locale.ROOT);
        return upper.startsWith("RUNE_") || upper.startsWith("UNIQUE_RUNE_");
    }

    public boolean isAspectOfTheVoid() {
        return tag != null && ASPECT_OF_THE_VOID.equalsIgnoreCase(tag.trim());
    }

    public boolean isArmor() {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String value = tag.toUpperCase(Locale.ROOT);
        return value.endsWith("_HELMET")
                || value.endsWith("_CHESTPLATE")
                || value.endsWith("_LEGGINGS")
                || value.endsWith("_BOOTS")
                || value.endsWith("_CAP")
                || value.endsWith("_TUNIC")
                || value.endsWith("_PANTS")
                || value.endsWith("_SKIRT")
                || value.endsWith("_MASK")
                || value.endsWith("_HOOD")
                || value.endsWith("_CHEST")
                || value.endsWith("_ROBE")
                || value.endsWith("_JACKET")
                || value.endsWith("_CLOAK");
    }

    public String petFilter() {
        if (petLevel <= 0 || petRarity == null || petRarity.isBlank()) {
            return "";
        }
        return "&PetLevel=" + petCompareLevel(petLevel) + "&Rarity=" + petRarity;
    }

    /** Pets below level 95 are valued as level 1; from 95 on the exact level counts. */
    public static int petCompareLevel(int level) {
        if (level <= 0) {
            return 0;
        }
        return level >= 95 ? level : 1;
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
        if (extra != null && (hasId(extra) || hasRuneKey(extra))) {
            return true;
        }
        CompoundTag components = root.getCompound("components").orElse(null);
        if (components != null) {
            CompoundTag custom = components.getCompound("minecraft:custom_data").orElse(null);
            if (custom != null) {
                CompoundTag extra2 = custom.getCompound("ExtraAttributes").orElse(null);
                if (extra2 != null && (hasId(extra2) || hasRuneKey(extra2))) {
                    return true;
                }
            }
        }
        return hasId(root) || hasRuneKey(root);
    }

    private static boolean hasId(CompoundTag tag) {
        return tag != null && tag.getString("id").isPresent()
                && !tag.getString("id").orElse("").isBlank();
    }

    private static boolean hasRuneKey(CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        for (String key : tag.keySet()) {
            if (isRuneNbtKey(key)) {
                return true;
            }
        }
        return false;
    }
}
