package com.coflnet.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * A scrollable widget that renders multi-line lore text with clipping.
 * Only consumes clicks on the scrollbar; other clicks pass through
 * so the "click anywhere" buy widget can handle them.
 */
public class ScrollableLoreWidget extends AbstractScrollArea {
    private final Font font;
    private Component message;
    private List<FormattedCharSequence> lines = List.of();
    private final int textColor;

    public ScrollableLoreWidget(int x, int y, int width, int height, Component message, int textColor) {
        super(x, y, width, height, Component.empty(), defaultSettings(height));
        this.font = Minecraft.getInstance().font;
        this.textColor = textColor;
        setMessage(message);
    }

    @Override
    public void setMessage(Component message) {
        super.setMessage(message);
        this.message = message;
        this.lines = font.split(message, getWidth() - scrollbarWidth() - 4);
        refreshScrollAmount();
    }

    @Override
    protected int contentHeight() {
        return lines.size() * (font.lineHeight + 1);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean fromScreen) {
        if (updateScrolling(click)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // Only report mouse-over for the scrollbar area so clicks on
        // the text pass through to the click-anywhere buy widget
        return scrollable() && isOverScrollbar(mouseX, mouseY);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.enableScissor(getX(), getY(), getRight(), getBottom());

        int yOffset = getY() - (int) scrollAmount();
        for (FormattedCharSequence line : lines) {
            if (yOffset + font.lineHeight > getY() - font.lineHeight && yOffset < getBottom() + font.lineHeight) {
                context.text(font, line, getX() + 2, yOffset, textColor);
            }
            yOffset += font.lineHeight + 1;
        }

        context.disableScissor();

        extractScrollbar(context, mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
    }
}
