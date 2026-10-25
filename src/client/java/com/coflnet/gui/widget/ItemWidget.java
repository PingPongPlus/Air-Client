package com.coflnet.gui.widget;

import com.coflnet.gui.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class ItemWidget extends AbstractWidget {
    public ItemStack item;

    public ItemWidget(int x, int y, ItemStack item) {
        super(x, y, 16, 16, Component.empty());
        this.item = item;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        RenderUtils.drawItemStack(context, item, getX(), getY(), 1);

        if(isMouseOver(mouseX, mouseY)){
            context.setTooltipForNextFrame(Minecraft.getInstance().font, item, mouseX, mouseY);
        }
    }


    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= (double)this.getX()
                && mouseY >= (double)this.getY()
                && mouseX < (double)(this.getX() + getWidth())
                && mouseY < (double)(this.getY() + getHeight());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {

    }
}
