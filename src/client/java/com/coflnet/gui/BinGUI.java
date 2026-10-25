package com.coflnet.gui;

import CoflCore.commands.models.FlipData;
import com.coflnet.CoflModClient;
import com.coflnet.gui.widget.ItemWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract class that contains logic which is needed in implementations of BIN GUIs.
 * @see com.coflnet.gui.cofl.CoflBinGUI
 * @see com.coflnet.gui.tfm.TfmBinGUI
 */
public abstract class BinGUI extends Screen {
    protected int width;
    protected int height;
    protected int screenHeight;
    protected int screenWidth;
    protected int p;
    protected int r;
    protected final int AUCTION_ITEM_SLOT = 13;
    protected final int AUCTION_BUY_SLOT = 31;
    protected final int AUCTION_CANCEL_SLOT = 49;
    protected final int AUCTION_CONFIRM_SLOT = 11;
    protected final int AUCTION_CONFIRMATION_CANCEL_SLOT = 15;
    protected final int OWN_AUCTION_CLAIM_SLOT = 31;
    protected final int OWN_AUCTION_CANCEL_SLOT = 33;
    protected ItemWidget itemWidget;
    protected ItemStack currentItem = Items.AIR.getDefaultInstance();
    protected FlipData flipData;
    private String auctionItemStateKey = "";
    private String actionItemStateKey = "";

    protected AuctionStatus auctionStatus;
    protected ChestMenu gcsh;
    protected ContainerScreen gcs;

    /**
     * Constructor for BIN-GUIs.
     * @param title title of the screen
     * @param gcs the BIN auction as an instance of {@link ContainerScreen}
     * @param p padding as an int
     * @param r radius for rounded corners
     */
    protected BinGUI(Component title, @NotNull ContainerScreen gcs, @NotNull int p, int r) {
        super(title);
        this.gcs = gcs;
        this.gcsh = gcs.getMenu();
        this.p = p;
        this.r = r;

        if (isAuctionConfirming(gcs)) this.auctionStatus = AuctionStatus.AUCTION_CONFIRMING;
        else if (isAuctionInit(gcs)) this.auctionStatus = AuctionStatus.INIT;

        flipData = CoflModClient.popFlipData();

        screenWidth = Minecraft.getInstance().gui.screen().width;
        screenHeight = Minecraft.getInstance().gui.screen().height;
        initSize(screenWidth, screenHeight);
        clearAndInitWidgets(screenWidth, screenHeight);
    }

    private void initSize(int screenWidth, int screenHeight){
        this.width = Minecraft.getInstance().gui.screen().width / 2;
        if (width < 300) this.width = 300;

        this.height = Minecraft.getInstance().gui.screen().height / 3 * 2;
        if (height < 225) this.height = 225;
    }

    /**
     * Implementations of this method should include {@code this.clearWidgets();}
     * and all initializations of your GUIs widgets (such as buttons).
     * @param screenWidth the current width of the screen as an int
     * @param screenHeight the current height of the screen as an int
     */
    protected abstract void clearAndInitWidgets(int screenWidth, int screenHeight);

    public void setItem(ItemStack item) {
        this.currentItem = item;
        this.itemWidget.item = item;
    }

    protected boolean syncAuctionItemFromContainer() {
        ItemStack auctionItem = gcsh.getContainer().getItem(AUCTION_ITEM_SLOT);
        String itemStateKey = getItemStateKey(auctionItem);
        if (itemStateKey.equals(auctionItemStateKey)) {
            if (itemWidget != null) {
                itemWidget.item = currentItem;
            }
            return false;
        }

        auctionItemStateKey = itemStateKey;
        if (auctionItem.isEmpty() || auctionItem.getItem() == Items.AIR) {
            setItem(Items.AIR.getDefaultInstance());
            return false;
        }

        setItem(auctionItem);
        return true;
    }

    protected AuctionStatus syncAuctionStatusFromContainer() {
        int actionSlot = getCurrentActionSlot();
        ItemStack actionItem = gcsh.getContainer().getItem(actionSlot);
        String itemStateKey = actionSlot + ":" + getItemStateKey(actionItem);
        if (itemStateKey.equals(actionItemStateKey)) {
            return auctionStatus;
        }

        actionItemStateKey = itemStateKey;
        if (actionItem.isEmpty() || actionItem.getItem() == Items.AIR) {
            return auctionStatus;
        }

        return updateAuctionStatus(actionItem);
    }

