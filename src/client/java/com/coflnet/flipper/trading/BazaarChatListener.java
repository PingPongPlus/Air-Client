package com.coflnet.flipper.trading;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class BazaarChatListener {

    public static class ChatEntry {
        public final long at;
        public final String text;
        public final BazaarChatEvent event;

        ChatEntry(long at, String text, BazaarChatEvent event) {
            this.at = at;
            this.text = text;
            this.event = event;
        }
    }

    private static final int MAX_ENTRIES = 200;
    private static final Deque<ChatEntry> RECENT = new ArrayDeque<>();

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                String stripped = ChatFormatting.stripFormatting(message.getString());
                if (stripped == null || stripped.isBlank()) {
                    return;
                }
                if (stripped.startsWith("[Flipper]") || stripped.startsWith("[AH-Flipper]")) {
                    return;
                }
                BazaarChatEvent event = BazaarChatEvent.parse(stripped);
                if (event == null) {
                    String lower = stripped.toLowerCase();
                    if (lower.contains("order") || lower.contains("offer")
                            || lower.contains("bazaar")) {
                        System.out.println("[Flipper] Unerkannte Bazaar-Nachricht: " + stripped);
                    }
                }
                ChatEntry entry = new ChatEntry(System.currentTimeMillis(), stripped, event);
                synchronized (RECENT) {
                    RECENT.addLast(entry);
                    while (RECENT.size() > MAX_ENTRIES) {
                        RECENT.removeFirst();
                    }
                }
                if (event != null) {
                    Trader trader = Trader.get();
                    if (trader != null) {
                        trader.onChatEvent(event);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static List<ChatEntry> since(long timestamp) {
        List<ChatEntry> result = new ArrayList<>();
        synchronized (RECENT) {
            for (ChatEntry entry : RECENT) {
                if (entry.at >= timestamp) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    public static boolean waitForChat(String contains, long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ChatEntry entry : since(start)) {
                if (entry.text.contains(contains)) {
                    return true;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public static Component styled(String prefix, String text) {
        return Component.literal(prefix).append(Component.literal(text));
    }
}
