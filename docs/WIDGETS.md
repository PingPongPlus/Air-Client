# Client widgets

Every on-screen feature that appears during gameplay is a HUD widget. Drag, resize,
chat hit-testing and layout persistence are shared. Liquid glass is **optional** and
only used by card-style widgets such as Spotify.

## Path from feature to widget

1. **Logic** — put state and I/O in a client class (`src/client/java/...`). Keep it
   independent of rendering.
2. **Draw content** — a method `render(GuiGraphicsExtractor g, ...)` that paints in
   **local coordinates** starting at `(0, 0)`.
   - Simple overlays (FPS, XYZ): just draw text, like `FpsHud`.
   - Card widgets: call `GlassStyle.widget` or `GlassStyle.surface`, then
     `GlassStyle.line` with `TEXT` / `MUTED` / `STATUS`. See `SpotifyWidget`.
3. **Register** in `VoicechatClient.initializeClientFeatures()` (or voice init):

   ```java
   HudEditor.register(new HudEditor.Entry(
           "clock", "Clock", 120, 18,
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

5. **Interact** — open chat (**T**) to drag, corner/scroll-resize, or click controls.
   Layout is stored in `config/voicechat-hud-layout.properties`. F1 hides the HUD.

## Rules

- One `Entry` id per widget; registering again replaces the previous entry.
- Buttons stay in local space; `HudEditor` maps them to the screen for chat.
- World/gameplay logic stays out of the renderer.
