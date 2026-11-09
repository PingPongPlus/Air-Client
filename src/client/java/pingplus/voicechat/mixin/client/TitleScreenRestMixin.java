package pingplus.voicechat.mixin.client;


import com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.apache.commons.compress.harmony.pack200.NewAttributeBands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.client.gui.components.SplashRenderer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.security.auth.callback.Callback;

@Mixin(TitleScreen.class)
public abstract class TitleScreenRestMixin extends Screen {
    protected TitleScreenRestMixin(Component title){
        super(title);
    }
    @Shadow private SplashRenderer splash;
    @Shadow private FriendsButton friends;
    @Shadow private RealmsNotificationsScreen realmsNotificationsScreen;
    @Shadow @Final private static Component COPYRIGHT_TEXT;

    @Shadow private boolean realmsNotificationsEnabled() { return false; }
    @Shadow private int createDemoMenuOptions(int topPos, int spacing) { return 0; }
    @Shadow private int createNormalMenuOptions(int topPos, int spacing) { return 0; }
    @Shadow private int getHorizontalPosition(int currentButton, int numberOfButtons, int buttonWidth) { return 0; }
    @Inject(
            method = "init",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onInit(CallbackInfo ci) {

        if (this.splash == null) {
            this.splash = this.minecraft.gui.splashManager().getSplash();
        }

        int copyrightWidth = this.font.width(COPYRIGHT_TEXT);
        int copyrightX = this.width - copyrightWidth - 2;
        int topPos = this.height / 4 + 48;

        if (this.minecraft.isDemo()) {
            topPos = this.createDemoMenuOptions(topPos, 24);
        } else {
            topPos = this.createNormalMenuOptions(topPos, 24);
        }

        topPos += 24;

        // --- EVENLY DISTRIBUTE 3 BUTTONS ACROSS [-100, +100] RANGE ---
        int totalWidth = 200;                  // Range from -100 to +100
        int buttonWidth = 20;                  // Width of each icon button
        int numButtons = 3;
        int startX = this.width / 2 - 100;     // Left border (-100 offset)

        // Calculate gap dynamically so buttons touch the outer bounds (-100 and +100 - buttonWidth)
        // Formula: (Total Area - Combined Width of Buttons) / (Number of Gaps)
        int gap = (totalWidth - (numButtons * buttonWidth)) / (numButtons - 1); // Gap = (200 - 60) / 2 = 70px

        int x1 = startX;                                    // Left:   -100
        int x2 = startX + buttonWidth + gap;                // Middle:  -10
        int x3 = startX + totalWidth - buttonWidth;         // Right:   +80

        this.friends = (FriendsButton) this.addRenderableWidget(
                CommonButtons.friends(20, (var1) -> OnlineOptionsScreen.confirmFriendsListEnabled(
                        this.minecraft, () -> this.minecraft.gui.setScreen(new FriendsOverlayScreen(this)), this
                ), !this.minecraft.isDemo())
        );
        this.friends.setPosition(x1, topPos);

        SpriteIconButton language = (SpriteIconButton) this.addRenderableWidget(
                CommonButtons.language(20, (var1) -> this.minecraft.gui.setScreen(
                        new LanguageSelectScreen(this, this.minecraft.options, this.minecraft.getLanguageManager())
                ), true)
        );
        language.setPosition(x2, topPos);

        SpriteIconButton accessibility = (SpriteIconButton) this.addRenderableWidget(
                CommonButtons.accessibility(20, (var1) -> this.minecraft.gui.setScreen(
                        new AccessibilityOptionsScreen(this, this.minecraft.options)
                ), true)
        );
        accessibility.setPosition(x3, topPos);
        // -------------------------------------------------------------

        // Bottom row (Options & Quit buttons)
        topPos += 24;
        int optionsX = this.width / 2 - 100;
        this.addRenderableWidget(Button.builder(Component.translatable("menu.options"),
                        (var1) -> this.minecraft.gui.setScreen(new OptionsScreen(this, this.minecraft.options, false)))
                .bounds(optionsX, topPos, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("menu.quit"),
                        (var1) -> this.minecraft.stop())
                .bounds(this.width / 2 + 2, topPos, 98, 20).build());

        this.addRenderableWidget(new PlainTextButton(copyrightX, this.height - 10, copyrightWidth, 10,
                COPYRIGHT_TEXT, (var1) -> this.minecraft.gui.setScreen(new CreditsAndAttributionScreen(this)), this.font));

        // Account status of the local player: Premium / Standard.
        boolean premium = com.coflnet.ahflipper.PriorityAccess.isLocalPriority();
        Component status = Component.literal("Air Client  ").withColor(0xD0D0D0)
                .append(Component.literal(premium ? "Premium" : "Standard")
                        .withColor(premium ? 0xA4F0C1 : 0x9A9A9A));
        this.addRenderableWidget(new StringWidget(4, 4, this.font.width(status), 10,
                status, this.font));

        if (this.realmsNotificationsScreen == null) {
            this.realmsNotificationsScreen = new RealmsNotificationsScreen();
        }

        if (this.realmsNotificationsEnabled()) {
            this.realmsNotificationsScreen.init(this.width, this.height);
        }
        ci.cancel();
    }
}


