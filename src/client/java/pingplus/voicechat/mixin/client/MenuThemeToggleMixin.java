package pingplus.voicechat.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.gui.glass.MenuThemeSettings;

@Mixin(TitleScreen.class)
public abstract class MenuThemeToggleMixin extends Screen {
    protected MenuThemeToggleMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void voicechat$addThemeToggle(CallbackInfo ci) {
        addRenderableWidget(Button.builder(voicechat$themeLabel(), button -> {
            boolean saved = MenuThemeSettings.setDarkMode(!MenuThemeSettings.isDarkMode());
            button.setMessage(voicechat$themeLabel());
            button.setTooltip(Tooltip.create(Component.literal(saved
                    ? "Switch between the night street and the original mountain theme."
                    : "Could not save theme. Check the client log.")));
        }).bounds(6, 6, 106, 20)
                .tooltip(Tooltip.create(Component.literal("Switch between the night street and the original mountain theme.")))
                .build());
    }

    @Unique
    private static Component voicechat$themeLabel() {
        return Component.literal("Mode: " + (MenuThemeSettings.isDarkMode() ? "Dark" : "Light"));
    }
}
