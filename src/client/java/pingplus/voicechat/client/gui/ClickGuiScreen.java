package pingplus.voicechat.client.gui;

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

import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.FlipperMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;
import org.lwjgl.glfw.GLFW;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.MobEspFeature;
import pingplus.voicechat.client.VoicechatClient;
import pingplus.voicechat.client.gui.glass.*;
import java.util.*;
import java.util.function.*;

/** Floating category ClickGUI. All input and narration use native widget semantics. */
public final class ClickGuiScreen extends Screen {
    /** Render and hit-test in the same logical space, independently of Minecraft GUI scale. */
    public static final float UI_SCALE = 0.60f;
    private int canvasWidth, canvasHeight;
    private final FpsHud fpsHud;
    private final CoordinatesHud coordinatesHud;
    private final ArraylistHud arraylistHud;
    private final KeyMapping openGuiKey;
    private final List<Category> categories = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Runnable> offActions = new ArrayList<>();
    private final Map<String, OptionsPanel> optionPanels = new LinkedHashMap<>();
    private final Map<String, int[]> positions = new HashMap<>();
    private static final int OPTIONS_WIDTH = 220;
    private OptionsPanel buildingOptions;
    private String openOptions;
    private String mobEspInput = "", mobEspMessage = "";
    private Button optionsClose;
    private Button allOff;
    private int optionsX, optionsY, optionsWidth, optionsHeight, optionsScroll, optionsMaxScroll;
    private boolean draggingOptions;
    private int scroll, maxScroll, panelWidth;
    private long opened = System.nanoTime(), closing;
    private float opacity = 1;
    private Category dragging;
    private double dragX, dragY;
    private KeyMapping pendingKey;
    private static final Identifier WALLPAPER = Identifier.fromNamespaceAndPath("voicechat", "textures/gui/title_background.png");

    public ClickGuiScreen(FpsHud fpsHud, KeyMapping openGuiKey, CoordinatesHud coordinatesHud, ArraylistHud arraylistHud) {
        super(Component.literal("Client controls"));
        this.fpsHud=fpsHud; this.openGuiKey=openGuiKey; this.coordinatesHud=coordinatesHud; this.arraylistHud=arraylistHud;
    }

