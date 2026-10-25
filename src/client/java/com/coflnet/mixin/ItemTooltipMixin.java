package com.coflnet.mixin;

import com.coflnet.ahflipper.AhItemValueTooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public class ItemTooltipMixin {
    @Inject(method = "getTooltipLines", at = @At("RETURN"))
    private void coflAddValue(Item.TooltipContext context, @Nullable Player player,
                              TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
        try {
            if (player == null) {
                return;
            }
            AhItemValueTooltip.append((ItemStack) (Object) this, cir.getReturnValue());
        } catch (Exception ignored) {
        }
    }
}
