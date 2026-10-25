package com.coflnet.mixin;

import com.coflnet.CoflModClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to detect Hypixel scoreboard updates via network packets.
 * Hypixel uses team prefixes/suffixes for the actual displayed text,
 * and scores only for ordering. This approach is more accurate than
 * mixing into Scoreboard class methods.
 */
@Mixin(ClientPacketListener.class)
public class ScoreboardMixin {
    
    /**
     * Detects when team data changes (prefix/suffix text updates).
     * Hypixel uses teams like "team_0", "team_1", etc. for sidebar lines.
     */
    @Inject(method = "handleSetPlayerTeamPacket", at = @At("TAIL"))
    private void onTeamPacket(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
        // Hypixel uses team names starting with "team_" for scoreboard lines
        String teamName = packet.getName();
        if (teamName != null && teamName.startsWith("team_")) {
            CoflModClient.markScoreboardDirty();
        }
    }
    
    /**
     * Detects when score values change (used for ordering).
     * The "update" objective is commonly used by Hypixel.
     */
    @Inject(method = "handleSetScore", at = @At("TAIL"))
    private void onScorePacket(ClientboundSetScorePacket packet, CallbackInfo ci) {
        String objectiveName = packet.objectiveName();
        if (objectiveName != null && objectiveName.equals("update")) {
            CoflModClient.markScoreboardDirty();
        }
    }
    
    /**
     * Detects when the scoreboard objective itself changes (title updates).
     */
    @Inject(method = "handleAddObjective", at = @At("TAIL"))
    private void onObjectivePacket(ClientboundSetObjectivePacket packet, CallbackInfo ci) {
        // Objective updates can indicate scoreboard title changes
        CoflModClient.markScoreboardDirty();
    }
}
