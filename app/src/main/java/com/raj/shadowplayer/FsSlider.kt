package com.raj.shadowplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Thin horizontal line with a ring-shaped thumb (full screen video: position bar and volume bar).
 * Values are fractions 0..1. [onStart] / [onChange] / [onEnd] only fire for the user's finger.
 */
class FsSlider(ctx: Context) : View(ctx) {
    var onStart: (() -> Unit)? = null
    var onChange: ((Float) -> Unit)? = null
    var onEnd: ((Float) -> Unit)? = null
    var dragging = false
        private set

    private var frac = 0f
    private var leftText = ""
    private var rightText = ""
    private val d = ctx.resources.displayMetrics.density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * d
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 0, 0, 0)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 255, 255, 255)
        textSize = 12f * ctx.resources.displayMetrics.scaledDensity
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private fun pad(): Float = 18f * d

    fun setFraction(f: Float) {
        if (dragging) return
        val n = f.coerceIn(0f, 1f)
        if (n != frac) {
            frac = n
            invalidate()
        }
    }

    fun setLabels(l: String, r: String) {
        if (l != leftText || r != rightText) {
            leftText = l
            rightText = r
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val x0 = pad()
        val x1 = w - pad()
        val cy = h * 0.64f
        val tx = x0 + (x1 - x0) * frac
        track.color = Color.argb(95, 255, 255, 255)
        c.drawLine(x0, cy, x1, cy, track)
        track.color = Color.argb(235, 255, 255, 255)
        c.drawLine(x0, cy, tx, cy, track)
        val r = (if (dragging) 10f else 8f) * d
        c.drawCircle(tx, cy, r, fill)
        c.drawCircle(tx, cy, r, ring)
        val ty = cy - 16f * d
        text.textAlign = Paint.Align.LEFT
        if (leftText.isNotEmpty()) c.drawText(leftText, x0, ty, text)
        text.textAlign = Paint.Align.RIGHT
        if (rightText.isNotEmpty()) c.drawText(rightText, x1, ty, text)
    }

    private fun setFromX(x: Float) {
        val x0 = pad()
        val x1 = width - pad()
        frac = if (x1 > x0) ((x - x0) / (x1 - x0)).coerceIn(0f, 1f) else 0f
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                setFromX(e.x)
                onStart?.invoke()
                onChange?.invoke(frac)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    setFromX(e.x)
                    onChange?.invoke(frac)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    setFromX(e.x)
                    dragging = false
                    invalidate()
                    onEnd?.invoke(frac)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    invalidate()
                    onEnd?.invoke(frac)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
