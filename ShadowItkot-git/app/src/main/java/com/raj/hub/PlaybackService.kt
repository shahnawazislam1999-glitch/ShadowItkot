package com.raj.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps SHADOWit playing with the screen locked, in the background, and after the app is cleared from Recents.
 *
 *  - foreground service (media playback) so Android does not stop the app
 *  - partial wake lock while playing so the sentence timer keeps running with the screen off
 *  - notification with the current subtitle + previous / pause / next / close
 *  - tiny floating subtitle window while the app screen is not in front
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 4711
        private const val ACT_TOGGLE = "com.raj.app.shadowit.TOGGLE"
        private const val ACT_PREV = "com.raj.app.shadowit.PREV"
        private const val ACT_NEXT = "com.raj.app.shadowit.NEXT"
        private const val ACT_CLOSE = "com.raj.app.shadowit.CLOSE"

        private var inst: PlaybackService? = null

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
            } catch (e: Exception) {
                // Android refused to start it from here; the app still works, just not in the background
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }

        /** Re-draws the notification, wake lock and floating window. Main thread only. */
        fun refreshNow() {
            inst?.refresh()
        }
    }

    private var overlay: FloatingSubtitle? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSig = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        createChannel()
        overlay = FloatingSubtitle(this) { c -> WebHost.current()?.mediaCmd(c) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = WebHost.current()
        if (host == null || host.destroyed) {
            // nothing to play any more
            goForeground()
            closeAll()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACT_TOGGLE -> host.mediaCmd("toggle")
            ACT_PREV -> host.mediaCmd("prev")
            ACT_NEXT -> host.mediaCmd("next")
            ACT_CLOSE -> {
                goForeground()
                closeAll()
                return START_NOT_STICKY
            }
        }
        goForeground()
        refresh()
        return START_NOT_STICKY
    }

    /** The app screen was cleared from Recents: keep going only if it is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MediaBus.playing) closeAll()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        releaseWakeLock()
        if (inst === this) inst = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ stop everything

    private fun closeAll() {
        overlay?.hide()
        releaseWakeLock()
        val host = WebHost.current()
        val a = host?.activity
        WebHost.shutdown()
        if (a != null) {
            try {
                a.finishAndRemoveTask()
            } catch (e: Exception) {
                // ignore
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ wake lock

    private fun syncWakeLock() {
        if (MediaBus.playing) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl: PowerManager.WakeLock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shadowit:playback")
            wl.setReferenceCounted(false)
            wakeLock = wl
            if (!wl.isHeld) wl.acquire()
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        try {
            if (wl.isHeld) wl.release()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun goForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            // not allowed right now; the next refresh tries again
        }
    }

    private fun action(act: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this,
            req,
            Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val host = WebHost.current()
        val sub = MediaBus.text.trim()
        val playing = MediaBus.playing
        val pos = if (MediaBus.total > 0) "${MediaBus.index} / ${MediaBus.total}" else ""
        val title = if (sub.isNotEmpty()) sub else getString(R.string.app_name)
        val line2 = when {
            pos.isNotEmpty() -> pos + (if (playing) "  ·  Playing" else "  ·  Paused")
            else -> getString(R.string.notif_open)
        }

        val open = Intent(this, AppActivity::class.java)
            .putExtra(AppActivity.EXTRA_ID, host?.app?.id ?: "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(line2)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(getString(R.string.app_name) + (if (pos.isNotEmpty()) "  ·  $pos" else ""))
                    .bigText(if (sub.isNotEmpty()) sub else line2)
            )
            .setContentIntent(openPi)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(R.drawable.ic_prev, "Previous", action(ACT_PREV, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                action(ACT_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_next, "Next", action(ACT_NEXT, 3))
            .addAction(R.drawable.ic_close, "Close", action(ACT_CLOSE, 4))
        return b.build()
    }

    /** Called (on the main thread) whenever the subtitle, the playing state or the screen visibility changed. */
    fun refresh() {
        syncWakeLock()

        val sig = MediaBus.text + "|" + MediaBus.index + "|" + MediaBus.total + "|" + MediaBus.playing
        if (sig != lastSig) {
            lastSig = sig
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification())
            } catch (e: Exception) {
                // notifications blocked
            }
        }

        val ov = overlay ?: return
        if (MediaBus.uiVisible) {
            ov.hide()
        } else {
            ov.show()
            ov.update(MediaBus.text, MediaBus.playing)
        }
    }
}
