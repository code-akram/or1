package com.or1.agent.tools

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import com.or1.agent.NotifListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** The longest `open` waits for the new app's screen to settle (see [HiddenDisplay.settle]). */
private const val OPEN_SETTLE_MS = 600L

/** Device state, alarms, apps, notifications and app launching, through platform APIs (no UI driving). */
class SystemTools(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val resolver = context.contentResolver
    private val uiMode = context.getSystemService(UiModeManager::class.java)

    @SuppressLint("MissingPermission")
    fun deviceGet(): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "charging (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "charging (USB)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging (wireless)"
            else -> "not charging"
        }
        val ssid = wifi.connectionInfo?.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID && it.isNotBlank() }
        val bluetooth = context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        val dnd = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val stat = StatFs(Environment.getDataDirectory().path)
        return listOf(
            "time=" + SimpleDateFormat("EEE dd MMM yyyy HH:mm zzz", Locale.US).format(Date()),
            "battery=$level% $plugged",
            "wifi=" + (if (wifi.isWifiEnabled) "on" else "off") + (ssid?.let { " connected to $it" } ?: ""),
            "bluetooth=" + if (bluetooth) "on" else "off",
            "media_volume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",
            "brightness=${Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)} of 255, auto_brightness=" +
                if (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) "on" else "off",
            "dnd=" + if (dnd) "on" else "off",
            "dark_mode=" + if (uiMode.nightMode == UiModeManager.MODE_NIGHT_YES) "on" else "off",
            "airplane=" + if (Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) "on" else "off",
            "screen_timeout=${Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 0) / 1000}s",
            "storage_free=%.1f GB of %.1f GB".format(stat.availableBytes / 1e9, stat.totalBytes / 1e9),
        ).joinToString("\n")
    }

    /** A setting's current value in the tool's terms (on|off or a number); `dnd_filter` is the exact filter, for undo. */
    @SuppressLint("MissingPermission")
    fun currentSetting(setting: String): String {
        fun onOff(b: Boolean) = if (b) "on" else "off"
        return when (setting) {
            "wifi" -> onOff(wifi.isWifiEnabled)
            "bluetooth" -> onOff(context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true)
            "dnd" -> onOff(notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
            "dnd_filter" -> notificationManager.currentInterruptionFilter.toString()
            "airplane" -> onOff(Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            "auto_brightness" -> onOff(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1)
            "dark_mode" -> onOff(uiMode.nightMode == UiModeManager.MODE_NIGHT_YES)
            "volume" -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toString()
            "brightness" -> Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1).toString()
            "screen_timeout" -> (Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 0) / 1000).toString()
            else -> throw IllegalArgumentException("Unknown setting '$setting'.")
        }
    }

    /** Changes a setting; the result's undo puts back what it was (null if it already had that value). */
    fun deviceSet(args: JSONObject): ToolOutput {
        val setting = args.optString("setting").ifBlank { throw IllegalArgumentException("set needs `setting` and `value`.") }
        val value = normalizeSetting(setting, args.optString("value"), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val undo = deviceUndo(setting, value, ::currentSetting)
        applySetting(setting, value)
        return ToolOutput(if (undo == null) "$setting is already $value." else "$setting set to $value (was ${undo.optString("was")}).", undo = undo)
    }

    /** Puts settings back from a [deviceUndo] `restore` list, in order. */
    fun restoreSettings(restore: JSONArray) {
        for (i in 0 until restore.length()) {
            val entry = restore.getJSONObject(i)
            applySetting(entry.getString("setting"), entry.getString("value"))
        }
    }

    /** `value` is normalised ([normalizeSetting]); `dnd_filter` takes a NotificationManager interruption filter. */
    @SuppressLint("MissingPermission")
    private fun applySetting(setting: String, value: String) {
        val on = value == "on"
        when (setting) {
            "wifi" -> check(wifi.setWifiEnabled(on)) { "Wi-Fi change was refused." }
            "bluetooth" -> {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("No Bluetooth adapter.")
                @Suppress("DEPRECATION")
                check(if (on) adapter.enable() else adapter.disable()) { "Bluetooth change was refused." }
            }
            "dnd" -> notificationManager.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            "dnd_filter" -> notificationManager.setInterruptionFilter(value.toInt())
            "airplane" -> ConnectivityManager::class.java.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType)
                .invoke(context.getSystemService(ConnectivityManager::class.java), on)
            "auto_brightness" -> Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0)
            "dark_mode" -> uiMode.nightMode = if (on) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            "volume" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
            "brightness" -> {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value.toInt())
            }
            "screen_timeout" -> Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, value.toInt() * 1000)
            else -> throw IllegalArgumentException("Unknown setting '$setting'.")
        }
    }

    suspend fun clock(args: JSONObject): ToolOutput {
        val label = args.optString("label").takeIf { it.isNotBlank() }
        val intent = when (val action = args.getString("action")) {
            "next_alarm" -> return ToolOutput(nextAlarm())
            "stop_timer" -> return ToolOutput(stopTimer(label))
            "set_alarm" -> {
                val hour = args.optInt("hour", -1)
                val minute = args.optInt("minute", 0)
                require(hour in 0..23 && minute in 0..59) { "set_alarm needs `hour` 0-23 and `minute` 0-59." }
                Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            }
            "set_timer" -> {
                val seconds = args.optInt("seconds", 0)
                require(seconds in 1..86400) { "set_timer needs `seconds` between 1 and 86400." }
                Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            }
            "dismiss_alarm" -> Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(
                AlarmClock.EXTRA_ALARM_SEARCH_MODE,
                if (label != null) AlarmClock.ALARM_SEARCH_MODE_LABEL else AlarmClock.ALARM_SEARCH_MODE_ALL,
            )
            else -> throw IllegalArgumentException("Unknown action '$action'.")
        }
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        // On the hidden display, so any UI the Clock app shows despite SKIP_UI stays out of sight.
        HiddenDisplay.start(intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        val undo = JSONObject().put("label", label)
        if (args.getString("action") == "set_timer") {
            val seconds = args.getInt("seconds")
            return ToolOutput("Timer set for $seconds seconds.", undo = undo.put("op", "timer").put("seconds", seconds))
        }
        Thread.sleep(1500)
        val alarm = if (args.getString("action") != "set_alarm") null else
            undo.put("op", "alarm").put("hour", args.getInt("hour")).put("minute", args.optInt("minute"))
        return ToolOutput(nextAlarm(), undo = alarm)
    }

    /**
     * Undo of set_alarm: asks the Clock app to dismiss the alarm by its label, else by its time (it only dismisses alarms
     * due within 24 hours, which a set_alarm one always is). Throws if the next alarm is still that one afterwards.
     */
    fun dismissAlarm(hour: Int, minute: Int, label: String?): String {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val before = alarms.nextAlarmClock?.triggerTime
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, label)
        } else {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
        }
        HiddenDisplay.start(intent)
        Thread.sleep(1500)
        val after = alarms.nextAlarmClock?.triggerTime
        if (before != null && after == before) {
            val time = java.util.Calendar.getInstance().apply { timeInMillis = after }
            check(time.get(java.util.Calendar.HOUR_OF_DAY) != hour || time.get(java.util.Calendar.MINUTE) != minute) {
                "The Clock app didn't turn the alarm off."
            }
        }
        return "Turned off the %02d:%02d alarm.".format(hour, minute)
    }

    /**
     * stop_timer: silences a ringing timer (its notification's Stop button, else DISMISS_TIMER) or cancels a running one
     * ([cancelTimer]). Through notifications, not the Clock app's screen, so it never covers what's on the work display
     * (a video playing there would stop).
     */
    private suspend fun stopTimer(label: String?): String {
        val listener = NotifListener.instance ?: error("Notification access is off, so the timer can't be reached.")
        val clock = Intent(AlarmClock.ACTION_SET_TIMER).resolveActivity(context.packageManager)?.packageName ?: error("No Clock app.")
        val stops = listener.activeNotifications.filter { it.packageName == clock }.mapNotNull { sbn ->
            sbn.notification.actions?.firstOrNull { it.title.toString().lowercase() in setOf("stop", "stop all", "dismiss") }
        }
        if (stops.isNotEmpty()) {
            stops.forEach { it.actionIntent.send() }
            return if (stops.size == 1) "Stopped the ringing timer." else "Stopped ${stops.size} ringing timers."
        }
        if (listener.activeNotifications.none { it.packageName == clock && !it.notification.actions.isNullOrEmpty() }) {
            // A ringing timer whose notification has no Stop button (or none showing yet): ask the Clock app.
            HiddenDisplay.start(Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            return "Asked the Clock app to stop any ringing timer; no timer was running."
        }
        return cancelTimer(label)
    }

    /**
     * Undo of set_timer. AlarmClock has no intent to cancel a running timer (DISMISS_TIMER only silences a ringing one),
     * so this presses the Clock app's own notification buttons through the listener: Reset, or Pause and then Reset (a
     * running timer's notification offers Pause, a paused one Reset). Matches the label when there is one. The buttons
     * are found by their English titles; with several timers running, "Reset all" resets them all. The Clock app hides
     * that notification while its own screen is in front, so the caller parks the work display first when it can.
     */
    suspend fun cancelTimer(label: String?): String {
        val listener = NotifListener.instance ?: error("Notification access is off, so the timer can't be reached.")
        val clock = Intent(AlarmClock.ACTION_SET_TIMER).resolveActivity(context.packageManager)?.packageName ?: error("No Clock app.")
        repeat(5) { attempt ->
            val timers = listener.activeNotifications.filter { it.packageName == clock && !it.notification.actions.isNullOrEmpty() }
            if (timers.isEmpty() && attempt < 4) {
                delay(800)
                return@repeat
            }
            val notification = timers.firstOrNull { sbn ->
                label != null && sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.contains(label, ignoreCase = true) == true
            } ?: timers.singleOrNull() ?: error(if (timers.isEmpty()) "No timer is running." else "Couldn't tell which timer it was.")
            val actions = notification.notification.actions.associateBy { it.title.toString().lowercase() }
            val reset = actions["reset"] ?: actions["reset all"]
            if (reset != null) {
                reset.actionIntent.send()
                return "Cancelled the timer."
            }
            (actions["pause"] ?: actions["pause all"] ?: error("The timer has no Pause or Reset button.")).actionIntent.send()
            delay(800)
        }
        error("The timer didn't stop.")
    }

    private fun nextAlarm(): String {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return "No alarm is set."
        return "Next alarm: " + SimpleDateFormat("EEE dd MMM HH:mm", Locale.US).format(Date(next.triggerTime)) + "."
    }

    fun notifications(): String {
        val listener = NotifListener.instance ?: return "Notification access is not enabled for the agent."
        val format = SimpleDateFormat("HH:mm dd MMM", Locale.US)
        val lines = listener.activeNotifications.filter { it.packageName != context.packageName }.mapNotNull { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = (extras.getCharSequence("android.bigText") ?: extras.getCharSequence("android.text"))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) null else "- ${sbn.packageName} [${format.format(Date(sbn.postTime))}] $title: $text"
        }
        return lines.joinToString("\n").ifEmpty { "No notifications." }
    }

    fun apps(args: JSONObject): String {
        val pm = context.packageManager
        if (args.getString("action") == "list") {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }
                .distinct().sorted()
            return "Launchable apps:\n" + apps.joinToString("\n")
        }
        val pkg = args.optString("package").ifBlank { throw IllegalArgumentException("info needs `package`.") }
        val info = try {
            pm.getPackageInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return "Package $pkg not found."
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return listOf(
            "label=${info.applicationInfo?.loadLabel(pm)}",
            "version=${info.versionName}",
            "installed=${format.format(Date(info.firstInstallTime))}, updated=${format.format(Date(info.lastUpdateTime))}",
            "enabled=${info.applicationInfo?.enabled}",
            "system_app=${(info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0}",
        ).joinToString("\n")
    }

    /** Opens on the hidden display (see [HiddenDisplay]); the user stays in the chat. */
    suspend fun open(args: JSONObject, screen: ScreenTools): ToolOutput {
        val intent = when {
            args.optString("package").isNotBlank() -> {
                val pkg = args.getString("package")
                context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: throw IllegalArgumentException("$pkg has no launchable activity (is the package name right? see apps list).")
            }
            // A fresh task, so the requested page shows even if the app is already open on another page.
            args.optString("uri").isNotBlank() ->
                Intent(Intent.ACTION_VIEW, Uri.parse(args.getString("uri"))).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            args.optString("settings_action").isNotBlank() ->
                Intent(args.getString("settings_action")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            else -> throw IllegalArgumentException("open needs `package`, `uri` or `settings_action`.")
        }
        // The package we expect in front afterwards; null when a chooser would pick (or nothing resolves yet).
        val expect = intent.resolveActivity(context.packageManager)?.packageName?.takeIf { it != "android" }
        if (HiddenDisplay.ensure() == null) screen.dismissShade()
        HiddenDisplay.launch(intent, expect)
        HiddenDisplay.settle(OPEN_SETTLE_MS)
        return screen.screen(false)
    }
}

