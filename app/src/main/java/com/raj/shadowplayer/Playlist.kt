package com.raj.shadowplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast

/** One video of the library. [key] is what the per-video memory (subtitles, stars, position) is stored under. */
class VideoItem(
    val key: String,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val folder: String,
    val dateAdded: Long,
    val path: String?
)

/** The list the player was opened from, so "next video" / "previous video" know where to go. */
object Playlist {
    var items: List<VideoItem> = emptyList()
    var index: Int = 0
}

/**
 * One-time "All files access" permission. Android only lets an app read a .srt that sits next to a video when this is on
 * (the normal video permission covers videos only). Without it everything still works: embedded subtitles and the
 * Upload subtitles menu entry do not need it.
 */
fun askAllFilesAccess(a: Activity) {
    if (Build.VERSION.SDK_INT < 30) {
        Toast.makeText(a, "On this Android version use Upload subtitles in the 3-dot menu", Toast.LENGTH_LONG).show()
        return
    }
    if (Environment.isExternalStorageManager()) {
        Toast.makeText(a, "All files access is already on", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName)))
    } catch (e: Exception) {
        try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e2: Exception) {
            Toast.makeText(a, "Could not open the settings screen", Toast.LENGTH_LONG).show()
        }
    }
}

fun fmtDuration(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000L)
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val sec = s % 60L
    return if (h > 0L) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
}

fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0) else String.format(java.util.Locale.US, "%.0f MB", mb)
}
