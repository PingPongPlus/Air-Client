package pingplus.voicechat.client.profile;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import pingplus.voicechat.client.*;
import pingplus.voicechat.client.damageglass.*;
import pingplus.voicechat.client.gui.glass.*;
import pingplus.voicechat.client.hud.*;
import pingplus.voicechat.client.hud.editor.*;
import pingplus.voicechat.client.spotify.SpotifySettings;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/** Bridges profiles to the same settings and callbacks used by the control center. */
public final class ClientProfiles {
    private static final Gson GSON = new Gson();
    private final ProfileSettings settings = new ProfileSettings();
    private ProfileStore store;
    private IOException loadFailure;

    public ClientProfiles(Path file, FpsHud fps, CoordinatesHud coordinates, ArraylistHud arraylist) {
        try { store = new ProfileStore(file); }
        catch (IOException e) { loadFailure = e; VoicechatClient.LOG.warn("Could not read client profiles", e); }
        bindPlayerSettings();
        hud("fps", fps::isEnabled, fps::toggle, fps::isGlass, fps::toggleGlass, fps::isEdges, fps::toggleEdges);
        hud("coordinates", coordinates::isEnabled, coordinates::toggle, coordinates::isGlass, coordinates::toggleGlass, coordinates::isEdges, coordinates::toggleEdges);
        hud("arraylist", arraylist::isEnabled, arraylist::toggle, arraylist::isGlass, arraylist::toggleGlass, arraylist::isEdges, arraylist::toggleEdges);
        settings.bool("hud.arraylist.rectangles", arraylist::isRectangles, arraylist::toggleRectangles);
        var ping = PingHud.INSTANCE;
        hud("ping", ping::isEnabled, ping::toggle, ping::isGlass, ping::toggleGlass, ping::isEdges, ping::toggleEdges);
        for (var entry : Map.of("cps", MetricsHud.CPS, "speed", MetricsHud.SPEED).entrySet()) {
            var hud = entry.getValue();
            hud(entry.getKey(), hud::isEnabled, hud::toggle, hud::isGlass, hud::toggleGlass, hud::isEdges, hud::toggleEdges);
        }
        var keys = KeystrokesHud.INSTANCE;
        hud("keystrokes", keys::isEnabled, keys::toggle, keys::isGlass, keys::toggleGlass, keys::isEdges, keys::toggleEdges);
        settings.bool("hud.keystrokes.mouseButtons", keys::isMouseButtons, keys::toggleMouseButtons);
        settings.bool("hud.keystrokes.spaceBar", keys::isSpaceBar, keys::toggleSpaceBar);
        var logo = LogoHud.INSTANCE;
        hud("logo", logo::isEnabled, logo::toggle, logo::isGlass, logo::toggleGlass, logo::isEdges, logo::toggleEdges);
        var scoreboard = ScoreboardHud.INSTANCE;
        hud("scoreboard", scoreboard::isEnabled, scoreboard::toggle, scoreboard::isGlass, scoreboard::toggleGlass, scoreboard::isEdges, scoreboard::toggleEdges);
        var chat = ChatHud.INSTANCE;
        hud("chat", chat::isEnabled, chat::toggle, chat::isGlass, chat::toggleGlass, chat::isEdges, chat::toggleEdges);
        bindCrosshair();
        bindGlass();
        settings.bool("spotify.enabled", SpotifySettings::enabled, SpotifySettings::toggle);
        settings.bool("spotify.edges", SpotifySettings::edges, SpotifySettings::toggleEdges);
        settings.bool("spotify.musicGlass", SpotifySettings::musicGlass, SpotifySettings::toggleMusicGlass);
        settings.integer("spotify.musicIntensity", SpotifySettings::musicIntensity, SpotifySettings::setMusicIntensity, 0, 100);
        settings.bool("voice.middleClickVolume", VoicechatClient::isMiddleClickVolumeEnabled, VoicechatClient::toggleMiddleClickVolume);
        for (var mineral : XrayFeature.Mineral.values())
            settings.bool("xray." + mineral.name(), () -> XrayFeature.selected(mineral), () -> XrayFeature.toggle(mineral));
        settings.bool("xray.enabled", XrayFeature::enabled, XrayFeature::toggle);
        key("gui", VoicechatClient::openGuiKey);
        key("hudEditor", VoicechatClient::hudEditorKey);
        key("voice", VoicechatClient::voiceMenuKey);
        key("talk", VoicechatClient::talkKey);
        key("mute", VoicechatClient::muteKey);
        key("zoom", VoicechatClient::zoomKey);
        key("shoulderCam", VoicechatClient::shoulderCamKey);
        key("killAura", VoicechatClient::killAuraKey);
        settings.bind("hud.layout", HudEditor::layoutSnapshot, HudEditor::applyLayout, ClientProfiles::parseLayout, GSON::toJson);
    }

