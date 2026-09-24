package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

/** Opt-in GPU smoke test: ./gradlew runClientGameTest. Never included in the mod JAR. */
public final class GlassRenderingTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.setScreen(TitleScreen::new);
        context.waitTicks(30);
        context.takeScreenshot("glass-title");
        boolean initialGlass = pingplus.voicechat.client.gui.glass.GlassRainSettings.isEnabled();
        context.clickScreenButton("Glass drops: " + (initialGlass ? "ON" : "OFF"));
        context.runOnClient(client -> {
            if (pingplus.voicechat.client.gui.glass.GlassRainSettings.isEnabled() == initialGlass)
                throw new AssertionError("Glass toggle did not change state");
            var saved = new java.util.Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("voicechat-glass-rain.properties"))) { saved.load(reader); }
            catch (java.io.IOException e) { throw new AssertionError("Cannot read saved glass preference", e); }
            if (Boolean.parseBoolean(saved.getProperty("enabled")) == initialGlass)
                throw new AssertionError("Glass preference was not saved");
        });
        context.waitTicks(10);
        context.takeScreenshot("glass-toggled");
        context.setScreen(TitleScreen::new);
        context.waitTicks(5);
        context.clickScreenButton("Glass drops: " + (initialGlass ? "OFF" : "ON"));
        context.runOnClient(client -> {
            if (pingplus.voicechat.client.gui.glass.GlassRainSettings.isEnabled() != initialGlass)
                throw new AssertionError("Glass toggle did not restore state");
        });

        context.runOnClient(client -> {
            String sample = "Mountain Air 0123456789";
            if (client.font.width(sample) != client.font.width(pingplus.voicechat.client.gui.glass.GlassStyle.label(sample)))
                throw new AssertionError("Default font differs from ClickGUI font");
            var vanillaStyle = net.minecraft.network.chat.Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "include/default")));
            if (client.font.width(sample) == client.font.width(Component.literal(sample).withStyle(vanillaStyle)))
                throw new AssertionError("UI font unexpectedly uses vanilla glyph metrics");
        });
        context.setScreen(() -> new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen()));
        context.waitTicks(20);
        context.takeScreenshot("glass-survival");
        context.setScreen(() -> new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new TitleScreen()));
        context.waitTicks(20);
        context.takeScreenshot("glass-multiplayer");
        context.setScreen(TitleScreen::new);
        context.waitTicks(20);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            var button = screen.children().stream().filter(child -> child instanceof Button)
                    .map(child -> (Button) child).filter(b -> b.active).findFirst().orElseThrow();
            screen.setFocused(button);
        });
        context.waitTicks(20);
        context.takeScreenshot("glass-focus");
        context.clickScreenButton("menu.options");
        context.waitForScreen(OptionsScreen.class);
        context.waitTicks(10);
        context.takeScreenshot("glass-options");
        var fps = new pingplus.voicechat.client.gui.FpsHud();
        var coords = new pingplus.voicechat.client.gui.CoordinatesHud();
        var category = net.minecraft.client.KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("voicechat", "glass_test"));
        var key = new net.minecraft.client.KeyMapping("glass.test", 344, category);
        context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps, key, coords));
        context.waitTicks(20);
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            for(var child:screen.children()) if(child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                if(w.getX()<0 || w.getRight()*0.60f>screen.width || w.getY()<26 || w.getBottom()*0.60f>screen.height-9)
                    throw new AssertionError("Control outside viewport: "+w.getMessage().getString());
            }
        });
        context.takeScreenshot("clickgui-overview");
        clickCompactButton(context, "Frame rate");
        context.runOnClient(client -> { if (fps.isEnabled()) throw new AssertionError("FPS toggle failed"); });
        context.runOnClient(client -> client.gui.screen().mouseScrolled(100, 100, 0, -6));
        context.waitTicks(10);
        context.takeScreenshot("clickgui-scrolled");
        clickCompactButton(context, "Hand swap");
        context.runOnClient(client -> {
            if (!PlayerSettings.handSwap) throw new AssertionError("Hand swap toggle failed");
            PlayerSettings.handSwap = false;
            client.gui.screen().mouseScrolled(100, 100, 0, 3);
            var box = client.gui.screen().children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.EditBox)
                .map(c -> (net.minecraft.client.gui.components.EditBox)c).findFirst().orElseThrow();
            box.setValue("1.75");
            if (PlayerSettings.xScale != 1.75f) throw new AssertionError("Scale input failed");
            box.setValue("NaN");
            if (PlayerSettings.xScale != 1.75f) throw new AssertionError("Invalid scale accepted");
            box.setValue("1.0");
        });
        context.runOnClient(client -> client.options.guiScale().set(3));
        context.waitTicks(15);
        context.takeScreenshot("clickgui-compact");
        context.runOnClient(client -> client.options.guiScale().set(2));
        context.runOnClient(client -> client.getWindow().setWindowed(1440, 900));
        context.waitTicks(15);
        context.runOnClient(client -> client.gui.screen().mouseScrolled(100,100,0,100));
        context.takeScreenshot("clickgui-wide");
        context.runOnClient(client -> GlassGpuTiming.begin());
        context.waitTicks(80);
        context.runOnClient(client -> GlassGpuTiming.finish("ClickGUI 1440x900"));
        clickCompactButton(context, "Player scale");
        context.runOnClient(client -> {
            if(client.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.EditBox))
                throw new AssertionError("Settings did not collapse");
        });
        clickCompactButton(context, "Player scale");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            if(screen.children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.EditBox).count()!=4)
                throw new AssertionError("Settings did not expand");
            var slider=screen.children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.AbstractSliderButton)
                .map(c -> (net.minecraft.client.gui.components.AbstractSliderButton)c).findFirst().orElseThrow();
            var click=new net.minecraft.client.input.MouseButtonEvent((slider.getRight()-5)*0.60,(slider.getY()+20)*0.60,new net.minecraft.client.input.MouseButtonInfo(0,0));
            screen.mouseClicked(click,false);screen.mouseReleased(click);
            if(PlayerSettings.handSwapIntervalTicks!=20)throw new AssertionError("Slider maximum failed");
            PlayerSettings.handSwapIntervalTicks=6;
            var first=screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c).findFirst().orElseThrow();
            int oldX=first.getX();
            var down=new net.minecraft.client.input.MouseButtonEvent((oldX+5)*0.60,(first.getY()-15)*0.60,new net.minecraft.client.input.MouseButtonInfo(0,0));
            screen.mouseClicked(down,false);
            var moved=new net.minecraft.client.input.MouseButtonEvent(down.x()+7.2,down.y()+9,down.buttonInfo());
            screen.mouseDragged(moved,7.2,9);screen.mouseReleased(moved);
            if(Math.abs(first.getX()-oldX-12)>1)throw new AssertionError("Panel drag failed");
            for(int i=0;i<16;i++)screen.keyPressed(new net.minecraft.client.input.KeyEvent(258,0,0));
            if(screen.getFocused()==null)throw new AssertionError("Keyboard navigation failed");
        });
        context.waitTicks(10);
        context.takeScreenshot("clickgui-dragged");
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> reload = new java.util.concurrent.atomic.AtomicReference<>();
        context.runOnClient(client -> reload.set(client.reloadResourcePacks()));
        context.waitTicks(2);
        context.takeScreenshot("glass-reload");
        context.waitFor(client -> reload.get().isDone());
        context.waitTicks(50);
        context.runOnClient(client -> reload.get().join());
        context.setScreen(() -> {
            var progress=new net.minecraft.client.gui.screens.ProgressScreen(false);
            progress.progressStart(Component.literal("Preparing your world"));
            progress.progressStage(Component.literal("Loading terrain"));
            progress.progressStagePercentage(65);
            return progress;
        });
        context.waitTicks(10);
        context.takeScreenshot("glass-progress");
        context.setScreen(() -> new net.minecraft.client.gui.screens.GenericMessageScreen(Component.literal("Connecting to your world")));
        context.waitTicks(10);
        context.takeScreenshot("glass-transition");
        context.setScreen(GlassTestScreen::new);
        context.waitTicks(10);
        context.takeScreenshot("glass-checkerboard");
        context.clickScreenButton("Glass test button");
        context.runOnClient(client -> {
            if (!((GlassTestScreen) client.gui.screen()).clicked) {
                throw new AssertionError("Glass button did not retain its click action");
            }
        });
        context.runOnClient(client -> client.options.guiScale().set(3));
        context.waitTicks(10);
        context.takeScreenshot("glass-scale-three");
        context.runOnClient(client -> client.options.guiScale().set(2));
        context.waitTicks(10);
        context.takeScreenshot("glass-scale-two");
        context.setScreen(TitleScreen::new);
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.gui.overlay() == null);
            context.runOnClient(client -> {
                if (!pingplus.voicechat.client.spotify.SpotifySettings.enabled()) pingplus.voicechat.client.spotify.SpotifySettings.toggle();
            });
            context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("draft stays here", false));
            context.waitTicks(10);
            context.takeScreenshot("spotify-chat-icons");
            context.runOnClient(client -> {
                Screen chat = client.gui.screen();
                var buttons = chat.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c).toList();
                if (buttons.size() != 3) throw new AssertionError("Spotify icon controls missing");
                if (pingplus.voicechat.client.spotify.SpotifyClient.INSTANCE.state().playback() == null && buttons.stream().anyMatch(b -> b.active))
                    throw new AssertionError("Unavailable playback controls must be disabled");
                var widget = pingplus.voicechat.client.gui.hud.HudEditor.bounds("spotify", chat.width, chat.height);
                int oldX = (int)Math.round(widget.x()), oldY = (int)Math.round(widget.y());
                var down = new net.minecraft.client.input.MouseButtonEvent(oldX + 20, oldY + 12, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                chat.mouseClicked(down, false);
                var move = new net.minecraft.client.input.MouseButtonEvent(60, 180, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                if (!chat.mouseDragged(move, 60 - down.x(), 180 - down.y())) throw new AssertionError("HUD drag not handled");
                chat.mouseReleased(move);
                var moved = pingplus.voicechat.client.gui.hud.HudEditor.bounds("spotify", chat.width, chat.height);
                if (Math.round(moved.x()) == oldX && Math.round(moved.y()) == oldY) throw new AssertionError("HUD did not move");
                var saved = new java.util.Properties();
                try (var reader = java.nio.file.Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getConfigDir().resolve("voicechat-hud-layout.properties"))) { saved.load(reader); }
                catch (java.io.IOException e) { throw new AssertionError(e); }
                if (saved.getProperty("spotify.x") == null || saved.getProperty("spotify.y") == null) throw new AssertionError("Position was not saved");
            });
            context.runOnClient(client -> {
                client.gui.screen().onClose();
            });
            context.waitTicks(10);
            context.takeScreenshot("spotify-persistent-hud");
            context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps,key,coords));
            context.waitTicks(10);
            clickCompactButton(context, "Spotify");
            context.runOnClient(client -> {
                if (pingplus.voicechat.client.spotify.SpotifySettings.enabled()) throw new AssertionError("ClickGUI did not disable Spotify");
            });
            context.takeScreenshot("spotify-clickgui-toggle");
            clickCompactButton(context, "Spotify");
            context.runOnClient(client -> {
                if (!pingplus.voicechat.client.spotify.SpotifySettings.enabled()) throw new AssertionError("ClickGUI did not enable Spotify");
                // Restore a clean default layout for subsequent captures.
                pingplus.voicechat.client.spotify.SpotifySettings.position(client.gui.screen().width, 8,
                        client.gui.screen().width, client.gui.screen().height, 240, 100);
                pingplus.voicechat.client.spotify.SpotifySettings.save();
            });
            context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
            context.waitTicks(20);
            context.takeScreenshot("glass-pause");
            context.runOnClient(client -> GlassGpuTiming.begin());
            context.waitTicks(80);
            context.runOnClient(client -> GlassGpuTiming.finish("Pause 1440x900"));
            context.waitTicks(20);
            context.takeScreenshot("glass-pause-rain-motion");
            context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps,key,coords));
            context.waitTicks(20);
            context.takeScreenshot("clickgui-world");
            context.runOnClient(client -> reload.set(client.reloadResourcePacks()));
            context.waitTicks(12);
            context.takeScreenshot("glass-world-reload");
            context.waitFor(client -> reload.get().isDone() && client.gui.overlay() == null);
            context.runOnClient(client -> reload.get().join());
            context.waitTicks(10);
            context.takeScreenshot("clickgui-after-reload");
            context.runOnClient(client -> client.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(344,0,0)));
            context.waitFor(client -> client.gui.screen() == null);
        }
        context.setScreen(TitleScreen::new);
        context.waitForScreen(TitleScreen.class);
    }

    private static void clickCompactButton(ClientGameTestContext context, String label) {
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            Button button=screen.children().stream().filter(c -> c instanceof Button)
                    .map(c -> (Button)c).filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow();
            float scale=pingplus.voicechat.client.gui.ClickGuiScreen.UI_SCALE;
            var event=new net.minecraft.client.input.MouseButtonEvent((button.getX()+button.getWidth()/2.0)*scale,
                    (button.getY()+button.getHeight()/2.0)*scale,new net.minecraft.client.input.MouseButtonInfo(0,0));
            if(!screen.mouseClicked(event,false))throw new AssertionError("Missed scaled button: "+label);
            screen.mouseReleased(event);
        });
    }

    private static final class GlassTestScreen extends Screen {
        private boolean clicked;
        GlassTestScreen() { super(Component.literal("Glass rendering test")); }
        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Glass test button"), b -> clicked = true)
                    .bounds(width / 2 - 100, height / 2 - 40, 200, 20).build());
            Button disabled = addRenderableWidget(Button.builder(Component.literal("Disabled"), b -> {
                throw new AssertionError("Disabled glass button invoked");
            }).bounds(width / 2 - 100, height / 2 - 10, 200, 20).build());
            disabled.active = false;
            Button fading = addRenderableWidget(Button.builder(Component.literal("Half opacity"), b -> {})
                    .bounds(width / 2 - 100, height / 2 + 20, 200, 20).build());
            fading.setAlpha(0.5F);
        }
        @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            for (int y = 0; y < height; y += 8) {
                for (int x = 0; x < width; x += 8) {
                    graphics.fill(x, y, x + 8, y + 8,
                            ((x / 8 + y / 8) & 1) == 0 ? 0xFF24374F : 0xFF8FC2DB);
                }
            }
        }
    }
}
