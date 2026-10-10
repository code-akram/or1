package com.or1.agent.tools

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * Keeps apps on the hidden work display quiet while the agent works: their PLAY_AUDIO app-op is set to ignored
 * (AudioService then mutes their players). Everything is restored when the display is parked at the end of a request
 * (a parked display's apps are stopped anyway), when the user brings an app to the front, or while they watch it in
 * the viewer. The muted set is kept in prefs so a crash can't leave an app muted: [init] restores them all.
 * Left alone: the alarm app (its alarms must ring even if the agent just set one through it), and apps sharing a system
 * uid (Settings runs as system), since the op would apply to that whole uid. setMode is a system API: reflection, with
 * MANAGE_APP_OPS_MODES (platform signature).
 */
object HiddenAudio {
    private const val TAG = "or1-display"
    private const val OP = "android:play_audio"
    private const val PREFS = "hidden-audio"
    private const val KEY = "muted"

    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        this.context = context.applicationContext
        // Anything still muted is left over from a previous process.
        muted().forEach { setMode(it, AppOpsManager.MODE_DEFAULT) }
        prefs.edit().remove(KEY).apply()
    }

    private fun muted(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    /** Packages that handle "set an alarm": never muted. */
    private val alarmApps by lazy {
        context.packageManager.queryIntentActivities(android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    @Synchronized
    fun mute(pkg: String?) {
        if (pkg == null || pkg == context.packageName || pkg in muted() || pkg in alarmApps) return
        if (setMode(pkg, AppOpsManager.MODE_IGNORED)) {
            prefs.edit().putStringSet(KEY, muted() + pkg).apply()
            Log.i(TAG, "muted $pkg on the hidden display")
        }
    }

    @Synchronized
    fun unmute(pkg: String?) {
        if (pkg == null || pkg !in muted()) return
        setMode(pkg, AppOpsManager.MODE_DEFAULT)
        prefs.edit().putStringSet(KEY, muted() - pkg).apply()
        Log.i(TAG, "unmuted $pkg")
    }

    @Synchronized
    fun unmuteAll() = muted().forEach { unmute(it) }

    private fun setMode(pkg: String, mode: Int): Boolean = try {
        val uid = context.packageManager.getPackageUid(pkg, 0)
        if (uid < Process.FIRST_APPLICATION_UID) {
            false
        } else {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.javaClass.getMethod("setMode", String::class.java, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(ops, OP, uid, pkg, mode)
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not set PLAY_AUDIO for $pkg", e)
        false
    }
}
