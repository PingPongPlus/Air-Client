package pingplus.voicechat.client.gui.hud;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.w3c.dom.Node;

/**
 * AGG sticker: an animated GIF rendered as a regular HUD widget (drag to move,
 * corner/wheel to scale, right-click to reset - same machinery as every other
 * entry in {@link HudEditor}).
 *
 * <p>Frames are decoded once from the bundled asset, uploaded as GPU textures
 * and cached for the session (no per-frame allocations or uploads). The frame
 * shown follows wall-clock GIF timing and loops seamlessly.
 */
public final class AggWidget {
    public static final AggWidget INSTANCE = new AggWidget();
    /** Logical widget size; on-screen size is this times the HUD scale (0.5-3). */
    public static final int SIZE = 96;
    private static final Identifier GIF = Identifier.fromNamespaceAndPath("voicechat", "textures/gui/agg.gif");
    /**
     * Background keying: only near-white pixels connected to the image border
     * are removed, so enclosed white areas (dress, hair) survive behind their
     * dark outlines. Verified clean on all dance poses, no leaks, no halo.
     */
    private static final int BG_THRESHOLD = 200;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final List<Identifier> frames = new ArrayList<>();
    private final List<Integer> delaysMs = new ArrayList<>();
    private int frameWidth, frameHeight, totalMs;
    private boolean attempted;

    private AggWidget() {}

    public void render(GuiGraphicsExtractor g, int mx, int my, float dt, boolean editing) {
        if (!AggSettings.isEnabled() || minecraft.gui.hud.isHidden()) return;
        ensureLoaded();
        if (frames.isEmpty() || totalMs <= 0) return;
        Identifier frame = frames.get(frameIndex(System.currentTimeMillis()));
        g.blit(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0, 0, SIZE, SIZE,
                frameWidth, frameHeight, frameWidth, frameHeight);
    }

    /** Frame for the given wall-clock time, looping over the GIF duration. */
    int frameIndex(long nowMs) {
        long t = nowMs % totalMs;
        for (int i = 0; i < delaysMs.size(); i++) {
            t -= delaysMs.get(i);
            if (t < 0) return i;
        }
        return delaysMs.size() - 1;
    }

    /** Decoded frame count (decodes on first call). */
    public int frameCount() {
        ensureLoaded();
        return frames.size();
    }

    /** Total loop duration in ms (decodes on first call). */
    public int totalDurationMs() {
        ensureLoaded();
        return totalMs;
    }

    public int frameWidth() {
        ensureLoaded();
        return frameWidth;
    }

    public int frameHeight() {
        ensureLoaded();
        return frameHeight;
    }

    private void ensureLoaded() {
        if (attempted) return;
        attempted = true;
        try {
            var resource = minecraft.getResourceManager().getResource(GIF);
            if (resource.isEmpty()) return;
            try (InputStream in = resource.get().open()) {
                decode(in);
            }
        } catch (Exception ignored) {
            // A missing/broken sticker must never break the HUD.
        }
    }

    private void decode(InputStream in) throws Exception {
        ImageInputStream stream = ImageIO.createImageInputStream(in);
        try {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(stream);
            int count = reader.getNumImages(true);
            for (int i = 0; i < count; i++) {
                java.awt.image.BufferedImage image = reader.read(i);
                if (i == 0) {
                    frameWidth = image.getWidth();
                    frameHeight = image.getHeight();
                }
                if (image.getWidth() != frameWidth || image.getHeight() != frameHeight) continue;
                removeBackground(image);
                NativeImage nativeImage = toNativeImage(image);
                Identifier id = Identifier.fromNamespaceAndPath("voicechat", "agg_frame_" + i);
                minecraft.getTextureManager().register(id, new DynamicTexture(() -> "AGG sticker frame", nativeImage));
                frames.add(id);
                delaysMs.add(frameDelayMs(reader, i));
            }
            totalMs = delaysMs.stream().mapToInt(Integer::intValue).sum();
            if (totalMs <= 0 && !delaysMs.isEmpty()) {
                delaysMs.replaceAll(ignored -> 100);
                totalMs = delaysMs.stream().mapToInt(Integer::intValue).sum();
            }
        } finally {
            try { stream.close(); } catch (Exception ignored) { }
        }
    }

    private static int frameDelayMs(ImageReader reader, int index) {
        try {
            IIOMetadata meta = reader.getImageMetadata(index);
            Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeName().equals("GraphicControlExtension")) {
                    int centis = Integer.parseInt(
                            child.getAttributes().getNamedItem("delayTime").getNodeValue());
                    int ms = centis * 10;
                    return ms < 20 ? 100 : ms; // browsers clamp near-zero delays to ~100ms
                }
            }
        } catch (Exception ignored) {
        }
        return 100;
    }

    /**
     * Removes the GIF background: flood fill over near-white pixels connected
     * to the image border. Enclosed white areas (dress, hair) are protected by
     * their dark outlines; the fill never leaks through them.
     */
    private static void removeBackground(java.awt.image.BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] px = image.getRGB(0, 0, w, h, null, 0, w);
        boolean[] bg = new boolean[w * h];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) { queue.add(x); queue.add((h - 1) * w + x); }
        for (int y = 0; y < h; y++) { queue.add(y * w); queue.add(y * w + w - 1); }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            if (bg[i]) continue;
            int rgb = px[i];
            int m = Math.min((rgb >> 16) & 0xFF, Math.min((rgb >> 8) & 0xFF, rgb & 0xFF));
            if (m < BG_THRESHOLD) continue;
            bg[i] = true;
            int x = i % w, y = i / w;
            if (x > 0) queue.add(i - 1);
            if (x < w - 1) queue.add(i + 1);
            if (y > 0) queue.add(i - w);
            if (y < h - 1) queue.add(i + w);
        }
        for (int i = 0; i < px.length; i++) {
            px[i] = bg[i] ? 0x00000000 : (px[i] | 0xFF000000);
        }
        image.setRGB(0, 0, w, h, px, 0, w);
    }

    private static NativeImage toNativeImage(java.awt.image.BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        NativeImage nativeImage = new NativeImage(w, h, false);
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int abgr = (pixel & 0xFF000000)
                    | ((pixel & 0x000000FF) << 16)
                    | (pixel & 0x0000FF00)
                    | ((pixel & 0x00FF0000) >>> 16);
            nativeImage.setPixelABGR(i % w, i / w, abgr);
        }
        return nativeImage;
    }
}
