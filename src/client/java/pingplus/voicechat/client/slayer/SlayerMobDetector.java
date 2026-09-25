package pingplus.voicechat.client.slayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;

// Detection follows SkyHanni (MobFilter.kt / SlayerType.kt / VampireSlayerFeatures.kt):
// the TARGET is always a real mob (never armor stand, never player).
// Bosses only glow when spawned by the local player ("Spawned by: <name>" tag,
// VampireSlayerFeatures.kt:122), minibosses always glow.
public final class SlayerMobDetector {
    // Full slayer bosses.
    private static final Set<String> BOSSES = Set.of(
        "Revenant Horror", "Atoned Horror",
        "Tarantula Broodfather", "Conjoined Brood",
        "Sven Packmaster", "Voidgloom Seraph",
        "Inferno Demonlord", "Bloodfiend", "Riftstalker Bloodfiend"
    );
    // Minibosses, red outline only.
    private static final Set<String> MINIBOSSES = Set.of(
        "Revenant Sycophant", "Revenant Champion", "Deformed Revenant",
        "Atoned Champion", "Atoned Revenant",
        "Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula",
        "Pack Enforcer", "Sven Follower", "Sven Alpha",
        "Voidling Devotee", "Voidling Radical", "Voidcrazed Maniac",
        "Flare Demon", "Kindleheart Demon", "Burningsoul Demon"
    );
    // Diana / mythological ritual mobs (health bar only, no glow).
    private static final Set<String> DIANA_MOBS = Set.of(
        "Minos Hunter", "Siamese Lynx", "Minotaur", "Gaia Construct",
        "Minos Champion", "Minos Inquisitor", "King Minos",
        "Sphinx", "Manticore", "Lazy Minos"
    );

    // Name tag stands sit roughly 1-3 blocks above/inside the mob.
    public static final double NAME_STAND_RADIUS = 3.0;
    public static final double NAME_STAND_RADIUS_SQ = NAME_STAND_RADIUS * NAME_STAND_RADIUS;

    public enum Kind { NONE, MINIBOSS, BOSS, DIANA }
    public enum Ownership { OWN, OTHER, UNKNOWN }

    private SlayerMobDetector() {}

    // Bosses first, then minibosses, then Diana, then regex fallback.
    public static Kind classifyName(String name, SlayerOutlineConfig cfg) {
        if (name == null || name.isEmpty()) return Kind.NONE;
        for (String b : BOSSES) if (name.contains(b)) return Kind.BOSS;
        for (String m : MINIBOSSES) if (name.contains(m)) return Kind.MINIBOSS;
        for (String d : DIANA_MOBS) if (name.contains(d)) return Kind.DIANA;
        if (cfg.nameFilter != null && !cfg.nameFilter.isEmpty()) {
            try { if (name.matches(cfg.nameFilter)) return Kind.MINIBOSS; }
            catch (Exception ignored) {}
        }
        return Kind.NONE;
    }

    // The matched display name, e.g. "Revenant Horror" from a full tag string.
    public static String matchName(String name) {
        if (name == null) return "";
        for (String b : BOSSES) if (name.contains(b)) return b;
        for (String m : MINIBOSSES) if (name.contains(m)) return m;
        for (String d : DIANA_MOBS) if (name.contains(d)) return d;
        return stripColor(name).trim();
    }

    // SkyHanni SlayerType.kt maps every slayer to one mob class; use it to
    // avoid highlighting a random mob standing next to a foreign name tag.
    private static Class<?> expectedClass(String name) {
        if (name.contains("Revenant") || name.contains("Atoned")) return Zombie.class;
        if (name.contains("Tarantula") || name.contains("Conjoined") || name.contains("Mutant")) return Spider.class;
        if (name.contains("Sven") || name.contains("Pack Enforcer") || name.contains("Follower") || name.contains("Alpha")) return Wolf.class;
        if (name.contains("Voidling") || name.contains("Voidcrazed") || name.contains("Seraph")) return EnderMan.class;
        if (name.contains("Flare Demon") || name.contains("Kindleheart") || name.contains("Burningsoul")) return Blaze.class;
        return null; // Bloodfiend and unknown names: no class restriction
    }

    private static boolean matchesClass(Class<?> expected, Entity mob) {
        return expected == null || expected.isInstance(mob);
    }

    private static boolean isEligibleMob(Entity e) {
        return e instanceof LivingEntity
            && !(e instanceof ArmorStand)
            && !(e instanceof Player);
    }

    // Strip formatting codes, then find the "Spawned by" owner if present.
    public static String spawnedByName(String raw) {
        String clean = stripColor(raw);
        int idx = clean.indexOf("Spawned by:");
        if (idx == -1) return null;
        return clean.substring(idx + "Spawned by:".length()).trim();
    }

    public static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    // Ownership of a boss: own name tag first, then nearby stands (VampireSlayerFeatures.kt:139).
    public static Ownership bossOwnership(String ownName, List<ArmorStand> stands, String myName) {
        String by = spawnedByName(ownName);
        if (by != null && !by.isEmpty())
            return by.equalsIgnoreCase(myName) ? Ownership.OWN : Ownership.OTHER;
        for (ArmorStand stand : stands) {
            by = spawnedByName(fullName(stand));
            if (by != null && !by.isEmpty())
                return by.equalsIgnoreCase(myName) ? Ownership.OWN : Ownership.OTHER;
        }
        return Ownership.UNKNOWN; // no marker: assume own (fallback)
    }

