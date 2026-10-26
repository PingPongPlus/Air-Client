package pingplus.voicechat.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Type-based glow filters; never changes entity metadata or loads chunks. */
public final class MobEspFeature {
    public static final int COLOR = 0x55FF88;
    private static final Set<EntityType<?>> SELECTED = new LinkedHashSet<>();

    public static void toggle() { PlayerSettings.mobEsp = !PlayerSettings.mobEsp; }

    /** Null means success. Validate the whole comma-separated batch before adding anything. */
    public static String add(String input) {
        if (input.isBlank()) return "Enter a mob name first.";
        var additions = new LinkedHashSet<EntityType<?>>();
        for (String name : input.split(",", -1)) {
            Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id))
                return "Unknown mob: " + name.trim();
            additions.add(BuiltInRegistries.ENTITY_TYPE.getValue(id));
        }
        SELECTED.addAll(additions);
        return null;
    }

    public static List<Identifier> selected() {
        return SELECTED.stream().map(BuiltInRegistries.ENTITY_TYPE::getKey).toList();
    }

    public static void remove(Identifier id) { SELECTED.remove(BuiltInRegistries.ENTITY_TYPE.getValue(id)); }
    public static void clear() { SELECTED.clear(); }

    public static int colorFor(Entity entity) {
        return PlayerSettings.mobEsp && entity instanceof Mob mob && mob.isAlive() && !mob.isRemoved()
                && SELECTED.contains(entity.getType()) ? COLOR : -1;
    }

    private MobEspFeature() { }
}
