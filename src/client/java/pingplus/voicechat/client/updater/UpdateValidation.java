package pingplus.voicechat.client.updater;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarFile;

public final class UpdateValidation {
    public static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void validate(Path file, GitHubReleases.Release release, Map<String, Version> installed) throws IOException {
        if (Files.size(file) != release.size() || !sha256(file).equals(release.sha256()))
            throw new IOException("The downloaded update failed its SHA-256 or size check. Please retry.");
        try (var jar = new JarFile(file.toFile())) {
            var entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) throw new IOException("The update is not a Fabric mod.");
            byte[] metadata;
            try (var in = jar.getInputStream(entry)) { metadata = in.readNBytes(65537); }
            if (metadata.length > 65536) throw new IOException("The update's mod metadata is too large.");
            var mod = JsonParser.parseString(new String(metadata, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"voicechat".equals(mod.get("id").getAsString())) throw new IOException("The update is not Air Client.");
            if (!release.version().equals(mod.get("version").getAsString()))
                throw new IOException("The JAR version does not match its GitHub release tag.");
            SemanticVersion.parse(mod.get("version").getAsString());
            if (mod.has("environment") && !"client".equals(mod.get("environment").getAsString())
                    && !"*".equals(mod.get("environment").getAsString()))
                throw new IOException("The update is not a client mod.");
            var dependencies = mod.getAsJsonObject("depends");
            if (dependencies == null) throw new IOException("The update has no compatibility requirements.");
            for (String required : java.util.List.of("minecraft", "java", "fabricloader", "fabric-api")) {
                if (!dependencies.has(required) || !installed.containsKey(required)
                        || !matches(dependencies.get(required), installed.get(required)))
                    throw new IOException("This update requires a different " + required + " version. Update it manually first.");
            }
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("The update contains invalid mod metadata.", e); }
    }

    private static boolean matches(JsonElement requirement, Version installed) throws Exception {
        if (requirement.isJsonArray()) {
            for (var alternative : requirement.getAsJsonArray())
                if (VersionPredicate.parse(alternative.getAsString()).test(installed)) return true;
            return false;
        }
        return VersionPredicate.parse(requirement.getAsString()).test(installed);
    }

    private UpdateValidation() { }
}
