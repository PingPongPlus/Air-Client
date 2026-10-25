package pingplus.voicechat.client;

import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.FlipperMod;
import com.coflnet.flipper.trading.Trader;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.lwjgl.glfw.GLFW;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.hud.ArraylistHud;
import pingplus.voicechat.client.hud.CoordinatesHud;
import pingplus.voicechat.client.hud.FpsHud;

/** Real control-center gestures and key dispatch, with trading confined to a local test world. */
final class FlipperControlsGameTest {
    static void run(ClientGameTestContext context) {
        Map<KeyMapping,String> originalKeys = new LinkedHashMap<>();
        var auction = AhFlipperMod.toggleKey();
        var bazaar = FlipperMod.toggleKey();
        boolean feed = AhFlipperMod.CONFIG.liveFeed, scan = AhFlipperMod.CONFIG.autoScan;
        boolean paused = Trader.get().isPaused();
        var previousSidebar = context.computeOnClient(client -> client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR));
        var objective = context.computeOnClient(client -> client.level.getScoreboard().addObjective(
                "flipper-ui-test", ObjectiveCriteria.DUMMY, Component.literal("SKYBLOCK"),
                ObjectiveCriteria.RenderType.INTEGER, false, null));
        try {
            context.runOnClient(client -> {
                check(!AhFlipperMod.isRunning() && !FlipperMod.isRunning(), "Flippers must start inactive");
                // Disable AH network feeds; the Bazaar worker remains paused in all local fixtures.
                AhFlipperMod.CONFIG.liveFeed = false;
                AhFlipperMod.CONFIG.autoScan = false;
                for (var key : new KeyMapping[]{VoicechatClient.openGuiKey(), VoicechatClient.hudEditorKey(),
                        VoicechatClient.voiceMenuKey(), VoicechatClient.talkKey(), VoicechatClient.muteKey(),
                        VoicechatClient.zoomKey(), VoicechatClient.shoulderCamKey(), VoicechatClient.killAuraKey(), auction, bazaar})
                    originalKeys.put(key,key.saveString());
                check(!Component.translatable(auction.getName()).getString().equals(auction.getName()), "Auction key label was not loaded");
                check(!Component.translatable(bazaar.getName()).getString().equals(bazaar.getName()), "Bazaar key label was not loaded");
            });
            controls(context);
            press(context,"Auction Flipper");
            press(context,"Bazaar Flipper");
            context.runOnClient(client -> check(!AhFlipperMod.isRunning() && !FlipperMod.isRunning(),
                    "Control-center toggles bypassed the SkyBlock requirement"));

            context.runOnClient(client -> {
                Screen screen=client.gui.screen();
                click(screen,keyButton(screen,"Auction Flipper"));
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_H,0,0));
                click(screen,keyButton(screen,"Bazaar Flipper"));
                var mouse=new MouseButtonEvent(1,1,new MouseButtonInfo(3,0));
                check(screen.mouseClicked(mouse,false),"Mouse key assignment was not captured");
                check(auction.saveString().equals("key.keyboard.h"),"Auction keyboard binding failed");
                check(bazaar.saveString().equals("key.mouse.4"),"Bazaar mouse binding failed");
                click(screen,keyButton(screen,"Auction Flipper"));
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE,0,0));
                check(auction.saveString().equals("key.keyboard.h"),"Escape changed the pending binding");
                check(!AhFlipperMod.isRunning() && !FlipperMod.isRunning(),"Binding a key activated a flipper");
                try {
                    String saved=Files.readString(client.gameDirectory.toPath().resolve("options.txt"));
                    for (var key : new KeyMapping[]{auction,bazaar})
                        check(saved.contains("key_"+key.getName()+":"+key.saveString()),"Key binding was not saved: "+key.getName());
                } catch (java.io.IOException e) { throw new AssertionError(e); }
                auction.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_UNKNOWN));
                bazaar.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_UNKNOWN));
                client.options.load();
                KeyMapping.resetMapping();
                check(auction.saveString().equals("key.keyboard.h") && bazaar.saveString().equals("key.mouse.4"),
                        "Flipper bindings did not survive options reload");
            });

            controls(context);
            context.runOnClient(client -> client.level.getScoreboard().setDisplayObjective(DisplaySlot.SIDEBAR,objective));
            activate(context);
            context.waitTicks(3);
            context.takeScreenshot("clickgui-flippers-and-keys");
            press(context,"Auction Flipper");
            long before=System.nanoTime();
            press(context,"Bazaar Flipper");
            check(System.nanoTime()-before < 1_000_000_000L,"Stopping Bazaar blocked the control center");
            awaitStopped(context);
            context.runOnClient(client -> check(client.gui.screen() instanceof ClickGuiScreen,"Stopping a flipper closed the control center"));

            activate(context);
            context.runOnClient(client -> {
                client.level.getScoreboard().setDisplayObjective(DisplaySlot.SIDEBAR,previousSidebar);
                client.gui.setScreen(null);
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9));
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8));
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                check(AhFlipperMod.isRunning() && FlipperMod.isRunning(),"Old defaults still triggered after rebinding");
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_H));
                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(3));
            });
            awaitStopped(context);

            controls(context);
            context.runOnClient(client -> client.level.getScoreboard().setDisplayObjective(DisplaySlot.SIDEBAR,objective));
            activate(context);
            press(context,"All off");
            awaitStopped(context);
            press(context,"All off");
            context.runOnClient(client -> {
                check(!AhFlipperMod.isRunning() && !FlipperMod.isRunning(),"Repeated All off restarted a flipper");
                click(client.gui.screen(),button(client.gui.screen(),"Reset keys"));
                check(auction.saveString().equals("key.keyboard.f9") && bazaar.saveString().equals("key.keyboard.f8"),
                        "Reset keys did not restore F9/F8");
            });
            System.out.println("PASS: flipper UI start/stop, SkyBlock guard, keyboard/mouse assignment, cancel, persistence/reload, live key dispatch, defaults and All off");
        } finally {
            context.runOnClient(client -> {
                if (AhFlipperMod.isRunning()) AhFlipperMod.toggle();
                if (FlipperMod.isRunning()) Trader.get().stop();
                Trader.get().setPaused(paused);
                AhFlipperMod.CONFIG.liveFeed=feed;
                AhFlipperMod.CONFIG.autoScan=scan;
                client.level.getScoreboard().setDisplayObjective(DisplaySlot.SIDEBAR,previousSidebar);
                client.level.getScoreboard().removeObjective(objective);
                originalKeys.forEach((key,value) -> key.setKey(InputConstants.getKey(value)));
                KeyMapping.resetMapping();
                client.options.save();
                client.gui.setScreen(null);
            });
        }
    }

    private static void activate(ClientGameTestContext context) {
        context.runOnClient(client -> {
            click(client.gui.screen(),button(client.gui.screen(),"Auction Flipper"));
            click(client.gui.screen(),button(client.gui.screen(),"Bazaar Flipper"));
            Trader.get().setPaused(true);
            check(AhFlipperMod.isRunning() && FlipperMod.isRunning(),"Flipper controls did not start their real runtimes");
        });
    }
    private static void awaitStopped(ClientGameTestContext context) {
        context.waitFor(client -> !AhFlipperMod.isRunning() && !FlipperMod.isRunning());
    }
    private static void controls(ClientGameTestContext context) {
        var fps=new FpsHud(); var coordinates=new CoordinatesHud();
        context.setScreen(() -> new ClickGuiScreen(fps,VoicechatClient.openGuiKey(),coordinates,new ArraylistHud(fps,coordinates)));
    }
    private static void press(ClientGameTestContext context,String name) {
        context.runOnClient(client -> click(client.gui.screen(),button(client.gui.screen(),name)));
    }
    private static Button button(Screen screen,String name) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(w -> w.getMessage().getString().equals(name)).findFirst().orElseThrow();
    }
    private static Button keyButton(Screen screen,String name) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(w -> w.getMessage().getString().startsWith(name+": ")).findFirst().orElseThrow();
    }
    private static void click(Screen screen,Button button) {
        var event=new MouseButtonEvent((button.getX()+button.getWidth()/2.0)*ClickGuiScreen.UI_SCALE,
                (button.getY()+button.getHeight()/2.0)*ClickGuiScreen.UI_SCALE,new MouseButtonInfo(0,0));
        check(screen.mouseClicked(event,false),"Control rejected input: "+button.getMessage().getString());
        screen.mouseReleased(event);
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
