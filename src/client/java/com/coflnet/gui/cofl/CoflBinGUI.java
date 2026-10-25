package com.coflnet.gui.cofl;

import CoflCore.handlers.DescriptionHandler;
import com.coflnet.gui.AuctionStatus;
import com.coflnet.gui.BinGUI;
import com.coflnet.gui.RenderUtils;
import com.coflnet.gui.widget.ItemWidget;
import com.coflnet.gui.widget.ScrollableLoreWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import oshi.util.tuples.Pair;

import java.awt.event.ComponentEvent;
import java.util.List;

public class CoflBinGUI extends BinGUI {
    private StringWidget titleTextWidget;
    private ScrollableLoreWidget loreScrollableTextWidget;
    private AbstractWidget rightClickableWidget;
    private AbstractWidget leftClickableWidget;
    private volatile boolean loreDirty = true;

    public String title = "";
    public Component lore = Component.literal("");
    public Pair<Integer, Integer> rightButtonCol = new Pair<>(CoflColConfig.BACKGROUND_SECONDARY, CoflColConfig.BACKGROUND_SECONDARY);

    public CoflBinGUI(ContainerScreen gcs){
        super(Component.literal("Cofl Bin Gui"), gcs, 5, 4);
        DescriptionHandler.setRefreshCallback((lines, title) -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().gui.screen() == this) {
                loreDirty = true;
            }
        }));
    }

    @Override
    protected void clearAndInitWidgets(int screenWidth, int screenHeight) {
        this.clearWidgets();
        itemWidget = new ItemWidget(
                screenWidth / 2 - width / 2 + p + 2,
                screenHeight / 2 - height / 2 + p + 12 + p + 2,
                Items.AIR.getDefaultInstance()
        );

        leftClickableWidget = new AbstractWidget(
                screenWidth / 2 - width / 2 + p,
                screenHeight / 2 + height / 2 - p - (225 - 150 - 12 - p * 5) - screenHeight / 15,
                width / 5 * 2 - p,
                225 - 150 - 12 - p*5 + screenHeight / 15,
                Component.literal("Cancel")
        ){
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
                RenderUtils.drawRoundedRect(context, getX(), getY(), getWidth(), getHeight(), r, this.isMouseOver(mouseX,mouseY) ? CoflColConfig.CANCEL_HOVER : CoflColConfig.CANCEL);
                RenderUtils.drawString(context, this.getMessage().getString(), getX() + 6, getY() + 4, CoflColConfig.TEXT_PRIMARY);
            }

            @Override
            protected void updateWidgetNarration(NarrationElementOutput builder) {}

            @Override
            public void onClick(net.minecraft.client.input.MouseButtonEvent click, boolean fromScreen) {
                double mouseX = click.x();
                double mouseY = click.y();
                if (auctionStatus != AuctionStatus.AUCTION_CONFIRMING) clickSlot(AUCTION_CANCEL_SLOT);
                else clickSlot(AUCTION_CONFIRMATION_CANCEL_SLOT);
            }

            @Override
            protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo mi) {
                int b = mi.button();
                return b == 0 || b == 1;
            }
        };

        int tempWidth = width;
        int tempHeight = height;
        rightClickableWidget = new AbstractWidget(
                0, //screenWidth / 2 - width / 2, //screenWidth / 2 - width / 2 + p + width / 5 * 2,
                0, //screenHeight / 2 - height / 2, //screenHeight / 2 + height / 2 - p - (225 - 150 - 12 - p*5) - screenHeight / 15,
                screenWidth, //width, //width / 5 * 3 - p*2,
                screenHeight, //height, //225 - 150 - 12 - p*5 + screenHeight / 15,
                Component.literal("")
        ){
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
                boolean mouseOver = mouseX >= (double)(screenWidth / 2 - tempWidth / 2 + p + tempWidth / 5 * 2)
                        && mouseY >= (double)(screenHeight / 2 + tempHeight / 2 - p - (225 - 150 - 12 - p*5) - screenHeight / 15)
                        && mouseX < (double)((screenWidth / 2 - tempWidth / 2 + p + tempWidth / 5 * 2) + (tempWidth / 5 * 3 - p*2))
                        && mouseY < (double)((screenHeight / 2 + tempHeight / 2 - p - (225 - 150 - 12 - p*5) - screenHeight / 15) + (225 - 150 - 12 - p*5 + screenHeight / 15));

                RenderUtils.drawRoundedRect(context,
                        screenWidth / 2 - tempWidth / 2 + p + tempWidth / 5 * 2,
                        screenHeight / 2 + tempHeight / 2 - p - (225 - 150 - 12 - p*5) - screenHeight / 15,
                        tempWidth / 5 * 3 - p*2,
                        225 - 150 - 12 - p*5 + screenHeight / 15,
                        r, mouseOver ? rightButtonCol.getB() : rightButtonCol.getA()
                );

                RenderUtils.drawString(context,
                        this.getMessage().getString(),
                        screenWidth / 2 - tempWidth / 2 + p + tempWidth / 5 * 2 + 6,
                        screenHeight / 2 + tempHeight / 2 - p - (225 - 150 - 12 - p*5) - screenHeight / 15 + 4,
                        CoflColConfig.TEXT_PRIMARY
                );
            }

            @Override
            protected void updateWidgetNarration(NarrationElementOutput builder) {}

            @Override
            public void onClick(net.minecraft.client.input.MouseButtonEvent click, boolean fromScreen) {
                double mouseX = click.x();
                double mouseY = click.y();
                if (leftClickableWidget.isMouseOver(mouseX, mouseY)) {
                    // No explicit button provided here; default to left mouse button (0) and no modifiers.
                    net.minecraft.client.input.MouseButtonInfo mi = new net.minecraft.client.input.MouseButtonInfo(0, 0);
                    leftClickableWidget.onClick(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, mi), true);
                } else {
                    switch (auctionStatus){
                        case INIT:
                        case AUCTION_BUYING:
                        case AUCTION_WAITING:
                            clickSlot(AUCTION_BUY_SLOT);
                            break;
                        case AUCTION_CONFIRMING:
                            clickSlot(AUCTION_CONFIRM_SLOT);
                            break;
                        case OWN_AUCTION_CLAIMING:
                            clickSlot(OWN_AUCTION_CLAIM_SLOT);
                            break;
                        case OWN_AUCTION_CANCELING:
                            clickSlot(OWN_AUCTION_CANCEL_SLOT);
                            break;
                    }
                }
            }

            @Override
            protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo mi) {
                int b = mi.button();
                return b == 0 || b == 1;
            }
        };

        titleTextWidget = new StringWidget(
                screenWidth / 2 - width / 2 + p + 3,
                screenHeight / 2 - height / 2 + p + 2,
                width - p*2 - 4, 10,
                Component.literal(flipData == null ? "" : flipData.getMessageAsString().replace("sellers ah", "")),
                Minecraft.getInstance().font
        );

        int loreX = screenWidth / 2 - width / 2 + p + 20 + p + 4;
        int loreY = screenHeight / 2 - height / 2 + p + 12 + p + 2;
        int loreWidth = width - 20 - p*4 - 4;
        int loreHeight = height - 75 - screenHeight / 15 - 4;
        loreScrollableTextWidget = new ScrollableLoreWidget(
                loreX, loreY, loreWidth, loreHeight,
                lore == null ? Component.literal("") : lore,
                CoflColConfig.TEXT_PRIMARY
        );

        if(auctionStatus.compareTo(AuctionStatus.AUCTION_CONFIRMING) == 0) setRightButtonConfig(auctionStatus);

        this.addRenderableWidget(titleTextWidget);
        this.addRenderableWidget(loreScrollableTextWidget);
        this.addRenderableWidget(rightClickableWidget);
        this.addRenderableWidget(leftClickableWidget);
        this.addRenderableWidget(itemWidget);
    }

    private void setRightButtonConfig(AuctionStatus auctionStatus){
        switch (auctionStatus){
            case INIT:
            case AUCTION_BUYING:
            case AUCTION_WAITING:
                rightButtonCol = new Pair<>(CoflColConfig.CONFIRM, CoflColConfig.CONFIRM_HOVER);
                rightClickableWidget.setMessage(Component.literal("Buy (You can click anywhere)"));
                break;
            case AUCTION_SOLD:
                rightButtonCol = new Pair<>(CoflColConfig.UNAVAILABLE, CoflColConfig.UNAVAILABLE);
                for (Component line : currentItem.getComponents().get(DataComponents.LORE).lines()) {
                    if (line.getString().startsWith("Buyer: ")) rightClickableWidget.setMessage(Component.literal("Bought by "+line.getString().substring("Buyer: ".length())));
                }
                break;
            case AUCTION_CONFIRMING:
                rightButtonCol = new Pair<>(CoflColConfig.CONFIRM, CoflColConfig.CONFIRM_HOVER);
                rightClickableWidget.setMessage(Component.literal("Confirm purchase"));
                break;
            case OWN_AUCTION_CLAIMING:
                rightButtonCol = new Pair<>(CoflColConfig.CONFIRM, CoflColConfig.CONFIRM_HOVER);
                rightClickableWidget.setMessage(Component.literal(
                        "Claim Auction"
                ));
                break;
            case OWN_AUCTION_CANCELING:
                rightButtonCol = new Pair<>(CoflColConfig.UNAVAILABLE, CoflColConfig.UNAVAILABLE);
                rightClickableWidget.setMessage(Component.literal(
                        "Cancel Auction"
                ));
                break;
        }
    }

    public MutableComponent convertTextList(List<Component> collection){
        MutableComponent res = Component.empty();
        if (collection == null || collection.isEmpty()) return res;

        res.append(collection.getFirst());
        collection.removeFirst();

        for (Component text : collection) {
            res.append(Component.literal("\n"));
            res.append(text);
        }
        return res;
    }


    @Override
    public void extractBackground(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractBackground(drawContext, mouseX, mouseY, delta);

        if(!gcsh.getContainer().isEmpty()){
            boolean itemChanged = syncAuctionItemFromContainer();
            if (itemChanged) {
                loreDirty = true;
            }

            if (currentItem.isEmpty() || currentItem.getItem() == Items.AIR) {
                if (itemChanged) {
                    lore = Component.empty();
                    loreScrollableTextWidget.setMessage(Component.empty());
                    loreDirty = false;
                }
            } else if (loreDirty) {
                lore = convertTextList(getTooltipFromItem(Minecraft.getInstance(), currentItem));
                loreScrollableTextWidget.setMessage(lore == null ? Component.empty() : lore);
                loreDirty = false;
            }

            AuctionStatus previousStatus = auctionStatus;
            AuctionStatus as = syncAuctionStatusFromContainer();
            if (itemChanged || as != previousStatus) {
                setRightButtonConfig(as);
            }
        }

        // Background
        RenderUtils.drawRoundedRect(drawContext, screenWidth / 2 - width / 2,screenHeight / 2 - height / 2, width, height, r, CoflColConfig.BACKGROUND_PRIMARY);
        // Title Background
        RenderUtils.drawRoundedRect(drawContext, screenWidth / 2 - width / 2 + p,screenHeight / 2 - height / 2 + p, width - p*2, 12, r, CoflColConfig.BACKGROUND_SECONDARY);
        // Item Background
        RenderUtils.drawRoundedRect(drawContext, screenWidth / 2 - width / 2 + p,screenHeight / 2 - height / 2 + p + 12 + p, 20, 20, r, CoflColConfig.BACKGROUND_SECONDARY);
        // Description Background
        RenderUtils.drawRoundedRect(drawContext,screenWidth / 2 - width / 2 + p + 20 + p, screenHeight / 2 - height / 2 + p + 12+ p, width - 20 - p*3, height - 75 - screenHeight / 15, r, CoflColConfig.BACKGROUND_SECONDARY);
    }
}
