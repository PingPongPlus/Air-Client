# ClickGUI

Press **Right Shift** to open the control center. Toggle features directly in
their category panels, and click the small gear beside a feature for additional
settings. Options appear in a separate glass window rather than expanding the
category. Groups such as **Bars**, **Player scale**, and **Glass style** have their
own gear.

The **All off** button in the top bar switches off every toggle across all
categories and gear windows. It applies each feature's
normal shutdown and saved preferences. Slider values, colors, layouts, and
keybinds remain available for later use. Repeated clicks keep everything off.

Drag the window's title bar to move it. Scroll inside longer option lists; toggle
buttons, sliders, and numeric fields apply changes immediately. Window positions
are kept while the current control center is open and clamped when resized.

Close the window with **×**, **Escape**, or a click outside it. That outside click
does not toggle a feature behind the window. **Tab / Shift-Tab** stay inside the
open window and scroll focused controls into view. Once the window is closed,
Escape closes the control center.

In **RENDER → Glass style**, enable **Custom glass color** and adjust **Red**,
**Green**, **Blue**, and **Glass tint**. The color applies to glass panels,
HUD backgrounds, buttons, switches, and bars, including menus. It takes priority
over Spotify's album tint while enabled; disabling it restores music-reactive
glass. Color and intensity are saved with the global glass effect preferences.

The implementation is in `src/client/java/pingplus/voicechat/client/gui/ClickGuiScreen.java`.
The focused graphical check is
`./gradlew -I tools/clickgui-options-test.gradle runClientGameTest --offline`.
It checks all gear windows, live controls, saved global tint and its rendered color,
invalid numeric input, focus, dragging,
closing, and scrolling after a resize, and saves screenshots in
`build/run/clientGameTest/screenshots`.

HUD edge toggles, the Arraylist, CPS, Speed, and Keystrokes start off. Existing saved preferences are respected.

For HUD widgets and their separate layout editor, see [WIDGETS.md](WIDGETS.md).
For the fixed-center reticle and its live preview, see [CROSSHAIR.md](CROSSHAIR.md).
For sneaking while inventories and other screens are open, see [FORCE_SNEAK.md](FORCE_SNEAK.md).

The Skyblock options also include **Etherwarp helper**, **Self glow**, and the animated
**AGG** sticker. AGG uses the shared HUD editor (normally **G**) for dragging,
resizing, and resetting its layout; visibility is saved separately.

**SKYBLOCK → Auction Flipper / Bazaar Flipper** starts or stops the corresponding
flipper. Both retain their Hypixel SkyBlock requirement. **All off** stops both.
Under **KEYS**, click **Auction Flipper** or **Bazaar Flipper**, then press a key
or mouse button to bind it. **Escape** cancels binding; changes save immediately
and also appear in Minecraft's Controls menu. **Reset keys** restores **F9** for
the Auction Flipper and **F8** for the Bazaar Flipper.
