package pingplus.voicechat.client.spotify;

import com.google.gson.JsonObject;

/** Immutable OS media snapshot. Artwork is inline data, never a remote URL. */
public record SpotifyPlayback(String title, String artist, String image, String key, long progress,
                              long duration, boolean playing, boolean previous, boolean next,
                              boolean toggle, long sampledAt) {
    static String text(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
    private static boolean flag(JsonObject o, String key) { return "true".equals(text(o, key)); }
    public static SpotifyPlayback parse(JsonObject root, long now) {
        if (!root.has("track") || !root.get("track").isJsonObject()) return null;
        var t = root.getAsJsonObject("track");
        return new SpotifyPlayback(text(t, "title"), text(t, "artist"), text(t, "image"), text(t, "key"),
                number(t, "progress"), number(t, "duration"), flag(t, "playing"), flag(t, "previous"),
                flag(t, "next"), flag(t, "toggle"), now);
    }
    private static long number(JsonObject o, String key) { String s = text(o, key); return s.isEmpty() ? 0 : Math.max(0, Long.parseLong(s)); }
    public long position(long now) { return Math.clamp(progress + (playing ? Math.max(0, now - sampledAt) / 1_000_000 : 0), 0, duration); }
}
