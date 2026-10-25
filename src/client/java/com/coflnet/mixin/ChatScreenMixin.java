package com.coflnet.mixin;

import com.coflnet.CoflModClient;
import CoflCore.CoflSkyCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void onSendMessage(String message, boolean addToHistory, CallbackInfo ci) {
        // Commands are never redirected here.
        if (message.startsWith("/")) return;

        // If the SkyCofl text_Tunnels channel is currently active, route the
        // typed message to /cofl chat BEFORE handleChatInput calls sendChat.
        // text_Tunnels' sendChat mixin would otherwise try to forward
        // "/cofl chat <msg>" to the Minecraft server (a client-only command).
        if (isTextTunnelsSkyCoflActive()) {
            ci.cancel();
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                String username = client.player.getName().getString();
                String[] parts = message.split(" ");
                String[] args = new String[parts.length + 1];
                args[0] = "chat";
                System.arraycopy(parts, 0, args, 1, parts.length);
                CoflSkyCommand.processCommand(args, username);
            }
            return;
        }

        // If flipper chat only mode is enabled and the message is not a command
        if (CoflModClient.flipperChatOnlyMode) {
            // Cancel the original message
            ci.cancel();

            // Send it to flipper chat instead
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                String username = client.player.getName().getString();
                String[] args = new String[message.split(" ").length + 1];
                args[0] = "chat";
                System.arraycopy(message.split(" "), 0, args, 1, message.split(" ").length);
                CoflSkyCommand.processCommand(args, username);
            }
        }
    }

    /**
     * Returns true when text_Tunnels is installed and the currently selected
     * tunnel has "/cofl chat " as its send-prefix (i.e. the SkyCofl tunnel).
     *
     * Uses reflection so that a text_Tunnels API change only silently disables
     * the feature rather than crashing the mod.
     */
    private static boolean isTextTunnelsSkyCoflActive() {
        if (!CoflModClient.isTextTunnelsInstalled()) return false;
        try {
            Class<?> msh = Class.forName("org.olim.text_tunnels.MessageSendHandler");
            java.lang.reflect.Field indexField = msh.getDeclaredField("currentIndex");
            indexField.setAccessible(true);
            int index = (int) indexField.get(null);
            if (index < 0) return false;
            java.lang.reflect.Field prefixesField = msh.getDeclaredField("sendPrefixes");
            prefixesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<String> prefixes =
                    (java.util.List<String>) prefixesField.get(null);
            return prefixes != null
                    && index < prefixes.size()
                    && "/cofl chat ".equals(prefixes.get(index));
        } catch (Exception ignored) {
            return false;
        }
    }
}
