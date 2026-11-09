package com.coflnet.mixin;

import com.coflnet.lowballing.LowballingMod;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the lowballer runs the player never jumps. Baritone has an infinite
 * jump penalty, but its movement input could still press jump (half blocks,
 * getting unstuck), which looked like hopping and twitching around.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerJumpMixin {
    @Shadow
    public ClientInput input;

    @Inject(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/ClientInput;tick()V",
            shift = At.Shift.AFTER))
    private void coflBlockLowballJump(CallbackInfo ci) {
        try {
            Input keys = input == null ? null : input.keyPresses;
            if (keys == null || !keys.jump()
                    || !LowballingMod.blocksJump((LocalPlayer) (Object) this)) {
                return;
            }
            input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(),
                    keys.right(), false, keys.shift(), keys.sprint());
        } catch (Exception ignored) {
        }
    }
}
