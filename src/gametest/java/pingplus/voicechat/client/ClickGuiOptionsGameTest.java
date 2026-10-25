package pingplus.voicechat.client;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.level.GameType;
import pingplus.voicechat.client.hud.ArraylistHud;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.hud.CoordinatesHud;
import pingplus.voicechat.client.hud.FpsHud;
import pingplus.voicechat.client.gui.glass.GlassButtonRenderer;
import pingplus.voicechat.client.gui.glass.GlassEffectSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import pingplus.voicechat.client.spotify.MusicGlass;
import pingplus.voicechat.client.spotify.SpotifySettings;

/** Real input and GPU captures for gear buttons and their floating settings windows. */
final class ClickGuiOptionsGameTest {
    static void run(ClientGameTestContext context) {
        FpsHud fps = new FpsHud();
        CoordinatesHud coordinates = new CoordinatesHud();
        boolean hiddenNames = PlayerSettings.hideMobNames;
        float originalScale = PlayerSettings.xScale, originalSize = PlayerSettings.dockSize;
        boolean originalTint = GlassEffectSettings.customTint(), originalMusicTint = SpotifySettings.musicGlass();
        int originalColor = GlassEffectSettings.tintColor(), originalIntensity = GlassEffectSettings.tintIntensity();
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.gui.overlay() == null);
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                client.getWindow().setWindowed(1440,900);
                client.options.guiScale().set(2);
                client.resizeGui();
                if (client.gui.hud.isHidden()) client.gui.hud.toggle();
                client.player.setXRot(7);
                var id = client.player.getUUID();
                var server = client.getSingleplayerServer();
                server.execute(() -> server.getPlayerList().getPlayer(id).setGameMode(GameType.SURVIVAL));
            });
            context.waitFor(client -> client.gameMode.getPlayerMode() == GameType.SURVIVAL);
            context.waitTicks(60);
            HudMetricsGameTest.run(context);
            KeystrokesGameTest.run(context);
            MiddleClickVoiceVolumeGameTest.run(context);
            FlipperControlsGameTest.run(context);
            captureHud(context);
            context.setScreen(() -> new ClickGuiScreen(fps,VoicechatClient.openGuiKey(),coordinates,new ArraylistHud(fps,coordinates)));
            context.waitTicks(10);
            context.takeScreenshot("clickgui-gears");

            List<String> groups = List.of("Crosshair options","FPS options","Ping options","CPS options","Speed options","Keystrokes options","XYZ options","Spotify options",
                    "Arraylist options","Scoreboard options","Chat options","Bars","Dock options","Logo options",
                    "Damage glass options","Storage ESP options","Xray options","Zoom options","Glass style",
                    "Weather Glass options","Player scale","Auto Tools options","Fast Place options",
                    "KillAura options","Swap interval","General");
            for (String name : groups) {
                open(context,name);
                context.runOnClient(client -> {
                    Screen screen=client.gui.screen();
                    button(screen,"Close "+name);
                    check(!button(screen,"Frame rate").active,"Background controls must not steal window input");
                    screen.keyPressed(new KeyEvent(256,0,0));
                    check(client.gui.screen()==screen,"Escape should close only the settings window");
                    check(button(screen,"Frame rate").active,"Escape must restore category controls");
                });
            }

            open(context,"FPS options");
            boolean originalGlass=fps.isGlass();
            press(context,"FPS glass");
            check(fps.isGlass()!=originalGlass,"Options toggle did not update FPS glass");
            check(fps.isEnabled(),"Opening or adjusting options toggled the main feature");
            press(context,"FPS glass");
            context.runOnClient(client -> {
                Screen screen=client.gui.screen();
                for(int i=0;i<6;i++) {
                    screen.keyPressed(new KeyEvent(258,0,0));
                    check(screen.getFocused() instanceof AbstractWidget w && w.active,
                            "Tab escaped into inactive background controls");
                }
                Button parent=button(screen,"Frame rate");
                click(screen,parent);
                check(fps.isEnabled(),"Outside click must not toggle a covered control");
                check(button(screen,"Frame rate").active,"Outside click did not close the window");
            });

            open(context,"Player scale");
            context.runOnClient(client -> {
                Screen screen=client.gui.screen();
                EditBox scale=screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).filter(box -> box.getMessage().getString().equals("Width scale")).findFirst().orElseThrow();
                scale.setValue("1.75"); check(PlayerSettings.xScale==1.75f,"Scale field did not update");
                scale.setValue("NaN"); check(PlayerSettings.xScale==1.75f,"Invalid scale was accepted");
                scale.setValue(Float.toString(originalScale));
                screen.keyPressed(new KeyEvent(256,0,0));
            });

            open(context,"Dock options");
            context.waitTicks(5);
            context.takeScreenshot("clickgui-options-dock");
            context.runOnClient(client -> {
                Screen screen=client.gui.screen();
                Button close=button(screen,"Close Dock options");
                int beforeX=close.getX();
                MouseButtonEvent down=pointer(close.getX()-80,close.getY()+8);
                check(screen.mouseClicked(down,false),"Window title rejected drag");
                MouseButtonEvent moved=pointer(close.getX()-40,close.getY()+28);
                screen.mouseDragged(moved,40*ClickGuiScreen.UI_SCALE,20*ClickGuiScreen.UI_SCALE);
                screen.mouseReleased(moved);
                check(close.getX()==beforeX+40,"Settings window did not move with its header");
                AbstractSliderButton size=screen.children().stream().filter(AbstractSliderButton.class::isInstance)
                        .map(AbstractSliderButton.class::cast).filter(w->w.getMessage().getString().startsWith("Size")).findFirst().orElseThrow();
                MouseButtonEvent click=pointer(size.getRight()-1,size.getY()+20);
                check(screen.mouseClicked(click,false),"Window slider rejected scaled input");
                screen.mouseReleased(click);
                check(PlayerSettings.dockSize>1.9f,"Window slider could not reach its maximum");
                PlayerSettings.dockSize=originalSize;
            });
            press(context,"Close Dock options");

            open(context,"General");
            context.runOnClient(client -> PlayerSettings.hideMobNames=false);
            press(context,"No mob names");
            context.runOnClient(client -> {
                button(client.gui.screen(),"Close General");
                button(client.gui.screen(),"Mob HP bar");
            });
            press(context,"No mob names");
            context.runOnClient(client -> client.gui.screen().keyPressed(new KeyEvent(256,0,0)));

            testTint(context);
            context.setScreen(() -> new ClickGuiScreen(fps,VoicechatClient.openGuiKey(),coordinates,new ArraylistHud(fps,coordinates)));

            open(context,"Xray options");
            context.runOnClient(client -> {
                client.getWindow().setWindowed(854,480);
                client.options.guiScale().set(3);
                client.resizeGui();
            });
            context.waitTicks(5);
            context.runOnClient(client -> {
                Screen screen=client.gui.screen();
                Button close=button(screen,"Close Xray options");
                check(close.getRight()*ClickGuiScreen.UI_SCALE<=screen.width,"Window left the viewport after resize");
                screen.mouseScrolled((close.getX()-60)*ClickGuiScreen.UI_SCALE,(close.getY()+40)*ClickGuiScreen.UI_SCALE,0,-100);
                var minerals=XrayFeature.Mineral.values();
                Button last=button(screen,minerals[minerals.length-1].label);
                check(last.getY()*ClickGuiScreen.UI_SCALE>=0 && last.getBottom()*ClickGuiScreen.UI_SCALE<=screen.height,
                        "Last options row is unreachable on a small window");
            });
            context.takeScreenshot("clickgui-options-small-scrolled");
            context.runOnClient(client -> {
                client.gui.screen().keyPressed(new KeyEvent(256,0,0));
                client.options.guiScale().set(2);
                client.getWindow().setWindowed(1440,900);
                client.resizeGui();
            });
            open(context,"Storage ESP options");
            context.waitTicks(5);
            context.takeScreenshot("clickgui-options-storage");
            context.runOnClient(client -> client.gui.screen().keyPressed(new KeyEvent(256,0,0)));
            ClickGuiAllOffGameTest.run(context);
            System.out.println("PASS: all gear windows, live controls, input validation, modal focus, close, drag, resize and scrolling");
        } finally {
            context.runOnClient(client -> {
                PlayerSettings.hideMobNames=hiddenNames;
                PlayerSettings.xScale=originalScale;
                PlayerSettings.dockSize=originalSize;
                GlassEffectSettings.setTintColor(originalColor);
                GlassEffectSettings.setTintIntensity(originalIntensity);
                if (GlassEffectSettings.customTint()!=originalTint) GlassEffectSettings.toggleCustomTint();
                if (SpotifySettings.musicGlass()!=originalMusicTint) SpotifySettings.toggleMusicGlass();
            });
        }
    }

    private static void testTint(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (GlassEffectSettings.customTint()) GlassEffectSettings.toggleCustomTint();
            if (SpotifySettings.musicGlass()) SpotifySettings.toggleMusicGlass();
        });
        open(context,"Glass style");
        press(context,"Custom glass color");
        setSlider(context,"Red",true);
        setSlider(context,"Green",false);
        setSlider(context,"Blue",false);
        setSlider(context,"Glass tint",true);
        context.runOnClient(client -> {
            check(GlassEffectSettings.customTint(),"Custom tint toggle failed");
            check(GlassEffectSettings.tintColor()==0xFF0000,"RGB sliders did not produce red");
            check(GlassEffectSettings.tintIntensity()==100,"Tint slider could not reach maximum");
            var saved=new java.util.Properties();
            try(var reader=Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("voicechat-glass-effects.properties"))) { saved.load(reader); }
            catch(java.io.IOException e) { throw new AssertionError(e); }
            check(saved.getProperty("customTint").equals("true") && saved.getProperty("tintColor").equals("FF0000")
                    && saved.getProperty("tintIntensity").equals("100"),"Custom tint preferences were not saved");
        });
        awaitTint(context,0xFF0000);
        context.takeScreenshot("clickgui-custom-glass-red");
        context.setScreen(TintShowroom::new);
        context.waitTicks(5);
        checkRenderedTint(context,true);
        context.takeScreenshot("glass-materials-red");
        context.runOnClient(client -> GlassEffectSettings.setTintColor(0x0000FF));
        awaitTint(context,0x0000FF);
        context.waitTicks(5);
        checkRenderedTint(context,false);
        context.takeScreenshot("glass-materials-blue");
        context.runOnClient(client -> GlassEffectSettings.toggleCustomTint());
        context.waitFor(client -> (MusicGlass.tint()>>>24)==0);
        System.out.println("PASS: saved custom RGB/intensity, tint without Spotify, red/blue GPU materials and disable fade");
    }
    private static void setSlider(ClientGameTestContext context,String label,boolean maximum) {
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            var slider=screen.children().stream().filter(AbstractSliderButton.class::isInstance).map(AbstractSliderButton.class::cast)
                    .filter(w->w.getMessage().getString().startsWith(label+"   ")).findFirst().orElseThrow();
            var event=pointer(maximum?slider.getRight()-4:slider.getX()+4,slider.getY()+20);
            check(screen.mouseClicked(event,false),"Color slider rejected input"); screen.mouseReleased(event);
        });
    }
    private static void awaitTint(ClientGameTestContext context,int color) {
        context.waitFor(client -> {
            int tint=MusicGlass.tint();
            return (tint>>>24)>=68 && (tint>>>24)<=72
                    && Math.abs(((tint>>>16)&255)-((color>>>16)&255))<4
                    && Math.abs(((tint>>>8)&255)-((color>>>8)&255))<4
                    && Math.abs((tint&255)-(color&255))<4;
        });
    }
    private static void checkRenderedTint(ClientGameTestContext context,boolean red) {
        var finished=context.computeOnClient(client -> {
            var future=new CompletableFuture<Void>();
            int scale=(int)client.getWindow().getGuiScale();
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image -> {
                try(image) {
                    var path=client.gameDirectory.toPath().resolve("screenshots/glass-tint-pixels.png");
                    image.writeToFile(path);
                    var pixels=javax.imageio.ImageIO.read(path.toFile());
                    for(int y:new int[]{70,120,170,220,270}) {
                        int pixel=pixels.getRGB(250*scale,y*scale);
                        int r=(pixel>>>16)&255,b=pixel&255;
                        check(red?r>b+15:b>r+15,"Glass material did not take the global "+(red?"red":"blue")+" tint at y="+y);
                    }
                    future.complete(null);
                } catch(Throwable failure) { future.completeExceptionally(failure); }
            });
            return future;
        });
        context.waitFor(client -> finished.isDone()); finished.join();
    }
    private static final class TintShowroom extends Screen {
        TintShowroom() { super(net.minecraft.network.chat.Component.literal("Glass tint materials")); }
        @Override public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g,int mx,int my,float dt) {
            g.fill(0,0,width,height,0xFF303030); g.nextStratum();
        }
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g,int mx,int my,float dt) {
            GlassButtonRenderer.drawRect(g,150,50,200,40,0xFF00FF00);
            GlassButtonRenderer.drawHudRect(g,150,100,200,40,0xFF00FF00,true,false);
            GlassButtonRenderer.control(g,150,150,200,40,0xFF777777);
            GlassButtonRenderer.bar(g,150,200,200,40,0xFF777777);
            GlassButtonRenderer.bossBar(g,150,250,200,40,false,0,1);
            g.nextStratum();
            String[] labels={"Button","HUD","Control","Bar","Boss bar"};
            for(int i=0;i<labels.length;i++)g.text(font,labels[i],30,66+i*50,0xFFFFFFFF,false);
        }
    }

    private static void open(ClientGameTestContext context,String name) {
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            Button gear=screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(w->w.getWidth()==18 && w.getMessage().getString().equals(name)).findFirst().orElseThrow();
            click(screen,gear);
            button(screen,"Close "+name);
        });
    }
    private static Button button(Screen screen,String name) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(w->w.getMessage().getString().equals(name)).findFirst().orElseThrow();
    }
    private static void press(ClientGameTestContext context,String name) {
        context.runOnClient(client -> click(client.gui.screen(),button(client.gui.screen(),name)));
    }
    private static void click(Screen screen,AbstractWidget widget) {
        MouseButtonEvent event=pointer(widget.getX()+widget.getWidth()/2.0,widget.getY()+widget.getHeight()/2.0);
        check(screen.mouseClicked(event,false),"Click was not handled: "+widget.getMessage().getString());
        screen.mouseReleased(event);
    }
    private static MouseButtonEvent pointer(double x,double y) {
        return new MouseButtonEvent(x*ClickGuiScreen.UI_SCALE,y*ClickGuiScreen.UI_SCALE,new MouseButtonInfo(0,0));
    }
    private static void captureHud(ClientGameTestContext context) {
        var finished=context.computeOnClient(client -> {
            var future=new CompletableFuture<Void>();
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image -> {
                try(image) {
                    var path=client.gameDirectory.toPath().resolve("screenshots/current-hud.png");
                    Files.createDirectories(path.getParent()); image.writeToFile(path); future.complete(null);
                } catch(Throwable failure) { future.completeExceptionally(failure); }
            });
            return future;
        });
        context.waitFor(client -> finished.isDone()); finished.join();
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
