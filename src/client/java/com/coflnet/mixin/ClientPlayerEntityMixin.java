package com.coflnet.mixin;

import com.coflnet.CoflModClient;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class ClientPlayerEntityMixin {
    @Inject(method = "openTextEdit", at = @At("HEAD"))
    private void openTextEdit(SignBlockEntity sign, boolean front, CallbackInfo ci){
        try {
            // Handle bazaar search first
            if (CoflModClient.pendingBazaarSearch != null) {
                handleBazaarSearch(sign, front);
                return;
            }

            // Handle existing price suggestion logic
            String toSuggest = CoflModClient.findPriceSuggestion();
            System.out.println("Value to suggest: '"+toSuggest+"'");
            Component[] lines = sign.getFrontText().getMessages(Minecraft.getInstance().isTextFilteringEnabled());
            String[] suggestionParts = toSuggest.split(": ");

            if(toSuggest == "") return;
            if(suggestionParts.length == 0) return;
            if(lines.length < 4) return;
            if(!suggestionParts[0].equals(lines[3].getString())) return;

            lines[0] = Component.literal(suggestionParts[1].trim());
            sign.updateText(signText -> new SignText(
                    lines, lines,
                    signText.getColor(),
                    signText.hasGlowingText()
            ), true);
        } catch (Exception e) {
            System.out.println("[ClientPlayerEntityMixin] openEditSignScreen failed: " + e.getMessage());
        }
    }

    /**
     * Handles filling in the bazaar search term and closing the sign.
     */
    private void handleBazaarSearch(SignBlockEntity sign, boolean front) {
        System.out.println("Filling bazaar search with: " + CoflModClient.pendingBazaarSearch);
        
        Component[] lines = sign.getFrontText().getMessages(Minecraft.getInstance().isTextFilteringEnabled());
        
        // Fill the first line with the search term
        lines[0] = Component.literal(CoflModClient.pendingBazaarSearch);
        
        // Clear other lines
        lines[1] = Component.literal("");
        lines[2] = Component.literal("");
        lines[3] = Component.literal("");
        
        // Apply the changes to the sign
        sign.updateText(signText -> new SignText(
                lines, lines,
                signText.getColor(),
                signText.hasGlowingText()
        ), front);
        
        System.out.println("Applied bazaar search text to sign");
        
        // Clear the pending search term
        CoflModClient.pendingBazaarSearch = null;
        
        // Schedule closing the sign after a short delay to ensure the text is saved
        Minecraft client = Minecraft.getInstance();
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(200); // Increased delay to ensure sign text is processed
                client.execute(() -> {
                    if (client.gui.screen() != null) {
                        System.out.println("Closing sign screen to complete bazaar search");
                        client.gui.screen().onClose();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
