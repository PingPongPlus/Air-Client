package pingplus.voicechat.client.updater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Public release assets only: no credentials, source archives, or arbitrary download hosts. */
public final class GitHubReleases {
    public static final long MAX_JAR_BYTES = 256L * 1024 * 1024;
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");
    private final String repository;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public record Release(String version, URI download, long size, String sha256) { }

    public GitHubReleases(String repository) {
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Invalid update repository");
        this.repository = repository;
    }

    public Optional<Release> latest(String installedVersion) throws Exception {
        var request = request(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").build();
        // Bound the metadata body too; a release response is normally a few KB.
        var future = http.sendAsync(request, info -> new BoundedSubscriber<>(
                HttpResponse.BodySubscribers.ofByteArray(), 2 * 1024 * 1024, bytes -> { }));
        HttpResponse<byte[]> response;
        try { response = future.get(30, TimeUnit.SECONDS); }
        catch (Exception e) { future.cancel(true); throw e; }
        if (response.statusCode() == 404) return Optional.empty(); // No published release yet.
        if (response.statusCode() == 403 || response.statusCode() == 429)
            throw new IOException("GitHub's request limit was reached. Try again later.");
        if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode());
        return parse(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8), repository, installedVersion);
    }

    public static Optional<Release> parse(String json, String repository, String installedVersion) throws IOException {
        try {
            JsonObject release = JsonParser.parseString(json).getAsJsonObject();
            if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) return Optional.empty();
            String tag = release.get("tag_name").getAsString();
            String version = normalizeVersion(tag);
            var parsed = SemanticVersion.parse(version);
            if (parsed.getPrereleaseKey().isPresent()
                    || parsed.compareTo(SemanticVersion.parse(normalizeVersion(installedVersion))) <= 0)
                return Optional.empty();
            String expectedName = "Air-Client-" + version + ".jar";
            var assetNames = Set.of(expectedName, tag + ".jar");
            Release selected = null;
            for (var item : release.getAsJsonArray("assets")) {
                var asset = item.getAsJsonObject();
                String assetName = asset.get("name").getAsString();
                if (!assetNames.contains(assetName)) continue;
                if (selected != null) throw new IOException("The release has ambiguous client assets.");
                long size = asset.get("size").getAsLong();
                if (size <= 0 || size > MAX_JAR_BYTES) throw new IOException("Invalid update size.");
                if (!asset.has("digest") || asset.get("digest").isJsonNull()
                        || !asset.get("digest").getAsString().matches("sha256:[0-9a-fA-F]{64}"))
                    throw new IOException("This release is missing its SHA-256 digest. Re-upload the release JAR.");
                URI url = URI.create(asset.get("browser_download_url").getAsString());
                requireDownloadUrl(url);
                String expectedPath = "/" + repository + "/releases/download/" + tag + "/" + assetName;
                if (!"github.com".equalsIgnoreCase(url.getHost()) || !expectedPath.equalsIgnoreCase(url.getPath()))
                    throw new IOException("The update asset does not belong to the configured repository.");
                selected = new Release(version, url, size, asset.get("digest").getAsString().substring(7).toLowerCase(java.util.Locale.ROOT));
            }
            if (selected == null) throw new IOException("The release needs an asset named " + expectedName);
            return Optional.of(selected);
        } catch (VersionParsingException | RuntimeException e) {
            throw new IOException("GitHub returned invalid release metadata.", e);
        }
    }

    public static String normalizeVersion(String version) {
        String normalized = version.replaceFirst("^[vV]\\.?", "");
        if (!normalized.matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"))
            throw new IllegalArgumentException("Invalid release version: " + version);
        return normalized;
    }

    public void download(Release release, Path destination, IntConsumer progress) throws Exception {
        URI url = release.download();
        for (int redirect = 0; redirect < 6; redirect++) {
            requireDownloadUrl(url);
            var request = request(url).timeout(Duration.ofMinutes(5)).build();
            var future = http.sendAsync(request, info -> info.statusCode() == 200
                    ? new BoundedSubscriber<>(HttpResponse.BodySubscribers.ofFile(destination), release.size(),
                            bytes -> progress.accept((int) (bytes * 100L / release.size())))
                    : HttpResponse.BodySubscribers.<Path>replacing(null));
            HttpResponse<Path> response;
            try { response = future.get(5, TimeUnit.MINUTES); }
            catch (Exception e) { future.cancel(true); throw e; }
            if (response.statusCode() == 200) return;
            if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("GitHub omitted the download location."));
                url = url.resolve(location);
            } else throw new IOException("The download returned HTTP " + response.statusCode());
        }
        throw new IOException("Too many download redirects.");
    }

    static void requireDownloadUrl(URI url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || !DOWNLOAD_HOSTS.contains(url.getHost().toLowerCase(java.util.Locale.ROOT))
                || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443))
            throw new IOException("The update download must use an approved GitHub HTTPS host.");
    }

    private static HttpRequest.Builder request(URI url) {
        return HttpRequest.newBuilder(url).header("User-Agent", "Air-Client-Updater");
    }

    /** Cancels before writing more bytes than advertised, including chunked responses. */
    static final class BoundedSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private final long limit;
        private final java.util.function.LongConsumer progress;
        private Flow.Subscription subscription;
        private long received;
        private boolean failed;
        BoundedSubscriber(HttpResponse.BodySubscriber<T> delegate, long limit, java.util.function.LongConsumer progress) {
            this.delegate = delegate; this.limit = limit; this.progress = progress;
        }
        @Override public CompletionStage<T> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) received += buffer.remaining();
            if (received > limit) {
                failed = true; subscription.cancel();
                delegate.onError(new IOException("The update response exceeds its allowed size."));
                return;
            }
            delegate.onNext(buffers); progress.accept(received);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
