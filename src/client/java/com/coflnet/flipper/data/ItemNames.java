package com.coflnet.flipper.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ItemNames {
    private static final Map<String, String> PRODUCT_TO_NAME = new HashMap<>();
    private static final Map<String, String> NAME_TO_PRODUCT = new HashMap<>();

    public static String nameKey(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}\\s]", " ").toLowerCase().trim();
        String[] words = cleaned.split("\\s+");
        java.util.Arrays.sort(words);
        return String.join(" ", words);
    }

    public static void load() {
        PRODUCT_TO_NAME.clear();
        NAME_TO_PRODUCT.clear();
        try (InputStream in = ItemNames.class.getResourceAsStream("/assets/coflmod/bazaar-resources.json")) {
            if (in == null) {
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject conversions = root.getAsJsonObject("bazaarConversions");
            if (conversions == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : conversions.entrySet()) {
                String name = entry.getValue().getAsString();
                PRODUCT_TO_NAME.put(entry.getKey(), name);
                NAME_TO_PRODUCT.putIfAbsent(name.toLowerCase(), entry.getKey());
                NAME_TO_PRODUCT.putIfAbsent(nameKey(name), entry.getKey());
            }
        } catch (Exception ignored) {
        }
    }

    public static String displayName(String productId) {
        String mapped = PRODUCT_TO_NAME.get(productId);
        if (mapped != null) {
            return mapped;
        }
        String[] words = productId.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    public static String productId(String displayName) {
        if (displayName == null) {
            return null;
        }
        String id = NAME_TO_PRODUCT.get(displayName.toLowerCase());
        if (id != null) {
            return id;
        }
        id = NAME_TO_PRODUCT.get(nameKey(displayName));
        if (id != null) {
            return id;
        }
        return displayName.toUpperCase().replace(' ', '_');
    }
}