    public ProfileStore store() throws IOException {
        if (store == null) throw new IOException("Could not read profiles; see the client log", loadFailure);
        return store;
    }
    public ProfileStore.Profile create(String name) throws IOException { return store().create(name, settings.capture()); }
    public void save(String id) throws IOException { store().save(id, settings.capture()); }
    public void load(String id) throws IOException {
        Runnable apply = settings.prepare(store().load(id));
        store().activate(id); // Fail before changing live settings if the active marker cannot be saved.
        AutoToolsFeature.finish(Minecraft.getInstance());
        KillAuraFeature.clear();
        apply.run();
        VoicechatClient.syncSlayerCfg();
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }
    public void restoreActive() {
        try { if (!store().activeId().isEmpty()) load(store.activeId()); }
        catch (IOException | IllegalArgumentException e) { VoicechatClient.LOG.warn("Could not restore active client profile", e); }
    }

    private void hud(String id, BooleanSupplier enabled, Runnable toggle, BooleanSupplier glass, Runnable toggleGlass,
                     BooleanSupplier edges, Runnable toggleEdges) {
        settings.bool("hud." + id + ".enabled", enabled, toggle);
        settings.bool("hud." + id + ".glass", glass, toggleGlass);
        settings.bool("hud." + id + ".edges", edges, toggleEdges);
    }
    private void key(String id, Supplier<KeyMapping> mapping) {
        settings.bind("key." + id, () -> mapping.get().saveString(), value -> mapping.get().setKey(InputConstants.getKey(value)), value -> {
            InputConstants.getKey(value);
            return value;
        });
    }
    private void bindCrosshair() {
        var crosshair = CustomCrosshairHud.INSTANCE;
        settings.bool("crosshair.enabled", crosshair::isEnabled, crosshair::toggle);
        settings.bool("crosshair.outline", crosshair::isOutline, crosshair::toggleOutline);
        settings.bool("crosshair.centerDot", crosshair::isCenterDot, crosshair::toggleCenterDot);
        settings.bool("crosshair.cooldownRing", crosshair::isCooldownRing, crosshair::toggleCooldownRing);
        settings.bind("crosshair.shape", crosshair::shape, crosshair::setShape, CustomCrosshairHud.Shape::valueOf);
        settings.integer("crosshair.size", crosshair::size, crosshair::setSize, 1, 24);
        settings.integer("crosshair.gap", crosshair::gap, crosshair::setGap, 0, 12);
        settings.integer("crosshair.thickness", crosshair::thickness, crosshair::setThickness, 1, 6);
        settings.integer("crosshair.color", crosshair::color, crosshair::setColor, 0, 0xFFFFFF);
        settings.integer("crosshair.opacity", crosshair::opacity, crosshair::setOpacity, 10, 100);
    }
    private void bindGlass() {
        settings.decimal("glass.corners", GlassCornerSettings::getScale, GlassCornerSettings::setScale, 0, 2);
        settings.integer("glass.blur", GlassEffectSettings::blurStep, GlassEffectSettings::setBlur, 0, GlassEffectSettings.STEPS);
        settings.integer("glass.shadow", GlassEffectSettings::shadowStep, GlassEffectSettings::setShadow, 0, GlassEffectSettings.STEPS);
        settings.bool("glass.customTint", GlassEffectSettings::customTint, GlassEffectSettings::toggleCustomTint);
        settings.integer("glass.tintColor", GlassEffectSettings::tintColor, GlassEffectSettings::setTintColor, 0, 0xFFFFFF);
        settings.integer("glass.tintIntensity", GlassEffectSettings::tintIntensity, GlassEffectSettings::setTintIntensity, 0, 100);
        settings.bool("weather.enabled", WeatherGlassSettings::enabled, WeatherGlassSettings::toggleEnabled);
        settings.bind("weather.mode", WeatherGlassSettings::mode,
                mode -> WeatherGlassSettings.set(new WeatherGlassSettings.Values(WeatherGlassSettings.enabled(), mode,
                        WeatherGlassSettings.alwaysActive(), WeatherGlassSettings.intensity())), WeatherGlassSettings.Mode::valueOf);
        settings.bool("weather.alwaysActive", WeatherGlassSettings::alwaysActive, WeatherGlassSettings::toggleAlwaysActive);
        settings.integer("weather.intensity", WeatherGlassSettings::intensity, WeatherGlassSettings::setIntensity, 0, 100);
        settings.bool("damage.enabled", DamageGlassSettings::enabled, DamageGlassSettings::toggleEnabled);
        settings.bool("damage.players", DamageGlassSettings::players, DamageGlassSettings::togglePlayers);
        settings.bool("damage.mobs", DamageGlassSettings::mobs, DamageGlassSettings::toggleMobs);
        settings.bool("damage.impactRipple", DamageGlassSettings::impactRipple, DamageGlassSettings::toggleImpactRipple);
        settings.bool("damage.deathWave", DamageGlassSettings::deathWave, DamageGlassSettings::toggleDeathWave);
        settings.bool("damage.alwaysOn", DamageGlassSettings::alwaysOn, DamageGlassSettings::toggleAlwaysOn);
        // Applying a preset resets reflectivity, so restore the explicit value afterward.
        settings.bind("damage.preset", DamageGlassSettings::preset, DamageGlassSettings::setPreset, GlassPreset::valueOf);
        settings.integer("damage.reflectivity", DamageGlassSettings::reflectivity, DamageGlassSettings::setReflectivity, 0, 100);
    }

