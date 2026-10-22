package com.raj.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Tiny floating subtitle window (needs "Display over other apps").
 * Shows only the current subtitle with previous / play-pause / next. Fully visible. Tap the text to copy the subtitle.
 * Drag it by the text to move it; the position is remembered.
 */
class FloatingSubtitle(private val ctx: Context, private val onCmd: (String) -> Unit) {

    companion object {
        /** How visible the window is when you are not touching it. 0.15 = 15%. */
        const val IDLE_ALPHA = 1.0f

        /** How visible it gets for a moment when you touch it, so you can see what you tap. */
        const val TOUCH_ALPHA = 1.0f
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = ctx.getSharedPreferences("rajhub", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var playBtn: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var text = ""
    private var playing = false

    private val dim = Runnable { root?.alpha = IDLE_ALPHA }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (root != null) return
        if (!Settings.canDrawOverlays(ctx)) return
        try {
            val r = build()
            wm.addView(r, params)
            root = r
        } catch (e: Exception) {
            root = null
            label = null
            playBtn = null
        }
        apply()
    }

    fun hide() {
        main.removeCallbacks(dim)
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (e: Exception) {
            // already gone
        }
        root = null
        label = null
        playBtn = null
    }

    fun update(newText: String, isPlaying: Boolean) {
        text = newText
        playing = isPlaying
        apply()
    }

    private fun apply() {
        label?.text = if (text.isBlank()) "SHADOWit" else text
        playBtn?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /** Tap on the subtitle text = copy it. */
    private fun copyText() {
        val t = text.trim()
        if (t.isEmpty()) return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("subtitle", t))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // clipboard not available
        }
    }

    private fun highlight() {
        val r = root ?: return
        r.alpha = TOUCH_ALPHA
        main.removeCallbacks(dim)
        main.postDelayed(dim, 2500)
    }

    private fun button(res: Int, cmd: String): ImageView {
        val b = ImageView(ctx)
        b.setImageResource(res)
        b.setPadding(dp(5), dp(5), dp(5), dp(5))
        b.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        b.setOnClickListener {
            highlight()
            onCmd(cmd)
        }
        return b
    }

    private fun build(): LinearLayout {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = prefs.getInt("float_x", dp(12))
        p.y = prefs.getInt("float_y", dp(96))
        params = p

        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#111111"))
        bg.cornerRadius = dp(14).toFloat()
        r.background = bg
        r.setPadding(dp(4), dp(2), dp(10), dp(2))
        r.alpha = IDLE_ALPHA

        r.addView(button(R.drawable.ic_prev, "prev"))
        val pb = button(R.drawable.ic_pause, "toggle")
        playBtn = pb
        r.addView(pb)
        r.addView(button(R.drawable.ic_next, "next"))

        val t = TextView(ctx)
        t.setTextColor(Color.WHITE)
        t.textSize = 11f
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        t.maxWidth = dp(170)
        t.setPadding(dp(4), 0, 0, 0)
        label = t
        r.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        t.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    highlight()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - downX) > dp(8) || Math.abs(e.rawY - downY) > dp(8)) moved = true
                    p.x = startX + (e.rawX - downX).toInt()
                    p.y = startY + (e.rawY - downY).toInt()
                    try {
                        wm.updateViewLayout(r, p)
                    } catch (ex: Exception) {
                        // window already removed
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt("float_x", p.x).putInt("float_y", p.y).apply()
                    } else {
                        copyText()
                    }
                }
            }
            true
        }
        return r
    }
}

/**
 * An invisible, touch-through overlay window that the page is parked in while the app screen is gone
 * (cleared from Recents) but still playing. A page that is attached to a window is not slowed down by Android.
 */
class WebAnchor(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var box: FrameLayout? = null

    fun show(web: View): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        return try {
            val dm = ctx.resources.displayMetrics
            val f = FrameLayout(ctx)
            f.addView(web, FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels))
            val lp = WindowManager.LayoutParams(
                dm.widthPixels,
                dm.heightPixels,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.alpha = 0f
            wm.addView(f, lp)
            box = f
            true
        } catch (e: Exception) {
            box = null
            (web.parent as? ViewGroup)?.removeView(web)
            false
        }
    }

    fun remove(web: View) {
        val f = box
        (web.parent as? ViewGroup)?.removeView(web)
        if (f != null) {
            try {
                wm.removeView(f)
            } catch (e: Exception) {
                // already gone
            }
        }
        box = null
    }
}
