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
import pingplus.voicechat.client.hud.ArraylistHud;
import pingplus.voicechat.client.hud.ChatHud;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.hud.CoordinatesHud;
import pingplus.voicechat.client.hud.FpsHud;
import pingplus.voicechat.client.hud.PingHud;
import pingplus.voicechat.client.hud.MetricsHud;
import pingplus.voicechat.client.hud.KeystrokesHud;
import pingplus.voicechat.client.hud.metrics.HudMetrics;
import pingplus.voicechat.client.hud.LogoHud;
import pingplus.voicechat.client.hud.ScoreboardHud;
import pingplus.voicechat.client.hud.VoiceHud;
import pingplus.voicechat.client.hud.editor.HudEditor;
import pingplus.voicechat.client.hud.editor.HudEditorScreen;
import pingplus.voicechat.client.spotify.SpotifySettings;
import pingplus.voicechat.client.hud.SpotifyWidget;

/**
 * Initializes client GUI features, HUD elements, and voice chat.
 */
public class VoicechatClient implements ClientModInitializer {
    private static VoiceConnection voiceConnection;
    private static KeyMapping openGuiKey;
    private static KeyMapping hudEditorKey;
    private static KeyMapping voiceMenuKey;
    private static KeyMapping talkKey;
    private static KeyMapping muteKey;
    private static KeyMapping zoomKey;
    private static KeyMapping shoulderCamKey;
    private static KeyMapping killauraKey;
    private static final KeyMapping.Category CLIENT_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("voicechat", "client")
    );

    public static VoiceStatus voiceStatus(java.util.UUID id) {
        return voiceConnection == null ? VoiceStatus.NOT_CONNECTED : voiceConnection.statusFor(id);
    }

    public static KeyMapping openGuiKey() { return openGuiKey; }
    public static KeyMapping hudEditorKey() { return hudEditorKey; }
    public static KeyMapping voiceMenuKey() { return voiceMenuKey; }
    public static KeyMapping talkKey() { return talkKey; }
    public static KeyMapping muteKey() { return muteKey; }
    public static VoiceSettings voiceSettings() { return voiceConnection == null ? null : voiceConnection.settings; }
    public static boolean isMiddleClickVolumeEnabled() {
        var settings = voiceSettings();
        return settings != null && settings.middleClickVolume;
    }
    public static void toggleMiddleClickVolume() {
        var settings = voiceSettings();
        if (settings == null) return;
        settings.middleClickVolume = !settings.middleClickVolume;
        settings.save();
    }
    /** Uses Minecraft's normal crosshair target, including its reach and obstruction checks. */
    public static boolean openPlayerVoiceVolume(Minecraft client) {
        var settings = voiceSettings();
        if (settings == null || !settings.middleClickVolume || client.player == null || client.level == null
                || client.gui.screen() != null || client.gui.overlay() != null || client.isPaused()) return false;
        if (!(client.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit)
                || !(hit.getEntity() instanceof net.minecraft.world.entity.player.Player target)
                || target == client.player || !target.isAlive() || target.isRemoved()) return false;
        client.gui.setScreen(new PlayerVoiceVolumeScreen(target.getUUID(), target.getName(), settings));
        return true;
    }
    public static KeyMapping zoomKey() { return zoomKey; }
    public static KeyMapping shoulderCamKey() { return shoulderCamKey; }
    public static KeyMapping killAuraKey() { return killauraKey; }

    public static final pingplus.voicechat.client.slayer.SlayerOutlineConfig SLAYER_CFG =
        new pingplus.voicechat.client.slayer.SlayerOutlineConfig();

    public static final Logger LOG =
            LoggerFactory.getLogger("laby-voicechat");

    @Override
    public void onInitializeClient() {
        pingplus.voicechat.client.gui.glass.GlassPipelines.initialize();
        pingplus.voicechat.client.damageglass.DamageGlassRenderer.initialize();
        syncSlayerCfg();
        pingplus.voicechat.client.slayer.SlayerOutlineHook.register(SLAYER_CFG);
        pingplus.voicechat.client.etherwarp.EtherwarpHook.register();
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
        hudEditorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.voicechat.hud_editor", GLFW.GLFW_KEY_G, CLIENT_CATEGORY));
        zoomKey = registerZoomKey();
        killauraKey = registerKillAura();
        shoulderCamKey = registerShoulderCamKey();
        HandSwapFeature handSwap = new HandSwapFeature();

        ArraylistHud arraylistHud = new ArraylistHud(fpsHud, coordinatesHud);

        HudEditor.register(new HudEditor.Entry("fps", "FPS", fpsHud::width, fpsHud::height, (w,h) -> 18, (w,h) -> 13,
                fpsHud::isEnabled, (g,mx,my,dt,editing) -> fpsHud.render(g), java.util.List::of));
        PingHud pingHud = PingHud.INSTANCE;
        HudEditor.register(new HudEditor.Entry("ping", "Ping", pingHud::width, pingHud::height,
                (w,h) -> 26 + fpsHud.width(), (w,h) -> 13,
                pingHud::isEnabled, (g,mx,my,dt,editing) -> pingHud.render(g), java.util.List::of));
        HudEditor.register(new HudEditor.Entry("coordinates", "Coordinates", coordinatesHud::width, coordinatesHud::height, (w,h) -> 18, (w,h) -> 38,
                coordinatesHud::isEnabled, (g,mx,my,dt,editing) -> coordinatesHud.render(g), java.util.List::of));
        MetricsHud cpsHud = MetricsHud.CPS, speedHud = MetricsHud.SPEED;
        HudEditor.register(new HudEditor.Entry("cps", "CPS", cpsHud::width, cpsHud::height, (w,h) -> 18, (w,h) -> 130,
                cpsHud::isEnabled, (g,mx,my,dt,editing) -> cpsHud.render(g), java.util.List::of));
        HudEditor.register(new HudEditor.Entry("speed", "Speed", speedHud::width, speedHud::height, (w,h) -> 18, (w,h) -> 155,
                speedHud::isEnabled, (g,mx,my,dt,editing) -> speedHud.render(g), java.util.List::of));
        KeystrokesHud keystrokesHud = KeystrokesHud.INSTANCE;
        HudEditor.register(new HudEditor.Entry("keystrokes", "Keystrokes", keystrokesHud::width, keystrokesHud::height,
                (w,h) -> 18, (w,h) -> 180, keystrokesHud::isEnabled,
                (g,mx,my,dt,editing) -> keystrokesHud.render(g), java.util.List::of));
        HudEditor.register(new HudEditor.Entry("arraylist", "Arraylist", arraylistHud::width, arraylistHud::height,
                (w,h) -> w - arraylistHud.width() - 8, (w,h) -> 8,
                arraylistHud::isEnabled, (g,mx,my,dt,editing) -> arraylistHud.render(g), java.util.List::of, true));
        HudEditor.register(new HudEditor.Entry("logo", "AIR logo", () -> LogoHud.WIDTH, () -> LogoHud.HEIGHT,
                (w,h) -> 18, (w,h) -> 64,
                LogoHud.INSTANCE::isEnabled, (g,mx,my,dt,editing) -> LogoHud.INSTANCE.render(g), java.util.List::of));
        ScoreboardHud scoreboardHud = ScoreboardHud.INSTANCE;
        HudEditor.register(new HudEditor.Entry("scoreboard", "Scoreboard", scoreboardHud::width, scoreboardHud::height,
                (w,h) -> w - scoreboardHud.width() - 8, (w,h) -> h / 2 - scoreboardHud.height() * 2 / 3,
                scoreboardHud::isVisible, (g,mx,my,dt,editing) -> scoreboardHud.render(g), java.util.List::of));
        ChatHud chatHud = ChatHud.INSTANCE;
        HudEditor.register(new HudEditor.Entry("chat", "Chat", chatHud::width, chatHud::height,
                (w,h) -> 8, (w,h) -> h - 40 - chatHud.height(),
                chatHud::isEnabled, chatHud::render, java.util.List::of));
        HudEditor.register(new HudEditor.Entry("spotify", "Spotify", () -> 240, () -> 100,
                (w,h) -> SpotifySettings.x(w, 240), (w,h) -> SpotifySettings.y(h, 100),
                SpotifySettings::enabled, (g,mx,my,dt,editing) -> SpotifyWidget.INSTANCE.render(g,mx,my,dt,editing),
                () -> SpotifyWidget.INSTANCE.buttons()));

        HudEditor.register(new HudEditor.Entry("agg", "AGG",
                () -> pingplus.voicechat.client.hud.AggWidget.SIZE,
                () -> pingplus.voicechat.client.hud.AggWidget.SIZE,
                (w,h) -> w - pingplus.voicechat.client.hud.AggWidget.SIZE - 8, (w,h) -> 70,
                pingplus.voicechat.client.hud.AggSettings::isEnabled,
                (g,mx,my,dt,editing) -> pingplus.voicechat.client.hud.AggWidget.INSTANCE.render(g,mx,my,dt,editing),
                java.util.List::of));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("voicechat", "editable_hud"), (graphics, delta) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player != null && !(client.gui.screen() instanceof HudEditorScreen))
                        HudEditor.render(graphics, -100, -100, 0, false);
                });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            HudMetrics.tick(client);
            boolean active = client.player != null && (SpotifySettings.enabled() || SpotifySettings.musicGlass());
            pingplus.voicechat.client.spotify.SpotifyClient.INSTANCE.active(active);
            pingplus.voicechat.client.spotify.MusicGlass.tick(client.player != null);
            if (!active) pingplus.voicechat.client.hud.SpotifyWidget.INSTANCE.clear();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> HudMetrics.reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            HudEditor.finish();
            pingplus.voicechat.client.spotify.SpotifyClient.INSTANCE.close();
            pingplus.voicechat.client.hud.SpotifyWidget.INSTANCE.clear();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (hudEditorKey != null && hudEditorKey.consumeClick()) {
                if (client.gui.screen() == null && client.player != null)
                    client.gui.setScreen(new HudEditorScreen());
            }
            while (openGuiKey != null && openGuiKey.consumeClick()) {
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
            AutoToolsFeature.tick(client);
            StorageEspFeature.tick(client);
            KillAuraFeature.tick(client);
            pingplus.voicechat.client.FollowFeature.tick(client);
            while (killauraKey != null && killauraKey.consumeClick()) {
                PlayerSettings.killAura = !PlayerSettings.killAura;
                if (!PlayerSettings.killAura) KillAuraFeature.clear();
            }
            pingplus.voicechat.client.ZoomFeature.tick(client, zoomKey != null && zoomKey.isDown());
            pingplus.voicechat.client.ShoulderCamFeature.tick(client, shoulderCamKey != null && shoulderCamKey.isDown());
        });

        // Glass health bars above mobs (unlocked with hidden mob names).
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("voicechat", "mob_health_bar"),
                (graphics, delta) ->
                        pingplus.voicechat.client.hud.MobHealthBarRenderer.render(graphics)
        );

        // Screen-space ESP lines to bosses/minibosses.
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("voicechat", "boss_line"),
                (graphics, delta) ->
                        pingplus.voicechat.client.hud.BossLineHud.render(graphics)
        );
    }

    private KeyMapping registerOpenGuiKey() {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.click_gui",
                        GLFW.GLFW_KEY_RIGHT_SHIFT,
                        CLIENT_CATEGORY
                )
        );
    }

    private KeyMapping registerZoomKey() {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.zoom",
                        GLFW.GLFW_KEY_C,
                        CLIENT_CATEGORY
                )
        );
    }
    private KeyMapping registerKillAura() {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.killaura",
                        GLFW.GLFW_KEY_Z,
                        CLIENT_CATEGORY
                )
        );
    }

    private KeyMapping registerShoulderCamKey() {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.voicechat.shoulder_cam",
                        GLFW.GLFW_KEY_R,
                        CLIENT_CATEGORY
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

        VoiceHud voiceHud = new VoiceHud(voice);
        HudEditor.register(new HudEditor.Entry("voice", "Voice status", () -> 300, () -> 78,
                (w,h) -> 8, (w,h) -> 70, voiceHud::isEnabled,
                (graphics, mx, my, dt, editing) -> voiceHud.render(graphics), java.util.List::of));

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
