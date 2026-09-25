# Simple ClickGUI

Start with `src/client/java/pingplus/voicechat/client/gui/ClickGuiScreen.java`.
It contains the entire menu: a rectangular background, an FPS toggle, and Close.
Minecraft's standard `Button` handles drawing, hovering, clicking, keyboard focus,
and narration. There are no custom button subclasses or animations.

## What happens when you press Right Shift?

1. `VoicechatClient` detects the key and opens `ClickGuiScreen`.
2. Minecraft calls `init()` to create its buttons.
3. Clicking the FPS button runs the callback inside `Button.builder(...)`:

   ```java
   fpsHud.toggle();
   button.setMessage(fpsLabel());
   ```

4. `FpsHud` uses that flag to show or hide FPS during gameplay.

`extractBackground()` draws the background and title. `onClose()` returns to the
game. Minecraft handles Escape; the screen also handles the opening key to close.

## Add a button

Inside `init()`, add another native button and choose its position:

```java
addRenderableWidget(Button.builder(Component.literal("My feature"), button -> {
    // Your click action goes here.
}).bounds(buttonX, buttonY + 60, 200, 20).build());
```

The bounds are `x, y, width, height` in GUI coordinates. Move the Close button and
enlarge the background if needed. No separate button class is required.

The HUD category toggles gameplay widgets. To add a feature from logic to the
on-screen glass panel, see [WIDGETS.md](WIDGETS.md).
