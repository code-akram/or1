package com.or1.agent.tools

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/** Injected input on any display (InputManager.injectInputEvent, hidden; needs INJECT_EVENTS, which the platform key grants). */
object Input {
    private const val TAG = "or1-display"

    /** MotionEvent.FLAG_NO_FOCUS_CHANGE (hidden). */
    private const val FLAG_NO_FOCUS_CHANGE = 0x40

    private val inputManager: Any? by lazy {
        try {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        } catch (e: Exception) {
            Log.w(TAG, "no InputManager", e)
            null
        }
    }

    /** [waitForFinish]: block until the target window has handled the event (InputManager mode WAIT_FOR_FINISH). */
    fun inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        inputManager!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(inputManager, event, if (waitForFinish) 2 else 0) as Boolean
    } catch (e: Exception) {
        Log.w(TAG, "injectInputEvent failed", e)
        false
    }

    /**
     * A tap (same start and end) or swipe as injected touch events, for when accessibility gestures don't land.
     * Marked FLAG_NO_FOCUS_CHANGE (honoured for trusted injections), so the touch doesn't move focus to its display.
     */
    fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, displayId: Int): Boolean {
        val down = SystemClock.uptimeMillis()
        val pointer = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        fun ev(action: Int, t: Long, x: Float, y: Float): MotionEvent {
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
            return MotionEvent.obtain(
                down, t, action, 1, pointer, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, FLAG_NO_FOCUS_CHANGE,
            )
        }
        if (!inject(ev(MotionEvent.ACTION_DOWN, down, x1, y1), displayId)) return false
        val steps = if (x1 == x2 && y1 == y2) 0 else 10
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            Thread.sleep(durationMs / steps)
            inject(ev(MotionEvent.ACTION_MOVE, down + durationMs * i / steps, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f), displayId)
        }
        if (steps == 0) Thread.sleep(durationMs)
        return inject(ev(MotionEvent.ACTION_UP, down + durationMs, x2, y2), displayId)
    }
}
