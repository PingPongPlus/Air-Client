package pingplus.voicechat.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import pingplus.voicechat.client.gui.ClickGuiScreen;
import pingplus.voicechat.client.hud.ArraylistHud;
import pingplus.voicechat.client.hud.CoordinatesHud;
import pingplus.voicechat.client.hud.FpsHud;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real mob render states and ClickGUI input in an isolated world. */
public final class MobEspGameTest {
    public static void run(ClientGameTestContext context) {
        boolean enabled = PlayerSettings.mobEsp;
        var selected = MobEspFeature.selected();
        var ids = new AtomicReference<List<Integer>>();
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.gui.overlay() == null);
            context.setScreen(()->null);
            context.runOnClient(client -> {
                PlayerSettings.mobEsp = false; MobEspFeature.clear();
                client.getWindow().setWindowed(1440,900); client.options.guiScale().set(2);
                var origin = client.player.blockPosition();
                client.player.setYRot(0); client.player.setXRot(0);
                var server = client.getSingleplayerServer();
                server.execute(()-> {
                    var mobs = List.of(EntityTypes.COW.create(server.overworld(),EntitySpawnReason.COMMAND),
                            EntityTypes.ZOMBIE.create(server.overworld(),EntitySpawnReason.COMMAND),
                            EntityTypes.SKELETON.create(server.overworld(),EntitySpawnReason.COMMAND));
                    for (int i=0;i<mobs.size();i++) {
                        var mob = mobs.get(i);
                        mob.setPos(origin.getX()-3+i*3,origin.getY()+1,origin.getZ()+8);
                        mob.setNoAi(true);mob.setNoGravity(true);mob.setInvulnerable(true);mob.setPersistenceRequired();
                        mob.setCustomName(Component.literal("Renamed test mob"));
                        server.overworld().addFreshEntity(mob);
                    }
                    ids.set(mobs.stream().map(Mob::getId).toList());
                    for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8,0,4),origin.offset(8,5,4)))
                        server.overworld().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
                });
            });
            context.waitFor(client -> ids.get()!=null && ids.get().stream().allMatch(id->client.level.getEntity(id) instanceof Mob));
            context.runOnClient(client -> {
                check(MobEspFeature.add(" COW, Minecraft:Zombie, iron golem ")==null,"names, IDs and spaces accepted");
                check(MobEspFeature.selected().size()==3,"multiple types added");
                check(MobEspFeature.add("cow, minecraft:cow")==null && MobEspFeature.selected().size()==3,"duplicates ignored");
                check(MobEspFeature.add("skeleton, no_such_mob")!=null && MobEspFeature.selected().size()==3,"invalid batch is atomic");
                check(MobEspFeature.add(" ")!=null && MobEspFeature.add("bad:name:here")!=null,"empty and malformed names rejected");
                check(MobEspFeature.colorFor(client.level.getEntity(ids.get().getFirst()))<0,"disabled feature has no glow");
                PlayerSettings.mobEsp = true;
                for (int i=0;i<ids.get().size();i++) {
                    var entity = client.level.getEntity(ids.get().get(i));
                    var state = client.getEntityRenderDispatcher().getRenderer(entity).createRenderState(entity,0);
                    check(state.outlineColor==(i<2?ARGB.opaque(MobEspFeature.COLOR):0),"actual renderer filters type "+i);
                    check(!entity.isCurrentlyGlowing(),"server glow flag is untouched");
                }
                check(MobEspFeature.colorFor(client.player)<0,"players excluded");
                for (var type : List.of(EntityTypes.IRON_GOLEM,EntityTypes.WITHER,EntityTypes.ENDER_DRAGON,
                        EntityTypes.BAT,EntityTypes.SQUID,EntityTypes.SLIME,EntityTypes.VILLAGER)) {
                    check(MobEspFeature.add(net.minecraft.world.entity.EntityType.getKey(type).toString())==null,"mob type accepted");
                    var mob = type.create(client.level,EntitySpawnReason.COMMAND);
                    check(MobEspFeature.colorFor(mob)==MobEspFeature.COLOR,"passive, hostile, aquatic, flying and boss support");
                }
                MobEspFeature.clear();
            });
            var fps = new FpsHud();var coords = new CoordinatesHud();var list = new ArraylistHud(fps,coords);
            context.setScreen(()->new ClickGuiScreen(fps,VoicechatClient.openGuiKey(),coords,list));
            click(context,"MobESP options");
            context.runOnClient(client -> {
                input(client.gui.screen()).setValue("cow, zombie");
                check(client.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER,0,0)),"Enter adds names");
                check(MobEspFeature.selected().size()==2,"text input adds multiple mobs");
            });
            context.waitTicks(5);context.takeScreenshot("mobesp-options");
            click(context,"Remove: cow");
            context.runOnClient(client -> check(MobEspFeature.selected().size()==1,"remove one keeps the other"));
            click(context,"Close MobESP options");
            click(context,"MobESP");
            context.runOnClient(client -> check(!PlayerSettings.mobEsp && MobEspFeature.selected().size()==1,"disable retains selection"));
            click(context,"MobESP");
            click(context,"All off");
            context.runOnClient(client -> check(!PlayerSettings.mobEsp,"All off disables MobESP"));
            // Real through-wall rendering with two different selected mob types.
            context.setScreen(()->null);
            context.runOnClient(client -> { MobEspFeature.add("cow, zombie");PlayerSettings.mobEsp=true; });
            context.waitTicks(10);context.takeScreenshot("mobesp-through-wall");
            context.runOnClient(client -> {
                var cow = (Mob)client.level.getEntity(ids.get().getFirst());
                cow.setHealth(0);
                check(MobEspFeature.colorFor(cow)<0,"dead mobs excluded immediately");
            });
        } finally {
            context.runOnClient(client -> {
                MobEspFeature.clear();
                for (var id : selected) MobEspFeature.add(id.toString());
                PlayerSettings.mobEsp=enabled;
            });
        }
    }
    private static EditBox input(net.minecraft.client.gui.screens.Screen screen) {
        return screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c)
                .filter(c->c.getMessage().getString().equals("Mob names")).findFirst().orElseThrow();
    }
    private static void click(ClientGameTestContext context,String label) {
        context.runOnClient(client -> {
            var screen=client.gui.screen();
            var button=screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c)
                    .filter(c->c.getMessage().getString().equals(label)).findFirst().orElseThrow();
            var event=new MouseButtonEvent((button.getX()+5)*ClickGuiScreen.UI_SCALE,
                    (button.getY()+5)*ClickGuiScreen.UI_SCALE,new MouseButtonInfo(0,0));
            check(screen.mouseClicked(event,false),"clicked "+label);screen.mouseReleased(event);
        });
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private MobEspGameTest() { }
}
