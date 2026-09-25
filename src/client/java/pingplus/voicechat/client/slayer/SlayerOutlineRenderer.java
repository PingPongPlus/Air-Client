package pingplus.voicechat.client.slayer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Tracks slayer bosses/minibosses/Diana mobs. The actual through-wall
// outline is drawn by SlayerOutlineMixin, which reads the CACHE colour
// per render frame from the entity render state. This keeps the outline
// stable because the server cannot reset render state between frames.
public final class SlayerOutlineRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("slayer-outline");
    // Entity id -> RGB24 outline colour for SlayerOutlineMixin.
    private static final Map<Integer, Integer> CACHE = new ConcurrentHashMap<>();
    // Entity id -> health bar target (bosses, minibosses, Diana mobs).
    private static final Map<Integer, Target> TARGETS = new ConcurrentHashMap<>();
    // Highest HP ever seen per entity (SkyHanni DamageIndicator pattern):
    // name tags usually only show current HP, so the max is tracked.
    private static final Map<Integer, Double> MAX_SEEN = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_NAMES = ConcurrentHashMap.newKeySet();
    private static final Set<String> REJECTED_LOGGED = ConcurrentHashMap.newKeySet();
    private static SlayerOutlineConfig cfg = new SlayerOutlineConfig();

    // Health bar entry, consumed by MobHealthBarRenderer.
    public record Target(int id, SlayerMobDetector.Kind kind, String name, float hp, float maxHp, long lastSeen) {}

    private SlayerOutlineRenderer() {}

    public static synchronized void init(SlayerOutlineConfig config) {
        cfg = config;
    }

    // Refresh each client tick from visible entities.
    public static void refresh(Iterable<Entity> entities) {
        if (!cfg.enabled) { CACHE.clear(); TARGETS.clear(); return; }

        List<Entity> all = SlayerMobDetector.snapshot(entities);
        String myName = playerName();

        Set<Integer> claimed = new HashSet<>();
        List<ArmorStand> leftoverStands = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();

        // Phase 1: mobs with their own name tag.
        for (Entity e : all) {
            SlayerMobDetector.TagMatch match = SlayerMobDetector.classifyMob(e, all, cfg, myName);
            if (match.kind() != SlayerMobDetector.Kind.NONE) {
                claimed.add(e.getId());
                apply(e, match.kind(), match.name(), SlayerMobDetector.fullName(e), now);
                logDetection(e, match.kind());
            } else {
                putGlow(e.getId(), SlayerMobDetector.Kind.NONE);
                logRejectedTag(SlayerMobDetector.fullName(e));
            }
        }

        // Phase 2: one name stand -> exactly one nearest mob (Enderman fix).
        for (Entity e : all) {
            if (e instanceof ArmorStand stand) {
                if (SlayerMobDetector.matchEntity(stand, cfg).kind()
                        != SlayerMobDetector.Kind.NONE) {
                    leftoverStands.add(stand);
                } else {
                    logRejectedTag(SlayerMobDetector.fullName(stand));
                }
            }
        }
        for (ArmorStand stand : leftoverStands) {
            String standName = SlayerMobDetector.fullName(stand);
            SlayerMobDetector.TagMatch match = SlayerMobDetector.matchEntity(stand, cfg);
            if (match.kind() == SlayerMobDetector.Kind.NONE) continue;
            LivingEntity target = SlayerMobDetector.nearestCandidate(stand, all, cfg, myName, claimed);
            if (target == null) continue;
            claimed.add(target.getId());
            apply(target, match.kind(), match.name(), standName, now);
            logDetection(stand, match.kind());
        }

        // Other players get a light-blue outline, same through-wall pass.
        if (pingplus.voicechat.client.PlayerSettings.playerOutline) {
            var me = Minecraft.getInstance().player;
            if (me != null) {
                int rgb = pingplus.voicechat.client.PlayerSettings.playerOutlineColor & 0xFFFFFF;
                for (Entity e : all) {
                    if (e instanceof net.minecraft.world.entity.player.Player player && player != me) {
                        CACHE.put(e.getId(), rgb);
                    }
                }
            }
        }

        // Own player glows pink: clean and strong, visible in third person.
        if (pingplus.voicechat.client.PlayerSettings.selfOutline) {
            var me = Minecraft.getInstance().player;
            if (me != null) {
                CACHE.put(me.getId(),
                        pingplus.voicechat.client.PlayerSettings.selfOutlineColor & 0xFFFFFF);
            }
        }

        // Evict ids no longer visible to avoid stale outlines/bars.
        CACHE.keySet().removeIf(id -> {
            for (Entity e : all)
                if (e.getId() == id) return false;
            return true;
        });
        TARGETS.keySet().removeIf(id -> {
            for (Entity e : all)
                if (e.getId() == id) return false;
            return true;
        });
        MAX_SEEN.keySet().removeIf(id -> {
            for (Entity e : all)
                if (e.getId() == id) return false;
            return true;
        });
    }

    // Outline colour only for boss/miniboss; every claimed kind gets a bar target.
    private static void apply(Entity e, SlayerMobDetector.Kind kind, String displayName, String hpName, long now) {
        putGlow(e.getId(), kind);
        if (e instanceof LivingEntity living && !living.isRemoved() && living.isAlive()) {
            float hp = living.getHealth();
            float maxHp = living.getMaxHealth();
            // Prefer the HP from the name tag (SkyHanni-style); max is tracked
            // because most tags only show the current HP.
            double[] parsed = SlayerMobDetector.parseHealth(hpName);
            if (parsed != null && parsed[0] >= 0) {
                double cur = parsed[0];
                Double seen = MAX_SEEN.get(e.getId());
                double max = parsed[1] >= 0 ? parsed[1] : Math.max(cur, seen == null ? cur : seen);
                if (parsed[1] < 0) MAX_SEEN.put(e.getId(), max);
                hp = (float) cur;
                maxHp = (float) max;
            }
            TARGETS.put(e.getId(), new Target(
                    e.getId(),
                    kind,
                    displayName,
                    hp,
                    maxHp,
                    now));
        }
    }

    private static void putGlow(int id, SlayerMobDetector.Kind kind) {
        if (kind == SlayerMobDetector.Kind.BOSS && cfg.highlightBoss)
            CACHE.put(id, cfg.bossColor & 0xFFFFFF);
        else if (kind == SlayerMobDetector.Kind.MINIBOSS && cfg.highlightMiniboss)
            CACHE.put(id, cfg.minibossColor & 0xFFFFFF);
        else
            CACHE.remove(id);
    }

    private static String playerName() {
        try {
            var user = Minecraft.getInstance().getUser();
            String name = user.getName();
            return name == null ? "" : name;
        } catch (Exception e) { return ""; }
    }

    private static void logDetection(Entity holder, SlayerMobDetector.Kind kind) {
        String name = SlayerMobDetector.fullName(holder).trim();
        if (name.isEmpty()) return;
        String key = kind + "|" + name;
        if (LOGGED_NAMES.add(key)) {
            String effect = switch (kind) {
                case BOSS -> "yellow outline";
                case MINIBOSS -> "red outline";
                case DIANA -> "health bar only (no outline)";
                default -> "none";
            };
            LOG.info("Detected {}: '{}' -> {}", kind, name, effect);
        }
    }

    // One-time diagnostics for boss-like tags that stay unclaimed (e.g. live tag
    // variants the strict filter misses, or foreign "Spawned by" bosses).
    private static void logRejectedTag(String tag) {
        String clean = SlayerMobDetector.stripColor(tag).trim();
        if (clean.isEmpty() || !SlayerMobDetector.containsKnownName(clean)) return;
        if (REJECTED_LOGGED.add(clean)) {
            LOG.info("Boss-like tag not claimed: '{}'", clean);
        }
    }

    public static void clear() { CACHE.clear(); TARGETS.clear(); MAX_SEEN.clear(); }

    /** Test hook: cached RGB24 for an entity id, -1 when not highlighted. */
    public static int colorFor(int entityId) {
        Integer rgb = CACHE.get(entityId);
        return rgb == null ? -1 : rgb;
    }

    /** Test hook: kind of a health bar target, null when not tracked. */
    public static SlayerMobDetector.Kind targetKindFor(int entityId) {
        Target t = TARGETS.get(entityId);
        return t == null ? null : t.kind();
    }

    /** Snapshot of all health bar targets for the HUD renderer. */
    public static List<Target> targets() {
        return List.copyOf(TARGETS.values());
    }
}
