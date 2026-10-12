package com.or1.agent.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log

/**
 * Root tasks across displays, through the hidden IActivityTaskManager (reflection; platform-signed apps are exempt
 * from the hidden-API blocklist): which tasks are on a display, moving one to another display, focus, and freeing
 * displays an earlier process left behind.
 */
object Tasks {
    private const val TAG = "or1-display"

    private val atm: Any? by lazy {
        try {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        } catch (e: Exception) {
            Log.w(TAG, "no ActivityTaskManager service", e)
            null
        }
    }

    private val info by lazy { Class.forName("android.app.TaskInfo") }

    private fun rootInfos(displayId: Int): List<*> =
        atm!!.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType).invoke(atm, displayId) as List<*>

    /** Task ids of root tasks on [displayId], topmost first, paired with their top activity's package. */
    fun on(displayId: Int): List<Pair<Int, String?>> = try {
        rootInfos(displayId).filterNotNull().map { task ->
            info.getField("taskId").getInt(task) to (info.getField("topActivity").get(task) as ComponentName?)?.packageName
        }
    } catch (e: Exception) {
        Log.w(TAG, "getAllRootTaskInfosOnDisplay failed", e)
        emptyList()
    }

    /** The task of [pkg] on [displayId], topmost first. */
    fun topOf(pkg: String, displayId: Int): Int? = on(displayId).firstOrNull { it.second == pkg }?.first

    /** The base intent of the top task on [displayId]. */
    fun topIntent(displayId: Int): Intent? = try {
        rootInfos(displayId).firstOrNull()?.let { info.getField("baseIntent").get(it) as Intent? }?.let { Intent(it) }
    } catch (e: Exception) {
        Log.w(TAG, "no top task on display $displayId", e)
        null
    }

    /** Moves a root task (and its activities, without relaunching them when the displays match) to [displayId]. */
    fun move(taskId: Int, displayId: Int): Boolean = try {
        atm!!.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(atm, taskId, displayId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "moveRootTaskToDisplay($taskId, $displayId) failed", e)
        false
    }

    fun setFocused(taskId: Int): Boolean = try {
        atm!!.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType).invoke(atm, taskId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "setFocusedTask($taskId) failed", e)
        false
    }

    /**
     * Frees hidden displays left behind by an earlier process of this app. DisplayManager drops a virtual display
     * when its owner dies, but WindowManager keeps the display until its last root task is gone. Apps that embed
     * activities (Settings) leave an empty task there: their activities finish, but the TaskFragments they organize
     * stay until the organizer removes them, which it never does for a removed display. Force-stopping the app
     * makes WindowManager drop its TaskFragments, the task, and then the display. Skipped for an app that still has a
     * task on a live display, so this never closes something the user has open.
     */
    fun releaseStale(context: Context) {
        try {
            val live = context.getSystemService(DisplayManager::class.java).displays.map { it.displayId }.toSet()
            val infos = atm!!.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
            val tasks = infos.filterNotNull().map { task ->
                val intent = info.getField("baseIntent").get(task) as Intent?
                val pkg = intent?.component?.packageName ?: (info.getField("realActivity").get(task) as ComponentName?)?.packageName
                info.getField("displayId").getInt(task) to pkg
            }
            val (stale, current) = tasks.partition { it.first !in live }
            val inUse = current.mapNotNull { it.second }.toSet()
            val packages = stale.mapNotNull { it.second }.toSet() - inUse - context.packageName
            if (stale.isEmpty()) return
            Log.i(TAG, "stale displays ${stale.map { it.first }.distinct()}; force-stopping $packages")
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            for (pkg in packages) am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
        } catch (e: Exception) {
            Log.w(TAG, "could not release stale displays", e)
        }
    }
}
