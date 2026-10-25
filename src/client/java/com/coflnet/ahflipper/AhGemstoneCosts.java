package com.coflnet.ahflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AhGemstoneCosts {
    public static final class ItemCost {
        public String itemId = "";
        public int amount = 1;
    }

    public static final class SlotCost {
        public String slotType = "";
        public long coins;
        public final List<ItemCost> items = new ArrayList<>();
    }

    private static Map<String, List<SlotCost>> cache;
    private static long cacheAt;

    public static synchronized List<SlotCost> forItem(String tag) {
        if (tag == null || tag.isBlank()) {
            return List.of();
        }
        ensureLoaded();
        if (cache == null) {
            return List.of();
        }
        List<SlotCost> costs = cache.get(tag.toUpperCase(Locale.ROOT));
        return costs == null ? List.of() : costs;
    }

    private static void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < 24L * 60 * 60 * 1000) {
            return;
        }
        try {
            String body = AhMarketData.getUrl("https://api.hypixel.net/v2/resources/skyblock/items");
            if (body == null) {
                return;
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray items = root.getAsJsonArray("items");
            if (items == null) {
                return;
            }
            Map<String, List<SlotCost>> parsed = new HashMap<>();
            for (JsonElement element : items) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String id = string(item, "id");
                if (id.isBlank()) {
                    continue;
                }
                JsonArray slots = item.getAsJsonArray("gemstone_slots");
                if (slots == null) {
                    continue;
                }
                List<SlotCost> costs = new ArrayList<>();
                for (JsonElement slotElement : slots) {
                    if (slotElement == null || !slotElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject slot = slotElement.getAsJsonObject();
                    JsonArray slotCosts = slot.getAsJsonArray("costs");
                    if (slotCosts == null || slotCosts.isEmpty()) {
                        continue;
                    }
                    SlotCost cost = new SlotCost();
                    cost.slotType = string(slot, "slot_type").toUpperCase(Locale.ROOT);
                    for (JsonElement costElement : slotCosts) {
                        if (costElement == null || !costElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject entry = costElement.getAsJsonObject();
                        String type = string(entry, "type");
                        if ("COINS".equalsIgnoreCase(type)) {
                            cost.coins += longValue(entry, "coins");
                        } else {
                            ItemCost itemCost = new ItemCost();
                            itemCost.itemId = string(entry, "item_id").toUpperCase(Locale.ROOT);
                            itemCost.amount = (int) Math.max(1, longValue(entry, "amount"));
                            if (!itemCost.itemId.isBlank()) {
                                cost.items.add(itemCost);
                            }
                        }
                    }
                    if (!cost.slotType.isBlank()) {
                        costs.add(cost);
                    }
                }
                if (!costs.isEmpty()) {
                    parsed.put(id.toUpperCase(Locale.ROOT), costs);
                }
            }
            cache = parsed;
            cacheAt = now;
        } catch (Exception e) {
            System.out.println("[AH-Flipper] Gemstone-Kosten Fehler: " + e.getMessage());
        }
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return json.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