    private int getCurrentActionSlot() {
        return auctionStatus.compareTo(AuctionStatus.AUCTION_CONFIRMING) == 0 ? AUCTION_CONFIRM_SLOT : AUCTION_BUY_SLOT;
    }

    protected String getItemStateKey(@NotNull ItemStack itemStack) {
        if (itemStack.isEmpty() || itemStack.getItem() == Items.AIR) {
            return "";
        }

        String hoverName = itemStack.getHoverName().getString();
        var lore = itemStack.get(DataComponents.LORE);
        return hoverName + "|" + itemStack.getCount() + "|" + (lore == null ? "" : lore);
    }

    @Override
    public void onClose() {
        auctionStatus = AuctionStatus.INIT;
        gcs.onClose();
        super.onClose();
    }

    /**
     * Handles clicking a {@link net.minecraft.world.inventory.Slot} for the player.
     * @param slotId the index of the slot that is to be clicked by the player.
     */
    protected void clickSlot(int slotId) {
        Player player = minecraft.player;

        minecraft.gameMode.handleContainerInput(
                gcsh.containerId,
                slotId,
                0,
                ContainerInput.PICKUP,
                player
        );
    }

    /**
     * Determines the status of the auction based on the metadata of the "Confirm"-item in a BIN Auction
     * and furthermore updates the status in {@link #auctionStatus auctionStatus}.
     * @return the updated auctionStatus
     * @see AuctionStatus
     */
    protected AuctionStatus updateAuctionStatus(@NotNull ItemStack itemStack){
        Item item = itemStack.getItem();
        if (item == Items.STAINED_GLASS_PANE.black()) {
            auctionStatus = AuctionStatus.OWN_AUCTION_CANCELING;
            return auctionStatus;
        }

        if (item == Items.BED.red()) {
            auctionStatus = AuctionStatus.AUCTION_WAITING;
            return auctionStatus;
        }

        if(itemStack.getCustomName() == null)
            return auctionStatus;

        String customName = itemStack.getCustomName().getString();
        String loreString = itemStack.getComponents().get(DataComponents.LORE).toString();

        switch (customName){
            case "Buy Item Right Now":
                if (loreString.contains("Cannot afford bid!")) auctionStatus = AuctionStatus.AUCTION_BUYING;
                else if (loreString.contains("Click to purchase!")) auctionStatus = AuctionStatus.AUCTION_BUYING;
                return auctionStatus;
            case "Collect Auction":
                if (loreString.contains("Click to collect coins!")) auctionStatus = AuctionStatus.OWN_AUCTION_CLAIMING;
                else if (loreString.contains("Click to pick up item!")) auctionStatus = AuctionStatus.OWN_AUCTION_CLAIMING;
                else if (loreString.contains("Someone else purchased the item")) auctionStatus = AuctionStatus.AUCTION_SOLD;
                return auctionStatus;
            case "Confirm":
                auctionStatus = AuctionStatus.AUCTION_CONFIRMING;
                return auctionStatus;
        }

        return auctionStatus;
    }

    /**
     * Adds logic to update the size of the UI elements when the window size changes dynamically.
     * <br/>
     * <br/>
     * When overriding this method, make sure to include
     * {@code super.extractBackground(drawContext, mouseX, mouseY, delta);}
     * so your GUI changes size dynamically.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (screenWidth != Minecraft.getInstance().gui.screen().width
            || screenHeight != Minecraft.getInstance().gui.screen().height){
            screenWidth = Minecraft.getInstance().gui.screen().width;
            screenHeight = Minecraft.getInstance().gui.screen().height;
            initSize(screenWidth, screenHeight);
            clearAndInitWidgets(screenWidth, screenHeight);
        }
    }

    /**
     * Determines if the given screen is the confirmation part of a BIN auction.
     * @param gcs instance of {@link ContainerScreen}
     * @return {@code true} if the screen is the confirmation screen of a BIN auction, otherwise {@code false}
     */
    public static boolean isAuctionConfirming(@NotNull ContainerScreen gcs){
        return  gcs.getTitle().getString().contains("Confirm Purchase")
                && gcs.getMenu().getContainer().getContainerSize() == 9 * 3;
    }

    /**
     * Determines if the given screen is a BIN auction.
     * @param gcs instance of {@link ContainerScreen}
     * @return {@code true} if the screen is a BIN auction, otherwise {@code false}
     */
    public static boolean isAuctionInit(@NotNull ContainerScreen gcs) {
        return gcs.getTitle().getString().contains("BIN Auction View")
                && gcs.getMenu().getContainer().getContainerSize() == 9 * 6;
    }
}
