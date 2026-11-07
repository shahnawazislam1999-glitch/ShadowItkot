# SHADOWit

Standalone Android app (Kotlin + WebView). Opens straight into **SHADOWit**, no launcher or lobby.

- App id: `com.raj.app.shadowit`
- Loads from: `https://shahnawazislam1999-glitch.github.io/raj_app/`
- First launch: Permission Center once, then a one-time download from GitHub. After that it opens instantly and works offline.
- Every 6 hours (when online) it quietly refreshes its saved copy from GitHub; the new version is used the next time you open it.
- Press **Back twice** to exit. Long-press the app icon > **Permissions** to reopen the Permission Center.
- Files the page saves go to **Downloads/SHADOWit**.

## Full screen video controls

- By default only the picture and the subtitle show. Gestures on the picture:
  - **Single tap, middle** of the screen: play / pause.
  - **Single tap, right side** (right 30%): next sentence. **Single tap, left side** (left 30%): previous sentence.
  - **Double tap**: the position bar (at the very bottom, below the subtitle), the button strip and the 3-dot button (top right) appear and stay until you double tap again.
  - **Press and slide left / right**: the video moves backward / forward (a finger across the whole screen = 2 minutes; change `FS_SWIPE_FULL_WIDTH_MS` in `NativeVideo.kt`). When you let go, the sentence player continues from that place.
- Position bar: slide with your finger, the picture follows. (The volume bar was removed; use the phone's volume keys.)
- Button strip (top right, slides sideways, 5 icons visible at a time): Previous, Play/Pause, Next, **Unlink** (film plays normally, no sentence repeat; tap again to go back), **Hide subtitle**, then slide for: **Bookmark** this subtitle, **Audio track**, **Repeat** (how many times every sentence repeats, or this sentence only; the number on the icon is the current count) **Favorite** (star) and **Second subtitle** on / off (translated SRT; the line shows right below the main subtitle).
- More icons at the end of the strip (slide further): **Copy** the subtitle, **Edit** it, **Delete** it (asks first), **Subtitles on / off** (hides the text on the picture, nothing else changes), **Jump** to a subtitle number, **Landscape / Portrait** (icon shows the current mode; in portrait the subtitle sits under the picture) and **Lock**.
- **Lock**: hides every control and turns all gestures off, so only the picture and the subtitle remain. Tap the screen once to show an unlock button for a moment, tap it to unlock. Back does not leave full screen while locked. Full screen always starts unlocked.
- **Slide up / down** on the picture: **left half = brightness, right half = volume** (a small read-out shows the level). Brightness is remembered for the next full screen and the phone's own brightness returns when you leave full screen.
- **3-dot button** (top right) opens a menu:
  - **Delay sentence** = pause between sentences in seconds (the same setting as Delay in the page).
  - **Upload subtitles / Upload translated subtitles**: pick the .srt files without leaving full screen.
  - **Words** (e.g. `5` or `5-8`) and **Range** (e.g. `1-5`): the same filters as the page. Range also has Pin range, Pinned ranges, Hide / show range and Delete range (no translate button).
  - **Starred subtitles** (tap = jump, hold = remove) and **Bulk delete**.
  - Edit, Delete and Jump pause the film while their box is open.
- Full screen always starts in normal repeat mode with the controls hidden.

## Background play

- Keeps playing with the screen locked, in the background, and after you clear the app from Recents (a notification stays; tap it to come back, or use its Close button).
- Notification shows the current subtitle with Previous / Pause / Next / Floating window on-off / Close (expand it to see all five).
- Tiny floating subtitle window (tap the text to copy, double-tap to hide that sentence) with Previous / Play-Pause / Next appears whenever the app is not on screen. Drag it by the text.
- Pen / headset Next and Previous buttons work while minimized or locked (media session). For pen buttons that send Page Up/Down or arrow keys, turn on "Pen buttons when minimized" in the Permission Center (Accessibility).
- Tip: in Recents, long-press the app card and choose "Keep open" / "Lock" so your phone's own cleaner does not kill it.

## Push to GitHub (fresh start, no old git data)

This folder has no `.git` inside, so no old remote, login or history comes along:

```
git init -b main
git add -A
git commit -m "SHADOWit background play"
git remote add origin https://github.com/shahnawazislam1999-glitch/ShadowItkot.git
git push -u origin main --force
```

If GitHub asks for a password, use a Personal Access Token with the `repo` and `workflow` scopes (workflow is needed because the push contains `.github/workflows`). If it says your email is private, run `git config user.email "<id>+<username>@users.noreply.github.com"` and commit again.

## Get the APK

1. Create a new GitHub repository and push everything in this folder to the `main` branch.
2. Open the **Actions** tab. **Build SHADOWit APK** runs by itself (3 to 5 minutes).
3. Download `SHADOWit.apk` from **Releases > SHADOWit (latest build)**, or from the finished run > **Artifacts**.

Every push builds a new APK. New builds install over the old one (same signing key, rising version number).

## Change things

- Name: `app/src/main/res/values/strings.xml`
- Web address / files / fonts: `app/src/main/assets/apps.json`
- Permissions: `app/src/main/AndroidManifest.xml` and `app/src/main/java/com/raj/hub/Perms.kt` (only notifications, display over other apps, battery)
- Floating window opacity: `IDLE_ALPHA` at the top of `Floating.kt`
- Icon: replace `app/src/main/res/drawable-nodpi/ic_launcher_art.png` (432x432) and `app_logo.png`

The signing key `app/raj-hub.jks` is included on purpose for personal use. Do not use it for the Play Store.