    private void bindPlayerSettings() {
        // PlayerSettings is the public static settings model; include new primitive fields automatically.
        for (Field field : PlayerSettings.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            String key = "player." + field.getName();
            if (field.getType() == boolean.class)
                settings.bind(key, () -> (Boolean)get(field), value -> set(field, value), ProfileSettings::parseBoolean);
            else if (field.getType() == int.class) {
                int[] range = switch (field.getName()) {
                    case "fastPlaceDelayTicks" -> new int[]{1, 4};
                    case "handSwapIntervalTicks" -> new int[]{1, 20};
                    case "storageEspRange" -> new int[]{16, 128};
                    case "dockAutoHideSeconds" -> new int[]{5, 120};
                    default -> field.getName().endsWith("Color") ? new int[]{0, 0xFFFFFF} : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
                };
                settings.integer(key, () -> (Integer)get(field), value -> set(field, value), range[0], range[1]);
            } else if (field.getType() == float.class) {
                float[] range = switch (field.getName()) {
                    case "mobBarOpacity" -> new float[]{0, 1};
                    case "dockMaxScale" -> new float[]{0, 2.5f};
                    case "dockRadius" -> new float[]{.5f, 8};
                    case "dockSize" -> new float[]{.5f, 2};
                    case "zoomStrength" -> new float[]{1, 8};
                    case "killAuraRange" -> new float[]{1, 4};
                    case "killAuraTurnSpeed" -> new float[]{45, 540};
                    default -> new float[]{-100000, 100000};
                };
                settings.decimal(key, () -> (Float)get(field), value -> set(field, value), range[0], range[1]);
            } else if (field.getType() == String.class) settings.bind(key, () -> (String)get(field), value -> set(field, value), value -> {
                if (value.length() > 16 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid player name");
                return value;
            });
        }
    }
    private static Object get(Field field) {
        try { return field.get(null); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static void set(Field field, Object value) {
        try { field.set(null, value); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static Map<String, HudLayout.Position> parseLayout(String json) {
        Map<String, HudLayout.Position> positions = GSON.fromJson(json, new TypeToken<Map<String, HudLayout.Position>>() {}.getType());
        if (positions == null) throw new IllegalArgumentException("Missing HUD layout");
        positions.forEach((id, p) -> {
            if (id == null || id.isBlank() || p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.scale())
                    || p.x() < 0 || p.x() > 1 || p.y() < 0 || p.y() > 1 || p.scale() < .5 || p.scale() > 3)
                throw new IllegalArgumentException("Invalid HUD position");
        });
        return Map.copyOf(positions);
    }
}
