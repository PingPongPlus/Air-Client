package com.coflnet.gui.tfm;

import com.coflnet.gui.AuctionStatus;
import com.coflnet.gui.BinGUI;
import com.coflnet.gui.RenderUtils;
import com.coflnet.gui.widget.ItemWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;

public class TfmBinGUI extends BinGUI {
    public String lore = "";

    public StringWidget titleTextWidget;
    public MultiLineTextWidget loreMultilineTextWidget;
    public AbstractWidget confirmClickableWidget;
    public AbstractWidget cancelClickableWidget;

    public TfmBinGUI(ContainerScreen gcs){
        super(Component.literal("Tfm Bin Gui"), gcs, 1, 4);
    }

    @Override
    protected void clearAndInitWidgets(int screenWidth, int screenHeight) {
        clearWidgets();
        itemWidget = new ItemWidget(
                screenWidth / 2 - 8,
                screenHeight / 2 - 8,
                Items.AIR.getDefaultInstance()
        );

        titleTextWidget = new StringWidget(
                screenWidth / 2 - width / 2 + 12,
                screenHeight / 2 - height / 2 + 8,
                width - 12, 10,
                Component.literal("Cofl - Auction View"),
                Minecraft.getInstance().font
        );

        loreMultilineTextWidget = new MultiLineTextWidget(
                screenWidth / 2 - width / 2 + 12,
                screenHeight / 2 - height / 2 + 8 + 8 + 6,
                Component.literal(flipData == null ? "" : flipData.getMessageAsString().replace("sellers ah", "")),
                Minecraft.getInstance().font
        ){
            protected boolean isValidClickButton(int button) {
                return button == 0 || button == 1;
            }
        }.setCentered(false);

        confirmClickableWidget = new AbstractWidget(
                0, 0, screenWidth, screenHeight, Component.empty()
        ) {
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
                RenderUtils.drawRectOutline(
                        context,
                        screenWidth / 2 - 80 / 2,
                        screenHeight / 2 - 40 / 2,
                        80, 40,
                        p, TfmColConfig.CONFIRM, TfmColConfig.BORDER
                );
            }

        @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent click, boolean fromScreen) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (cancelClickableWidget.isMouseOver(mouseX, mouseY)){
            net.minecraft.client.input.MouseButtonInfo mi = new net.minecraft.client.input.MouseButtonInfo(0, 0);
            net.minecraft.client.input.MouseButtonEvent c = new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, mi);
            cancelClickableWidget.onClick(c, true);
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
            protected void updateWidgetNarration(NarrationElementOutput builder) {}

            protected boolean isValidClickButton(int button) {
                return button == 0 || button == 1;
            }
        };

        cancelClickableWidget = new AbstractWidget(
                screenWidth / 2 - 36 / 2,
                screenHeight / 2 + 40 + p,
                36, 30, Component.empty()
        ) {
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
                RenderUtils.drawRectOutline(
                        context, getX(), getY(),
                        getWidth(), getHeight(),
                        p, TfmColConfig.CANCEL, TfmColConfig.BORDER
                );
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

        this.addRenderableWidget(titleTextWidget);
        this.addRenderableWidget(loreMultilineTextWidget);
        this.addRenderableWidget(confirmClickableWidget);
        this.addRenderableWidget(cancelClickableWidget);
        this.addRenderableWidget(itemWidget);

        gcsh.getContainer();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta){
        super.extractBackground(drawContext, mouseX, mouseY, delta);

        if(!gcsh.getContainer().isEmpty()){
            syncAuctionItemFromContainer();
            syncAuctionStatusFromContainer();
        }

        RenderUtils.drawRectOutline(
                drawContext,
                screenWidth / 2 - width / 2,
                screenHeight / 2 - height / 2,
                width, height,
                p, TfmColConfig.BACKGROUND_PRIMARY, TfmColConfig.BORDER
        );
    }
}
