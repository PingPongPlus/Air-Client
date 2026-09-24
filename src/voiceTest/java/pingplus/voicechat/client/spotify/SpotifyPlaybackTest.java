package pingplus.voicechat.client.spotify;

import com.google.gson.JsonParser;

public final class SpotifyPlaybackTest {
    public static void main(String[] args) {
        var json = JsonParser.parseString("""
            {"track":{"title":"A track","artist":"One, Two","image":"base64", "key":"test",
                      "progress":1000,"duration":5000,"playing":true,"previous":false,"next":true,"toggle":true}}
            """).getAsJsonObject();
        var track = SpotifyPlayback.parse(json, 1_000_000_000L);
        check(track.artist().equals("One, Two") && track.image().equals("base64"), "local metadata and artwork");
        check(!track.previous() && track.next() && track.toggle(), "per-action restrictions");
        check(track.position(3_000_000_000L) == 3000, "smooth playback progress");
        check(track.position(99_000_000_000L) == 5000, "progress clamps at duration");
        json.getAsJsonObject("track").addProperty("playing", false);
        track = SpotifyPlayback.parse(json, 1_000_000_000L);
        check(track.position(3_000_000_000L) == 1000, "paused position stays fixed");
        json.getAsJsonObject("track").addProperty("toggle", false);
        json.getAsJsonObject("track").addProperty("next", false);
        track = SpotifyPlayback.parse(json, 0);
        check(!track.toggle() && !track.next() && !track.previous(), "restricted devices disable every control");
        json.add("track", com.google.gson.JsonNull.INSTANCE);
        check(SpotifyPlayback.parse(json, 0) == null, "null items and ads do not crash");
        var episode = SpotifyPlayback.parse(JsonParser.parseString("""
            {"track":{"title":"Episode","duration":60000,"artist":"Podcast","progress":null}}
            """).getAsJsonObject(), 0);
        check(episode.artist().equals("Podcast") && episode.progress() == 0 && !episode.toggle(), "episode and absent-device handling");
        System.out.println("PASS: Spotify metadata, progress, pause, restrictions, empty playback and episodes");
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
}
