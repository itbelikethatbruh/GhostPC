# Radiant PC Game Browser

An Android WebView browser that presents itself to web games as desktop Chrome on Windows 11. It turns any Bluetooth/USB controller (Xbox, DualShock/DualSense, Backbone, 8BitDo) into PC keyboard + mouse input, an XInput-style gamepad, or both.

## Build the APK (no Android Studio needed)

1. Create a GitHub repository and push this folder to it (`git init && git add . && git commit -m init && git push`).
2. GitHub Actions runs `.github/workflows/build-apk.yml` on every push.
3. Download the APK:
   - **From the default branch:** open the repo's **Releases** page on your phone and tap `RadiantPCGameBrowser-<n>.apk`.
   - **From any branch:** Actions tab → the run → `RadiantPCGameBrowser-<n>` artifact (a zip containing the APK).
4. Install it, allowing "Install unknown apps" for your browser or file manager when Android asks.

Every build is signed with the same key (`keystore/radiant-shared.jks`), so new builds install over old ones and keep your settings.
**That key is public once the repo is public.** To use your own key, add these repository secrets; the workflow picks them up automatically:

| Secret | Value |
| --- | --- |
| `RADIANT_KEYSTORE_BASE64` | `base64 -w0 your.jks` |
| `RADIANT_KEYSTORE_PASSWORD` | keystore password |
| `RADIANT_KEY_ALIAS` | key alias |
| `RADIANT_KEY_PASSWORD` | key password |

Switching keys means uninstalling the old build once.

Local build: `./gradlew assembleRelease`, with JDK 17 and the Android SDK (API 36) installed.

## Controls

**Anywhere**
- **Select + Start** (or **Guide**): open / close the command drawer. The Android Back button and the ☰ button do the same.
- **L3 + R3:** toggle the virtual mouse cursor.

**PC keyboard + mouse mode (default)**

| Controller | PC input |
| --- | --- |
| Left stick | W A S D |
| Right stick | Mouse look (response curve plus edge acceleration; sensitivity slider) |
| RT / R2 | Left click (fire) |
| LT / L2 | Right click (aim) |
| A / Cross | Space |
| B / Circle | C |
| X / Square | R |
| Y / Triangle | Mouse wheel down (next weapon) |
| LB / L1 | F + E (use, buy, interact) |
| RB / R1 | G |
| L3 | Shift (sprint) |
| R3 | V (melee) |
| D-pad | 1 2 3 4, or arrow keys (drawer toggle) |
| Start | Tap = Esc (releases the mouse first, like a PC), hold = Enter |
| Select | Tab (held for the scoreboard) |

Every button can be rebound in the drawer to any key, mouse button, wheel direction, or "toggle weapon 1/2".

**Virtual cursor** appears automatically in menus whenever the game is not capturing the mouse.
- Either stick moves it; the right stick moves slower for precision.
- A or RT clicks; hold it to drag.
- LB / RB scroll.
- B or Start = Esc, Y = Enter, X = Backspace, D-pad = arrow keys.

**XInput gamepad mode** exposes the controller via `navigator.getGamepads()` as a standard 17-button / 4-axis "Xbox 360 Controller (XInput STANDARD GAMEPAD)", including rumble. **Hybrid** sends both.

**On-screen gamepad** (drawer toggle):
- Floating left stick; double-tap and hold it for L3.
- Drag the right side to look (in XInput mode the drag acts as the right stick); tap the right side to click.

A physical USB/Bluetooth mouse is captured for real relative movement while a game holds pointer lock.

## How it differs from the original spec, and why

| Spec asked for | What this build does | Why |
| --- | --- | --- |
| COOP/COEP headers so SharedArrayBuffer works | The shim probes whether shared WebAssembly memory can reach a Worker. It defines `SharedArrayBuffer` only if it can, and the drawer shows the result. | Android WebView never becomes cross-origin isolated, so injected headers change nothing. Faking the global sends libraries that have a single-thread fallback down a threaded path that then crashes. **Multithreaded WASM builds may simply not run in any WebView app.** |
| Always advertise OES_texture_float, WEBGL_depth_texture, etc. | Advertise only what is real or truly emulated: S3TC (software DXT1/3/5 decoder) and anisotropic filtering (safe no-op). | A fake extension the GPU can't back produces GL errors and black textures instead of the game's own fallback path. |
| Report MAX_TEXTURE_IMAGE_UNITS ≥ 16/32 | Real values are reported. | Shaders written against inflated limits fail to link. |
| Y = mouse wheel **and** 1/2 toggle | Y = wheel down; "Toggle 1/2" is a separate binding. | Sending both switches weapons twice per press. |
| Start = Esc **and** Enter | Tap = Esc, hold = Enter. | Pressing both at once opens a menu and confirms it in the same instant. |
| Dispatch keys to canvas, document **and** window | One event on the focused target, bubbling to document and window. | Three dispatches = triple key presses for listeners on `window`. |
| UA hardcoded to Chrome/131 | UA uses the real WebView's Chrome major version (e.g. Chrome/141). | Version-sniffing sites then match the engine's real features; also applied to UA client-hint headers. |
| Cursor clicks as synthetic JS events | Cursor clicks are real touch events injected into the WebView, re-typed as mouse for the page. | Synthetic events carry no user activation: file pickers (.wad/.mpq/.iso import), audio and popups would not open, and they can't reach cross-origin iframes. |
| Fake fullscreen promise | Element fills the screen via the top layer and also works from inside iframes. | Games then get the full-screen canvas size they expect. |

## Known limitations

- **Engine:** games are limited by Android System WebView (Chromium) on a phone GPU. Keep Android System WebView updated from the Play Store.
- **Threads:** multithreaded (pthreads) WASM builds may fail; the drawer's *Status* section says whether threads are available.
- **S3TC cost:** each S3TC texture is decoded once on upload, about 70-130 ms for a 2048×2048 DXT5 texture on a desktop CPU and slower on phones. Decoded textures use 4-8× more GPU memory. If a game crashes with "out of memory", try 50% resolution; the app reloads crashed renderers automatically.
- **Worker rendering:** WebGL rendered from Workers (OffscreenCanvas) is not covered by the S3TC shim.
- **Desktop spoofing:** CSS `@media (pointer: coarse)` rules inside stylesheets still see the touchscreen; JavaScript `matchMedia` is spoofed.
- **Game files:** sites that need your own files (Half-Life valve data, Diablo DIABDAT.MPQ, ISO images) get them through the file picker. Only use files you own.
