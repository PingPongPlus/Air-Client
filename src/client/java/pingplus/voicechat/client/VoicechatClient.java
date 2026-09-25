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
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.gui.CoordinatesHud;
import pingplus.voicechat.client.gui.FpsHud;

/**
 * Initializes client GUI features, HUD elements, and voice chat.
 */
public class VoicechatClient implements ClientModInitializer {
    private static VoiceConnection voiceConnection;
    private static KeyMapping openGuiKey;
    private static KeyMapping voiceMenuKey;
    private static KeyMapping talkKey;
    private static KeyMapping muteKey;

    public static VoiceStatus voiceStatus(java.util.UUID id) {
        return voiceConnection == null ? VoiceStatus.NOT_CONNECTED : voiceConnection.statusFor(id);
    }

    public static KeyMapping openGuiKey() { return openGuiKey; }
    public static KeyMapping voiceMenuKey() { return voiceMenuKey; }
    public static KeyMapping talkKey() { return talkKey; }
    public static KeyMapping muteKey() { return muteKey; }

    public static final pingplus.voicechat.client.slayer.SlayerOutlineConfig SLAYER_CFG =
        new pingplus.voicechat.client.slayer.SlayerOutlineConfig();

    public static final Logger LOG =
            LoggerFactory.getLogger("laby-voicechat");

    @Override
    public void onInitializeClient() {
        pingplus.voicechat.client.gui.glass.GlassPipelines.initialize();
        syncSlayerCfg();
        pingplus.voicechat.client.slayer.SlayerOutlineHook.register(SLAYER_CFG);
        initializeClientFeatures();
        initializeVoiceChat();
    }

    public static void syncSlayerCfg() {
        SLAYER_CFG.enabled = PlayerSettings.slayerOutline && PlayerSettings.slayerBossHighlight;
        SLAYER_CFG.highlightBoss = PlayerSettings.slayerBoss;
        SLAYER_CFG.highlightMiniboss = PlayerSettings.slayerMiniboss;
        SLAYER_CFG.bossColor = PlayerSettings.slayerBossColor;
        SLAYER_CFG.minibossColor = PlayerSettings.slayerMinibossColor;
    }

    private void initializeClientFeatures() {
        FpsHud fpsHud = new FpsHud();
        CoordinatesHud coordinatesHud = new CoordinatesHud();
        openGuiKey = registerOpenGuiKey();
        HandSwapFeature handSwap = new HandSwapFeature();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey != null && openGuiKey.consumeClick()) {
                // Do not replace inventory, chat, or another mod's screen.
                if (client.gui.screen() == null && client.player != null) {
                    client.gui.setScreen(
                            new ClickGuiScreen(
                                    fpsHud,
                                    openGuiKey,
                                    coordinatesHud
                            )
                    );
                }
            }

            handSwap.tick(client);
        });

        // Attaching to a vanilla layer inherits its visibility condition (F1).
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("voicechat", "client_hud"),
                fpsHud::extract
        );

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("voicechat", "coordinates_hud"),
                coordinatesHud::extract
        );

        // Glass health bars above mobs (unlocked with hidden mob names).
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("voicechat", "mob_health_bar"),
                (graphics, delta) ->
                        pingplus.voicechat.client.gui.MobHealthBarRenderer.render(graphics)
        );

        // Screen-space ESP lines to bosses/minibosses.
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("voicechat", "boss_line"),
                (graphics, delta) ->
                        pingplus.voicechat.client.gui.BossLineHud.render(graphics)
        );
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

        voiceMenuKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.settings",
                        GLFW.GLFW_KEY_V,
                        category
                )
        );

        talkKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.talk",
                        GLFW.GLFW_KEY_CAPS_LOCK,
                        category
                )
        );

        muteKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.mute",
                        GLFW.GLFW_KEY_M,
                        category
                )
        );
        KeyMapping menu = voiceMenuKey;
        KeyMapping talk = talkKey;
        KeyMapping mute = muteKey;

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("voicechat", "status"),
                (graphics, delta) -> {
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
                            label,
                            8,
                            8,
                            voice.connected() ? 0xFF80DD99 : 0xFFFFCC80
                    );

                    int y = 20;

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
                                    "Speaking: " + playerName,
                                    8,
                                    y,
                                    0xFFFFFFFF
                            );

                            y += 12;
                        }
                    }
                }
        );

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
