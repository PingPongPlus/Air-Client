# Spotify HUD

Run Spotify's desktop app on the same Windows computer and play a song. The liquid
glass widget automatically displays its title, artist, artwork and playback progress
during gameplay. There is no Spotify developer app, API key, sign-in page or mod
account setup. The mod does not add an API Premium requirement; controls respect
the actions that Spotify itself allows for the current account and track.

- Open chat (normally **T**) to interact with the previous, play/pause and next icons.
- Drag the upper part of the widget while chat is open, then release to save its position.
- Close chat: the widget stays visible in the same position during gameplay.
- Use **Right Shift → HUD → Spotify** to toggle it. Visibility and relative position
  are saved in `config/voicechat-spotify-hud.properties` and survive restarts.
- F6 focuses/cycles available media controls; Tab/Shift-Tab navigate, Enter/Space
  activate. Icons retain readable narration and tooltips. Chat keeps its normal
  completion behavior when the text field has focus.
- F1 hides it with the rest of the HUD. Position is clamped when resizing or changing
  GUI scale, keeping the chat input clear.

## Platform support and behavior

The local integration currently supports **Windows 10 (1809+) and Windows 11**.
macOS and Linux show an unsupported-platform message and need separate adapters.
Use the Spotify desktop app: browser sessions and music playing only on a phone are
not targeted. If Spotify exposes no session, the widget asks you to start Spotify.
Unavailable actions are disabled, and missing artwork uses a placeholder. Progress
and artwork depend on the metadata Spotify supplies to Windows.

One hidden, bundled Windows PowerShell helper reads the OS media-session API every
750 ms and accepts only fixed playback commands over stdin. It selects only Spotify
sessions, so it cannot accidentally pause another media app. No network calls, tokens,
ports, downloaded scripts, extra runtimes or user-installed modules are involved.
Java never runs metadata as commands. The helper is stopped when the HUD is disabled,
the world is left, or Minecraft shuts down; stalled helpers are restarted with a delay.
Systems that prohibit PowerShell or WinRT access show an unavailable state.

The earlier `voicechat-spotify.properties` client ID file, if present, is unused.

## Verification

`gradlew.bat build --offline` runs local snapshot/progress/control-availability tests
and the existing voice smoke tests. `gradlew.bat runClientGameTest --offline` checks
chat icon controls, dragging, position persistence and the ClickGUI toggle, and captures
the HUD with chat both open and closed. Tests do not send playback commands to a user's
actual Spotify session. A read-only Windows bridge probe verifies metadata and cover
decoding on the development computer.

The bridge uses the documented [Windows media-session API](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssession).