    @Override protected void init() {
        canvasWidth = (int)(width / UI_SCALE);
        canvasHeight = (int)(height / UI_SCALE);
        categories.clear(); entries.clear(); optionPanels.clear();
        offActions.clear();
        buildingOptions = null;
        allOff = addRenderableWidget(new Button(canvasWidth-162,7,72,18,Component.literal("All off"),
                b->disableAllSettings(),supplier->supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                GlassButtonRenderer.draw(g,this);
                g.nextStratum();
                text(g,"All off",getX()+12,getY()+4,GlassStyle.TEXT);
            }
        });
        allOff.setTooltip(Tooltip.create(Component.literal("Turn off every toggle, including extra settings.")));
        int columns = Math.clamp((canvasWidth-16)/148,2,4);
        panelWidth = Math.min(140, (canvasWidth-24-(columns-1)*8)/columns);
        int startX = 16;
        Category hud = category("HUD", "On-screen information");
        var crosshair = CustomCrosshairHud.INSTANCE;
        toggle(hud,"Crosshair",crosshair::isEnabled,crosshair::toggle);
        options(hud,"Crosshair options",()-> {
            add(hud,new Button(0,0,panelWidth-20,110,Component.literal("Crosshair preview"),b->{},supplier->supplier.get()) {
                { active=false; }
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                    g.fill(getX(),getY(),getRight(),getBottom(),GlassStyle.alpha(0xFF20242C,opacity));
                    g.nextStratum();
                    text(g,"Live preview",getX()+4,getY()+4,GlassStyle.MUTED);
                    crosshair.render(g,getX()+width/2,getY()+64,(System.nanoTime()%1_500_000_000L)/1_500_000_000f);
                }
            },110);
            add(hud,new Button(0,0,panelWidth-20,18,Component.literal("Shape: "+crosshair.shape().label),
                    b->{crosshair.cycleShape();rebuildWidgets();},supplier->supplier.get()) {
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                    if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                    text(g,getMessage().getString(),getX()+4,getY()+4,GlassStyle.MUTED);
                }
            },18);
            add(hud,new CombatSlider(panelWidth-20,"Size",1,24,1,crosshair::size,v->crosshair.setSize((int)v)),26);
            add(hud,new CombatSlider(panelWidth-20,"Gap",0,12,1,crosshair::gap,v->crosshair.setGap((int)v)),26);
            add(hud,new CombatSlider(panelWidth-20,"Thickness",1,6,1,crosshair::thickness,v->crosshair.setThickness((int)v)),26);
            add(hud,new EffectSlider(panelWidth-20,"Red",crosshair::red,crosshair::setRed,255,1,""),26);
            add(hud,new EffectSlider(panelWidth-20,"Green",crosshair::green,crosshair::setGreen,255,1,""),26);
            add(hud,new EffectSlider(panelWidth-20,"Blue",crosshair::blue,crosshair::setBlue,255,1,""),26);
            add(hud,new CombatSlider(panelWidth-20,"Opacity (%)",10,100,1,crosshair::opacity,v->crosshair.setOpacity((int)v)),26);
            toggle(hud,"Outline",crosshair::isOutline,crosshair::toggleOutline);
            toggle(hud,"Center dot",crosshair::isCenterDot,crosshair::toggleCenterDot);
            toggle(hud,"Attack cooldown ring",crosshair::isCooldownRing,crosshair::toggleCooldownRing);
            add(hud,new Button(0,0,panelWidth-20,18,Component.literal("Reset crosshair"),
                    b->{crosshair.resetAppearance();rebuildWidgets();},supplier->supplier.get()) {
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                    if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                    text(g,"Reset crosshair",getX()+4,getY()+4,GlassStyle.MUTED);
                }
            },18);
        });
        toggle(hud,"Frame rate",fpsHud::isEnabled,fpsHud::toggle);
        options(hud,"FPS options",()-> {
            toggle(hud,"FPS glass",fpsHud::isGlass,fpsHud::toggleGlass);
            toggle(hud,"FPS edges",fpsHud::isEdges,fpsHud::toggleEdges);
        });
        toggle(hud,"Ping",PingHud.INSTANCE::isEnabled,PingHud.INSTANCE::toggle);
        options(hud,"Ping options",()-> {
            toggle(hud,"Ping glass",PingHud.INSTANCE::isGlass,PingHud.INSTANCE::toggleGlass);
            toggle(hud,"Ping edges",PingHud.INSTANCE::isEdges,PingHud.INSTANCE::toggleEdges);
        });
        toggle(hud,"CPS",MetricsHud.CPS::isEnabled,MetricsHud.CPS::toggle);
        options(hud,"CPS options",()-> {
            toggle(hud,"CPS glass",MetricsHud.CPS::isGlass,MetricsHud.CPS::toggleGlass);
            toggle(hud,"CPS edges",MetricsHud.CPS::isEdges,MetricsHud.CPS::toggleEdges);
        });
        toggle(hud,"Speed",MetricsHud.SPEED::isEnabled,MetricsHud.SPEED::toggle);
        options(hud,"Speed options",()-> {
            toggle(hud,"Speed glass",MetricsHud.SPEED::isGlass,MetricsHud.SPEED::toggleGlass);
            toggle(hud,"Speed edges",MetricsHud.SPEED::isEdges,MetricsHud.SPEED::toggleEdges);
        });
        toggle(hud,"Keystrokes",KeystrokesHud.INSTANCE::isEnabled,KeystrokesHud.INSTANCE::toggle);
        options(hud,"Keystrokes options",()-> {
            toggle(hud,"Keystrokes glass",KeystrokesHud.INSTANCE::isGlass,KeystrokesHud.INSTANCE::toggleGlass);
            toggle(hud,"Keystrokes edges",KeystrokesHud.INSTANCE::isEdges,KeystrokesHud.INSTANCE::toggleEdges);
            toggle(hud,"Mouse buttons",KeystrokesHud.INSTANCE::isMouseButtons,KeystrokesHud.INSTANCE::toggleMouseButtons);
            toggle(hud,"Space bar",KeystrokesHud.INSTANCE::isSpaceBar,KeystrokesHud.INSTANCE::toggleSpaceBar);
        });
        toggle(hud,"Coordinates",coordinatesHud::isEnabled,coordinatesHud::toggle);
        options(hud,"XYZ options",()-> {
            toggle(hud,"XYZ glass",coordinatesHud::isGlass,coordinatesHud::toggleGlass);
            toggle(hud,"XYZ edges",coordinatesHud::isEdges,coordinatesHud::toggleEdges);
        });
        toggle(hud,"Spotify",pingplus.voicechat.client.spotify.SpotifySettings::enabled,pingplus.voicechat.client.spotify.SpotifySettings::toggle);
        options(hud,"Spotify options",()-> {
            toggle(hud,"Spotify edges",pingplus.voicechat.client.spotify.SpotifySettings::edges,pingplus.voicechat.client.spotify.SpotifySettings::toggleEdges);
            toggle(hud,"Music-reactive glass",pingplus.voicechat.client.spotify.SpotifySettings::musicGlass,pingplus.voicechat.client.spotify.SpotifySettings::toggleMusicGlass);
            add(hud,new EffectSlider(panelWidth-20,"Music tint",pingplus.voicechat.client.spotify.SpotifySettings::musicIntensity,
                    pingplus.voicechat.client.spotify.SpotifySettings::setMusicIntensity,100,1),26);
        });
        toggle(hud,"Arraylist",arraylistHud::isEnabled,arraylistHud::toggle);
        options(hud,"Arraylist options",()-> {
            toggle(hud,"Glass",arraylistHud::isGlass,arraylistHud::toggleGlass);
            toggle(hud,"Per-module boxes",arraylistHud::isRectangles,arraylistHud::toggleRectangles);
            toggle(hud,"Edges",arraylistHud::isEdges,arraylistHud::toggleEdges);
        });
        toggle(hud,"Scoreboard",ScoreboardHud.INSTANCE::isEnabled,ScoreboardHud.INSTANCE::toggle);
        options(hud,"Scoreboard options",()-> {
            toggle(hud,"Liquid glass",ScoreboardHud.INSTANCE::isGlass,ScoreboardHud.INSTANCE::toggleGlass);
            toggle(hud,"Glass edges",ScoreboardHud.INSTANCE::isEdges,ScoreboardHud.INSTANCE::toggleEdges);
        });
        toggle(hud,"Chat",ChatHud.INSTANCE::isEnabled,ChatHud.INSTANCE::toggle);
        options(hud,"Chat options",()-> {
            toggle(hud,"Chat glass",ChatHud.INSTANCE::isGlass,ChatHud.INSTANCE::toggleGlass);
            toggle(hud,"Chat edges",ChatHud.INSTANCE::isEdges,ChatHud.INSTANCE::toggleEdges);
        });
        options(hud,"Bars", null,()-> {
            toggle(hud,"Health bar",()->!PlayerSettings.hideHealth,()->PlayerSettings.hideHealth=!PlayerSettings.hideHealth);
            toggle(hud,"Hunger bar",()->!PlayerSettings.hideHunger,()->PlayerSettings.hideHunger=!PlayerSettings.hideHunger);
            toggle(hud,"XP bar",()->!PlayerSettings.hideXp,()->PlayerSettings.hideXp=!PlayerSettings.hideXp);
            toggle(hud,"Locator bar",()->!PlayerSettings.hideLocator,()->PlayerSettings.hideLocator=!PlayerSettings.hideLocator);
        });
        toggle(hud,"Dock hotbar",()->PlayerSettings.dockHotbar,()->PlayerSettings.dockHotbar=!PlayerSettings.dockHotbar);
        options(hud,"Dock options",()-> {
            add(hud,new DockScaleSlider(panelWidth-20),26);
            add(hud,new DockRadiusSlider(panelWidth-20),26);
            add(hud,new DockSizeSlider(panelWidth-20),26);
            toggle(hud,"Dock shelf",()->PlayerSettings.dockShelf,()->PlayerSettings.dockShelf=!PlayerSettings.dockShelf);
            toggle(hud,"Hotbar frame",()->PlayerSettings.dockFrames,()->PlayerSettings.dockFrames=!PlayerSettings.dockFrames);
            toggle(hud,"Auto hide",()->PlayerSettings.dockAutoHide,()->PlayerSettings.dockAutoHide=!PlayerSettings.dockAutoHide);
            add(hud,new DockAutoHideSlider(panelWidth-20),26);
        });
        toggle(hud,"AIR logo",LogoHud.INSTANCE::isEnabled,LogoHud.INSTANCE::toggle);
        options(hud,"Logo options",()-> {
            toggle(hud,"Logo glass",LogoHud.INSTANCE::isGlass,LogoHud.INSTANCE::toggleGlass);
            toggle(hud,"Logo edges",LogoHud.INSTANCE::isEdges,LogoHud.INSTANCE::toggleEdges);
        });
        Category render = category("RENDER", "See the details");
        toggle(render,"Glass damage effect",pingplus.voicechat.client.damageglass.DamageGlassSettings::enabled,pingplus.voicechat.client.damageglass.DamageGlassSettings::toggleEnabled);
        toggle(render,"Impact ripple",pingplus.voicechat.client.damageglass.DamageGlassSettings::impactRipple,pingplus.voicechat.client.damageglass.DamageGlassSettings::toggleImpactRipple);
        toggle(render,"Death melt glass",pingplus.voicechat.client.damageglass.DamageGlassSettings::deathWave,pingplus.voicechat.client.damageglass.DamageGlassSettings::toggleDeathWave);
        options(render,"Damage glass options", "Glass damage effect",()-> {
            add(render,new Button(0,0,panelWidth-20,18,Component.literal("Preset: "+pingplus.voicechat.client.damageglass.DamageGlassSettings.preset().label),
                    b->{pingplus.voicechat.client.damageglass.DamageGlassSettings.cyclePreset();rebuildWidgets();},supplier->supplier.get()) {
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                    if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                    text(g,getMessage().getString(),getX()+4,getY()+4,GlassStyle.MUTED);
                }
            },18);
            toggle(render,"Always-on glass",pingplus.voicechat.client.damageglass.DamageGlassSettings::alwaysOn,pingplus.voicechat.client.damageglass.DamageGlassSettings::toggleAlwaysOn);
            add(render,new EffectSlider(panelWidth-20,"Reflectivity",
                    pingplus.voicechat.client.damageglass.DamageGlassSettings::reflectivity,
                    pingplus.voicechat.client.damageglass.DamageGlassSettings::setReflectivity,100,1),26);
            toggle(render,"Players",pingplus.voicechat.client.damageglass.DamageGlassSettings::players,pingplus.voicechat.client.damageglass.DamageGlassSettings::togglePlayers);
            toggle(render,"Mobs",pingplus.voicechat.client.damageglass.DamageGlassSettings::mobs,pingplus.voicechat.client.damageglass.DamageGlassSettings::toggleMobs);
        });
        toggle(render,"Hitboxes",()->PlayerSettings.hitboxes,()->PlayerSettings.hitboxes=!PlayerSettings.hitboxes);
        toggle(render,"Projectile preview",()->PlayerSettings.projectilePreview,()->PlayerSettings.projectilePreview=!PlayerSettings.projectilePreview);
        toggle(render,"Fullbright",()->PlayerSettings.fullbright,()->PlayerSettings.fullbright=!PlayerSettings.fullbright);
        toggle(render,"MobESP",()->PlayerSettings.mobEsp,MobEspFeature::toggle);
        options(render,"MobESP options",()-> {
            mobEspLabel(render,"Add mob names (comma separated)");
            add(render,new MobNameBox(),22);
            mobEspButton(render,"Add mob(s)",this::addMobEspNames);
            mobEspLabel(render,mobEspMessage.isEmpty() ? "e.g. zombie, skeleton, iron_golem" : mobEspMessage);
            if (MobEspFeature.selected().isEmpty()) mobEspLabel(render,"No mobs selected.");
            for (Identifier id : MobEspFeature.selected()) {
                String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
                mobEspButton(render,"Remove: "+name,()-> { MobEspFeature.remove(id); rebuildWidgets(); });
            }
        });
        toggle(render,"Storage ESP",()->PlayerSettings.storageEsp,pingplus.voicechat.client.StorageEspFeature::toggle);
        options(render,"Storage ESP options",()-> {
            for (var kind : pingplus.voicechat.client.StorageEspFeature.Kind.values())
                toggle(render,kind.label,kind::enabled,kind::toggle);
            toggle(render,"Filled boxes",()->PlayerSettings.storageEspFill,()->PlayerSettings.storageEspFill=!PlayerSettings.storageEspFill);
            add(render,new CombatSlider(panelWidth-20,"Storage range",16,128,16,
                    pingplus.voicechat.client.StorageEspFeature::range,v->pingplus.voicechat.client.StorageEspFeature.setRange((int)v)),26);
        });
        toggle(render,"Xray",pingplus.voicechat.client.XrayFeature::enabled,pingplus.voicechat.client.XrayFeature::toggle);
        options(render,"Xray options",()-> {
            for (var mineral : pingplus.voicechat.client.XrayFeature.Mineral.values())
                toggle(render,mineral.label,()->pingplus.voicechat.client.XrayFeature.selected(mineral),
                        ()->pingplus.voicechat.client.XrayFeature.toggle(mineral));
        });
        toggle(render,"Zoom",()->PlayerSettings.zoom,()->PlayerSettings.zoom=!PlayerSettings.zoom);
        options(render,"Zoom options",()-> {
            add(render,new ZoomStrengthSlider(panelWidth-20),26);
        });
        toggle(render,"Shoulder cam",()->PlayerSettings.shoulderCam,()->PlayerSettings.shoulderCam=!PlayerSettings.shoulderCam);
        options(render,"Glass style",null,()-> {
            add(render,new CornerSlider(panelWidth-20),26);
            add(render,new EffectSlider(panelWidth-20,"Glass blur",GlassEffectSettings::blurStep,GlassEffectSettings::setBlur),26);
            add(render,new EffectSlider(panelWidth-20,"Glass shadow",GlassEffectSettings::shadowStep,GlassEffectSettings::setShadow),26);
            var customTint = new Toggle("Custom glass color",GlassEffectSettings::customTint,GlassEffectSettings::toggleCustomTint);
            customTint.setTooltip(Tooltip.create(Component.literal("Use this color for glass throughout the HUD and menus. Overrides Spotify tint while enabled.")));
            add(render,customTint,18);
            add(render,new EffectSlider(panelWidth-20,"Red",GlassEffectSettings::tintRed,GlassEffectSettings::setTintRed,255,1,""),26);
            add(render,new EffectSlider(panelWidth-20,"Green",GlassEffectSettings::tintGreen,GlassEffectSettings::setTintGreen,255,1,""),26);
            add(render,new EffectSlider(panelWidth-20,"Blue",GlassEffectSettings::tintBlue,GlassEffectSettings::setTintBlue,255,1,""),26);
            add(render,new EffectSlider(panelWidth-20,"Glass tint",GlassEffectSettings::tintIntensity,GlassEffectSettings::setTintIntensity,100,1),26);
        });
        toggle(render,"Weather Glass",WeatherGlassSettings::enabled,WeatherGlassSettings::toggleEnabled);
        options(render,"Weather Glass options",()-> {
            add(render,new Button(0,0,panelWidth-20,18,Component.literal("Weather mode: "+WeatherGlassSettings.mode().label),
                    b->{WeatherGlassSettings.cycleMode();rebuildWidgets();},supplier->supplier.get()) {
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                    if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                    text(g,getMessage().getString(),getX()+4,getY()+4,GlassStyle.MUTED);
                }
            },18);
            var alwaysWeather = new Toggle("Always active",WeatherGlassSettings::alwaysActive,WeatherGlassSettings::toggleAlwaysActive);
            alwaysWeather.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                    "Rain and Frost stay active everywhere, including indoors and menus. Automatic always follows local weather and shelter.")));
            add(render,alwaysWeather,18);
            add(render,new EffectSlider(panelWidth-20,"Weather intensity",WeatherGlassSettings::intensity,WeatherGlassSettings::setIntensity,100,1),26);
            add(render,new Button(0,0,panelWidth-20,38,Component.empty(),b->{},supplier->supplier.get()) {
                { active=false; }
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                    text(g,"Always active:",getX()+4,getY()+2,GlassStyle.MUTED);
                    text(g,"Rain / Frost anywhere",getX()+4,getY()+14,GlassStyle.MUTED);
                    text(g,"Auto: weather + shelter",getX()+4,getY()+26,GlassStyle.MUTED);
                }
            },38);
        });
        Category player = category("PLAYER", "Shape your presence");
        toggle(player,"Force Sneak",()->PlayerSettings.forceSneak,()->PlayerSettings.forceSneak=!PlayerSettings.forceSneak);
        toggle(player,"Body",()->PlayerSettings.mainBodyPart,()->PlayerSettings.mainBodyPart=!PlayerSettings.mainBodyPart);
        toggle(player,"Left arm",()->PlayerSettings.leftArm,()->PlayerSettings.leftArm=!PlayerSettings.leftArm);
        toggle(player,"Right arm",()->PlayerSettings.rightArm,()->PlayerSettings.rightArm=!PlayerSettings.rightArm);
        options(player,"Player scale", null,()-> {
            scale(player,"Width",PlayerSettings.xScale,v->PlayerSettings.xScale=v);
            scale(player,"Height",PlayerSettings.yScale,v->PlayerSettings.yScale=v);
            scale(player,"Depth",PlayerSettings.zScale,v->PlayerSettings.zScale=v);
            scale(player,"Head",PlayerSettings.xyzHeadscale,v->PlayerSettings.xyzHeadscale=v);
        });

        toggle(player, "Direction", ()->PlayerSettings.direction,()->PlayerSettings.direction = !PlayerSettings.direction);
        toggle(player,"Middle-click volume",VoicechatClient::isMiddleClickVolumeEnabled,VoicechatClient::toggleMiddleClickVolume);
        Category automation = category("AUTOMATION", "Small actions, effortless");
        toggle(automation,"Auto Tools",()->PlayerSettings.autoTools,()->{
            PlayerSettings.autoTools=!PlayerSettings.autoTools;
            if (!PlayerSettings.autoTools) pingplus.voicechat.client.AutoToolsFeature.finish(net.minecraft.client.Minecraft.getInstance());
        });
        options(automation,"Auto Tools options",()-> {
            toggle(automation,"Restore slot",()->PlayerSettings.autoToolsRestore,()->PlayerSettings.autoToolsRestore=!PlayerSettings.autoToolsRestore);
        });
        toggle(automation,"Fast Place",()->PlayerSettings.fastPlace,()->PlayerSettings.fastPlace=!PlayerSettings.fastPlace);
        options(automation,"Fast Place options",()-> {
            add(automation,new CombatSlider(panelWidth-20,"Place delay (ticks)",1,4,1,
                    pingplus.voicechat.client.FastPlaceFeature::delayTicks,v->PlayerSettings.fastPlaceDelayTicks=(int)v),26);
        });
        toggle(automation,"KillAura",()->PlayerSettings.killAura,()->{
            PlayerSettings.killAura=!PlayerSettings.killAura;
            if (!PlayerSettings.killAura) pingplus.voicechat.client.KillAuraFeature.clear();
        });
        options(automation,"KillAura options",()-> {
            toggle(automation,"Target players",()->PlayerSettings.killAuraPlayers,()->PlayerSettings.killAuraPlayers=!PlayerSettings.killAuraPlayers);
            toggle(automation,"Target mobs",()->PlayerSettings.killAuraMobs,()->PlayerSettings.killAuraMobs=!PlayerSettings.killAuraMobs);
            add(automation,new CombatSlider(panelWidth-20,"Range",1,4,.1,()->PlayerSettings.killAuraRange,v->PlayerSettings.killAuraRange=(float)v),26);
            add(automation,new CombatSlider(panelWidth-20,"Turn speed",45,540,15,()->PlayerSettings.killAuraTurnSpeed,v->PlayerSettings.killAuraTurnSpeed=(float)v),26);
        });
        nameBox(automation,"Follow",()->PlayerSettings.followTarget,v->PlayerSettings.followTarget=v);
        toggle(automation,"Look at nearby players",()->PlayerSettings.followLookAtNearbyPlayers,
            ()->PlayerSettings.followLookAtNearbyPlayers=!PlayerSettings.followLookAtNearbyPlayers);
        toggle(automation,"Hand swap",()->PlayerSettings.handSwap,()->PlayerSettings.handSwap=!PlayerSettings.handSwap);
        options(automation,"Swap interval",()-> {
            add(automation,new SpeedSlider(panelWidth-20),26);
        });
        Category skyblock = category("SKYBLOCK", "Hypixel skyblock helpers");
        toggle(skyblock,"Auction Flipper",AhFlipperMod::isRunning,AhFlipperMod::toggle);
        toggle(skyblock,"Bazaar Flipper",FlipperMod::isRunning,FlipperMod::toggle);
        options(skyblock,"General", null,()-> {
            toggle(skyblock,"No particles",()->PlayerSettings.hideParticles,()->PlayerSettings.hideParticles=!PlayerSettings.hideParticles);
            toggle(skyblock,"No mob names",()->PlayerSettings.hideMobNames,()->{
                PlayerSettings.hideMobNames=!PlayerSettings.hideMobNames;
                rebuildWidgets();
            });
            // Health bar is only unlocked while mob names are hidden.
            if (PlayerSettings.hideMobNames) {
                toggle(skyblock,"Mob HP bar",()->PlayerSettings.mobHealthBar,()->PlayerSettings.mobHealthBar=!PlayerSettings.mobHealthBar);
                add(skyblock,new PercentSlider(panelWidth-20),26);
            }
            toggle(skyblock,"Slayer outline",()->PlayerSettings.slayerOutline,()->{
                PlayerSettings.slayerOutline=!PlayerSettings.slayerOutline;
                PlayerSettings.slayerBossHighlight=PlayerSettings.slayerOutline;
                VoicechatClient.syncSlayerCfg();
            });
            toggle(skyblock,"Boss yellow",()->PlayerSettings.slayerBoss,()->{
                PlayerSettings.slayerBoss=!PlayerSettings.slayerBoss;
                VoicechatClient.syncSlayerCfg();
            });
            toggle(skyblock,"Miniboss red",()->PlayerSettings.slayerMiniboss,()->{
                PlayerSettings.slayerMiniboss=!PlayerSettings.slayerMiniboss;
                VoicechatClient.syncSlayerCfg();
            });
            toggle(skyblock,"Boss lines",()->PlayerSettings.slayerLine,()->PlayerSettings.slayerLine=!PlayerSettings.slayerLine);
            toggle(skyblock,"Etherwarp helper",()->PlayerSettings.etherwarpHelper,()->PlayerSettings.etherwarpHelper=!PlayerSettings.etherwarpHelper);
            toggle(skyblock,"Self glow",()->PlayerSettings.selfOutline,()->PlayerSettings.selfOutline=!PlayerSettings.selfOutline);
            toggle(skyblock,"AGG",()->pingplus.voicechat.client.hud.AggSettings.isEnabled(),()->pingplus.voicechat.client.hud.AggSettings.setEnabled(!pingplus.voicechat.client.hud.AggSettings.isEnabled()));
            toggle(skyblock,"Player outline",()->PlayerSettings.playerOutline,()->PlayerSettings.playerOutline=!PlayerSettings.playerOutline);
        });
        Category keys = category("KEYS", "Hotkeys, click then press");
        keyButton(keys, "GUI", VoicechatClient.openGuiKey());
        keyButton(keys, "HUD editor", VoicechatClient.hudEditorKey());
        keyButton(keys, "Voice", VoicechatClient.voiceMenuKey());
        keyButton(keys, "Talk", VoicechatClient.talkKey());
        keyButton(keys, "Mute", VoicechatClient.muteKey());
        keyButton(keys, "Zoom", VoicechatClient.zoomKey());
        keyButton(keys, "Shoulder cam", VoicechatClient.shoulderCamKey());
        keyButton(keys, "Killaura", VoicechatClient.killAuraKey());
        keyButton(keys, "Auction Flipper", AhFlipperMod.toggleKey());
        keyButton(keys, "Bazaar Flipper", FlipperMod.toggleKey());
        add(keys, new Button(0,0,panelWidth-20,18,Component.literal("Reset keys"),b->{resetKeys();rebuildWidgets();},supplier->supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                text(g,"Reset keys",getX()+4,getY()+4,GlassStyle.MUTED);
            }
        },18);
        // Stack shorter categories together, keeping the full Player settings column visible.
        int[] columnY = new int[columns];
        Arrays.fill(columnY, 30);
        int[] columnFor = columns == 2 ? new int[]{0,0,1,0,1,0}
                : columns == 3 ? new int[]{0,0,1,2,1,2} : new int[]{0,1,2,3,1,3};
        for (int i=0; i<categories.size(); i++) {
            Category c=categories.get(i);
            int col=columnFor[Math.min(i, columnFor.length-1)];
            c.x=startX+col*(panelWidth+8); c.y=columnY[col];
            columnY[col]+=c.height()+6;
            int[] saved=positions.get(c.title);
            if(saved!=null){
                c.x=Math.clamp(saved[0],8,Math.max(8,canvasWidth-panelWidth-8));
                c.y=Math.clamp(saved[1],28,Math.max(28,canvasHeight-18-c.height()));
            }
        }
        updateScroll(); layout();
        installOptionsWindow();
    }
    private void disableAllSettings() {
        openOptions = null;
        pendingKey = null;
        // Some toggle callbacks rebuild the widgets. Keep this complete snapshot until all are off.
        for (Runnable off : List.copyOf(offActions)) off.run();
        // This toggle may be hidden when "No mob names" is already off.
        PlayerSettings.mobHealthBar = false;
        PlayerSettings.slayerBossHighlight = false;
        VoicechatClient.syncSlayerCfg();
        if (GlassRainSettings.isEnabled()) GlassRainSettings.setEnabled(false);
        rebuildWidgets();
    }
    private Category category(String name,String subtitle) { Category c=new Category(name,subtitle);categories.add(c);return c; }
    private void add(Category c,AbstractWidget w,int h) {
        if (buildingOptions != null) {
            if (!(w instanceof EditBox)) w.setWidth(OPTIONS_WIDTH - 20);
            buildingOptions.entries.add(new OptionEntry(w, buildingOptions.content));
            buildingOptions.content += h;
        } else {
            entries.add(new Entry(c,addRenderableWidget(w),c.content));
            c.content += h;
        }
    }
    private void toggle(Category c,String name,BooleanSupplier state,Runnable action) { add(c,new Toggle(name,state,action),18); }
    private void options(Category c,String name,Runnable controls) {
        Entry last = entries.isEmpty() ? null : entries.getLast();
        String anchor = last != null && last.category == c && last.widget instanceof Toggle
                ? last.widget.getMessage().getString() : null;
        options(c,name,anchor,controls);
    }
    private void options(Category c,String name,String anchor,Runnable controls) {
        Entry owner = entries.stream().filter(e -> e.category == c && e.widget.getMessage().getString().equals(anchor))
                .findFirst().orElse(null);
        if (owner == null) {
            add(c,new Button(0,0,panelWidth-20,18,Component.literal(name),b->openOptions(name,b),supplier->supplier.get()) {
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                    if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                    text(g,name,getX()+4,getY()+4,GlassStyle.MUTED);
                }
            },18);
            owner = entries.getLast();
        }
        owner.widget.setWidth(panelWidth-42);
        OptionGear gear = new OptionGear(name);
        entries.add(new Entry(c,addRenderableWidget(gear),owner.row));
        OptionsPanel panel = new OptionsPanel(name);
        optionPanels.put(name,panel);
        buildingOptions = panel;
        try { controls.run(); } finally { buildingOptions = null; }
    }
    private void openOptions(String name,AbstractWidget anchor) {
        openOptions = name;
        optionsScroll = 0;
        optionsX = anchor.getRight()+8;
        if (optionsX+OPTIONS_WIDTH > canvasWidth-8) optionsX = anchor.getX()-OPTIONS_WIDTH-8;
        optionsY = anchor.getY()-4;
        dragging = null;
        draggingOptions = false;
        setDragging(false);
        rebuildWidgets();
    }
    private void closeOptions() {
        openOptions = null;
        draggingOptions = false;
        setDragging(false);
        setFocused(null);
        rebuildWidgets();
    }
    private void installOptionsWindow() {
        OptionsPanel panel = optionPanels.get(openOptions);
        if (panel == null) { openOptions = null; return; }
        optionsWidth = Math.min(OPTIONS_WIDTH,canvasWidth-16);
        optionsHeight = Math.min(panel.content+34,canvasHeight-50);
        optionsMaxScroll = Math.max(0,panel.content-(optionsHeight-34));
        optionsScroll = Math.clamp(optionsScroll,0,optionsMaxScroll);
        optionsX = Math.clamp(optionsX,8,canvasWidth-optionsWidth-8);
        optionsY = Math.clamp(optionsY,28,canvasHeight-optionsHeight-18);
        for (Entry e : entries) e.widget.active = false;
        allOff.active = false;
        for (OptionEntry e : panel.entries) {
            if (!(e.widget instanceof EditBox) || e.widget instanceof MobNameBox) e.widget.setWidth(optionsWidth-20);
            addRenderableWidget(e.widget);
        }
        optionsClose = addRenderableWidget(new Button(0,0,18,18,Component.literal("Close "+panel.title),
                b->closeOptions(),supplier->supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                text(g,"x",getX()+6,getY()+4,GlassStyle.TEXT);
            }
        });
        layoutOptions();
        setFocused(panel.entries.stream().map(OptionEntry::widget).filter(w->w.active).findFirst().orElse(optionsClose));
    }
    private void layoutOptions() {
        OptionsPanel panel = optionPanels.get(openOptions);
        if (panel == null) return;
        for (OptionEntry e : panel.entries) {
            e.widget.setX(optionsX+(e.widget instanceof EditBox && !(e.widget instanceof MobNameBox)?optionsWidth-65:10));
            e.widget.setY(optionsY+26+e.row-optionsScroll);
        }
        optionsClose.setPosition(optionsX+optionsWidth-23,optionsY+3);
    }
    private boolean insideOptions(double x,double y) {
        return x>=optionsX && x<optionsX+optionsWidth && y>=optionsY && y<optionsY+optionsHeight;
    }
    private void drawOptions(GuiGraphicsExtractor g,int mx,int my,float dt) {
        OptionsPanel panel = optionPanels.get(openOptions);
        if (panel == null) return;
        g.nextStratum();
        g.fill(0,26,canvasWidth,canvasHeight-16,GlassStyle.alpha(0x38000000,opacity));
        g.nextStratum();
        GlassStyle.surface(g,optionsX,optionsY,optionsWidth,optionsHeight,opacity,.2f);
        g.nextStratum();
        text(g,panel.title,optionsX+10,optionsY+8,GlassStyle.TEXT);
        GlassButtonRenderer.control(g,optionsX+10,optionsY+22,optionsWidth-20,1,GlassStyle.alpha(0xFFBBBBBB,opacity*.5f));
        optionsClose.setAlpha(opacity);
        optionsClose.extractRenderState(g,mx,my,dt);
        g.enableScissor(optionsX+6,optionsY+26,optionsX+optionsWidth-6,optionsY+optionsHeight-6);
        for (OptionEntry e : panel.entries) {
            e.widget.setAlpha(opacity);
            e.widget.extractRenderState(g,mx,my,dt);
        }
        g.disableScissor();
        if (optionsMaxScroll>0) {
            int track=optionsHeight-34;
            int thumb=Math.max(12,track*track/panel.content);
            int y=optionsY+26+(track-thumb)*optionsScroll/optionsMaxScroll;
            g.nextStratum();
            GlassButtonRenderer.control(g,optionsX+optionsWidth-5,y,2,thumb,GlassStyle.alpha(GlassStyle.MUTED,opacity*.7f));
        }
    }
    private void scale(Category c,String label,float value,Consumer<Float> setter) {
        EditBox box=new EditBox(font,0,0,45,14,Component.literal(label+" scale")) {
            @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
                text(g,label,getX()-(optionsWidth-80),getY()+3,GlassStyle.MUTED);
                GlassButtonRenderer.control(g,getX()-5,getY()-2,getWidth()+10,18,GlassStyle.alpha(isFocused()?0xFF555555:0xFF303030,opacity));
                g.nextStratum();super.extractWidgetRenderState(g,mx,my,dt);
            }
        };
        box.addFormatter((text,index)->net.minecraft.util.FormattedCharSequence.forward(text,GlassStyle.FONT));
        box.setBordered(false);box.setTextShadow(false);box.setTextColor(GlassStyle.TEXT);box.setMaxLength(1000);box.setValue(Float.toString(value));
        box.setResponder(text->{
            try {float n=Float.parseFloat(text);boolean valid=Float.isFinite(n)&&n>=-100000&&n<=100000;
                box.setTextColor(valid?GlassStyle.TEXT:0xFFFF9B99);if(valid)setter.accept(n);
            }catch(NumberFormatException ignored){box.setTextColor(0xFFFF9B99);}
        });
        add(c,box,20);
    }
    private void nameBox(Category c,String label,Supplier<String> getter,Consumer<String> setter) {
        EditBox box=new EditBox(font,0,0,55,14,Component.literal(label)) {
            @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
                text(g,label,getX()-(optionsWidth-80),getY()+3,GlassStyle.MUTED);
                GlassButtonRenderer.control(g,getX()-5,getY()-2,getWidth()+10,18,GlassStyle.alpha(isFocused()?0xFF555555:0xFF303030,opacity));
                g.nextStratum();super.extractWidgetRenderState(g,mx,my,dt);
            }
        };
        box.addFormatter((text,index)->net.minecraft.util.FormattedCharSequence.forward(text,GlassStyle.FONT));
        box.setBordered(false);box.setTextShadow(false);box.setTextColor(GlassStyle.TEXT);box.setMaxLength(16);box.setValue(getter.get());
        box.setResponder(setter);
        add(c,box,20);
    }
    private void mobEspLabel(Category c,String label) {
        var button = mobEspButton(c,label,()->{});
        button.active = false;
    }
    private void addMobEspNames() {
        String error = MobEspFeature.add(mobEspInput);
        mobEspMessage = error == null ? "Added. Click a mob below to remove." : error;
        if (error == null) mobEspInput = "";
        rebuildWidgets();
    }
    private Button mobEspButton(Category c,String label,Runnable action) {
        var button = new Button(0,0,OPTIONS_WIDTH-20,18,Component.literal(label),b->action.run(),supplier->supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                if(active && isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                GlassStyle.line(g,label,getX()+4,getY()+4,width-8,GlassStyle.alpha(GlassStyle.MUTED,opacity));
            }
        };
        button.setTooltip(Tooltip.create(Component.literal(label)));
        add(c,button,18);
        return button;
    }
    private final class MobNameBox extends EditBox {
        MobNameBox() {
            super(ClickGuiScreen.this.font,0,0,OPTIONS_WIDTH-20,18,Component.literal("Mob names"));
            addFormatter((value,index)->net.minecraft.util.FormattedCharSequence.forward(value,GlassStyle.FONT));
            setBordered(false);setTextShadow(false);setTextColor(GlassStyle.TEXT);setMaxLength(512);
            setHint(Component.literal("zombie, skeleton"));
            setValue(mobEspInput);
            setResponder(value->mobEspInput=value);
        }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
            GlassButtonRenderer.control(g,getX()-2,getY()-2,getWidth()+4,20,GlassStyle.alpha(isFocused()?0xFF555555:0xFF303030,opacity));
            g.nextStratum();super.extractWidgetRenderState(g,mx,my,dt);
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key()==GLFW.GLFW_KEY_ENTER || event.key()==GLFW.GLFW_KEY_KP_ENTER) {
                addMobEspNames();return true;
            }
            return super.keyPressed(event);
        }
    }
    private void keyButton(Category c, String name, KeyMapping mapping) {
        String label = mapping == null ? name + ": -"
            : mapping == pendingKey ? name + ": press..." : name + ": " + mapping.getTranslatedKeyMessage().getString();
        add(c, new Button(0,0,panelWidth-20,18,Component.literal(label),b->{pendingKey=mapping;rebuildWidgets();},supplier->supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                if(isHoveredOrFocused() || mapping == pendingKey) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                text(g,mapping == pendingKey ? name + ": press..." : name + ": " + (mapping == null ? "-" : mapping.getTranslatedKeyMessage().getString()),getX()+4,getY()+4,mapping == pendingKey ? GlassStyle.ACCENT : GlassStyle.MUTED);
            }
        },18);
    }
    private void resetKeys() {
        if (VoicechatClient.openGuiKey() != null) VoicechatClient.openGuiKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT));
        if (VoicechatClient.hudEditorKey() != null) VoicechatClient.hudEditorKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G));
        if (VoicechatClient.voiceMenuKey() != null) VoicechatClient.voiceMenuKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V));
        if (VoicechatClient.talkKey() != null) VoicechatClient.talkKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_CAPS_LOCK));
        if (VoicechatClient.muteKey() != null) VoicechatClient.muteKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M));
        if (VoicechatClient.zoomKey() != null) VoicechatClient.zoomKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_C));
        if (VoicechatClient.shoulderCamKey() != null) VoicechatClient.shoulderCamKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_R));
        if (VoicechatClient.killAuraKey() != null) VoicechatClient.killAuraKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Z));
        if (AhFlipperMod.toggleKey() != null) AhFlipperMod.toggleKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9));
        if (FlipperMod.toggleKey() != null) FlipperMod.toggleKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8));
        pendingKey = null;
        saveKeys();
    }
    private void saveKeys() {
        KeyMapping.resetMapping();
        try { minecraft.options.save(); } catch (Exception ignored) {}
    }
    private void updateScroll() {
        maxScroll=Math.max(0,categories.stream().mapToInt(c->c.y+c.height()).max().orElse(0)-(canvasHeight-16));
        scroll=Math.clamp(scroll,0,maxScroll);
    }
    private void layout() {
        for(Entry e:entries){
            e.widget.setX(e.category.x+(e.widget instanceof OptionGear?panelWidth-28:10));
            e.widget.setY(e.category.y+22+e.row-scroll);
        }
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float dt) {
        if(minecraft.level==null){
            double s=Math.max(width/1672.0,height/941.0);int w=(int)Math.ceil(1672*s),h=(int)Math.ceil(941*s);
            g.blit(RenderPipelines.GUI_TEXTURED,WALLPAPER,(width-w)/2,(height-h)/2,0,0,w,h,1672,941,1672,941);
        }
        g.fill(0,0,width,height,GlassStyle.alpha(0x50000000,opacity));
        pingplus.voicechat.client.gui.glass.GlassRain.draw(g);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
        opacity=closing==0?Math.clamp((System.nanoTime()-opened)/220_000_000f,0,1):1-Math.clamp((System.nanoTime()-closing)/160_000_000f,0,1);
        extractBackground(g,mx,my,dt);
        g.pose().pushMatrix();
        g.pose().scale(UI_SCALE);
        mx = (int)(mx / UI_SCALE);
        my = (int)(my / UI_SCALE);
        text(g,"UNTITLED",16,13,GlassStyle.TEXT);text(g,"/  CONTROL CENTER",74,13,GlassStyle.MUTED);
        allOff.setAlpha(opacity);
        allOff.extractRenderState(g,mx,my,dt);
        text(g,"ESC  /  CLOSE",canvasWidth-78,13,GlassStyle.MUTED);
        g.enableScissor(0,26,canvasWidth,canvasHeight-16);
        for(Category c:categories){
            int y=c.y-scroll;
            float light=.15f+.55f*(1-Math.clamp((float)Math.hypot(mx-c.x-panelWidth*.3,my-y-12)/200,0,1));
            GlassStyle.surface(g,c.x,y,panelWidth,c.height(),opacity,light);
        }
        g.nextStratum();
        for(Category c:categories){
            int y=c.y-scroll;
            text(g,c.title,c.x+10,y+6,GlassStyle.TEXT);
            GlassButtonRenderer.control(g,c.x+10,y+18,panelWidth-20,1,GlassStyle.alpha(0xFFBBBBBB,opacity*.5f));
            for(Entry e:entries)if(e.category==c){e.widget.setAlpha(opacity);e.widget.extractRenderState(g,openOptions==null?mx:-100,openOptions==null?my:-100,dt);}
        }
        g.disableScissor();
        text(g,"DRAG HEADERS  /  GEARS OPEN OPTIONS",16,canvasHeight-13,GlassStyle.MUTED);
        if(maxScroll>0)text(g,"SCROLL",canvasWidth-48,canvasHeight-13,GlassStyle.ACCENT);
        drawOptions(g,mx,my,dt);
        g.pose().popMatrix();
    }
    private void text(GuiGraphicsExtractor g,String value,int x,int y,int color){g.text(font,GlassStyle.label(value),x,y,GlassStyle.alpha(color,opacity),false);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if (openOptions!=null) {
            if (insideOptions(x/UI_SCALE,y/UI_SCALE)) {
                optionsScroll=Math.clamp(optionsScroll-(int)(vertical*28),0,optionsMaxScroll);
                layoutOptions();
            }
            return true;
        }
        scroll=Math.clamp(scroll-(int)(vertical*28),0,maxScroll);layout();return true;
    }
    private MouseButtonEvent logicalMouse(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() / UI_SCALE, event.y() / UI_SCALE, event.buttonInfo());
    }
    @Override public void mouseMoved(double x, double y) { super.mouseMoved(x / UI_SCALE, y / UI_SCALE); }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
        if (pendingKey != null) {
            try {
                pendingKey.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
                saveKeys();
            } catch (Exception ignored) {}
            pendingKey = null;
            rebuildWidgets();
            return true;
        }
        MouseButtonEvent e = logicalMouse(event);
        if(openGuiKey != null && openGuiKey.matchesMouse(e)){onClose();return true;}
        if(closing!=0)return true;
        if(openOptions!=null){
            if (!insideOptions(e.x(),e.y())) { closeOptions(); return true; }
            if (optionsClose.mouseClicked(e,twice)) return true;
            if (e.button()==0 && e.y()<optionsY+24) {
                draggingOptions=true;dragX=e.x()-optionsX;dragY=e.y()-optionsY;return true;
            }
            if (e.y()>=optionsY+26 && e.y()<optionsY+optionsHeight-6) {
                for (OptionEntry entry:optionPanels.get(openOptions).entries) {
                    if(entry.widget.mouseClicked(e,twice)){
                        // A mode or conditional toggle may rebuild the window during its callback.
                        if(children().contains(entry.widget)){setFocused(entry.widget);setDragging(true);}
                        return true;
                    }
                }
            }
            return true;
        }
        if(e.y()<26){
            if(allOff.mouseClicked(e,twice))return true;
            if(e.x()>canvasWidth-85)onClose();
            return true;
        }
        if(e.y()>canvasHeight-16)return true;
        for(int i=categories.size()-1;i>=0;i--){Category c=categories.get(i);int y=c.y-scroll;
            if(e.button()==0&&e.x()>=c.x&&e.x()<c.x+panelWidth&&e.y()>=y&&e.y()<y+20){dragging=c;dragX=e.x()-c.x;dragY=e.y()-y;return true;}
            if(e.x()>=c.x&&e.x()<c.x+panelWidth&&e.y()>=y&&e.y()<y+c.height()){
                for(Entry entry:entries)if(entry.category==c&&entry.widget.mouseClicked(e,twice)){if(children().contains(entry.widget)){setFocused(entry.widget);setDragging(true);}return true;}
                return true;
            }
        }
        return false;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        MouseButtonEvent e = logicalMouse(event);
        dx /= UI_SCALE; dy /= UI_SCALE;
        if(draggingOptions){
            optionsX=Math.clamp((int)(e.x()-dragX),8,canvasWidth-optionsWidth-8);
            optionsY=Math.clamp((int)(e.y()-dragY),28,canvasHeight-optionsHeight-18);
            layoutOptions();return true;
        }
        if(dragging!=null){dragging.x=Math.clamp((int)(e.x()-dragX),8,Math.max(8,canvasWidth-panelWidth-8));dragging.y=Math.clamp((int)(e.y()-dragY)+scroll,28,Math.max(28,canvasHeight-18-dragging.height()));positions.put(dragging.title,new int[]{dragging.x,dragging.y});updateScroll();layout();return true;}
        return super.mouseDragged(e,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e){if(draggingOptions){draggingOptions=false;return true;}if(dragging!=null){dragging=null;return true;}return super.mouseReleased(logicalMouse(e));}
    @Override public boolean keyPressed(KeyEvent e){
        if (pendingKey != null) {
            if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
                pendingKey = null;
                rebuildWidgets();
                return true;
            }
            try {
                pendingKey.setKey(InputConstants.getKey(e));
                saveKeys();
            } catch (Exception ignored) {}
            pendingKey = null;
            rebuildWidgets();
            return true;
        }
        if(openOptions!=null && e.key()==GLFW.GLFW_KEY_ESCAPE){closeOptions();return true;}
        if(openGuiKey != null && openGuiKey.matches(e)){onClose();return true;}
        boolean handled=super.keyPressed(e);
        if(getFocused()!=allOff && getFocused() instanceof AbstractWidget w){
            if(openOptions!=null){
                if(w.getY()<optionsY+26)optionsScroll=Math.max(0,optionsScroll+w.getY()-(optionsY+26));
                if(w.getBottom()>optionsY+optionsHeight-8)optionsScroll=Math.min(optionsMaxScroll,optionsScroll+w.getBottom()-(optionsY+optionsHeight-8));
                layoutOptions();
            }else{
                if(w.getY()<28)scroll=Math.max(0,scroll+w.getY()-30);
                if(w.getBottom()>canvasHeight-18)scroll=Math.min(maxScroll,scroll+w.getBottom()-(canvasHeight-20));
                layout();
            }
        }
        return handled;
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(closing==0)closing=System.nanoTime();}
    @Override public void tick(){if(closing!=0&&System.nanoTime()-closing>=160_000_000L)minecraft.gui.setScreen(null);}

    private static final class Category {
        final String title,subtitle;int x,y,content;
        Category(String title,String subtitle){this.title=title;this.subtitle=subtitle;}
        int height(){return 22+content;}
    }
    private record Entry(Category category,AbstractWidget widget,int row){}
    private static final class OptionsPanel {
        final String title;
        final List<OptionEntry> entries = new ArrayList<>();
        int content;
        OptionsPanel(String title){this.title=title;}
    }
    private record OptionEntry(AbstractWidget widget,int row){}
    private final class OptionGear extends Button {
        OptionGear(String name){
            super(0,0,18,18,Component.literal(name),b->openOptions(name,b),DEFAULT_NARRATION);
            setTooltip(Tooltip.create(Component.literal("Configure "+name)));
        }
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt){
            if(isHoveredOrFocused())GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
            int cx=getX()+8,cy=getY()+8,color=GlassStyle.alpha(GlassStyle.MUTED,opacity);
            for(int y=-6;y<=6;y++)for(int x=-6;x<=6;x++){
                double radius=Math.hypot(x,y);
                double edge=4.7+1.2*Math.max(0,Math.cos(Math.atan2(y,x)*8));
                if(radius>=2.1&&radius<=edge)g.fill(cx+x,cy+y,cx+x+1,cy+y+1,color);
            }
        }
    }
    private final class Toggle extends Button {
        private final String label;private final BooleanSupplier state;private float position,hover;private long last=System.nanoTime();
        Toggle(String label,BooleanSupplier state,Runnable action){
            super(0,0,panelWidth-20,18,Component.literal(label),b->action.run(),DEFAULT_NARRATION);
            this.label=label;this.state=state;position=state.getAsBoolean()?1:0;
            offActions.add(()->{if(state.getAsBoolean())action.run();});
        }
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt){
            long now=System.nanoTime();float step=(float)(1-Math.exp(-16*Math.min(.1,(now-last)/1e9)));last=now;
            position+=((state.getAsBoolean()?1:0)-position)*step;hover+=((isHoveredOrFocused()?1:0)-hover)*step;
            if(hover>.01)GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*hover*.85f));
            GlassStyle.line(g,label,getX()+4,getY()+4,width-38,GlassStyle.alpha(state.getAsBoolean()?GlassStyle.TEXT:GlassStyle.MUTED,opacity));
            int x=getRight()-30,y=getY()+3;
            int r=(int)(55+position*105), green=r,b=r;
            GlassButtonRenderer.control(g,x,y,26,13,GlassStyle.alpha(0xFF000000|(r<<16)|(green<<8)|b,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,x+2+Math.round(position*13),y+2,9,9,GlassStyle.alpha(0xFFF3F3F3,opacity));
        }
        @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage(){return Component.literal(label+(state.getAsBoolean()?", on":", off"));}
    }
    private final class CornerSlider extends AbstractSliderButton {
        CornerSlider(int w){super(0,0,w,26,Component.literal("Corner radius"),GlassCornerSettings.getScale()/GlassCornerSettings.MAX);updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Corners   %d %%",Math.round(GlassCornerSettings.getScale()*100))));}
        @Override protected void applyValue(){GlassCornerSettings.setScale((float)(value*GlassCornerSettings.MAX));updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class EffectSlider extends AbstractSliderButton {
        private final String label;
        private final IntSupplier getter;
        private final IntConsumer setter;
        private final int steps, percentPerStep;
        private final String suffix;
        EffectSlider(int w,String label,IntSupplier getter,IntConsumer setter){
            this(w,label,getter,setter,GlassEffectSettings.STEPS,25);
        }
        EffectSlider(int w,String label,IntSupplier getter,IntConsumer setter,int steps,int percentPerStep){
            this(w,label,getter,setter,steps,percentPerStep," %");
        }
        EffectSlider(int w,String label,IntSupplier getter,IntConsumer setter,int steps,int percentPerStep,String suffix){
            super(0,0,w,26,Component.literal(label),getter.getAsInt()/(double)steps);
            this.label=label;this.getter=getter;this.setter=setter;this.steps=steps;this.percentPerStep=percentPerStep;this.suffix=suffix;updateMessage();
        }
        @Override protected void updateMessage(){setMessage(Component.literal(label+"   "+(getter.getAsInt()*percentPerStep)+suffix));}
        @Override protected void applyValue(){setter.accept((int)Math.round(value*steps));updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class CombatSlider extends AbstractSliderButton {
        private final String label;
        private final double min,max,step;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;
        CombatSlider(int w,String label,double min,double max,double step,DoubleSupplier getter,DoubleConsumer setter) {
            super(0,0,w,26,Component.literal(label),(getter.getAsDouble()-min)/(max-min));
            this.label=label;this.min=min;this.max=max;this.step=step;this.getter=getter;this.setter=setter;updateMessage();
        }
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"%s   %.1f",label,getter.getAsDouble())));}
        @Override protected void applyValue(){setter.accept(Math.clamp(min+Math.round(value*(max-min)/step)*step,min,max));updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(GlassStyle.TEXT,opacity));
        }
    }
    private final class SpeedSlider extends AbstractSliderButton {
        SpeedSlider(int w){super(0,0,w,26,Component.literal("Hand swap interval"),Math.clamp((PlayerSettings.handSwapIntervalTicks-1)/19.0,0,1));updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Interval   %.2f s",PlayerSettings.handSwapIntervalTicks/20.0)));}
        @Override protected void applyValue(){PlayerSettings.handSwapIntervalTicks=1+(int)Math.round(value*19);updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class PercentSlider extends AbstractSliderButton {
        PercentSlider(int w){super(0,0,w,26,Component.literal("Bar opacity"),Math.clamp(PlayerSettings.mobBarOpacity,0,1));updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Bar opacity   %d%%",Math.round(value*100))));}
        @Override protected void applyValue(){PlayerSettings.mobBarOpacity=(float)value;updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class DockSizeSlider extends AbstractSliderButton {
        DockSizeSlider(int w){super(0,0,w,26,Component.literal("Hotbar size"),Math.clamp((PlayerSettings.dockSize-0.5)/1.5,0,1));updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Size   %d %%",Math.round(PlayerSettings.dockSize*100))));}
        @Override protected void applyValue(){PlayerSettings.dockSize=(float)(0.5+value*1.5);updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class DockAutoHideSlider extends AbstractSliderButton {
        DockAutoHideSlider(int w){super(0,0,w,26,Component.literal("Hide delay"),Math.clamp((PlayerSettings.dockAutoHideSeconds-5)/115.0,0,1));updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Hide after   %d s",PlayerSettings.dockAutoHideSeconds)));}
        @Override protected void applyValue(){PlayerSettings.dockAutoHideSeconds=5+(int)Math.round(value*115);updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class ZoomStrengthSlider extends AbstractSliderButton {
        ZoomStrengthSlider(int w){super(0,0,w,26,Component.literal("Zoom strength"),Math.clamp((PlayerSettings.zoomStrength-1)/7.0,0,1));updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Zoom   %.1fx",1+PlayerSettings.zoomStrength)));}
        @Override protected void applyValue(){PlayerSettings.zoomStrength=(float)(1+value*7);updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class DockScaleSlider extends AbstractSliderButton {
        DockScaleSlider(int w){super(0,0,w,26,Component.literal("Dock magnification"),Math.clamp(PlayerSettings.dockMaxScale/2.5,0,1));updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Magnification   %d %%",Math.round(PlayerSettings.dockMaxScale*100))));}
        @Override protected void applyValue(){PlayerSettings.dockMaxScale=(float)(value*2.5);updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
    private final class DockRadiusSlider extends AbstractSliderButton {
        DockRadiusSlider(int w){super(0,0,w,26,Component.literal("Dock falloff"),Math.clamp((PlayerSettings.dockRadius-0.5)/7.5,0,1));updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Falloff   %.1f slots",PlayerSettings.dockRadius)));}
        @Override protected void applyValue(){PlayerSettings.dockRadius=(float)(0.5+value*7.5);updateMessage();}
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
            text(g,getMessage().getString(),getX()+4,getY()+3,GlassStyle.MUTED);
            int x=getX()+4,y=getY()+19,length=width-8;
            GlassButtonRenderer.control(g,x,y,length,3,GlassStyle.alpha(0xFF555555,opacity));
            GlassButtonRenderer.control(g,x,y,Math.max(1,(int)(length*value)),3,GlassStyle.alpha(GlassStyle.ACCENT,opacity));
            g.nextStratum();
            GlassButtonRenderer.control(g,getX()+(int)Math.round((width-8)*value),y-3,8,9,GlassStyle.alpha(isHoveredOrFocused()?0xFFFFFFFF:GlassStyle.TEXT,opacity));
        }
    }
}
