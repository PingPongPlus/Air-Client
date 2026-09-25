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
        var arraylist = new pingplus.voicechat.client.gui.ArraylistHud(fps, coords);
        var category = net.minecraft.client.KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("voicechat", "glass_test"));
        var key = new net.minecraft.client.KeyMapping("glass.test", 344, category);
        context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps, key, coords, arraylist));
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
            // Slayer outline end-to-end: spawn a named boss and check the glow pipeline.
            var bossId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var plainId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var wolfId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var wrongZombieId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var foreignBossId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var minibossId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var dianaId = new java.util.concurrent.atomic.AtomicInteger(-1);
            context.runOnClient(client -> {
                var server = client.getSingleplayerServer();
                var serverWorld = server.overworld();
                var look = client.player.getLookAngle();
                var base = client.player.position().add(look.x * 3, look.y * 3, look.z * 3);
                var boss = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                boss.setCustomName(Component.literal("Revenant Horror"));
                boss.setCustomNameVisible(true);
                boss.setPos(base.x, base.y, base.z);
                serverWorld.addFreshEntity(boss);
                bossId.set(boss.getId());
                var plain = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                plain.setPos(base.x + 2, base.y, base.z);
                serverWorld.addFreshEntity(plain);
                plainId.set(plain.getId());
                // Name-tag pattern: invisible armor stand above a WOLF with "Sven Packmaster".
                var wolf = net.minecraft.world.entity.EntityTypes.WOLF.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                wolf.setPos(base.x + 5, base.y, base.z);
                serverWorld.addFreshEntity(wolf);
                wolfId.set(wolf.getId());
                var wolfStand = net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                wolfStand.setCustomName(Component.literal("Sven Packmaster"));
                wolfStand.setCustomNameVisible(true);
                wolfStand.setInvisible(true);
                wolfStand.setPos(base.x + 5, base.y + 2, base.z);
                serverWorld.addFreshEntity(wolfStand);
                // Same name tag over a ZOMBIE must NOT glow (wrong mob class).
                var wrong = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                wrong.setPos(base.x + 8, base.y, base.z);
                serverWorld.addFreshEntity(wrong);
                wrongZombieId.set(wrong.getId());
                var wrongStand = net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                wrongStand.setCustomName(Component.literal("Sven Packmaster"));
                wrongStand.setCustomNameVisible(true);
                wrongStand.setInvisible(true);
                wrongStand.setPos(base.x + 8, base.y + 2, base.z);
                serverWorld.addFreshEntity(wrongStand);
                // Boss spawned by ANOTHER player must not glow.
                var foreign = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                foreign.setCustomName(Component.literal("Revenant Horror Spawned by: SomebodyElse"));
                foreign.setCustomNameVisible(true);
                foreign.setPos(base.x + 11, base.y, base.z);
                serverWorld.addFreshEntity(foreign);
                foreignBossId.set(foreign.getId());
                // Miniboss: red outline, always glows.
                var mini = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                mini.setCustomName(Component.literal("Revenant Champion"));
                mini.setCustomNameVisible(true);
                mini.setPos(base.x + 14, base.y, base.z);
                serverWorld.addFreshEntity(mini);
                minibossId.set(mini.getId());
                // Diana mob: health bar target, no glow.
                var diana = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                diana.setCustomName(Component.literal("Gaia Construct"));
                diana.setCustomNameVisible(true);
                diana.setPos(base.x + 17, base.y, base.z);
                serverWorld.addFreshEntity(diana);
                dianaId.set(diana.getId());
                // Wall between player and the boss: the outline must stay visible
                // through it (vanilla outline pass). The screenshot proves it.
                // Block placement must run on the server thread.
                var wallBase = net.minecraft.core.BlockPos.containing(
                        client.player.position().add(look.x * 1.6, look.y * 1.6, look.z * 1.6));
                server.execute(() -> {
                    for (int wy = 0; wy < 3; wy++) {
                        serverWorld.setBlockAndUpdate(wallBase.above(wy),
                                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    }
                });
            });
            context.runOnClient(client -> {
                // Enable health bars and boss lines so screenshots show them.
                PlayerSettings.hideMobNames = true;
                PlayerSettings.mobHealthBar = true;
                PlayerSettings.slayerLine = true;
            });
            context.waitTicks(30);
            context.takeScreenshot("slayer-boss");
            context.waitTicks(10);
            context.takeScreenshot("slayer-boss-steady");
            context.runOnClient(client -> {
                int bossColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(bossId.get());
                if (bossColour != (PlayerSettings.slayerBossColor & 0xFFFFFF))
                    throw new AssertionError("Slayer outline did not highlight boss, cache colour="
                            + Integer.toHexString(bossColour));
                int plainColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(plainId.get());
                if (plainColour != -1)
                    throw new AssertionError("Plain zombie wrongly highlighted, colour="
                            + Integer.toHexString(plainColour));
                int wolfColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(wolfId.get());
                if (wolfColour != (PlayerSettings.slayerBossColor & 0xFFFFFF))
                    throw new AssertionError("Wolf with Sven name tag not highlighted, colour="
                            + Integer.toHexString(wolfColour));
                int wrongColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(wrongZombieId.get());
                if (wrongColour != -1)
                    throw new AssertionError("Zombie next to Sven name tag wrongly highlighted, colour="
                            + Integer.toHexString(wrongColour));
                int foreignColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(foreignBossId.get());
                if (foreignColour != -1)
                    throw new AssertionError("Boss spawned by another player must not glow, colour="
                            + Integer.toHexString(foreignColour));
                int miniColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(minibossId.get());
                if (miniColour != (PlayerSettings.slayerMinibossColor & 0xFFFFFF))
                    throw new AssertionError("Miniboss not highlighted, colour=" + Integer.toHexString(miniColour));
                // Diana mob: health bar target without glow.
                int dianaColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(dianaId.get());
                if (dianaColour != -1)
                    throw new AssertionError("Diana mob must not glow, colour=" + Integer.toHexString(dianaColour));
                if (pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targetKindFor(dianaId.get())
                        != pingplus.voicechat.client.slayer.SlayerMobDetector.Kind.DIANA)
                    throw new AssertionError("Diana mob missing from health bar targets");
                if (pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targetKindFor(bossId.get())
                        != pingplus.voicechat.client.slayer.SlayerMobDetector.Kind.BOSS)
                    throw new AssertionError("Boss missing from health bar targets");
                if (pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targetKindFor(client.player.getId()) != null)
                    throw new AssertionError("Player must never be a health bar target");
                int playerColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(client.player.getId());
                if (playerColour != -1)
                    throw new AssertionError("Player must never glow, colour=" + Integer.toHexString(playerColour));
                // Perspective scaling: full close up, smaller far away, hidden beyond 20.
                double near = pingplus.voicechat.client.gui.MobHealthBarRenderer.scaleFor(3 * 3);
                double mid = pingplus.voicechat.client.gui.MobHealthBarRenderer.scaleFor(7 * 7);
                double far = pingplus.voicechat.client.gui.MobHealthBarRenderer.scaleFor(21 * 21);
                if (near != 1.0) throw new AssertionError("Bar should be full size when close: " + near);
                if (!(mid > 0.3 && mid < near)) throw new AssertionError("Bar should shrink with distance: " + mid);
                if (far != 0) throw new AssertionError("Bar must hide beyond 20 blocks: " + far);
                // Health bar targets feed the bar and the line renderer.
                if (pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targets().isEmpty())
                    throw new AssertionError("No health bar targets for bar/line rendering");
            });
            // Damage the boss: the bar fill must shrink with the mob's HP.
            context.runOnClient(client -> {
                var server = client.getSingleplayerServer();
                server.execute(() -> {
                    var boss = server.overworld().getEntity(bossId.get());
                    if (boss instanceof net.minecraft.world.entity.LivingEntity living) {
                        living.hurt(server.overworld().damageSources().generic(), 8.0F);
                    }
                });
            });
            context.waitTicks(10);
            context.takeScreenshot("slayer-boss-damaged");
            context.runOnClient(client -> {
                var damaged = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targets().stream()
                        .filter(t -> t.id() == bossId.get()).findFirst().orElse(null);
                if (damaged == null || damaged.hp() >= damaged.maxHp())
                    throw new AssertionError("Boss bar must shrink with HP after damage, hp="
                            + (damaged == null ? "missing" : damaged.hp() + "/" + damaged.maxHp()));
            });
            context.runOnClient(client -> {
                PlayerSettings.hideMobNames = false;
                PlayerSettings.mobHealthBar = false;
                PlayerSettings.slayerLine = false;
            });
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
            context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps,key,coords,arraylist));
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
