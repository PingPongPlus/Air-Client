package pingplus.voicechat.client;

import pingplus.voicechat.client.hud.ArraylistHud;
import pingplus.voicechat.client.hud.ChatHud;
import pingplus.voicechat.client.hud.CoordinatesHud;
import pingplus.voicechat.client.hud.CustomCrosshairHud;
import pingplus.voicechat.client.hud.FpsHud;
import pingplus.voicechat.client.hud.LogoHud;
import pingplus.voicechat.client.hud.MetricsHud;
import pingplus.voicechat.client.hud.KeystrokesHud;
import pingplus.voicechat.client.hud.PingHud;
import pingplus.voicechat.client.hud.ScoreboardHud;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import pingplus.voicechat.client.damageglass.DamageGlassSettings;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.gui.glass.*;
import pingplus.voicechat.client.spotify.SpotifySettings;

/** Bulk shutdown through real scaled mouse input, including hidden and saved options. */
final class ClickGuiAllOffGameTest {
    static void run(ClientGameTestContext context) {
        FpsHud fps = new FpsHud();
        CoordinatesHud coordinates = new CoordinatesHud();
        ArraylistHud arraylist = new ArraylistHud(fps,coordinates);
        List<SavedToggle> toggles = new ArrayList<>();
        Map<Field,Boolean> player = new LinkedHashMap<>();
        boolean xray = XrayFeature.enabled();
        boolean[] minerals = new boolean[XrayFeature.Mineral.values().length];
        for (var mineral : XrayFeature.Mineral.values()) minerals[mineral.ordinal()] = XrayFeature.selected(mineral);
        context.runOnClient(client -> {
            var crosshair = CustomCrosshairHud.INSTANCE;
            toggles.add(saved(crosshair::isEnabled,crosshair::toggle));
            toggles.add(saved(crosshair::isOutline,crosshair::toggleOutline));
            toggles.add(saved(crosshair::isCenterDot,crosshair::toggleCenterDot));
            toggles.add(saved(crosshair::isCooldownRing,crosshair::toggleCooldownRing));
            toggles.add(saved(fps::isEnabled,fps::toggle));
            toggles.add(saved(fps::isGlass,fps::toggleGlass));
            toggles.add(saved(fps::isEdges,fps::toggleEdges));
            toggles.add(saved(coordinates::isEnabled,coordinates::toggle));
            toggles.add(saved(coordinates::isGlass,coordinates::toggleGlass));
            toggles.add(saved(coordinates::isEdges,coordinates::toggleEdges));
            toggles.add(saved(arraylist::isEnabled,arraylist::toggle));
            toggles.add(saved(arraylist::isGlass,arraylist::toggleGlass));
            toggles.add(saved(arraylist::isEdges,arraylist::toggleEdges));
            toggles.add(saved(arraylist::isRectangles,arraylist::toggleRectangles));
            toggles.add(saved(PingHud.INSTANCE::isEnabled,PingHud.INSTANCE::toggle));
            toggles.add(saved(PingHud.INSTANCE::isGlass,PingHud.INSTANCE::toggleGlass));
            toggles.add(saved(PingHud.INSTANCE::isEdges,PingHud.INSTANCE::toggleEdges));
            for (var metrics : List.of(MetricsHud.CPS,MetricsHud.SPEED)) {
                toggles.add(saved(metrics::isEnabled,metrics::toggle));
                toggles.add(saved(metrics::isGlass,metrics::toggleGlass));
                toggles.add(saved(metrics::isEdges,metrics::toggleEdges));
            }
            toggles.add(saved(ChatHud.INSTANCE::isEnabled,ChatHud.INSTANCE::toggle));
            toggles.add(saved(KeystrokesHud.INSTANCE::isEnabled,KeystrokesHud.INSTANCE::toggle));
            toggles.add(saved(KeystrokesHud.INSTANCE::isGlass,KeystrokesHud.INSTANCE::toggleGlass));
            toggles.add(saved(KeystrokesHud.INSTANCE::isEdges,KeystrokesHud.INSTANCE::toggleEdges));
            toggles.add(saved(KeystrokesHud.INSTANCE::isMouseButtons,KeystrokesHud.INSTANCE::toggleMouseButtons));
            toggles.add(saved(KeystrokesHud.INSTANCE::isSpaceBar,KeystrokesHud.INSTANCE::toggleSpaceBar));
            toggles.add(saved(ChatHud.INSTANCE::isGlass,ChatHud.INSTANCE::toggleGlass));
            toggles.add(saved(ChatHud.INSTANCE::isEdges,ChatHud.INSTANCE::toggleEdges));
            toggles.add(saved(ScoreboardHud.INSTANCE::isEnabled,ScoreboardHud.INSTANCE::toggle));
            toggles.add(saved(ScoreboardHud.INSTANCE::isGlass,ScoreboardHud.INSTANCE::toggleGlass));
            toggles.add(saved(ScoreboardHud.INSTANCE::isEdges,ScoreboardHud.INSTANCE::toggleEdges));
            toggles.add(saved(LogoHud.INSTANCE::isEnabled,LogoHud.INSTANCE::toggle));
            toggles.add(saved(LogoHud.INSTANCE::isGlass,LogoHud.INSTANCE::toggleGlass));
            toggles.add(saved(LogoHud.INSTANCE::isEdges,LogoHud.INSTANCE::toggleEdges));
            toggles.add(saved(SpotifySettings::enabled,SpotifySettings::toggle));
            toggles.add(saved(SpotifySettings::edges,SpotifySettings::toggleEdges));
            toggles.add(saved(SpotifySettings::musicGlass,SpotifySettings::toggleMusicGlass));
            toggles.add(saved(DamageGlassSettings::enabled,DamageGlassSettings::toggleEnabled));
            toggles.add(saved(DamageGlassSettings::impactRipple,DamageGlassSettings::toggleImpactRipple));
            toggles.add(saved(DamageGlassSettings::deathWave,DamageGlassSettings::toggleDeathWave));
            toggles.add(saved(DamageGlassSettings::alwaysOn,DamageGlassSettings::toggleAlwaysOn));
            toggles.add(saved(DamageGlassSettings::players,DamageGlassSettings::togglePlayers));
            toggles.add(saved(DamageGlassSettings::mobs,DamageGlassSettings::toggleMobs));
            toggles.add(saved(GlassEffectSettings::customTint,GlassEffectSettings::toggleCustomTint));
            toggles.add(saved(WeatherGlassSettings::enabled,WeatherGlassSettings::toggleEnabled));
            toggles.add(saved(WeatherGlassSettings::alwaysActive,WeatherGlassSettings::toggleAlwaysActive));
            toggles.add(saved(VoicechatClient::isMiddleClickVolumeEnabled,VoicechatClient::toggleMiddleClickVolume));
        });
        try {
            context.runOnClient(client -> {
                try {
                    for (Field field : PlayerSettings.class.getFields()) if (field.getType()==boolean.class) {
                        player.put(field,field.getBoolean(null));
                        field.setBoolean(null,!isHiddenBar(field));
                    }
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                for (SavedToggle toggle : toggles) if (!toggle.state.getAsBoolean()) toggle.action.run();
                XrayFeature.setEnabled(true);
                for (var mineral : XrayFeature.Mineral.values()) if (!XrayFeature.selected(mineral)) XrayFeature.toggle(mineral);
            });
            float scale = PlayerSettings.xScale, range = PlayerSettings.killAuraRange;
            int blur = GlassEffectSettings.blurStep(), color = GlassEffectSettings.tintColor();
            context.setScreen(() -> new ClickGuiScreen(fps,VoicechatClient.openGuiKey(),coordinates,arraylist));
            pressAllOff(context);
            context.runOnClient(client -> {
                try {
                    for (Field field : player.keySet())
                        check(field.getBoolean(null)==isHiddenBar(field),"All off left a player toggle on: "+field.getName());
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                for (SavedToggle toggle : toggles) check(!toggle.state.getAsBoolean(),"All off left a HUD/effect option on");
                check(!XrayFeature.enabled(),"Xray remained active");
                for (var mineral : XrayFeature.Mineral.values()) check(!XrayFeature.selected(mineral),"Xray filter remained selected");
                check(PlayerSettings.xScale==scale && PlayerSettings.killAuraRange==range
                        && GlassEffectSettings.blurStep()==blur && GlassEffectSettings.tintColor()==color,"All off changed numeric settings");
                checkSavedOff("voicechat-spotify-hud.properties","enabled","edges","musicGlass");
                checkSavedOff("voicechat-logo-hud.properties","enabled","glass","edges");
                checkSavedOff("voicechat-metrics-hud.properties","cps","cpsGlass","cpsEdges","speed","speedGlass","speedEdges");
                checkSavedOff("voicechat-keystrokes-hud.properties","enabled","glass","edges","mouseButtons","spaceBar");
                checkSavedOff("voicechat-crosshair.properties","enabled","outline","centerDot","cooldownRing");
                check(!VoiceSettings.load().middleClickVolume,"All off must save the middle-click volume setting");
                checkSavedOff("voicechat-glass-effects.properties","customTint");
            });
            // The HP option is normally hidden in this state; a second click must still clear it.
            context.runOnClient(client -> PlayerSettings.mobHealthBar=true);
            pressAllOff(context);
            context.runOnClient(client -> {
                check(!PlayerSettings.mobHealthBar,"Hidden mob HP toggle was missed");
                for (SavedToggle toggle : toggles) check(!toggle.state.getAsBoolean(),"Repeated All off enabled an option");
                check(client.gui.screen() instanceof ClickGuiScreen,"All off closed the GUI");
            });
            context.waitTicks(5);
            context.takeScreenshot("clickgui-all-off");
            System.out.println("PASS: All off across categories, sub-options, saved preferences, conditional rebuilds, hidden HP and repeated clicks");
        } finally {
            context.runOnClient(client -> {
                try { for(var entry:player.entrySet())entry.getKey().setBoolean(null,entry.getValue()); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                VoicechatClient.syncSlayerCfg();
                for (SavedToggle toggle : toggles) if (toggle.state.getAsBoolean()!=toggle.previous) toggle.action.run();
                for (var mineral : XrayFeature.Mineral.values()) if (XrayFeature.selected(mineral)!=minerals[mineral.ordinal()]) XrayFeature.toggle(mineral);
                XrayFeature.setEnabled(xray);
            });
        }
    }
    private static boolean isHiddenBar(Field field) {
        return List.of("hideHealth","hideHunger","hideXp","hideLocator").contains(field.getName());
    }
    private static SavedToggle saved(BooleanSupplier state,Runnable action) {
        return new SavedToggle(state,action,state.getAsBoolean());
    }
    private record SavedToggle(BooleanSupplier state,Runnable action,boolean previous) {}
    private static void pressAllOff(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var screen=client.gui.screen();
            Button button=screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(w->w.getMessage().getString().equals("All off")).findFirst().orElseThrow();
            var event=new MouseButtonEvent((button.getX()+button.getWidth()/2.0)*ClickGuiScreen.UI_SCALE,
                    (button.getY()+8)*ClickGuiScreen.UI_SCALE,new MouseButtonInfo(0,0));
            check(screen.mouseClicked(event,false),"All off rejected scaled pointer input"); screen.mouseReleased(event);
        });
    }
    private static void checkSavedOff(String file,String... keys) {
        Properties saved=new Properties();
        try(var reader=Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(file))) { saved.load(reader); }
        catch(java.io.IOException e) { throw new AssertionError(e); }
        for(String key:keys)check("false".equals(saved.getProperty(key)),"All off was not saved: "+file+" "+key);
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
