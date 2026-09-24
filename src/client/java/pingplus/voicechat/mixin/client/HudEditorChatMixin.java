package pingplus.voicechat.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pingplus.voicechat.client.gui.hud.HudEditor;

/** Leaves vanilla chat input/history/completion alone outside the mod's visible HUD bounds. */
@Mixin(ChatScreen.class)
public abstract class HudEditorChatMixin extends Screen {
    @Shadow protected EditBox input;
    protected HudEditorChatMixin(Component title) { super(title); }
    @Inject(method = "init", at = @At("TAIL"))
    private void hudInit(CallbackInfo ci) {
        HudEditor.finish(); input.setCanLoseFocus(true);
        HudEditor.controls().forEach(this::addWidget);
    }
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void hudRender(GuiGraphicsExtractor g, int mx, int my, float dt, CallbackInfo ci) {
        HudEditor.render(g, mx, my, dt, true);
    }
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void hudClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> ci) {
        if (HudEditor.click(this, event, doubleClick)) ci.setReturnValue(true);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (HudEditor.drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (HudEditor.finish()) return true;
        return super.mouseReleased(event);
    }
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void hudScroll(double mx, double my, double horizontal, double vertical, CallbackInfoReturnable<Boolean> ci) {
        if (HudEditor.scroll(mx, my, vertical)) ci.setReturnValue(true);
    }
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void hudKeyboard(KeyEvent event, CallbackInfoReturnable<Boolean> ci) {
        var controls = HudEditor.controls();
        if (event.key() == 295) {
            var active = controls.stream().filter(b -> b.active).toList();
            if (!active.isEmpty()) setFocused(active.get((active.indexOf(getFocused()) + 1) % active.size()));
            ci.setReturnValue(true);
        } else if (controls.contains(getFocused()) && event.key() != 256) ci.setReturnValue(super.keyPressed(event));
    }
    @Inject(method = "updateNarrationState", at = @At("TAIL"))
    private void hudNarration(net.minecraft.client.gui.narration.NarrationElementOutput output, CallbackInfo ci) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.HINT, Component.literal(
                "HUD editing. Drag a mod widget to move it. Drag its bottom right corner or scroll over it to resize. "
                + "Right click to reset. Press F6 for media controls."));
    }
    @Inject(method = "removed", at = @At("HEAD"))
    private void hudClose(CallbackInfo ci) {
        HudEditor.finish(); HudEditor.controls().forEach(b -> b.setFocused(false));
    }
}
