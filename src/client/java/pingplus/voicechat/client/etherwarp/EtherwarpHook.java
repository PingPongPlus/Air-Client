package pingplus.voicechat.client.etherwarp;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

// Tick hook, touches no entity metadata so Hypixel cannot overwrite it.
public final class EtherwarpHook {
    private EtherwarpHook() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                EtherwarpHelper.clear();
                return;
            }
            try {
                EtherwarpHelper.tick(client);
            } catch (Exception ignored) {}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> EtherwarpHelper.clear());
    }
}
