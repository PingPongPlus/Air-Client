package pingplus.voicechat.client;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

/**
 * Slayer boss outline, inspired by SkyHanni.
 *
 * <p>SkyHanni Vorbild:
 * <ul>
 *   <li>{@code SlayerType.kt} - Boss-Namen (Revenant Horror, Tarantula Broodfather, Sven Packmaster,
 *       Voidgloom Seraph, Inferno Demonlord, Bloodfiend)</li>
 *   <li>{@code SlayerMiniBossFeatures.kt:31} - {@code mob.highlight(AQUA)} bei Spawn</li>
 *   <li>{@code RenderLivingEntityHelper.kt:94} - {@code setEntityColor(entity, color)} via Glow-Map +
 *       {@code CustomGlowCallback} (RenderChest, nur 26.2+)</li>
 * </ul>
 * Hier vereinfacht ohne RenderChest/Kotlin/Config-Infra: Vanilla-Glow mit gelbem Scoreboard-Team.
 * Das gibt die gleiche cleane leuchtende Outline, Farbe kommt vom Team.
 */
public final class SlayerBossHighlight {
    private static final String TEAM_NAME = "untitled_slayer_boss";

    // Aus SkyHanni SlayerType.kt + otherNames
    private static final Set<String> BOSS_NAMES = Set.of(
        "Revenant Horror",
        "Atoned Horror",
        "Tarantula Broodfather",
        "Conjoined Brood",
        "Sven Packmaster",
        "Voidgloom Seraph",
        "Inferno Demonlord",
        "Bloodfiend",
        "Riftstalker Bloodfiend"
    );

    // Aus SkyHanni SlayerMiniBossType.kt - optional mit highlighten
    private static final Set<String> MINIBOSS_NAMES = Set.of(
        "Revenant Sycophant", "Revenant Champion", "Deformed Revenant", "Atoned Champion", "Atoned Revenant",
        "Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula",
        "Pack Enforcer", "Sven Follower", "Sven Alpha",
        "Voidling Devotee", "Voidling Radical", "Voidcrazed Maniac",
        "Flare Demon", "Kindleheart Demon", "Burningsoul Demon"
    );

    private SlayerBossHighlight() {}

    public static void tick(Minecraft client) {
        if (!PlayerSettings.slayerBossHighlight) return;
        if (client.level == null) return;

        Scoreboard scoreboard;
        try {
            scoreboard = client.level.getScoreboard();
        } catch (NoSuchMethodError | Exception e) {
            return;
        }
        PlayerTeam team = ensureYellowTeam(scoreboard);
        if (team == null) return;

        Iterable<Entity> entities;
        try {
            entities = client.level.entitiesForRendering();
        } catch (NoSuchMethodError | Exception e) {
            return;
        }

        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isRemoved() || !living.isAlive()) {
                cleanup(living, scoreboard, team);
                continue;
            }

            if (isSlayerBoss(living)) {
                try {
                    String scoreName = living.getScoreboardName();
                    if (living.getTeam() != team) {
                        scoreboard.addPlayerToTeam(scoreName, team);
                    }
                    if (!living.hasGlowingTag()) {
                        living.setGlowingTag(true);
                    }
                } catch (NoSuchMethodError | Exception ignored) {
                    // API-Drift zwischen MC-Versionen ignorieren
                }
            } else {
                // Nur eigene Markierung entfernen, fremde Teams/Glows nicht anfassen
                if (living.getTeam() == team) {
                    cleanup(living, scoreboard, team);
                }
            }
        }
    }

    public static void onDisconnect() {
        // Glow wird mit Entity-Unload automatisch verworfen, Team bleibt für Rejoin bestehen.
    }

    public static boolean isSlayerBoss(String name) {
        if (name == null || name.isEmpty()) return false;
        // Strict SkyHanni-style: one tag must match the slayer/basic name format
        // AND equal a known boss/miniboss name. No substring heuristics.
        pingplus.voicechat.client.slayer.SlayerMobDetector.TagMatch match =
                pingplus.voicechat.client.slayer.SlayerMobDetector.matchTag(name);
        return match.kind() == pingplus.voicechat.client.slayer.SlayerMobDetector.Kind.BOSS
                || match.kind() == pingplus.voicechat.client.slayer.SlayerMobDetector.Kind.MINIBOSS;
    }

    public static boolean isSlayerBoss(Entity entity) {
        if (entity == null) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        if (living.isRemoved() || !living.isAlive()) return false;
        // Own name tags only (custom name, then display name), plus the exact
        // slayer mob class - same criteria as SlayerMobDetector.
        for (String tag : pingplus.voicechat.client.slayer.SlayerMobDetector.nameTags(entity)) {
            pingplus.voicechat.client.slayer.SlayerMobDetector.TagMatch match =
                    pingplus.voicechat.client.slayer.SlayerMobDetector.matchTag(tag);
            if (match.kind() != pingplus.voicechat.client.slayer.SlayerMobDetector.Kind.BOSS
                    && match.kind() != pingplus.voicechat.client.slayer.SlayerMobDetector.Kind.MINIBOSS) {
                continue;
            }
            if (pingplus.voicechat.client.slayer.SlayerMobDetector.matchesClass(
                    pingplus.voicechat.client.slayer.SlayerMobDetector.expectedClass(match.name()), entity)) {
                return true;
            }
        }
        return false;
    }

    private static String entityName(LivingEntity living) {
        return entityName((Entity) living);
    }

    public static String entityName(Entity entity) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(entity.getName().getString()).append(' ');
            sb.append(entity.getDisplayName().getString()).append(' ');
            if (entity.hasCustomName() && entity.getCustomName() != null) {
                sb.append(entity.getCustomName().getString());
            }
            return sb.toString();
        } catch (NoSuchMethodError | Exception e) {
            return "";
        }
    }

    private static PlayerTeam ensureYellowTeam(Scoreboard scoreboard) {
        try {
            PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
            if (team == null) {
                team = scoreboard.addPlayerTeam(TEAM_NAME);
            }
            if (!team.getColor().map(c -> c == TeamColor.YELLOW).orElse(false)) {
                team.setColor(Optional.of(TeamColor.YELLOW));
            }
            return team;
        } catch (NoSuchMethodError | Exception e) {
            return null;
        }
    }

    private static void cleanup(LivingEntity living, Scoreboard scoreboard, PlayerTeam team) {
        try {
            if (living.getTeam() == team) {
                scoreboard.removePlayerFromTeam(living.getScoreboardName(), team);
            }
            // Glow nur entfernen wenn wir es gesetzt haben (Team war unseres)
            if (living.hasGlowingTag()) {
                // Vorsichtig: nicht fremdes Glow entfernen wenn Entity noch im Kampf ist
                if (!isSlayerBoss(living)) {
                    living.setGlowingTag(false);
                }
            }
        } catch (NoSuchMethodError | Exception ignored) {
        }
    }

    /** Für Tests / Debug: welche Namen matchen. */
    static List<String> bossNames() {
        return List.copyOf(BOSS_NAMES);
    }
}