/** [value] for [setting] as the tool reports it and undo restores it: on|off, or the number (volume and brightness clamped). */
internal fun normalizeSetting(setting: String, value: String, maxVolume: Int): String {
    val v = value.trim().lowercase()
    fun number(): Int = v.toIntOrNull() ?: throw IllegalArgumentException("$setting takes a number.")
    return when (setting) {
        "wifi", "bluetooth", "dnd", "airplane", "auto_brightness", "dark_mode" -> when (v) {
            "on", "true", "1" -> "on"
            "off", "false", "0" -> "off"
            else -> throw IllegalArgumentException("$setting takes on|off.")
        }
        "volume" -> number().coerceIn(0, maxVolume).toString()
        "brightness" -> number().coerceIn(0, 255).toString()
        "screen_timeout" -> number().toString()
        else -> throw IllegalArgumentException("Unknown setting '$setting'.")
    }
}

/**
 * The undo of setting [setting] to [next], from the values [current] reads before the change: `restore` lists what to
 * put back, in order (setting brightness also turns auto-brightness off, so that comes back after it; DND comes back
 * as its exact interruption filter). `value` and `was` are the new and old values, for labels. Null when nothing changes.
 */
internal fun deviceUndo(setting: String, next: String, current: (String) -> String): JSONObject? {
    val was = current(setting)
    val autoWas = if (setting == "brightness") current("auto_brightness") else null
    if (was == next && autoWas != "on") return null
    val restore = JSONArray().put(
        if (setting == "dnd") JSONObject().put("setting", "dnd_filter").put("value", current("dnd_filter"))
        else JSONObject().put("setting", setting).put("value", was)
    )
    if (autoWas == "on") restore.put(JSONObject().put("setting", "auto_brightness").put("value", "on"))
    return JSONObject().put("op", "device").put("setting", setting).put("value", next).put("was", was).put("restore", restore)
}
