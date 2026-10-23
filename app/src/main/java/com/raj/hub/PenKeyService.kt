package com.raj.hub

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * OPTIONAL. Lets the smart pen's up / down (page) buttons change the subtitle while SHADOWit plays in the
 * background (the floating window is showing). It only listens to those keys, it never reads the screen.
 * Turn it on in Settings > Accessibility > SHADOWit. When the app is on screen the page handles the keys itself.
 */
class PenKeyService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // not used
    }

    override fun onInterrupt() {
        // not used
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only while the app is in the background and playing; otherwise the keys belong to whatever is on screen.
        if (MediaBus.uiVisible || !MediaBus.playing) return false
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -1
            else -> 0
        }
        if (dir == 0) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            WebHost.current()?.mediaCmd(if (dir > 0) "next" else "prev")
        }
        return true
    }
}
