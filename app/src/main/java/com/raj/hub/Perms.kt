package com.raj.hub

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * One permission row on the Permission Center screen.
 *
 *  - [runtime]  : normal Android pop-up permissions (empty on Android versions that do not need them)
 *  - [special]  : custom "is it allowed?" check, for permissions that live in Settings screens
 *  - [settings] : Settings screens to try (in order) when a pop-up is not possible
 */
class PermItem(
    val id: String,
    val title: String,
    val desc: String,
    private val runtime: () -> Array<String> = { emptyArray() },
    private val special: ((Context) -> Boolean)? = null,
    val settings: ((Context) -> List<Intent>)? = null
) {
    fun runtimeList(): Array<String> = runtime()

    fun granted(c: Context): Boolean {
        val sp = special
        if (sp != null) return sp(c)
        return runtime().all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
    }
}

object Perms {

    private fun pkgUri(c: Context): Uri = Uri.parse("package:${c.packageName}")

    fun appDetails(c: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))

    // ==== ANCHOR: PERMISSION LIST (add or remove a permission row here) ====
    // Only the three things background play really needs. Everything else (camera, mic, location,
    // all-files, accessibility, install apps ...) was removed on purpose.
    val all: List<PermItem> = listOf(

        PermItem(
            id = "notif",
            title = "Notifications",
            desc = "Shows the current subtitle with pause, previous and next buttons, also on the lock screen.",
            runtime = {
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            },
            special = { NotificationManagerCompat.from(it).areNotificationsEnabled() },
            settings = { c ->
                listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                )
            }
        ),

        PermItem(
            id = "overlay",
            title = "Display over other apps",
            desc = "Shows the tiny floating subtitle window when SHADOWit is in the background.",
            special = { Settings.canDrawOverlays(it) },
            settings = { c -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c))) }
        ),

        PermItem(
            id = "penkeys",
            title = "Pen buttons when minimized (optional)",
            desc = "Lets the pen's up/down buttons change the subtitle while the floating window is showing. Turn on SHADOWit in Accessibility. Only those keys are used.",
            special = { c ->
                val me = ComponentName(c, PenKeyService::class.java)
                val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                list.split(':').any { ComponentName.unflattenFromString(it) == me }
            },
            settings = { listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ),

        PermItem(
            id = "battery",
            title = "Run without battery limits",
            desc = "Stops Android from putting SHADOWit to sleep while it plays with the screen off.",
            special = { c ->
                val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(c.packageName)
            },
            settings = { c ->
                listOf(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(c)),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        )
    )

    fun missingCount(c: Context): Int = all.count { !it.granted(c) }
}
