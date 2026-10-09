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
import org.json.JSONObject

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

    @SuppressLint("MissingPermission")
    fun deviceSet(args: JSONObject): String {
        val setting = args.optString("setting").ifBlank { throw IllegalArgumentException("set needs `setting` and `value`.") }
        val value = args.optString("value").trim().lowercase()
        fun onOff(): Boolean = when (value) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> throw IllegalArgumentException("$setting takes on|off.")
        }
        fun number(): Int = value.toIntOrNull() ?: throw IllegalArgumentException("$setting takes a number.")
        when (setting) {
            "wifi" -> check(wifi.setWifiEnabled(onOff())) { "Wi-Fi change was refused." }
            "bluetooth" -> {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("No Bluetooth adapter.")
                @Suppress("DEPRECATION")
                check(if (onOff()) adapter.enable() else adapter.disable()) { "Bluetooth change was refused." }
            }
            "dnd" -> notificationManager.setInterruptionFilter(
                if (onOff()) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            "airplane" -> ConnectivityManager::class.java.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType)
                .invoke(context.getSystemService(ConnectivityManager::class.java), onOff())
            "auto_brightness" -> Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (onOff()) 1 else 0)
            "dark_mode" -> uiMode.nightMode = if (onOff()) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            "volume" -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, number().coerceIn(0, max), 0)
            }
            "brightness" -> {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, number().coerceIn(0, 255))
            }
            "screen_timeout" -> Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, number() * 1000)
            else -> throw IllegalArgumentException("Unknown setting '$setting'.")
        }
        return "$setting set to ${args.optString("value")}."
    }

    fun clock(args: JSONObject): String {
        val label = args.optString("label").takeIf { it.isNotBlank() }
        val intent = when (val action = args.getString("action")) {
            "next_alarm" -> return nextAlarm()
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
        if (args.getString("action") == "set_timer") return "Timer set for ${args.getInt("seconds")} seconds."
        Thread.sleep(1500)
        return nextAlarm()
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
        delay(600)
        return screen.screen(false)
    }
}
