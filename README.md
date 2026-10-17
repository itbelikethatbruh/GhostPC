# Radiant PC Game Browser

An Android WebView browser that presents itself to web games as desktop Chrome on Windows 11. It turns any Bluetooth/USB controller (Xbox, DualShock/DualSense, Backbone, 8BitDo) into PC keyboard + mouse input, an XInput-style gamepad, or both.

## Build the APK (no Android Studio needed)

1. Create a GitHub repository and push this folder to it (`git init && git add . && git commit -m init && git push`).
2. GitHub Actions runs `.github/workflows/build-apk.yml` on every push. It builds the APKs, then installs the release APK on an Android emulator and uses it (start-up, every menu tab, a multithreaded site). A build that crashes there is not published. Screenshots and logs from that run are in the `smoke-test-<n>` artifact.
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
- **Select + Start** (or **Guide**): open / close the command drawer. The Android Back button and the menu button (top left) do the same.
- **In the drawer:** LB / RB switch tabs (Games, Controls, Display, Buttons, Status), the D-pad moves focus, A selects, B goes back or closes.
- **L3 + R3:** toggle the virtual mouse cursor.
- **During the launch animation:** any tap or button skips it.

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

## Multithreaded games ("Shared memory is unavailable")

Sites like vel.gg check for `SharedArrayBuffer` and `crossOriginIsolated`. That is a capability check, not a browser check, so no user-agent or identity spoof can get past it. The page needs real shared memory.

Android WebView ignores COOP + COEP. It only isolates a page when **both** of these hold:
1. the app allowlists the site's origin (`Profile.setCrossOriginIsolatedAllowlist`, androidx.webkit 1.18), and
2. the page is served with a `Document-Isolation-Policy` header.

Radiant does both:
- **Detection:** the page script checks each site's own COOP / COEP headers. If a site asks for isolation, Radiant allowlists that origin and reloads once ("Turn on when a site asks", on by default). vel.gg is on from the start.
- **Header:** for allowlisted sites, the app fetches HTML documents itself and adds `Document-Isolation-Policy: isolate-and-require-corp` (or `isolate-and-credentialless` when the site uses credentialless COEP).
- **Manual control:** Games tab → *Multithreading* switches it per site. Display tab lists every site that has it on.

This needs an Android System WebView recent enough to support the allowlist. If yours is older, the drawer says "Needs a newer Android System WebView": update it from the Play Store.

## Browser identity

Display tab → *Browser identity* picks what sites see. The page reloads after a change.

| Identity | User agent and client hints |
| --- | --- |
| Chrome (default) | Chrome on Windows 11, real WebView version |
| Edge | Chrome UA plus `Edg/`, "Microsoft Edge" brand in client hints |
| Firefox | Firefox on Windows 11; `navigator.userAgentData`, `window.chrome` and `deviceMemory` are removed |
| Android | No spoofing: the stock WebView identity and touch environment |

Firefox identity only changes what JavaScript and headers report. The engine is still Chromium, so a site that tests Firefox-only features will see Chromium behaviour.

## How it differs from the original spec, and why

| Spec asked for | What this build does | Why |
| --- | --- | --- |
| COOP/COEP headers so SharedArrayBuffer works | Per-site origin allowlist plus an injected `Document-Isolation-Policy` header (see *Multithreaded games*). The shim never fakes `SharedArrayBuffer`. | WebView ignores COOP + COEP entirely. Faking the global sends libraries that have a single-thread fallback down a threaded path that then crashes. |
| Always advertise OES_texture_float, WEBGL_depth_texture, etc. | Advertise only what is real or truly emulated: S3TC (software DXT1/3/5 decoder) and anisotropic filtering (safe no-op). | A fake extension the GPU can't back produces GL errors and black textures instead of the game's own fallback path. |
| Report MAX_TEXTURE_IMAGE_UNITS ≥ 16/32 | Real values are reported. | Shaders written against inflated limits fail to link. |
| Y = mouse wheel **and** 1/2 toggle | Y = wheel down; "Toggle 1/2" is a separate binding. | Sending both switches weapons twice per press. |
| Start = Esc **and** Enter | Tap = Esc, hold = Enter. | Pressing both at once opens a menu and confirms it in the same instant. |
| Dispatch keys to canvas, document **and** window | One event on the focused target, bubbling to document and window. | Three dispatches = triple key presses for listeners on `window`. |
| UA hardcoded to Chrome/131 | UA uses the real WebView's Chrome major version (e.g. Chrome/141). | Version-sniffing sites then match the engine's real features; also applied to UA client-hint headers. |
| Cursor clicks as synthetic JS events | Cursor clicks are real touch events injected into the WebView, re-typed as mouse for the page. | Synthetic events carry no user activation: file pickers (.wad/.mpq/.iso import), audio and popups would not open, and they can't reach cross-origin iframes. |
| Fake fullscreen promise | Element fills the screen via the top layer and also works from inside iframes. | Games then get the full-screen canvas size they expect. |

## If the app closes unexpectedly

Open it again, then Menu > Status. A *Last crash* row shows what failed, and *Copy crash details* copies the full report so you can paste it into an issue or a chat. Crashes inside Android System WebView itself (Android 11+) are listed too, but Android gives no stack trace for those.

## Known limitations

- **Engine:** games are limited by Android System WebView (Chromium) on a phone GPU. Keep Android System WebView updated from the Play Store.
- **Threads:** shared memory needs a WebView that supports the isolation allowlist (see *Multithreaded games*). The drawer's *Status* tab shows whether the current page has it. A page reached through an HTTP redirect may load without isolation; open its final URL directly (or bookmark it) if that happens.
- **S3TC cost:** each S3TC texture is decoded once on upload, about 70-130 ms for a 2048×2048 DXT5 texture on a desktop CPU and slower on phones. Decoded textures use 4-8× more GPU memory. If a game crashes with "out of memory", try 50% resolution; the app reloads crashed renderers automatically.
- **Worker rendering:** WebGL rendered from Workers (OffscreenCanvas) is not covered by the S3TC shim.
- **Desktop spoofing:** CSS `@media (pointer: coarse)` rules inside stylesheets still see the touchscreen; JavaScript `matchMedia` is spoofed.
- **Game files:** sites that need your own files (Half-Life valve data, Diablo DIABDAT.MPQ, ISO images) get them through the file picker. Only use files you own.
