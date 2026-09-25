package pingplus.voicechat.client.slayer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

// Tick hook, touches no entity metadata so Hypixel cannot overwrite it.
public final class SlayerOutlineHook {
    private SlayerOutlineHook() {}

    public static void register(SlayerOutlineConfig cfg) {
        SlayerOutlineRenderer.init(cfg);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            try { SlayerOutlineRenderer.refresh(client.level.entitiesForRendering()); }
            catch (Exception ignored) {}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> SlayerOutlineRenderer.clear());
    }
}
