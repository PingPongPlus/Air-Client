package com.coflnet.gui;

import com.coflnet.CoflModClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;

/**
 * Utility class for bazaar-related functionality.
 */
public class BazaarUtils {
    
    /**
     * Automatically searches for an item in the bazaar if currently viewing the bazaar.
     * This is a convenience method that can be called from anywhere in the mod.
     * 
     * @param itemName the name of the item to search for
     * @return true if the search was initiated, false if not on bazaar or search failed
     */
    public static boolean autoSearchBazaar(String itemName) {
        Minecraft client = Minecraft.getInstance();
        
        if (!(client.gui.screen() instanceof ContainerScreen gcs)) {
            return false;
        }
        
        if (!CoflModClient.isBazaar(gcs)) {
            return false;
        }
        
        CoflModClient.searchInBazaarSmart(itemName);
        return true;
    }

    /**
     * Searches for an item in the bazaar with basic name cleaning.
     * 
     * @param itemName the name of the item to search for
     * @return true if the search was initiated, false if not on bazaar or search failed
     */
    public static boolean searchBazaar(String itemName) {
        return autoSearchBazaar(itemName);
    }
    
    /**
     * Checks if the player is currently viewing the bazaar.
     * 
     * @return true if currently on the bazaar, false otherwise
     */
    public static boolean isCurrentlyOnBazaar() {
        Minecraft client = Minecraft.getInstance();
        
        if (!(client.gui.screen() instanceof ContainerScreen gcs)) {
            return false;
        }
        
        return CoflModClient.isBazaar(gcs);
    }
    
    /**
     * Gets the current bazaar screen if available.
     * 
     * @return the bazaar screen or null if not currently on bazaar
     */
    public static ContainerScreen getCurrentBazaarScreen() {
        Minecraft client = Minecraft.getInstance();
        
        if (!(client.gui.screen() instanceof ContainerScreen gcs)) {
            return null;
        }
        
        if (!CoflModClient.isBazaar(gcs)) {
            return null;
        }
        
        return gcs;
    }
    
    /**
     * Lists all searchable items currently visible in the bazaar.
     * 
     * @return an array of item names that can be searched for
     */
    public static String[] getVisibleBazaarItems() {
        ContainerScreen bazaar = getCurrentBazaarScreen();
        if (bazaar == null) {
            return new String[0];
        }
        
        java.util.List<String> items = new java.util.ArrayList<>();
        
        for (int i = 0; i < bazaar.getMenu().getContainer().getContainerSize(); i++) {
            ItemStack stack = bazaar.getMenu().getContainer().getItem(i);
            
            if (stack.isEmpty() || stack.getItem() == Items.STAINED_GLASS_PANE.black()) {
                continue;
            }
            
            if (stack.getCustomName() != null) {
                String itemName = stack.getCustomName().getString();
                // Filter out UI elements
                if (!itemName.contains("Search") && !itemName.contains("Go Back") && !itemName.contains("Close")) {
                    items.add(itemName);
                }
            }
        }
        
        return items.toArray(new String[0]);
    }
}
