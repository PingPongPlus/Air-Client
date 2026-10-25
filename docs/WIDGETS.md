# Client widgets

HUD widgets live in `src/client/java/pingplus/voicechat/client/hud/`, with shared
layout/editing in `hud/editor/` and measurements in `hud/metrics/`.
Drag, resize, control hit-testing and layout persistence are shared. Liquid glass
is **optional**. See [ADDING_FEATURES.md](ADDING_FEATURES.md) for a short FPS example.

## Path from feature to widget

1. **Logic** — put state and I/O in a client class (`src/client/java/...`). Keep it
   independent of rendering.
2. **Draw content** — a method `render(GuiGraphicsExtractor g, ...)` that paints in
   **local coordinates** starting at `(0, 0)`.
   - Text overlays (FPS, XYZ): draw text and optional glass, like `FpsHud`.
   - Card widgets: call `GlassStyle.widget` or `GlassStyle.surface`, then
     `GlassStyle.line` with `TEXT` / `MUTED` / `STATUS`. See `SpotifyWidget`.
3. **Register** in `VoicechatClient.initializeClientFeatures()` (or voice init):

   ```java
   HudEditor.register(new HudEditor.Entry(
           "clock", "Clock", () -> 120, () -> 18,
           (sw, sh) -> 18, (sw, sh) -> 66,
           clock::isEnabled,
           (g, mx, my, dt, editing) -> clock.render(g),
           List::of));
   ```

   `width` / `height` are the unscaled panel size. Default `x` / `y` are GUI pixels.
   `visible` hides the widget. `controls` is a list of native `Button`s in local
   coordinates, or `List::of` if there are none.
4. **Toggle** — add a HUD row in `ClickGuiScreen.init()`:

   ```java
   toggle(hud, "Clock", clock::isEnabled, clock::toggle);
   ```

5. **Interact** — press **G** to open the HUD editor, then drag, corner/scroll-resize,
   or click controls. Change the key under **Right Shift → KEYS → HUD editor** or
   Minecraft's Controls screen.
   Layout is stored in `config/voicechat-hud-layout.properties`. F1 hides the HUD.

## Rules

- One `Entry` id per widget; registering again replaces the previous entry.
- Buttons stay in local space; `HudEditor` maps them to the editor screen.
- World/gameplay logic stays out of the renderer.

The Skyblock options also include **Etherwarp helper**, **Self glow**, and the animated
**AGG** sticker. AGG uses the shared HUD editor (normally **G**) for dragging,
resizing, and resetting its layout; visibility is saved separately.
