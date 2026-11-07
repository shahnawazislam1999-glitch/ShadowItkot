package com.raj.shadowplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The full screen player. Opens in landscape (or portrait if you chose that last time) with every control hidden. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var ui: PlayerUi
    private var pendingKind = "main"

    private val subsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) ui.onSubsPicked(pendingKind, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui = PlayerUi(this)
        ui.requestVideo = { delta -> openRelative(delta) }
        requestedOrientation = ui.fsOrientation()
        setContentView(ui.panel)
        hideBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!ui.handleBack()) finish()
            }
        })

        val item = resolveItem(intent)
        if (item == null) {
            finish()
            return
        }
        ui.open(item)
    }

    private fun resolveItem(i: Intent?): VideoItem? {
        val uri = i?.data
        if (i != null && i.action == Intent.ACTION_VIEW && uri != null) {
            // opened from a file manager / another app
            var name = "video"
            var size = 0L
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0) name = c.getString(ni) ?: name
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (e: Exception) {
                // keep defaults
            }
            if (name == "video") name = uri.lastPathSegment ?: name
            val path = if (uri.scheme == "file") uri.path else null
            val item = VideoItem("u" + uri.toString().hashCode(), uri, name, 0L, size, "", 0L, path)
            Playlist.items = listOf(item)
            Playlist.index = 0
            return item
        }
        return Playlist.items.getOrNull(Playlist.index)
    }

    /** Previous (-1) / next (+1) video of the list. */
    private fun openRelative(delta: Int): Boolean {
        val n = Playlist.index + delta
        if (n < 0 || n >= Playlist.items.size) return false
        Playlist.index = n
        val item = Playlist.items[n]
        ui.panel.post { ui.open(item) }
        return true
    }

    fun launchSubsPicker(kind: String) {
        pendingKind = kind
        subsPicker.launch(arrayOf("*/*"))     // .srt has no Android MIME type, so never filter
    }

    fun askAllFilesAccess() {
        com.raj.shadowplayer.askAllFilesAccess(this)
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        ui.onResume()
    }

    override fun onPause() {
        ui.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.release()
        super.onDestroy()
    }
}
