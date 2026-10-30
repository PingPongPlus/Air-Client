# Glass client UI

Use **Mode: Light/Dark** in the top-left of the main menu to switch themes.
Light mode is the default and retains the mountain wallpaper and original square AIR logo.
Dark mode uses the extended monochrome streetlamp wallpaper and the wide Air Client title.
Both retain smooth cursor parallax and the existing glass raindrops toggle. The theme also
applies to the other menu wallpapers and is saved in `config/voicechat-menu-theme.properties`.
Run `./gradlew -I tools/menu-theme-test.gradle runClientGameTest --offline` to check
switching, persistence, parallax in both themes, small layouts and resource reloads.

Use **Glass drops: ON/OFF** in the top-right of the main menu to toggle animated
glass raindrops globally. Glass buttons and panels stay unchanged. The preference
is saved in `config/voicechat-glass-rain.properties`.

Open the ClickGUI with the existing client-controls key (Right Shift by default).
The interface renders at 60% of its logical size, including text and hit targets,
leaving room for more categories. `ClickGuiScreen.UI_SCALE` controls this independently
of Minecraft GUI scale. Mouse clicks, releases and dragging use the inverse transform.

Drag category headers to move panels. Gear buttons beside features open their
additional settings in a floating glass window. Drag its title to move it; scroll
inside it for longer option lists. The close button, clicking outside, or Escape
closes the settings window. Tab/Shift-Tab navigate its controls without activating
the category panels underneath. Escape then closes the control center.

All existing controls are retained: FPS, coordinates, hitboxes, body, both arms,
hand swapping, four scale values, and the 1–20 tick swap interval. Settings retain
their existing session lifetime; this redesign does not add disk persistence or
change gameplay logic. Panel positions survive resizing and settings expansion
within the current screen. Closing and reopening starts with the responsive layout.

In **RENDER > MobESP**, enable the toggle and open its gear. Enter Minecraft mob
names such as `zombie, skeleton, iron_golem`, then press Enter or **Add mob(s)**.
Names ignore case; `minecraft:zombie` and `iron golem` also work. Add as many types
as needed and click **Remove: name** to remove one. Selected living mobs receive
a green glow through walls, including mobs with custom name tags. Specialized
slayer colors take priority. The selection lasts for the current client session
and remains available while MobESP is disabled. No mobs glow until names are added.

`./gradlew runClientGameTest --offline -I tools/mob-esp-test.gradle` checks MobESP
against actual entity render states and menu input in a local world.

## Implementation

- `ClickGuiScreen`: category layout, dragging, custom controls, input and animations.
- `GlassStyle`: palette, UI font, and resource-independent rounded geometry.
- `GlassButtonRenderer`: extracts antialiased GPU geometry for glass and tinted controls.
- `GlassBackdrop` / `GlassGuiRendererMixin`: capture the dry background for rain, then
  capture the wet scene for panels. One shared half-resolution separable blur is reused
  across controls. Targets resize with the framebuffer and are released outside menus.
- `glass_button.fsh`: rounded glass, screen-space refraction, frosted sampling, rim lighting.
- `glass_control.fsh`: antialiased switches, slider tracks/thumbs, and input surfaces.
- `GlassLoadingOverlayMixin`: changes startup/reload visuals while leaving vanilla
  completion callbacks, exception handling, progress calculation, and fade lifecycle intact.
- Loading/transition mixins retain original status text, progress and connection actions.

During resource reload, glass geometry falls back to built-in solid rendering. The
startup artwork is registered directly from the bundled image before resource packs
load; its matching progress bar uses built-in geometry. Neither needs custom shaders
or fonts. The overlay shows only the supplied Minecraft AIR artwork and real progress.
The Rajdhani font is distributed under its bundled SIL Open Font License. The world
rendering is unchanged; default text in menus, HUD, chat, tooltips and input fields uses Rajdhani.

## Verification

`./gradlew build --offline` compiles/packages the mod and runs the voice smoke tests.
`./gradlew runClientGameTest --offline` launches an isolated client, exercises glass
controls, checks settings windows, numeric validation, slider input, panel dragging, keyboard
focus, resizing and GUI scales, and performs a resource reload in a temporary world.
It also checks the close key and captures screenshots in
`build/run/clientGameTest/screenshots`. The test mod is not included in the production JAR.

The Mountain Air update is verified on Windows/OpenGL (NVIDIA Quadro P5000), at
854x480 and 1440x900, including GUI scales 2 and 3, resource reloads and menu transitions.
The test captures title, options, survival/world selection, multiplayer, pause and ClickGUI
screens. A default-font assertion checks that measurements match the ClickGUI and differ
from vanilla. Test-only asynchronous GPU timestamps measure the entire GUI render pass;
they do not measure the world renderer or whole-frame latency. Other GPU backends remain
unverified. On this machine, the final 1440x900 run recorded:

