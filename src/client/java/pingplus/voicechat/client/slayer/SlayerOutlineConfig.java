package pingplus.voicechat.client.slayer;

// Persistent-ish config, defaults match request.
public final class SlayerOutlineConfig {
    // Master toggle.
    public boolean enabled = true;
    // Boss outline, vivid yellow.
    public int bossColor = 0xFFF200;
    // Miniboss outline, soft but strong light red.
    public int minibossColor = 0xFF5757;
    public boolean highlightBoss = true;
    public boolean highlightMiniboss = true;
    // Optional regex fallback on full mob name, empty = off.
    public String nameFilter = "";
}
