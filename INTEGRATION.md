# Adding MYRAA pack to an existing repo (Saiif07/Myaa07)

IMPORTANT - what this pack could and could not check
* The pack was produced WITHOUT access to your GitHub repository (no network there), so your real file list,
  package name, application ID, Gradle versions and workflow were NOT inspected.
* Because of that, everything in this pack is NEW. Nothing in your repository is modified or deleted by it.
  A list of "files to replace" would be a guess, so there is none. Wherever your code must change, it is listed
  under "Edits you do in YOUR files" below.

## Safest way: add it as a separate folder
1. Create a branch: `git checkout -b myraa-pack`
2. Unzip, then copy the folder `myraa-pack/` into the repo root (next to your existing app).
3. The pack uses applicationId `com.myraa.assistant` (namespace `com.myraa.app`). It installs NEXT TO your current
   app and cannot overwrite it. Change `applicationId` in `myraa-pack/app/build.gradle.kts` only if you want to replace it.
4. Build it on its own: `cd myraa-pack && ./gradlew :app:assembleDebug` (needs JDK 17).
5. CI: GitHub only runs workflows from the repo root. Copy `myraa-pack/INTEGRATION/build-myraa-pack.yml` to
   `.github/workflows/build-myraa-pack.yml`. Your existing workflow is not touched.

## Telegram section (added in this pack version)
Where: header paper-plane icon -> "Telegram" sheet; "APP UPDATES" button there and in Settings.
1. Hackflix India - https://t.me/Hackflix07 - first, gold "OFFICIAL OWNER CHANNEL" badge, Join Channel button.
2. Hackflix Movie Search - https://t.me/fuusaw - second, Join Channel button.
APP UPDATES opens https://t.me/Hackflix07. Tapping a card or a button opens the Telegram app (`tg://resolve?domain=...`);
if no app handles it, the https link opens in the browser. Android only opens these two allow-listed channels.
No subscriber counts, verification badges or other statistics are shown.
Files involved (relative to this pack): MODIFIED vs the previous pack: `app/src/main/assets/ui.html` (+ identical `preview.html`),
`app/src/main/java/com/myraa/app/MainActivity.kt` (method `openChannel` / `openTelegram`), `app/src/main/java/com/myraa/app/LocalCommands.kt` (mode rule fix).
Nothing else changed. In YOUR repository all of these are NEW paths (your repo was not inspected).
Not verified: tapping a card on a real phone with and without the Telegram app installed.

## Versions used (match them if you merge into your own Gradle files)
AGP 8.5.2 | Gradle 8.7 | Kotlin 1.9.24 | JDK 17 | compileSdk 34 | targetSdk 34 | minSdk 24 | no third-party libraries.

## NEW files, and where they belong
| File | Purpose |
|---|---|
| app/src/main/assets/ui.html | Whole frontend: full-screen 3D avatar, 4 modes, outfits, sheets (single self-contained HTML) |
| app/src/main/java/com/myraa/app/MainActivity.kt | WebView shell + JavaScript bridge |
| .../Brain.kt | AI call, memory, Modes (personas, voice pitch/rate), cancellation |
| .../LocalCommands.kt, Fuzzy.kt, PhoneAgent.kt, MyraaAccess.kt | Offline voice commands, any-app opening, in-app search, messaging |
| .../WakeService.kt, Reminders.kt, MyraaApp.kt | Background wake word, reminders, crash note |
| app/src/main/res/* , AndroidManifest.xml | Resources and manifest entries |
| gradlew, gradlew.bat, gradle/wrapper/* | Wrapper (bootstraps Gradle 8.7) |
| myraa.py | Optional backend + self-test (`python3 myraa.py test`) |
| preview.html | Open in any phone browser to preview the avatar UI without building |

## Edits you do in YOUR files (only if you merge instead of using a separate folder)
* AndroidManifest.xml: merge the permissions, the `<queries>` block, the services/receivers from `myraa-pack/app/src/main/AndroidManifest.xml`.
* Same package name? If your app is also `com.myraa.app`, DELETE duplicates instead of overwriting: classes with the same name will clash.
* Only want the new look? Copy `assets/ui.html` and expose these methods with `@JavascriptInterface` under the name `MyraaBridge`:
  send, mic, toggleWake, stop, setup, attach, wakeOn, setMode, getSettings, saveSettings, getMemory, clearMemory, getHistory.
  Native -> page calls: UI.onLine(role,text), UI.onState(key), UI.onWake(bool), UI.setMode(m), UI.setOutfit(n,true), UI.nextOutfit().
* Using your own VRM / three.js avatar? Keep your renderer; reuse only `Core.MODES`, the persona strings in `Brain.kt -> Modes`,
  and the outfit/mode voice commands in `LocalCommands.kt`.

## What was verified (and what was not)
Verified by automated checks: Python backend self-test, frontend script executed against a fake DOM, every outfit SVG is valid XML,
Kotlin static cross-checks (balanced brackets, imports, manifest classes/permissions/resources, bridge names on both sides),
gradlew shell syntax, zip contents.
NOT verified: the Gradle/Kotlin compile (no Android SDK available), anything on a real phone (wake word, accessibility
automation, WhatsApp/Telegram flows, battery behaviour), rendering quality of the avatar. Treat the first CI build as the first real compile.
