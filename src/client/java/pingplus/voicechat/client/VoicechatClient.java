package pingplus.voicechat.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pingplus.voicechat.client.gui.ArraylistHud;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.gui.CoordinatesHud;
import pingplus.voicechat.client.gui.FpsHud;
import pingplus.voicechat.client.gui.hud.HudEditor;
import pingplus.voicechat.client.spotify.SpotifySettings;
import pingplus.voicechat.client.spotify.SpotifyWidget;

/**
 * Initializes client GUI features, HUD elements, and voice chat.
 */
public class VoicechatClient implements ClientModInitializer {
    private static VoiceConnection voiceConnection;

    public static VoiceStatus voiceStatus(java.util.UUID id) {
        return voiceConnection == null ? VoiceStatus.NOT_CONNECTED : voiceConnection.statusFor(id);
    }

    public static final Logger LOG =
            LoggerFactory.getLogger("laby-voicechat");

    @Override
    public void onInitializeClient() {
        pingplus.voicechat.client.gui.glass.GlassPipelines.initialize();
        initializeClientFeatures();
        initializeVoiceChat();
    }

    private void initializeClientFeatures() {
        FpsHud fpsHud = new FpsHud();
        CoordinatesHud coordinatesHud = new CoordinatesHud();
        KeyMapping openGuiKey = registerOpenGuiKey();
        HandSwapFeature handSwap = new HandSwapFeature();

        ArraylistHud arraylistHud = new ArraylistHud(fpsHud, coordinatesHud);

        HudEditor.register(new HudEditor.Entry("fps", "FPS", () -> 112, () -> 18, (w,h) -> 18, (w,h) -> 13,
                fpsHud::isEnabled, (g,mx,my,dt,editing) -> fpsHud.render(g), java.util.List::of));
        HudEditor.register(new HudEditor.Entry("coordinates", "Coordinates", () -> 200, () -> 18, (w,h) -> 18, (w,h) -> 38,
                coordinatesHud::isEnabled, (g,mx,my,dt,editing) -> coordinatesHud.render(g), java.util.List::of));
        HudEditor.register(new HudEditor.Entry("arraylist", "Arraylist", arraylistHud::width, arraylistHud::height,
                (w,h) -> w - arraylistHud.width() - 8, (w,h) -> 8,
                arraylistHud::isEnabled, (g,mx,my,dt,editing) -> arraylistHud.render(g), java.util.List::of, true));
        HudEditor.register(new HudEditor.Entry("spotify", "Spotify", () -> 240, () -> 100,
                (w,h) -> SpotifySettings.x(w, 240), (w,h) -> SpotifySettings.y(h, 100),
                SpotifySettings::enabled, (g,mx,my,dt,editing) -> SpotifyWidget.INSTANCE.render(g,mx,my,dt,editing),
                () -> SpotifyWidget.INSTANCE.buttons()));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("voicechat", "editable_hud"), (graphics, delta) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player != null && !(client.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen))
                        HudEditor.render(graphics, -100, -100, 0, false);
                });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean active = client.player != null && pingplus.voicechat.client.spotify.SpotifySettings.enabled();
            pingplus.voicechat.client.spotify.SpotifyClient.INSTANCE.active(active);
            if (!active) pingplus.voicechat.client.spotify.SpotifyWidget.INSTANCE.clear();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            HudEditor.finish();
            pingplus.voicechat.client.spotify.SpotifyClient.INSTANCE.close();
            pingplus.voicechat.client.spotify.SpotifyWidget.INSTANCE.clear();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.consumeClick()) {
                // Do not replace inventory, chat, or another mod's screen.
                if (client.gui.screen() == null && client.player != null) {
                    client.gui.setScreen(
                            new ClickGuiScreen(
                                    fpsHud,
                                    openGuiKey,
                                    coordinatesHud,
                                    arraylistHud
                            )
                    );
                }
            }

            handSwap.tick(client);
        });

    }

    private KeyMapping registerOpenGuiKey() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("voicechat", "client")
        );

        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.click_gui",
                        GLFW.GLFW_KEY_RIGHT_SHIFT,
                        category
                )
        );
    }

    private void initializeVoiceChat() {
        VoiceConnection voice = new VoiceConnection(
                Minecraft.getInstance(),
                VoiceSettings.load()
        );
        voiceConnection = voice;

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("voicechat", "controls")
        );

        KeyMapping menu = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.settings",
                        GLFW.GLFW_KEY_V,
                        category
                )
        );

        KeyMapping talk = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.talk",
                        GLFW.GLFW_KEY_CAPS_LOCK,
                        category
                )
        );

        KeyMapping mute = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.mute",
                        GLFW.GLFW_KEY_M,
                        category
                )
        );

        HudEditor.register(new HudEditor.Entry("voice", "Voice status", () -> 300, () -> 78,
                (w,h) -> 8, (w,h) -> 70, () -> voice.settings.enabled,
                (graphics, mx, my, dt, editing) -> {
                    Minecraft client = Minecraft.getInstance();

                    if (!voice.settings.enabled || client.level == null) {
                        return;
                    }

                    String label;

                    if (!voice.connected()) {
                        label = voice.status;
                    } else if (voice.settings.deafened) {
                        label = "Voice: deafened";
                    } else if (voice.settings.muted) {
                        label = "Voice: microphone muted";
                    } else if (voice.transmitting()) {
                        label = voice.inputPeak() > 0.002
                                ? "Voice: transmitting audio"
                                : "Voice: sending silence (no microphone signal)";
                    } else {
                        label = voice.status;
                    }

                    graphics.text(
                            client.font,
                            client.font.plainSubstrByWidth(label, 292),
                            4,
                            4,
                            voice.connected() ? 0xFF80DD99 : 0xFFFFCC80
                    );

                    int y = 16;

                    for (var entry : voice.talking.entrySet()) {
                        if (y > 68) {
                            break;
                        }

                        boolean recentlyTalking =
                                System.currentTimeMillis() - entry.getValue() < 300;

                        boolean audible =
                                voice.settings.volume(entry.getKey()) > 0
                                        && !voice.settings.deafened;

                        if (recentlyTalking && audible) {
                            String playerName = voice.players.getOrDefault(
                                    entry.getKey(),
                                    entry.getKey().toString().substring(0, 8)
                            );

                            graphics.text(
                                    client.font,
                                    client.font.plainSubstrByWidth("Speaking: " + playerName, 292),
                                    4,
                                    y,
                                    0xFFFFFFFF
                            );

                            y += 12;
                        }
                    }
                }, java.util.List::of));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menu.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) {
                    client.gui.setScreen(new VoiceScreen(null, voice));
                }
            }

            while (mute.consumeClick()) {
                voice.settings.muted = !voice.settings.muted;
                voice.settings.save();
                voice.updateProperties();
            }

            voice.pushToTalk =
                    talk.isDown()
                            && client.gui.screen() == null
                            && client.isWindowActive();

            voice.tick();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (voice.settings.enabled) {
                voice.connect();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> voice.disconnect()
        );

        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> voice.disconnect()
        );
    }
}
