package pingplus.voicechat.client.gui.glass;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared palette and resource-independent geometry, also safe during a resource reload. */
public final class GlassStyle {
    public static final net.minecraft.network.chat.Style FONT = net.minecraft.network.chat.Style.EMPTY.withFont(
            new net.minecraft.network.chat.FontDescription.Resource(net.minecraft.resources.Identifier.fromNamespaceAndPath("voicechat", "ui")));
    public static net.minecraft.network.chat.Component label(String text) { return net.minecraft.network.chat.Component.literal(text).withStyle(FONT); }
    public static final int TEXT = 0xFFF4F4F4, MUTED = 0xFFD0D0D0, ACCENT = 0xFFE8E8E8, STATUS = 0xFFA4F0C1;
    public static int alpha(int color, float alpha) {
        return (Math.round((color >>> 24) * Math.clamp(alpha, 0, 1)) << 24) | (color & 0xFFFFFF);
    }
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        // Rasterize the fallback silhouette at physical-pixel resolution, including edge coverage.
        int scale = Math.max(1, (int)Math.ceil(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
        int sx=x*scale, sy=y*scale, sw=w*scale, sh=h*scale;
        double radius=Math.min(r,Math.min(w,h)/2.0)*scale;
        g.pose().pushMatrix();
        g.pose().scale(1f/scale);
        for(int row=0;row<sh;row++) {
            double dy=Math.max(0,Math.max(radius-row-.5,row+.5-(sh-radius)));
            double inset=radius-Math.sqrt(Math.max(0,radius*radius-dy*dy));
            int whole=(int)Math.ceil(inset);
            g.fill(sx+whole,sy+row,sx+sw-whole,sy+row+1,color);
            float coverage=(float)(whole-inset);
            if(coverage>0) {
                int edge=alpha(color,coverage);
                g.fill(sx+whole-1,sy+row,sx+whole,sy+row+1,edge);
                g.fill(sx+sw-whole,sy+row,sx+sw-whole+1,sy+row+1,edge);
            }
        }
        g.pose().popMatrix();
    }
    public static void surface(GuiGraphicsExtractor g, int x, int y, int w, int h, float opacity, float light) {
        GlassButtonRenderer.drawRect(g, x, y, w, h,
                (Math.round(opacity * 255) << 24) | (Math.round(light * 255) << 16) | 0xFF00);
    }
    /** Liquid glass chrome for HUD widgets. Call from HudEditor; feature code only draws content. */
    public static void widget(GuiGraphicsExtractor g, int w, int h, boolean editing, boolean selected) {
        g.nextStratum();
        surface(g, 0, 0, w, h, editing ? .94f : .88f, selected ? .28f : .12f);
        g.nextStratum();
    }
    public static void line(GuiGraphicsExtractor g, String text, int x, int y, int maxWidth, int color) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        g.text(font, label(font.plainSubstrByWidth(text, Math.max(1, maxWidth))), x, y, color, false);
    }
    private GlassStyle() {}
}
