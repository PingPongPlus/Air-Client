# Weather Glass

Open **Control Center → RENDER → Weather Glass** to enable the feature, then click its gear for
**Weather Glass options**. It defaults to off. Mode, Always active and intensity
persist in `config/voicechat-weather-glass.properties`.

- **Automatic:** exposed glass gets wet during local rain, or freezes in cold
  biomes. Snow selects frost rather than liquid droplets. Dry biomes do not get
  rain. Going under a roof gradually dries/thaws the material.
- **Rain:** local rain activates the effect unless **Always active** is enabled.
- **Frost:** exposed cold-biome conditions activate the effect unless **Always
  active** is enabled.
- **Always active:** Rain or Frost works everywhere, including indoors and menus.
  Automatic always follows local conditions and ignores this saved toggle.
- **Intensity:** 0–100%, default 65%. Mode changes, wetting, drying, freezing and
  thawing blend smoothly. Turning the feature off also fades the material away.

This material is bounded to individual glass surfaces.

Rain has varied convex beads, softly merging pairs, gliding lenses, winding thin
trails, refracted background detail and directional highlights. Frost develops
irregular edge seeds, fine branching needles, broken microstructure and patchy
condensation while keeping the centre clear. These are procedural optical effects,
not fluid or ice physics simulations.

The material uses the shared glass renderer for panels, buttons, controls, HUD
widgets, hotbar and bars. It matches capsules, adjustable panel corners, square
HUD panels and slanted boss bars; existing scissors and transforms also apply.
Weather is layered after music tint and before text/icons. Shapes under six GUI
pixels high omit it to keep thin tracks readable. Startup/reload fallback geometry
also omits it while custom shaders are unavailable.

The shader reuses the existing scene texture and adds no framebuffers, textures,
per-drop objects or accumulating particle system. Climate is sampled at most ten
times per second, while material transitions use elapsed time shared by all
surfaces. Animation continues while paused. The optional visual test measures
whole GUI-pass GPU time, not isolated effect cost or overall gameplay FPS.

Verification:

```text
./gradlew build --offline
./gradlew runClientGameTest -I tools/weather-glass-test.gradle --offline
```

The first command tests weather selection, shelter, Always active, bounds and
transition agreement at 30/144 FPS alongside the existing checks. The isolated
client test checks saved preferences and malformed-file fallback, mode controls,
shader compilation/reload, rain motion, square/rounded surfaces, GUI scales,
window sizes, actual rainy-world HUD exposure, shelter drying and forced indoor
frost. It saves previews under `build/run/clientGameTest/screenshots` and restores
its isolated preferences. The test mod is not packaged with the production client.

Review captures: [Rain](screenshots/weather-glass-rain.png),
[Frost](screenshots/weather-glass-frost.png), and
[Settings](screenshots/weather-glass-settings.png).

The final build and client test passed. On the test machine's Quadro P5000,
the showcase's whole GUI pass averaged 0.367 ms with rain and 0.410 ms with frost
(118 samples each). These measurements are specific to that scene and hardware.
