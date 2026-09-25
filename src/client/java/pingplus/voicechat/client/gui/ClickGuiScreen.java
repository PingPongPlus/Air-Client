package pingplus.voicechat.client.gui;

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
    private final Map<String, Boolean> expanded = new HashMap<>();
    private final Map<String, int[]> positions = new HashMap<>();
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
        expanded.put("Player scale",true); expanded.put("Swap interval",true); expanded.put("General",true);
    }

    @Override protected void init() {
        canvasWidth = (int)(width / UI_SCALE);
        canvasHeight = (int)(height / UI_SCALE);
        categories.clear(); entries.clear();
        int columns = Math.clamp((canvasWidth-16)/148,2,4);
        panelWidth = Math.min(140, (canvasWidth-24-(columns-1)*8)/columns);
        int startX = 16;
        Category hud = category("HUD", "On-screen information");
        toggle(hud,"Frame rate",fpsHud::isEnabled,fpsHud::toggle);
        toggle(hud,"Coordinates",coordinatesHud::isEnabled,coordinatesHud::toggle);
        toggle(hud,"Spotify",pingplus.voicechat.client.spotify.SpotifySettings::enabled,pingplus.voicechat.client.spotify.SpotifySettings::toggle);
        toggle(hud,"Arraylist",arraylistHud::isEnabled,arraylistHud::toggle);
        toggle(hud,"Arraylist glass",arraylistHud::isGlass,arraylistHud::toggleGlass);
        Category render = category("RENDER", "See the details");
        toggle(render,"Hitboxes",()->PlayerSettings.hitboxes,()->PlayerSettings.hitboxes=!PlayerSettings.hitboxes);
        add(render,new CornerSlider(panelWidth-20),26);
        Category player = category("PLAYER", "Shape your presence");
        toggle(player,"Body",()->PlayerSettings.mainBodyPart,()->PlayerSettings.mainBodyPart=!PlayerSettings.mainBodyPart);
        toggle(player,"Left arm",()->PlayerSettings.leftArm,()->PlayerSettings.leftArm=!PlayerSettings.leftArm);
        toggle(player,"Right arm",()->PlayerSettings.rightArm,()->PlayerSettings.rightArm=!PlayerSettings.rightArm);
        disclosure(player,"Player scale");
        if (expanded.get("Player scale")) {
            scale(player,"Width",PlayerSettings.xScale,v->PlayerSettings.xScale=v);
            scale(player,"Height",PlayerSettings.yScale,v->PlayerSettings.yScale=v);
            scale(player,"Depth",PlayerSettings.zScale,v->PlayerSettings.zScale=v);
            scale(player,"Head",PlayerSettings.xyzHeadscale,v->PlayerSettings.xyzHeadscale=v);
        }

        toggle(player, "Direction", ()->PlayerSettings.direction,()->PlayerSettings.direction = !PlayerSettings.direction);
        Category automation = category("AUTOMATION", "Small actions, effortless");
        toggle(automation,"Hand swap",()->PlayerSettings.handSwap,()->PlayerSettings.handSwap=!PlayerSettings.handSwap);
        disclosure(automation,"Swap interval");
        if (expanded.get("Swap interval")) add(automation,new SpeedSlider(panelWidth-20),26);
        Category skyblock = category("SKYBLOCK", "Hypixel skyblock helpers");
        disclosure(skyblock,"General");
        if (expanded.get("General")) {
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
            toggle(skyblock,"Player outline",()->PlayerSettings.playerOutline,()->PlayerSettings.playerOutline=!PlayerSettings.playerOutline);
            toggle(skyblock,"Self glow",()->PlayerSettings.selfOutline,()->PlayerSettings.selfOutline=!PlayerSettings.selfOutline);
            toggle(skyblock,"AGG",()->pingplus.voicechat.client.gui.hud.AggSettings.isEnabled(),()->pingplus.voicechat.client.gui.hud.AggSettings.setEnabled(!pingplus.voicechat.client.gui.hud.AggSettings.isEnabled()));
        }
        Category keys = category("KEYS", "Hotkeys, click then press");
        keyButton(keys, "GUI", VoicechatClient.openGuiKey());
        keyButton(keys, "Voice", VoicechatClient.voiceMenuKey());
        keyButton(keys, "Talk", VoicechatClient.talkKey());
        keyButton(keys, "Mute", VoicechatClient.muteKey());
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
                : columns == 3 ? new int[]{0,0,1,2,1,2} : new int[]{0,1,2,3,1,0};
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
    }
    private Category category(String name,String subtitle) { Category c=new Category(name,subtitle);categories.add(c);return c; }
    private void add(Category c,AbstractWidget w,int h) { entries.add(new Entry(c,addRenderableWidget(w),c.content));c.content+=h; }
    private void toggle(Category c,String name,BooleanSupplier state,Runnable action) { add(c,new Toggle(name,state,action),18); }
    private void disclosure(Category c,String name) {
        add(c,new Button(0,0,panelWidth-20,18,Component.literal(name),b->{expanded.put(name,!expanded.getOrDefault(name,false));rebuildWidgets();},supplier->supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
                if(isHoveredOrFocused()) GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*.85f));
                text(g,name,getX()+4,getY()+4,GlassStyle.MUTED);
                text(g,expanded.getOrDefault(name,false)?"-":"+",getRight()-13,getY()+4,GlassStyle.ACCENT);
            }
        },18);
    }
    private void scale(Category c,String label,float value,Consumer<Float> setter) {
        EditBox box=new EditBox(font,0,0,45,14,Component.literal(label+" scale")) {
            @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
                text(g,label,getX()-(panelWidth-80),getY()+3,GlassStyle.MUTED);
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
        if (VoicechatClient.voiceMenuKey() != null) VoicechatClient.voiceMenuKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V));
        if (VoicechatClient.talkKey() != null) VoicechatClient.talkKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_CAPS_LOCK));
        if (VoicechatClient.muteKey() != null) VoicechatClient.muteKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M));
        pendingKey = null;
        saveKeys();
    }
    private void saveKeys() {
        try { minecraft.options.save(); } catch (Exception ignored) {}
    }
    private void updateScroll() {
        maxScroll=Math.max(0,categories.stream().mapToInt(c->c.y+c.height()).max().orElse(0)-(canvasHeight-16));
        scroll=Math.clamp(scroll,0,maxScroll);
    }
    private void layout() {
        for(Entry e:entries){
            e.widget.setX(e.category.x+(e.widget instanceof EditBox?panelWidth-65:10));
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
            for(Entry e:entries)if(e.category==c){e.widget.setAlpha(opacity);e.widget.extractRenderState(g,mx,my,dt);}
        }
        g.disableScissor();
        text(g,"DRAG HEADERS  /  EXPAND SETTINGS",16,canvasHeight-13,GlassStyle.MUTED);
        if(maxScroll>0)text(g,"SCROLL",canvasWidth-48,canvasHeight-13,GlassStyle.ACCENT);
        g.pose().popMatrix();
    }
    private void text(GuiGraphicsExtractor g,String value,int x,int y,int color){g.text(font,GlassStyle.label(value),x,y,GlassStyle.alpha(color,opacity),false);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){scroll=Math.clamp(scroll-(int)(vertical*28),0,maxScroll);layout();return true;}
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
        if(e.y()<26){if(e.x()>canvasWidth-85)onClose();return true;}
        if(e.y()>canvasHeight-16)return true;
        for(int i=categories.size()-1;i>=0;i--){Category c=categories.get(i);int y=c.y-scroll;
            if(e.button()==0&&e.x()>=c.x&&e.x()<c.x+panelWidth&&e.y()>=y&&e.y()<y+20){dragging=c;dragX=e.x()-c.x;dragY=e.y()-y;return true;}
            if(e.x()>=c.x&&e.x()<c.x+panelWidth&&e.y()>=y&&e.y()<y+c.height()){
                for(Entry entry:entries)if(entry.category==c&&entry.widget.mouseClicked(e,twice)){setFocused(entry.widget);setDragging(true);return true;}
                return true;
            }
        }
        return false;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        MouseButtonEvent e = logicalMouse(event);
        dx /= UI_SCALE; dy /= UI_SCALE;
        if(dragging!=null){dragging.x=Math.clamp((int)(e.x()-dragX),8,Math.max(8,canvasWidth-panelWidth-8));dragging.y=Math.clamp((int)(e.y()-dragY)+scroll,28,Math.max(28,canvasHeight-18-dragging.height()));positions.put(dragging.title,new int[]{dragging.x,dragging.y});updateScroll();layout();return true;}
        return super.mouseDragged(e,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e){if(dragging!=null){dragging=null;return true;}return super.mouseReleased(logicalMouse(e));}
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
        if(openGuiKey != null && openGuiKey.matches(e)){onClose();return true;}
        boolean handled=super.keyPressed(e);
        if(getFocused() instanceof AbstractWidget w){
            if(w.getY()<28)scroll=Math.max(0,scroll+w.getY()-30);
            if(w.getBottom()>canvasHeight-18)scroll=Math.min(maxScroll,scroll+w.getBottom()-(canvasHeight-20));
            layout();
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
    private final class Toggle extends Button {
        private final String label;private final BooleanSupplier state;private float position,hover;private long last=System.nanoTime();
        Toggle(String label,BooleanSupplier state,Runnable action){super(0,0,panelWidth-20,18,Component.literal(label),b->action.run(),DEFAULT_NARRATION);this.label=label;this.state=state;position=state.getAsBoolean()?1:0;}
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt){
            long now=System.nanoTime();float step=(float)(1-Math.exp(-16*Math.min(.1,(now-last)/1e9)));last=now;
            position+=((state.getAsBoolean()?1:0)-position)*step;hover+=((isHoveredOrFocused()?1:0)-hover)*step;
            if(hover>.01)GlassButtonRenderer.control(g,getX(),getY(),width,height,GlassStyle.alpha(0xFF858585,opacity*hover*.85f));
            text(g,label,getX()+4,getY()+4,state.getAsBoolean()?GlassStyle.TEXT:GlassStyle.MUTED);
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
}
