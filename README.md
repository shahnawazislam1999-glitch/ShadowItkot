# SHADOWit

Standalone Android app (Kotlin + WebView). Opens straight into **SHADOWit**, no launcher or lobby.

- App id: `com.raj.app.shadowit`
- Loads from: `https://shahnawazislam1999-glitch.github.io/raj_app/`
- First launch: Permission Center once, then a one-time download from GitHub. After that it opens instantly and works offline.
- Every 6 hours (when online) it quietly refreshes its saved copy from GitHub; the new version is used the next time you open it.
- Press **Back twice** to exit. Long-press the app icon > **Permissions** to reopen the Permission Center.
- Files the page saves go to **Downloads/SHADOWit**.

## Get the APK

1. Create a new GitHub repository and push everything in this folder to the `main` branch.
2. Open the **Actions** tab. **Build SHADOWit APK** runs by itself (3 to 5 minutes).
3. Download `SHADOWit.apk` from **Releases > SHADOWit (latest build)**, or from the finished run > **Artifacts**.

Every push builds a new APK. New builds install over the old one (same signing key, rising version number).

## Change things

- Name: `app/src/main/res/values/strings.xml`
- Web address / files / fonts: `app/src/main/assets/apps.json`
- Permissions: `app/src/main/AndroidManifest.xml` and `app/src/main/java/com/raj/hub/Perms.kt`
- Icon: replace `app/src/main/res/drawable-nodpi/ic_launcher_art.png` (432x432) and `app_logo.png`

The signing key `app/raj-hub.jks` is included on purpose for personal use. Do not use it for the Play Store.
