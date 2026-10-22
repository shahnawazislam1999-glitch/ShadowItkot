# SHADOWit

Standalone Android app (Kotlin + WebView). Opens straight into **SHADOWit**, no launcher or lobby.

- App id: `com.raj.app.shadowit`
- Loads from: `https://shahnawazislam1999-glitch.github.io/raj_app/`
- First launch: Permission Center once, then a one-time download from GitHub. After that it opens instantly and works offline.
- Every 6 hours (when online) it quietly refreshes its saved copy from GitHub; the new version is used the next time you open it.
- Press **Back twice** to exit. Long-press the app icon > **Permissions** to reopen the Permission Center.
- Files the page saves go to **Downloads/SHADOWit**.

## Background play

- Keeps playing with the screen locked, in the background, and after you clear the app from Recents (a notification stays; tap it to come back, or use its Close button).
- Notification shows the current subtitle with Previous / Pause / Next / Close.
- Tiny floating subtitle window (tap the text to copy) with Previous / Play-Pause / Next appears whenever the app is not on screen. Drag it by the text.
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
