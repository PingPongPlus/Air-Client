package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.*;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.network.chat.numbers.BlankFormat;
import pingplus.voicechat.client.hud.ScoreboardHud;

/** Opt-in GPU smoke test: ./gradlew runClientGameTest. Never included in the mod JAR. */
public final class GlassRenderingTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("voicechat.forceSneakTest")) {
            ForceSneakGameTest.run(context);
            if (Boolean.getBoolean("voicechat.crosshairTest")) CustomCrosshairGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.crosshairTest")) {
            CustomCrosshairGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.clickGuiOptionsTest")) {
            ClickGuiOptionsGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.storageEspTest")) {
            StorageEspGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.miningFeaturesTest")) {
            MiningFeaturesGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.fastPlaceTest")) {
            FastPlaceGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.weatherGlassTest")) {
            WeatherGlassRenderingTest.run(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.hudEditorTest")) {
            hudEditor(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.killAuraTest")) {
            new KillAuraGameTest().runTest(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.scoreboardEdgesTest")) {
            scoreboardEdges(context);
            return;
        }
        if (Boolean.getBoolean("voicechat.damageGlassTest")) {
            new DamageGlassRenderingTest().runTest(context);
            return;
        }
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
        var fps = new pingplus.voicechat.client.hud.FpsHud();
        var coords = new pingplus.voicechat.client.hud.CoordinatesHud();
        var arraylist = new pingplus.voicechat.client.hud.ArraylistHud(fps, coords);
        var category = net.minecraft.client.KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("voicechat", "glass_test"));
        var key = new net.minecraft.client.KeyMapping("glass.test", 344, category);
        context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps, key, coords, arraylist));
        context.waitTicks(20);
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            for(var child:screen.children()) if(child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                if (!w.getMessage().getString().equals("All off") && (w.getX()<0 || w.getRight()*0.60f>screen.width || w.getY()<26 || w.getBottom()*0.60f>screen.height-9))
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
        clickCompactButton(context, "Player scale");
        context.runOnClient(client -> {
            if (!PlayerSettings.handSwap) throw new AssertionError("Hand swap toggle failed");
            PlayerSettings.handSwap = false;
            client.gui.screen().mouseScrolled(100, 100, 0, 3);
            var box = client.gui.screen().children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.EditBox)
                .map(c -> (net.minecraft.client.gui.components.EditBox)c)
                .filter(field -> field.getMessage().getString().equals("Width scale")).findFirst().orElseThrow();
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
            if(client.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.EditBox box
                    && box.getMessage().getString().endsWith(" scale")))
                throw new AssertionError("Settings did not collapse");
        });
        clickCompactButton(context, "Player scale");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            if(screen.children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.EditBox box
                    && box.getMessage().getString().endsWith(" scale")).count()!=4)
                throw new AssertionError("Settings did not expand");
            screen.keyPressed(new net.minecraft.client.input.KeyEvent(256,0,0));
        });
        clickCompactButton(context,"Swap interval");
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            var slider=screen.children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.AbstractSliderButton)
                .map(c -> (net.minecraft.client.gui.components.AbstractSliderButton)c)
                .filter(c -> c.getMessage().getString().startsWith("Interval")).findFirst().orElseThrow();
            var click=new net.minecraft.client.input.MouseButtonEvent((slider.getRight()-5)*0.60,(slider.getY()+20)*0.60,new net.minecraft.client.input.MouseButtonInfo(0,0));
            screen.mouseClicked(click,false);screen.mouseReleased(click);
            if(PlayerSettings.handSwapIntervalTicks!=20)throw new AssertionError("Slider maximum failed");
            PlayerSettings.handSwapIntervalTicks=6;
            screen.keyPressed(new net.minecraft.client.input.KeyEvent(256,0,0));
            var first=screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c)
                .filter(b -> b.getMessage().getString().equals("Frame rate")).findFirst().orElseThrow();
            int oldX=first.getX();
            var top=screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c)
                .filter(b -> b.getMessage().getString().equals("Crosshair")).findFirst().orElseThrow();
            var down=new net.minecraft.client.input.MouseButtonEvent((oldX+5)*0.60,(top.getY()-15)*0.60,new net.minecraft.client.input.MouseButtonInfo(0,0));
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
        int initialBlur = pingplus.voicechat.client.gui.glass.GlassEffectSettings.blurStep();
        int initialShadow = pingplus.voicechat.client.gui.glass.GlassEffectSettings.shadowStep();
        try {
            for (int step : new int[]{0, 4, 8}) {
                context.runOnClient(client -> {
                    pingplus.voicechat.client.gui.glass.GlassEffectSettings.setBlur(step);
                    pingplus.voicechat.client.gui.glass.GlassEffectSettings.setShadow(step);
                });
                context.waitTicks(5);
                context.takeScreenshot("glass-effects-" + step);
            }
        } finally {
            context.runOnClient(client -> {
                pingplus.voicechat.client.gui.glass.GlassEffectSettings.setBlur(initialBlur);
                pingplus.voicechat.client.gui.glass.GlassEffectSettings.setShadow(initialShadow);
            });
        }

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
                // Keep the media card from intercepting scoreboard editor gestures.
                if (pingplus.voicechat.client.spotify.SpotifySettings.enabled())
                    pingplus.voicechat.client.spotify.SpotifySettings.toggle();
                var board = client.level.getScoreboard();
                var objective = board.addObjective("widget-test", ObjectiveCriteria.DUMMY,
                        Component.literal("SERVER SCOREBOARD").withStyle(net.minecraft.ChatFormatting.GOLD),
                        ObjectiveCriteria.RenderType.INTEGER, false, null);
                for (int i = 1; i <= 17; i++)
                    board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Line " + i), objective).set(i);
                board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("#hidden"), objective).set(100);
                var team = board.addPlayerTeam("widget-team");
                team.setPlayerPrefix(Component.literal("[VIP] ").withStyle(net.minecraft.ChatFormatting.GREEN));
                board.addPlayerToTeam("Line 17", team);
                board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Line 17"), objective).numberFormatOverride(BlankFormat.INSTANCE);
                board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
                var view = ScoreboardHud.createView(objective);
                if (view.rows().size() != 15 || !view.rows().getFirst().name().getString().equals("[VIP] Line 17")
                        || view.rows().getFirst().scoreWidth() != 0)
                    throw new AssertionError("Scoreboard ordering, hidden entries, team prefix or number format lost");
                if (!ScoreboardHud.INSTANCE.isVisible()) throw new AssertionError("Sidebar widget missing");
            });
            context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("scoreboard widget", false));
            context.waitTicks(5);
            context.takeScreenshot("scoreboard-glass");
            context.setScreen(pingplus.voicechat.client.hud.editor.HudEditorScreen::new);
            context.waitTicks(5);
            context.runOnClient(client -> {
                Screen editor = client.gui.screen();
                var before = pingplus.voicechat.client.hud.editor.HudEditor.bounds("scoreboard", editor.width, editor.height);
                var down = new net.minecraft.client.input.MouseButtonEvent(before.x()+10, before.y()+before.height()/2,
                        new net.minecraft.client.input.MouseButtonInfo(0,0));
                editor.mouseClicked(down,false);
                var moved = new net.minecraft.client.input.MouseButtonEvent(before.x()<150 ? 220 : 100, 140, down.buttonInfo());
                if (!editor.mouseDragged(moved,moved.x()-down.x(),moved.y()-down.y()))
                    throw new AssertionError("Scoreboard drag failed");
                editor.mouseReleased(moved);
                var after = pingplus.voicechat.client.hud.editor.HudEditor.bounds("scoreboard", editor.width, editor.height);
                if (before.x()==after.x() && before.y()==after.y()) throw new AssertionError("Scoreboard did not move");
                editor.mouseScrolled(after.x()+10,after.y()+after.height()/2,0,1);
                var scaled = pingplus.voicechat.client.hud.editor.HudEditor.bounds("scoreboard", editor.width, editor.height);
                if (scaled.scale()<=after.scale()) throw new AssertionError("Scoreboard resize failed");
                // Restore default placement so later runs start consistently.
                editor.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(scaled.x()+10,scaled.y()+scaled.height()/2,
                        new net.minecraft.client.input.MouseButtonInfo(1,0)),false);
            });
            context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps,key,coords,arraylist));
            clickCompactButton(context,"Scoreboard options");
            clickCompactButton(context,"Liquid glass");
            context.runOnClient(client -> {
                if (ScoreboardHud.INSTANCE.isGlass()) throw new AssertionError("Scoreboard glass toggle failed");
            });
            context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("plain scoreboard",false));
            context.waitTicks(5);
            context.takeScreenshot("scoreboard-plain");
            context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps,key,coords,arraylist));
            clickCompactButton(context,"Scoreboard");
            context.runOnClient(client -> {
                if (ScoreboardHud.INSTANCE.isVisible()) throw new AssertionError("Disabled scoreboard still visible");
                ScoreboardHud.INSTANCE.toggle();
                ScoreboardHud.INSTANCE.toggleGlass();
                var board=client.level.getScoreboard();
                var objective=board.getObjective("widget-test");
                var teamObjective=board.addObjective("widget-team-test",ObjectiveCriteria.DUMMY,Component.literal("Team sidebar"),
                        ObjectiveCriteria.RenderType.INTEGER,false,null);
                var team=board.getPlayerTeam("widget-team");
                team.setColor(java.util.Optional.of(TeamColor.RED));
                board.addPlayerToTeam(client.player.getScoreboardName(),team);
                board.setDisplayObjective(TeamColor.RED.displaySlot(),teamObjective);
                if (ScoreboardHud.objective()!=teamObjective) throw new AssertionError("Team sidebar not selected");
                board.removeObjective(teamObjective);
                if (ScoreboardHud.objective()!=objective) throw new AssertionError("Sidebar fallback failed");
                board.removePlayerTeam(team);
                board.removeObjective(objective);
                if (ScoreboardHud.INSTANCE.isVisible()) throw new AssertionError("Removed sidebar left a stale widget");
            });
            context.setScreen(() -> null);
            // Slayer outline end-to-end: spawn a named boss and check the glow pipeline.
            boolean oldOutline = PlayerSettings.slayerOutline, oldBoss = PlayerSettings.slayerBoss;
            boolean oldMini = PlayerSettings.slayerMiniboss, oldSelf = PlayerSettings.selfOutline;
            boolean oldHighlight = PlayerSettings.slayerBossHighlight;
            var bossId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var plainId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var wolfId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var wrongZombieId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var foreignBossId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var minibossId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var dianaId = new java.util.concurrent.atomic.AtomicInteger(-1);
            var bossNoTierId = new java.util.concurrent.atomic.AtomicInteger(-1);
            context.runOnClient(client -> {
                var server = client.getSingleplayerServer();
                var serverWorld = server.overworld();
                var look = client.player.getLookAngle();
                var base = client.player.position().add(look.x * 3, look.y * 3, look.z * 3);
                var boss = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                boss.setCustomName(Component.literal("☠ Revenant Horror IV 20❤"));
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
                wolfStand.setCustomName(Component.literal("☠ Sven Packmaster III 500k❤"));
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
                wrongStand.setCustomName(Component.literal("☠ Sven Packmaster III 500k❤"));
                wrongStand.setCustomNameVisible(true);
                wrongStand.setInvisible(true);
                wrongStand.setPos(base.x + 8, base.y + 2, base.z);
                serverWorld.addFreshEntity(wrongStand);
                // Boss spawned by ANOTHER player must not glow.
                var foreign = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                foreign.setCustomName(Component.literal("☠ Revenant Horror IV 20❤ Spawned by: SomebodyElse"));
                foreign.setCustomNameVisible(true);
                foreign.setPos(base.x + 11, base.y, base.z);
                serverWorld.addFreshEntity(foreign);
                foreignBossId.set(foreign.getId());
                // Miniboss: red outline, always glows.
                var mini = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                mini.setCustomName(Component.literal("☠ Revenant Champion II 900k❤"));
                mini.setCustomNameVisible(true);
                mini.setPos(base.x + 14, base.y, base.z);
                serverWorld.addFreshEntity(mini);
                minibossId.set(mini.getId());
                // Diana mob: health bar target, no glow.
                var diana = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                diana.setCustomName(Component.literal("[Lv120] Gaia Construct 1.5M❤"));
                diana.setCustomNameVisible(true);
                diana.setPos(base.x + 17, base.y, base.z);
                serverWorld.addFreshEntity(diana);
                dianaId.set(diana.getId());
                // Tier-less boss tag (no roman numeral) must still count as boss.
                var bossNoTier = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(
                        serverWorld, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                bossNoTier.setCustomName(Component.literal("☠ Revenant Horror 20❤"));
                bossNoTier.setCustomNameVisible(true);
                bossNoTier.setPos(base.x + 20, base.y, base.z);
                serverWorld.addFreshEntity(bossNoTier);
                bossNoTierId.set(bossNoTier.getId());
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
                PlayerSettings.slayerOutline = true;
                PlayerSettings.slayerBossHighlight = true;
                PlayerSettings.slayerBoss = true;
                PlayerSettings.slayerMiniboss = true;
                PlayerSettings.selfOutline = true;
                VoicechatClient.syncSlayerCfg();
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
                // Tier-less boss tag must still count as boss (exact name + HP + class).
                int bossNoTierColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(bossNoTierId.get());
                if (bossNoTierColour != (PlayerSettings.slayerBossColor & 0xFFFFFF))
                    throw new AssertionError("Tier-less boss not highlighted, colour="
                            + Integer.toHexString(bossNoTierColour));
                if (pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targetKindFor(bossNoTierId.get())
                        != pingplus.voicechat.client.slayer.SlayerMobDetector.Kind.BOSS)
                    throw new AssertionError("Tier-less boss missing from health bar targets");
                if (pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targetKindFor(client.player.getId()) != null)
                    throw new AssertionError("Player must never be a health bar target");
                int selfColour = pingplus.voicechat.client.slayer.SlayerOutlineRenderer.colorFor(client.player.getId());
                if (selfColour != (PlayerSettings.selfOutlineColor & 0xFFFFFF))
                    throw new AssertionError("Own player must glow pink, colour=" + Integer.toHexString(selfColour));
                // Perspective scaling: full close up, smaller far away, hidden beyond 20.
                double near = pingplus.voicechat.client.hud.MobHealthBarRenderer.scaleFor(3 * 3);
                double mid = pingplus.voicechat.client.hud.MobHealthBarRenderer.scaleFor(7 * 7);
                double far = pingplus.voicechat.client.hud.MobHealthBarRenderer.scaleFor(21 * 21);
                if (near != 1.0) throw new AssertionError("Bar should be full size when close: " + near);
                if (!(mid > 0.3 && mid < near)) throw new AssertionError("Bar should shrink with distance: " + mid);
                if (far != 0) throw new AssertionError("Bar must hide beyond 20 blocks: " + far);
                // Health bar targets feed the bar and the line renderer.
                if (pingplus.voicechat.client.slayer.SlayerOutlineRenderer.targets().isEmpty())
                    throw new AssertionError("No health bar targets for bar/line rendering");
            });
            // Damage the boss: the bar fill must shrink with the mob's HP.
            // Like on Hypixel, the name tag updates to the new current HP.
            context.runOnClient(client -> {
                var server = client.getSingleplayerServer();
                server.execute(() -> {
                    var boss = server.overworld().getEntity(bossId.get());
                    if (boss instanceof net.minecraft.world.entity.LivingEntity living) {
                        living.hurt(server.overworld().damageSources().generic(), 8.0F);
                        living.setCustomName(Component.literal("☠ Revenant Horror IV 12❤"));
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
                PlayerSettings.slayerOutline = oldOutline;
                PlayerSettings.slayerBossHighlight = oldHighlight;
                PlayerSettings.slayerBoss = oldBoss;
                PlayerSettings.slayerMiniboss = oldMini;
                PlayerSettings.selfOutline = oldSelf;
                VoicechatClient.syncSlayerCfg();
            });
            context.runOnClient(client -> {
                if (!pingplus.voicechat.client.spotify.SpotifySettings.enabled()) pingplus.voicechat.client.spotify.SpotifySettings.toggle();
            });
            context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("draft stays here", false));
            context.waitTicks(10);
            context.takeScreenshot("spotify-chat-icons");
            context.runOnClient(client -> {
                Screen chat = client.gui.screen();
                if (chat.children().stream().anyMatch(c -> c instanceof Button))
                    throw new AssertionError("HUD controls must not appear in chat");
            });
            context.setScreen(pingplus.voicechat.client.hud.editor.HudEditorScreen::new);
            context.waitTicks(5);
            context.runOnClient(client -> {
                Screen chat = client.gui.screen();
                var buttons = chat.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c).toList();
                if (buttons.size() != 3) throw new AssertionError("Spotify icon controls missing");
                if (pingplus.voicechat.client.spotify.SpotifyClient.INSTANCE.state().playback() == null && buttons.stream().anyMatch(b -> b.active))
                    throw new AssertionError("Unavailable playback controls must be disabled");
                var widget = pingplus.voicechat.client.hud.editor.HudEditor.bounds("spotify", chat.width, chat.height);
                int oldX = (int)Math.round(widget.x()), oldY = (int)Math.round(widget.y());
                var down = new net.minecraft.client.input.MouseButtonEvent(oldX + 20, oldY + 12, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                chat.mouseClicked(down, false);
                var move = new net.minecraft.client.input.MouseButtonEvent(oldX < 100 ? 160 : 60, 180, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                if (!chat.mouseDragged(move, move.x() - down.x(), move.y() - down.y())) throw new AssertionError("HUD drag not handled");
                chat.mouseReleased(move);
                var moved = pingplus.voicechat.client.hud.editor.HudEditor.bounds("spotify", chat.width, chat.height);
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
            context.runOnClient(client -> {
                if (!arraylist.isEnabled()) arraylist.toggle();
                if (!arraylist.isEdges()) arraylist.toggleEdges();
            });
            clickCompactButton(context, "Arraylist options");
            clickCompactButton(context, "Per-module boxes");
            clickCompactButton(context, "Edges");
            context.runOnClient(client -> {
                if (!arraylist.isRectangles() || arraylist.isEdges())
                    throw new AssertionError("Arraylist options did not toggle");
            });
            context.setScreen(() -> new Screen(Component.literal("Arraylist alignment test")) {
                @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
                    g.pose().pushMatrix();
                    g.pose().translate(width / 2f, 30f);
                    g.pose().scale(2f);
                    arraylist.render(g);
                    g.pose().popMatrix();
                }
            });
            context.waitTicks(5);
            context.takeScreenshot("arraylist-no-edges");
            context.runOnClient(client -> arraylist.toggleEdges());
            context.waitTicks(5);
            context.takeScreenshot("arraylist-edges");
            context.runOnClient(client -> arraylist.toggleRectangles());
            context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
            context.waitTicks(20);
            context.takeScreenshot("glass-pause");
            context.runOnClient(client -> GlassGpuTiming.begin());
            context.waitTicks(80);
            context.runOnClient(client -> GlassGpuTiming.finish("Pause 1440x900"));
            context.waitTicks(20);
            context.takeScreenshot("glass-pause-rain-motion");
            context.setScreen(() -> new pingplus.voicechat.client.gui.ClickGuiScreen(fps,key,coords,new pingplus.voicechat.client.hud.ArraylistHud(fps,coords)));
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

    private static void hudEditor(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.gui.overlay() == null);
            context.runOnClient(client -> client.getWindow().setWindowed(1440, 900));
            context.waitTicks(5);
            context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("normal chat", false));
            context.waitTicks(3);
            context.runOnClient(client -> {
                Screen chat = client.gui.screen();
                if (chat.children().stream().anyMatch(child -> child instanceof Button))
                    throw new AssertionError("HUD controls appeared in chat");
                var before = pingplus.voicechat.client.hud.editor.HudEditor.bounds("fps", chat.width, chat.height);
                var down = new net.minecraft.client.input.MouseButtonEvent(before.x()+5, before.y()+5,
                        new net.minecraft.client.input.MouseButtonInfo(0, 0));
                chat.mouseClicked(down, false);
                var moved = new net.minecraft.client.input.MouseButtonEvent(down.x()+60, down.y()+40, down.buttonInfo());
                chat.mouseDragged(moved, 60, 40);
                chat.mouseReleased(moved);
                var after = pingplus.voicechat.client.hud.editor.HudEditor.bounds("fps", chat.width, chat.height);
                if (before.x() != after.x() || before.y() != after.y())
                    throw new AssertionError("Chat still edits HUD widgets");
                chat.onClose();
                VoicechatClient.hudEditorKey().setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(71));
                net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(71));
            });
            context.waitForScreen(pingplus.voicechat.client.hud.editor.HudEditorScreen.class);
            context.waitTicks(3);
            context.takeScreenshot("hud-editor");
            context.runOnClient(client -> {
                Screen editor = client.gui.screen();
                var ids = java.util.List.of("fps", "coordinates", "arraylist", "logo", "chat", "spotify", "voice");
                var allBefore = ids.stream().collect(java.util.stream.Collectors.toMap(id -> id,
                        id -> pingplus.voicechat.client.hud.editor.HudEditor.bounds(id, editor.width, editor.height)));
                var before = pingplus.voicechat.client.hud.editor.HudEditor.bounds("fps", editor.width, editor.height);
                var down = new net.minecraft.client.input.MouseButtonEvent(before.x()+5, before.y()+5,
                        new net.minecraft.client.input.MouseButtonInfo(0, 0));
                editor.mouseClicked(down, false);
                var moved = new net.minecraft.client.input.MouseButtonEvent(down.x()+60, down.y()+40, down.buttonInfo());
                if (!editor.mouseDragged(moved, 60, 40)) throw new AssertionError("HUD drag was not handled");
                editor.mouseReleased(moved);
                var after = pingplus.voicechat.client.hud.editor.HudEditor.bounds("fps", editor.width, editor.height);
                boolean movedAny = ids.stream().anyMatch(id -> {
                    var previous = allBefore.get(id);
                    var current = pingplus.voicechat.client.hud.editor.HudEditor.bounds(id, editor.width, editor.height);
                    return previous.x() != current.x() || previous.y() != current.y();
                });
                if (!movedAny)
                    throw new AssertionError("HUD did not move in editor");
                editor.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(after.x()+5, after.y()+5,
                        new net.minecraft.client.input.MouseButtonInfo(1, 0)), false);
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(71, 0, 0));
                if (client.gui.screen() != null) throw new AssertionError("G did not close the editor");
                VoicechatClient.hudEditorKey().setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(72));
                net.minecraft.client.KeyMapping.resetMapping();
                net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(72));
            });
            context.waitForScreen(pingplus.voicechat.client.hud.editor.HudEditorScreen.class);
            context.runOnClient(client -> {
                client.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(72, 0, 0));
                if (client.gui.screen() != null) throw new AssertionError("Custom HUD key did not close the editor");
                VoicechatClient.hudEditorKey().setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(71));
                net.minecraft.client.KeyMapping.resetMapping();
            });
        }
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

    private static void scoreboardEdges(ClientGameTestContext context) {
        boolean original = ScoreboardHud.INSTANCE.isEdges();
        try {
            context.runOnClient(client -> {
                if (!ScoreboardHud.INSTANCE.isEdges()) ScoreboardHud.INSTANCE.toggleEdges();
            });
            context.setScreen(() -> new Screen(Component.literal("Scoreboard edge regression")) {
                @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
                    g.fill(0, 0, width, height, 0xFF344354);
                    g.nextStratum();
                    pingplus.voicechat.client.gui.glass.GlassButtonRenderer.drawHudRect(
                            g, width / 2 - 85, height / 2 - 70, 170, 140, 0xE01FFF00, false, ScoreboardHud.INSTANCE.isEdges());
                    g.nextStratum();
                    g.text(font, Component.literal("Scoreboard"), width / 2 - 30, height / 2 - 55, 0xFFFFFFFF, false);
                }
            });
            context.waitTicks(10);
            context.takeScreenshot("scoreboard-edges-on");
            context.runOnClient(client -> ScoreboardHud.INSTANCE.toggleEdges());
            context.waitTicks(10);
            context.takeScreenshot("scoreboard-edges-off");
            context.runOnClient(client -> {
                if (ScoreboardHud.INSTANCE.isEdges()) throw new AssertionError("Scoreboard edges did not switch off");
            });
        } finally {
            context.runOnClient(client -> {
                if (ScoreboardHud.INSTANCE.isEdges() != original) ScoreboardHud.INSTANCE.toggleEdges();
            });
            context.setScreen(TitleScreen::new);
        }
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
