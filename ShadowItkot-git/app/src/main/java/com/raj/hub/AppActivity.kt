package com.raj.hub

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The screen. The page and the player live in [WebHost] (so they keep playing in the background);
 * this activity just shows them. Press Back twice quickly to exit.
 */
class AppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "app_id"
    }

    lateinit var root: FrameLayout

    private var host: WebHost? = null
    private var lastBack = 0L
    private var ready = false

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        host?.onChooserResult(r.resultCode, r.data)
    }

    // remembered video (played by the native ExoPlayer, see NativeVideo.kt)
    private val videoPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == RESULT_OK && uri != null) {
            host?.let { it.nv.onPicked(uri) }
        }
    }

    fun launchChooser(i: Intent) {
        chooser.launch(i)
    }

    fun launchVideoPicker(i: Intent) {
        videoPicker.launch(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AppStore(this)

        val id = intent.getStringExtra(EXTRA_ID)
        val found = AppRegistry.load(this).firstOrNull { it.id == id }
        if (found == null || !store.isInstalled(found)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = lp

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B1020"))
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val h = WebHost.obtain(this, found)
        host = h
        h.attach(this, column)

        // Full screen: the page draws edge to edge (under the camera notch too). Only the keyboard pushes it up.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, 0, 0, ime.bottom)
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            h.nv.setInsets(cut.left, cut.top, cut.right)
            insets
        }

        hideSystemBars()
        ready = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cur = host ?: return
                if (cur.nv.handleBack()) return
                if (cur.hideCustomView()) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastBack < 2000) {
                    finish()
                } else {
                    lastBack = now
                    Toast.makeText(this@AppActivity, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // starts the background player (notification, wake lock, floating window)
        PlaybackService.start(this)
    }

    private fun hideSystemBars() {
        // ==== ANCHOR: AppActivity.hideSystemBars (full screen, no top bar) ====
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ready) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        host?.setUiVisible(true)
    }

    override fun onStop() {
        // Going to the background / locking the screen does NOT pause anything. The sound keeps playing.
        host?.setUiVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        host?.let {
            it.web.onResume()
            it.web.resumeTimers()
        }
    }

    override fun onPause() {
        // Deliberately no web.onPause() and no player pause: that is what stopped the sound before.
        host?.nv?.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.nv?.relayout()
    }

    override fun onDestroy() {
        val h = host
        if (h != null) {
            h.detach(this)
            // Still playing (cleared from Recents, or screen closed): keep going in the background.
            // Not playing: close everything so nothing stays running for nothing.
            if (!MediaBus.playing) {
                WebHost.shutdown()
                PlaybackService.stop(this)
            }
        }
        super.onDestroy()
    }
}