| Screen | Samples | Mean GUI GPU time | Maximum |
| --- | ---: | ---: | ---: |
| ClickGUI | 235 | 1.002 ms | 1.574 ms |
| Pause | 237 | 1.007 ms | 1.660 ms |

These are hardware-specific timings for the whole GUI pass, not an isolated rain cost.
Two pause captures also confirmed rain motion while the world was paused.

## Mountain Air materials

`GlassLogoRenderer` binds the original mark as a silhouette/relief texture and the wet
scene as a second sampler. `glass_logo.fsh` transmits and refracts that live background
through the ribbon, with thickness absorption, Fresnel reflection, slight dispersion and
broad specular highlights. Display-resolution filtering softens the source alpha halo.
The logo shares the existing scene capture; no additional render target is allocated.
`LogoRendererMixin` preserves each theme's logo proportions and the title fade.

`GlassRain` and `glass_rain.fsh` render two procedural droplet layers in one full-screen
pass plus stationary condensation. Moving drops vary in size, asymmetry, aspect and speed;
long refractive trails follow fixed winding paths and leave smaller pearls behind. Adjacent
cells overlap their wakes so trails continue across cell boundaries. The material includes
Fresnel rims and sky highlights. Motion uses wall time so it continues on the pause menu.
The effect is an optical approximation, not a fluid simulation. Its cost does not increase
with an accumulating particle count. Text and controls draw in the following stratum;
glass panels sample the wet background. Rain is skipped while resource packs reload.

`GlassMenuBackgroundMixin` covers the common menu background used by pause, world
selection/creation, multiplayer and options. Title and ClickGUI have explicit hooks.
Inventory and chat do not receive the rain overlay during gameplay.

`GlassFontMixin` routes the default font to `voicechat:ui`, keeping both measurement and
rendering consistent. The existing Rajdhani SemiBold asset, size 11 and oversample 3 are
shared. Explicit icon fonts and Force Unicode Font remain available. The UI font references
vanilla include fonts directly for missing glyphs, avoiding a default/UI reference cycle.

## Arraylist and effect controls

In HUD, open **Arraylist options**. Enable **Glass** and **Per-module boxes** for
separate square backgrounds sized to each enabled entry. Rows share a right edge,
use equal padding and center their text vertically. **Edges** toggles the glass
outline and edge refraction for this HUD only. The arraylist and edges start off; a single panel is the default layout when enabled. Arraylist options follow the other HUD toggles' session lifetime.

In RENDER, **Glass blur** and **Glass shadow** range from 0% (off) to 200% in 25%
steps. Both default to 100%, preserving the existing appearance. Blur changes the
shared Gaussian radius; shadow changes its spread, offset and opacity. Effect
preferences are saved in `config/voicechat-glass-effects.properties`.

Open the **Glass style** gear to enable **Custom glass color**, choose RGB channels
(0–255), and adjust **Glass tint** (0–100%). This reuses Spotify's tint overlay,
28% opacity cap, and smooth color transitions. The custom color works without
Spotify playback and takes priority over album colors while enabled. Disabling
it returns panels to their Spotify tint when active. Custom tint also colors the
shared switch, slider, and bar materials. It uses the same saved effect preferences.

## Server scoreboard widget

**HUD > Scoreboard** shows or hides the server sidebar. Click its gear to open
**Scoreboard options** and toggle **Liquid glass** and **Glass edges** independently.
Edges off removes the bright rim, refraction, sheen, and outer shadow
while preserving the blurred glass background and rounded corners. Glass off shows only
server text, with a text shadow for readability. These toggles follow the other HUD
options' session lifetime. Position and scale persist through the shared HUD editor:
press G to open the HUD editor, drag to move, use the corner or mouse wheel to resize, and right-click to reset.

The widget appears only when the server supplies a sidebar objective, including
team-specific sidebars. It preserves styled titles, team prefixes/suffixes, custom
score names and number formats, hidden entries, and vanilla's 15-entry sort/limit.
Tab-list and below-name scores are unaffected.

## AIR logo widget

**HUD > AIR logo** toggles the supplied transparent AIR artwork. **Logo options**
contains **Logo glass** (blurred glass background on/off) and **Logo edges**.
Glass uses the global Render blur, shadow and corner settings. All three preferences
persist in `config/voicechat-logo-hud.properties`. Open the HUD editor (G) to drag, resize with the
corner or wheel, or right-click to reset its saved layout. The artwork keeps its
native proportions and uses smooth filtering. The main-menu logo is separate.
