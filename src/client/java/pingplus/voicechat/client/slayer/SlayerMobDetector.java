package pingplus.voicechat.client.slayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Detection follows SkyHanni (MobFilter.kt / MobFactories.kt / SlayerType.kt / MobFinder.kt):
//  1. the name tag must match the strict slayer format "icon <Name> <Tier> <HP>"
//     (SkyHanni MobFilter "filter.slayer", including the tier-less Atoned Horror /
//     Conjoined Brood special case), or the strict basic format "[LvN] <Name> <HP>"
//     (SkyHanni MobFilter "filter.basic") for tier-less tags (minibosses, Diana);
//  2. the parsed name must EXACTLY equal a known boss/miniboss/Diana name
//     (SkyHanni uses exact set membership / equality, never substring checks);
//  3. the entity must be the exact slayer mob class from SkyHanni SlayerType.kt
//     (Zombie / Spider / Wolf / EnderMan / Blaze);
//  4. full bosses only count when spawned by the local player ("Spawned by" tag,
//     VampireSlayerFeatures.kt), minibosses always count.
// The TARGET is always a real mob (never armor stand, never player).
public final class SlayerMobDetector {
    // Full slayer bosses (SkyHanni SlayerType.kt displayName + otherNames).
    private static final Set<String> BOSSES = Set.of(
        "Revenant Horror", "Atoned Horror",
        "Tarantula Broodfather", "Conjoined Brood",
        "Sven Packmaster", "Voidgloom Seraph",
        "Inferno Demonlord", "Bloodfiend", "Riftstalker Bloodfiend"
    );
    // Minibosses (SkyHanni SlayerMiniBossType.kt), red outline only.
    private static final Set<String> MINIBOSSES = Set.of(
        "Revenant Sycophant", "Revenant Champion", "Deformed Revenant",
        "Atoned Champion", "Atoned Revenant",
        "Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula",
        "Primordial Jockey", "Primordial Viscount",
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
    // Tier-less bosses: the Bloodfiend line never shows a roman tier on Hypixel,
    // so (unlike the other bosses) it is also accepted via the basic <Name> <HP>
    // format. Entity type is still enforced exactly.
    private static final Set<String> TIERLESS_BOSSES = Set.of("Bloodfiend", "Riftstalker Bloodfiend");

    // Exact slayer mob class per known name (SkyHanni SlayerType.kt clazz).
    private static final Map<String, Class<?>> NAME_TO_CLASS = Map.ofEntries(
        Map.entry("Revenant Horror", Zombie.class),
        Map.entry("Atoned Horror", Zombie.class),
        Map.entry("Tarantula Broodfather", Spider.class),
        Map.entry("Conjoined Brood", Spider.class),
        Map.entry("Sven Packmaster", Wolf.class),
        Map.entry("Voidgloom Seraph", EnderMan.class),
        Map.entry("Inferno Demonlord", Blaze.class),
        Map.entry("Bloodfiend", Zombie.class),
        Map.entry("Riftstalker Bloodfiend", Zombie.class),
        Map.entry("Revenant Sycophant", Zombie.class),
        Map.entry("Revenant Champion", Zombie.class),
        Map.entry("Deformed Revenant", Zombie.class),
        Map.entry("Atoned Champion", Zombie.class),
        Map.entry("Atoned Revenant", Zombie.class),
        Map.entry("Tarantula Vermin", Spider.class),
        Map.entry("Tarantula Beast", Spider.class),
        Map.entry("Mutant Tarantula", Spider.class),
        Map.entry("Primordial Jockey", Spider.class),
        Map.entry("Primordial Viscount", Spider.class),
        Map.entry("Pack Enforcer", Wolf.class),
        Map.entry("Sven Follower", Wolf.class),
        Map.entry("Sven Alpha", Wolf.class),
        Map.entry("Voidling Devotee", EnderMan.class),
        Map.entry("Voidling Radical", EnderMan.class),
        Map.entry("Voidcrazed Maniac", EnderMan.class),
        Map.entry("Flare Demon", Blaze.class),
        Map.entry("Kindleheart Demon", Blaze.class),
        Map.entry("Burningsoul Demon", Blaze.class)
    );

    // SkyHanni MobFilter.kt "filter.slayer", transliterated to Java:
    // "^$mobType. (?<name>.*)(?: (?<tier>[IV]+)|(?<=Atoned Horror|Conjoined Brood)) \\d+.*"
    // Requires the tier suffix (or the special tier-less boss names) plus HP digits,
    // e.g. "☠ Revenant Horror IV 1.5M❤" or "☠ Atoned Horror 2M❤".
    // Anything without that structure (plain mob names, player names, pets) is rejected.
    // NOTE: SkyHanni's literal ". " after the icon group eats the icon when the
    // group is empty, but eats the name's first letter when the group matched
    // ("evenant Horror"); the intent is "optional icon, then name", implemented here.
    // Unlike SkyHanni, several leading icon runs are accepted ("✯ ☠ ...") so live
    // tag variants still resolve; exact name + exact class stay mandatory.
    private static final Pattern SLAYER_TAG = Pattern.compile(
        "^(?:[^\\w\\s\\-]+ )*(?<name>.*)(?: (?<tier>[IV]+)|(?<=Atoned Horror|Conjoined Brood)) \\d+.*");

    // SkyHanni MobFilter.kt "filter.basic" (level / icon / name / HP, no tier):
    // "$level$mobType(?<corrupted>.Corrupted )?(?<name>[^ᛤ]*)(?: ᛤ)? [\\dBMk.,<hp-icon>]+"
    // Used for tier-less tags (minibosses without tier, Diana mobs, tier-less Bloodfiend).
    private static final Pattern BASIC_TAG = Pattern.compile(
        "(?:\\[Lv(?<level>\\d+)\\] )?(?:[^\\w\\s\\-]+ )*(?:.Corrupted )?(?<name>[^ᛤ]*)(?: ᛤ)? [\\dBMk.,❤♥]+");

    // Trailing roman tier on a basically-structured tag, e.g. "Revenant Horror IV"
    // from "[Lv100] ☠ Revenant Horror IV 1.5M❤". Stripped only for the exact-name
    // retry below; the entity type is still enforced exactly by the caller.
    private static final Pattern TIER_SUFFIX = Pattern.compile("^(.*) ([IVXLCDM]+)$");

    // Fast pre-filter for diagnostics: does this tag mention any known name at all?
    private static final Pattern KNOWN_NAME_HINT = Pattern.compile(knownNameAlternation());

    private static String knownNameAlternation() {
        java.util.StringJoiner joiner = new java.util.StringJoiner("|");
        for (String n : BOSSES) joiner.add(Pattern.quote(n));
        for (String n : MINIBOSSES) joiner.add(Pattern.quote(n));
        return joiner.toString();
    }

    /** True when the tag mentions any known boss/miniboss name (diagnostics only, not detection). */
    public static boolean containsKnownName(String cleanTag) {
        return cleanTag != null && KNOWN_NAME_HINT.matcher(cleanTag).find();
    }

    // Precompiled once: stripColor runs for every entity every tick.
    private static final Pattern COLOR_CODES = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    // Name tag stands sit roughly 1-3 blocks above/inside the mob.
    public static final double NAME_STAND_RADIUS = 3.0;
    public static final double NAME_STAND_RADIUS_SQ = NAME_STAND_RADIUS * NAME_STAND_RADIUS;

    private static final Logger LOG = LoggerFactory.getLogger("slayer-outline");
    private static final Set<String> REJECT_LOG = ConcurrentHashMap.newKeySet();

    public enum Kind { NONE, MINIBOSS, BOSS, DIANA }
    public enum Ownership { OWN, OTHER, UNKNOWN }

    /** Strict classification result for one name tag: kind plus the exactly matched known name. */
    public record TagMatch(Kind kind, String name) {
        public static final TagMatch NONE = new TagMatch(Kind.NONE, "");
    }

    private SlayerMobDetector() {}

    // Strict classification of ONE cleaned name tag (SkyHanni MobFactories.slayer/basic
    // + MobFinder exact-name checks). Returns the exactly matched known name.
    public static TagMatch matchTag(String tag) {
        if (tag == null) return TagMatch.NONE;
        String clean = stripColor(tag).trim();
        if (clean.isEmpty()) return TagMatch.NONE;

        Matcher slayer = SLAYER_TAG.matcher(clean);
        if (slayer.matches()) {
            String name = slayer.group("name").trim();
            if (BOSSES.contains(name)) return new TagMatch(Kind.BOSS, name);
            if (MINIBOSSES.contains(name)) return new TagMatch(Kind.MINIBOSS, name);
            // Unknown name with slayer structure (e.g. extra live prefixes around
            // the same core): fall through to the basic route instead of rejecting.
        }
        Matcher basic = BASIC_TAG.matcher(clean);
        if (basic.matches()) {
            String name = basic.group("name").trim();
            if (MINIBOSSES.contains(name)) return new TagMatch(Kind.MINIBOSS, name);
            if (TIERLESS_BOSSES.contains(name)) return new TagMatch(Kind.BOSS, name);
            if (DIANA_MOBS.contains(name)) return new TagMatch(Kind.DIANA, name);
            if (BOSSES.contains(name)) return new TagMatch(Kind.BOSS, name);
            // Live variance: basically-structured tags with a trailing roman tier
            // (e.g. prefixed "☠ Revenant Horror IV 1.5M❤" variants). Retry with the
            // tier stripped, still exact-name plus exact-class (enforced by caller).
            String stripped = stripTierSuffix(name);
            if (!stripped.equals(name)) {
                if (BOSSES.contains(stripped)) return new TagMatch(Kind.BOSS, stripped);
                if (MINIBOSSES.contains(stripped)) return new TagMatch(Kind.MINIBOSS, stripped);
            }
            return TagMatch.NONE;
        }
        return TagMatch.NONE;
    }

    private static String stripTierSuffix(String name) {
        Matcher m = TIER_SUFFIX.matcher(name);
        return m.matches() ? m.group(1).trim() : name;
    }

    // Bosses first, then minibosses, then Diana, then regex fallback.
    // The input must be ONE name tag (custom name or display name), never the
    // concatenated fullName: the strict patterns are start-anchored like SkyHanni's.
    public static Kind classifyName(String tag, SlayerOutlineConfig cfg) {
        if (tag == null || tag.isEmpty()) return Kind.NONE;
        TagMatch match = matchTag(tag);
        if (match.kind() != Kind.NONE) return match.kind();
        if (cfg != null && cfg.nameFilter != null && !cfg.nameFilter.isEmpty()) {
            try { if (stripColor(tag).trim().matches(cfg.nameFilter)) return Kind.MINIBOSS; }
            catch (Exception ignored) {}
        }
        return Kind.NONE;
    }

    // Strict classification over the entity's own name tags (custom name first,
    // then display name). Entity type names ("Zombie", ...) are deliberately NOT
    // tested: like SkyHanni, the tag alone must prove the mob is a boss.
    public static TagMatch matchEntity(Entity e, SlayerOutlineConfig cfg) {
        for (String tag : nameTags(e)) {
            TagMatch match = matchTag(tag);
            if (match.kind() != Kind.NONE) return match;
            if (cfg != null && cfg.nameFilter != null && !cfg.nameFilter.isEmpty()) {
                try {
                    if (stripColor(tag).trim().matches(cfg.nameFilter))
                        return new TagMatch(Kind.MINIBOSS, stripColor(tag).trim());
                } catch (Exception ignored) {}
            }
        }
        return TagMatch.NONE;
    }

    // The matched display name, e.g. "Revenant Horror" from a full tag string.
    public static String matchName(String tag) {
        TagMatch match = matchTag(tag);
        if (match.kind() != Kind.NONE) return match.name();
        return stripColor(tag).trim();
    }

    // Exact slayer mob class for a known name (SkyHanni SlayerType.kt clazz),
    // null when the name carries no class restriction (Diana mobs, unknown).
    public static Class<?> expectedClass(String name) {
        return name == null ? null : NAME_TO_CLASS.get(name);
    }

    public static boolean matchesClass(Class<?> expected, Entity mob) {
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
        return s == null ? "" : COLOR_CODES.matcher(s).replaceAll("");
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

    // The entity's own name tags: custom name first, then display name.
    // (The plain entity type name is not a boss tag and is never tested.)
    public static List<String> nameTags(Entity e) {
        List<String> tags = new ArrayList<>(2);
        try {
            if (e.hasCustomName() && e.getCustomName() != null) {
                String custom = e.getCustomName().getString();
                if (custom != null && !custom.isEmpty()) tags.add(custom);
            }
            String display = e.getDisplayName().getString();
            if (display != null && !display.isEmpty() && !tags.contains(display)) tags.add(display);
        } catch (Exception ex) {
            // API drift between MC versions: no tags then.
        }
        return tags;
    }

    // Phase 1: the mob carries its own name (common for slayer bosses).
    public static TagMatch classifyMob(Entity e, Iterable<Entity> all, SlayerOutlineConfig cfg, String myName) {
        if (!isEligibleMob(e)) return TagMatch.NONE;
        LivingEntity living = (LivingEntity) e;
        if (living.isRemoved() || !living.isAlive()) return TagMatch.NONE;

        TagMatch match = matchEntity(living, cfg);
        if (match.kind() == Kind.NONE) return TagMatch.NONE;
        Class<?> expected = expectedClass(match.name());
        if (!matchesClass(expected, living)) {
            logReject("class mismatch (expected " + simpleName(expected)
                    + ", got " + living.getClass().getSimpleName() + ")", fullName(living));
            return TagMatch.NONE;
        }

        if (match.kind() == Kind.BOSS) {
            if (bossOwnership(fullName(living), nearbyStands(living, all), myName) == Ownership.OTHER) {
                logReject("foreign boss (spawned by someone else)", fullName(living));
                return TagMatch.NONE;
            }
        }
        return match;
    }

    // One-time diagnostics: why a boss-like tag was rejected (live tag variants).
    static void logReject(String reason, String tag) {
        String clean = stripColor(tag).trim();
        if (clean.isEmpty()) return;
        if (REJECT_LOG.add(reason + "|" + clean)) {
            LOG.info("Slayer detection rejected ({}): '{}'", reason, clean);
        }
    }

    private static String simpleName(Class<?> clazz) {
        return clazz == null ? "<any>" : clazz.getSimpleName();
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
        TagMatch match = matchEntity(stand, cfg);
        if (match.kind() == Kind.NONE) return null;
        Class<?> expected = expectedClass(match.name());
        if (match.kind() == Kind.BOSS && bossOwnership("", List.of(stand), myName) == Ownership.OTHER) {
            logReject("foreign boss (spawned by someone else)", fullName(stand));
            return null;
        }

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
    // NOTE: only used for "Spawned by" ownership and HP parsing (substring
    // searches). Strict classification must use matchTag/matchEntity on single
    // tags instead, because those patterns are start-anchored like SkyHanni's.
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