    // Phase 1: the mob carries its own name (common for slayer bosses).
    public static Kind classifyMob(Entity e, Iterable<Entity> all, SlayerOutlineConfig cfg, String myName) {
        if (!isEligibleMob(e)) return Kind.NONE;
        LivingEntity living = (LivingEntity) e;
        if (living.isRemoved() || !living.isAlive()) return Kind.NONE;

        String name = fullName(living);
        Kind kind = classifyName(name, cfg);
        if (kind == Kind.NONE) return Kind.NONE;
        if (!matchesClass(expectedClass(name), living)) return Kind.NONE;

        if (kind == Kind.BOSS) {
            if (bossOwnership(name, nearbyStands(living, all), myName) == Ownership.OTHER)
                return Kind.NONE;
        }
        return kind;
    }

    private static List<ArmorStand> nearbyStands(Entity mob, Iterable<Entity> all) {
        List<ArmorStand> stands = new ArrayList<>();
        for (Entity e : all) {
            if (e instanceof ArmorStand stand
                    && !stand.isRemoved()
                    && stand.distanceToSqr(mob) <= NAME_STAND_RADIUS_SQ) {
                stands.add(stand);
            }
        }
        return stands;
    }

    // Phase 2: one name stand -> exactly one target mob. The mob must sit
    // almost directly below the name tag (tight horizontal window), so a
    // random mob passing by cannot steal the boss outline.
    public static LivingEntity nearestCandidate(ArmorStand stand, Iterable<Entity> all,
                                                SlayerOutlineConfig cfg, String myName, Set<Integer> claimed) {
        String name = fullName(stand);
        Kind kind = classifyName(name, cfg);
        if (kind == Kind.NONE) return null;
        Class<?> expected = expectedClass(name);
        if (kind == Kind.BOSS && bossOwnership("", List.of(stand), myName) == Ownership.OTHER) return null;

        double sx = stand.getX(), sz = stand.getZ();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : all) {
            if (!isEligibleMob(e) || claimed.contains(e.getId())) continue;
            if (!matchesClass(expected, e)) continue;
            LivingEntity living = (LivingEntity) e;
            if (living.isRemoved() || !living.isAlive()) continue;
            double dx = living.getX() - sx;
            double dz = living.getZ() - sz;
            if (Math.abs(dx) > 1.2 || Math.abs(dz) > 1.2) continue;
            double dy = stand.getY() - (living.getY() + living.getBbHeight() * 0.5);
            if (dy < -1.0 || dy > 4.5) continue;
            double d = dx * dx + dz * dz + dy * dy * 0.25;
            if (d < bestDist) {
                bestDist = d;
                best = living;
            }
        }
        return best;
    }

    // Snapshot helper so callers iterate a stable copy.
    public static List<Entity> snapshot(Iterable<Entity> entities) {
        List<Entity> list = new ArrayList<>();
        for (Entity e : entities) list.add(e);
        return list;
    }

    // Skyblock mob HP lives in the name tag, e.g. "1.5M/2M❤" or "900k❤".
    private static final java.util.regex.Pattern HP_PATTERN =
            java.util.regex.Pattern.compile(
                    "([0-9][0-9.,]*[kKmMbB]?)\\s*(?:/\\s*([0-9][0-9.,]*[kKmMbB]?))?\\s*[❤♥]");

    // Returns [current, max]; max is -1 when the tag only shows current HP.
    public static double[] parseHealth(String name) {
        if (name == null) return null;
        java.util.regex.Matcher m = HP_PATTERN.matcher(stripColor(name));
        if (!m.find()) return null;
        double cur = parseNumber(m.group(1));
        double max = m.group(2) != null ? parseNumber(m.group(2)) : -1;
        return new double[]{cur, max};
    }

    // Handles "2,000", "900k", "1.5M", "2B".
    public static double parseNumber(String s) {
        if (s == null) return -1;
        String t = s.replace(",", "").trim();
        if (t.isEmpty()) return -1;
        try {
            char last = t.charAt(t.length() - 1);
            switch (last) {
                case 'k': case 'K': return Double.parseDouble(t.substring(0, t.length() - 1)) * 1e3;
                case 'm': case 'M': return Double.parseDouble(t.substring(0, t.length() - 1)) * 1e6;
                case 'b': case 'B': return Double.parseDouble(t.substring(0, t.length() - 1)) * 1e9;
            }
            return Double.parseDouble(t);
        } catch (Exception e) { return -1; }
    }

    // Hypixel puts names on armor stands, so check all name sources.
    public static String fullName(Entity e) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(e.getName().getString()).append(' ');
            sb.append(e.getDisplayName().getString()).append(' ');
            if (e.hasCustomName() && e.getCustomName() != null)
                sb.append(e.getCustomName().getString());
            return sb.toString();
        } catch (Exception ex) { return ""; }
    }
}
