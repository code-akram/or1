package com.or1.agent

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * The user doesn't use apps directly on this phone; the agent does. This closes the system UI's ways into them:
 * home/back/recents buttons, the notification shade and quick settings (Settings gear, tiles, tapping a notification),
 * heads-up pop-ups, notifications on the lock screen, and the camera gestures (double-press power). Notifications reach
 * the user through the chat instead.
 *
 * Status-bar part: StatusBarManager.disable/disable2 (hidden; STATUS_BAR, granted by the platform signature), the
 * mechanism behind kiosk mode. The flags are held against this process, so they lift by themselves if it dies (a crash
 * can't strand the phone). Flag values are read from the platform by name. The power menu stays (power off, restart).
 * Dev: `DebugReceiver --es lockdown off|on` (remembered across restarts).
 */
object Lockdown {
    private const val TAG = "or1-lockdown"
    private const val PREFS = "lockdown"
    private const val KEY_OFF = "off"
    private const val SAVED = "before:"
    /** Secure settings held while locked down, restored on release (the previous values are kept in prefs). */
    private val SETTINGS = mapOf(
        "lock_screen_show_notifications" to 0,
        "camera_double_tap_power_gesture_disabled" to 1,
        "camera_gesture_disabled" to 1,
    )

    private val DISABLE = listOf(
        "DISABLE_HOME", "DISABLE_BACK", "DISABLE_RECENT", "DISABLE_SEARCH", "DISABLE_EXPAND",
        "DISABLE_NOTIFICATION_ICONS", "DISABLE_NOTIFICATION_ALERTS", "DISABLE_NOTIFICATION_TICKER",
    )
    private val DISABLE2 = listOf("DISABLE2_QUICK_SETTINGS", "DISABLE2_NOTIFICATION_SHADE")

    private lateinit var context: Context
    // Kept for the process's life: the disable flags are tied to this manager's token.
    private var statusBar: Any? = null
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val enabled get() = !prefs.getBoolean(KEY_OFF, false)

    fun init(context: Context) {
        this.context = context.applicationContext
        statusBar = context.getSystemService("statusbar")
        if (enabled) apply()
    }

    /** Re-applies (cheap and idempotent), e.g. on resume in case SystemUI restarted. */
    fun apply() {
        if (!::context.isInitialized || !enabled) return
        setFlags(flags(DISABLE), flags(DISABLE2))
        val resolver = context.contentResolver
        for ((key, locked) in SETTINGS) {
            // Remember the value from before the first lockdown only (-1: it wasn't set).
            if (!prefs.contains(SAVED + key)) prefs.edit().putInt(SAVED + key, Settings.Secure.getInt(resolver, key, -1)).apply()
            Settings.Secure.putInt(resolver, key, locked)
        }
    }

    /** Dev escape hatch: lifts everything and remembers it until [setEnabled] true. */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_OFF, !on).apply()
        if (on) {
            apply()
        } else {
            setFlags(0, 0)
            for (key in SETTINGS.keys.filter { prefs.contains(SAVED + it) }) {
                val before = prefs.getInt(SAVED + key, -1)
                if (before == -1) Settings.Secure.putString(context.contentResolver, key, null) else Settings.Secure.putInt(context.contentResolver, key, before)
                prefs.edit().remove(SAVED + key).apply()
            }
        }
        Log.i(TAG, "lockdown ${if (on) "on" else "off"}")
    }

    private fun flags(names: List<String>): Int {
        val cls = Class.forName("android.app.StatusBarManager")
        return names.fold(0) { acc, name ->
            acc or (runCatching { cls.getField(name).getInt(null) }.onFailure { Log.w(TAG, "no $name") }.getOrDefault(0))
        }
    }

    private fun setFlags(what: Int, what2: Int) {
        val bar = statusBar ?: return
        try {
            bar.javaClass.getMethod("disable", Int::class.javaPrimitiveType).invoke(bar, what)
            bar.javaClass.getMethod("disable2", Int::class.javaPrimitiveType).invoke(bar, what2)
        } catch (e: Exception) {
            Log.e(TAG, "StatusBarManager.disable failed", e)
        }
    }
}
