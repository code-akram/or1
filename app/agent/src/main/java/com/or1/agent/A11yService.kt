package com.or1.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.or1.agent.tools.HiddenDisplay

/** The agent's eyes and hands: reads the window tree and performs gestures and actions. Enabled by provision.sh. */
class A11yService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            HiddenDisplay.onWindowsChanged(event.displayId)
        }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        var instance: A11yService? = null
    }
}
