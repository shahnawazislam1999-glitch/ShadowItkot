# ShadowPlayer

Native Android video player (Kotlin + Media3) with the full-screen sentence-practice features of SHADOWit.
No web page, no uploads: open the app, allow file access, pick a video.

## Use
- Videos are scanned automatically (All / Folders, search, sort).
- Tap a video: full screen opens at once. Controls are hidden; gestures:
  - tap middle = play/pause, tap left/right = previous/next sentence, double tap = show/pin controls
  - slide left/right = seek, slide up/down on left half = brightness, right half = volume
- Button strip: previous, play, next, unlink, hide, bookmark, audio track, repeat, star, translated subtitle, copy, edit, delete, subtitles on/off, jump, landscape/portrait, lock.
- 3-dot menu: delay, playback settings (speed, passage loops, lines per group, sync, shuffle, starred only), previous/next video, upload subtitles/translated subtitles, Words, Range (pin / hide / delete), starred list, bulk delete.
- Subtitles: loaded from the video itself (MKV/MP4 text tracks), from a .srt next to the video (needs the one-time "All files access", menu entry), or uploaded from the 3-dot menu. They are remembered per video together with stars, bookmarks, hidden lines and position.
- Without subtitles it behaves as a normal player.

## Build the APK
Push this folder to a GitHub repo (branch `main`); **Actions > Build ShadowPlayer APK** produces `ShadowPlayer.apk` (Releases > latest, or run Artifacts).
The signing key `app/raj-hub.jks` is included for personal use only.
