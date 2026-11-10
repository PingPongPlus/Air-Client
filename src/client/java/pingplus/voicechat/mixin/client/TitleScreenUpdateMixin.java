package pingplus.voicechat.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.gui.UpdateScreen;
import pingplus.voicechat.client.updater.ClientUpdater;

@Mixin(TitleScreen.class)
public abstract class TitleScreenUpdateMixin extends Screen {
    @Unique private Button airclient$updateButton;

    protected TitleScreenUpdateMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void airclient$addUpdater(CallbackInfo ci) {
        Component version = Component.literal("Air Client v" + ClientUpdater.get().version()).withColor(0xD0D0D0);
        addRenderableWidget(new StringWidget(6, 32, font.width(version), 10, version, font));
        airclient$updateButton = addRenderableWidget(Button.builder(Component.literal("Update"),
                button -> minecraft.gui.setScreen(new UpdateScreen(this)))
                .bounds(Math.max(4, width - 86), 6, 80, 20).build());
        airclient$refreshUpdate();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void airclient$tickUpdate(CallbackInfo ci) { airclient$refreshUpdate(); }

    @Unique
    private void airclient$refreshUpdate() {
        if (airclient$updateButton == null) return;
        var state = ClientUpdater.get().snapshot();
        airclient$updateButton.visible = state.showButton();
        airclient$updateButton.setMessage(Component.literal(switch (state.state()) {
            case READY -> "Restart";
            case DOWNLOADING -> "Updating...";
            default -> "Update";
        }));
        airclient$updateButton.setTooltip(Tooltip.create(Component.literal(state.message())));
    }
}
