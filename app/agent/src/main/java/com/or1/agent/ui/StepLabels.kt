package com.or1.agent.ui

import org.json.JSONObject

/** Plain-language labels for tool calls, shown in the chat instead of raw tool arguments. */
object StepLabels {
    private val settingNames = mapOf(
        "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "dnd" to "Do Not Disturb", "airplane" to "airplane mode",
        "auto_brightness" to "auto-brightness", "dark_mode" to "dark mode", "volume" to "media volume",
        "brightness" to "brightness", "screen_timeout" to "screen timeout",
    )

    fun label(tool: String, args: JSONObject, elementLabel: (Int) -> String): String {
        val action = args.optString("action")
        return when (tool) {
            "screen" -> if (args.optBoolean("screenshot")) "Looked at the screen" else "Read the screen"
            "act" -> when (action) {
                "tap" -> "Tapped ${target(args, elementLabel)}"
                "long_press" -> "Long-pressed ${target(args, elementLabel)}"
                "type" -> "Typed “${args.optString("text").take(40)}”"
                "scroll" -> "Scrolled ${args.optString("direction", "down")}"
                "key" -> "Pressed ${args.optString("key")}"
                else -> "Used the screen"
            }
            "open" -> when {
                args.optString("package").isNotBlank() -> "Opened ${appName(args.getString("package"))}"
                args.optString("settings_action").isNotBlank() ->
                    "Opened ${args.getString("settings_action").substringAfterLast('.').replace('_', ' ').lowercase()}"
                else -> "Opened ${args.optString("uri").take(40)}"
            }
            "device" -> if (action == "get") "Checked device status" else {
                val setting = args.optString("setting")
                val value = args.optString("value")
                val name = settingNames[setting] ?: setting
                when {
                    value.lowercase() in setOf("on", "off", "true", "false") -> "Turn $name ${if (value.lowercase() in setOf("on", "true")) "on" else "off"}"
                    setting == "screen_timeout" -> "Set screen timeout to ${durationNoun(value.toIntOrNull() ?: 0)}"
                    else -> "Set $name to $value"
                }
            }
            "clock" -> when (action) {
                "set_alarm" -> "Set an alarm for %02d:%02d".format(args.optInt("hour"), args.optInt("minute")) +
                    (args.optString("label").takeIf { it.isNotBlank() }?.let { " “$it”" } ?: "")
                "set_timer" -> "Set a ${duration(args.optInt("seconds"))} timer"
                "dismiss_alarm" -> args.optString("label").takeIf { it.isNotBlank() }?.let { "Turn off the “$it” alarm" } ?: "Turn off all alarms"
                else -> "Checked the next alarm"
            }
            "notifications" -> "Read notifications"
            "apps" -> if (action == "list") "Listed apps" else "Looked up ${appName(args.optString("package"))}"
            "shell" -> "Run: ${args.optString("command").take(60)}"
            "memory" -> when (action) {
                "add" -> "Saved a note"
                "remove" -> "Removed a note"
                else -> "Checked notes"
            }
            else -> tool
        }
    }

    private fun target(args: JSONObject, elementLabel: (Int) -> String): String =
        if (args.has("element")) "“${elementLabel(args.getInt("element"))}”" else "the screen at ${args.optInt("x")},${args.optInt("y")}"

    private fun appName(pkg: String): String = when (pkg) {
        "com.android.settings" -> "Settings"
        "com.android.deskclock" -> "Clock"
        "com.android.contacts" -> "Contacts"
        "com.android.calendar" -> "Calendar"
        "com.android.camera2" -> "Camera"
        "com.android.gallery3d" -> "Gallery"
        "com.android.messaging" -> "Messaging"
        "com.android.dialer" -> "Phone"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** "5-minute" (as in "a 5-minute timer"). */
    private fun duration(seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> "${seconds / 3600}-hour"
        seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60}-minute"
        else -> "$seconds-second"
    }

    /** "2 minutes" (as in "timeout to 2 minutes"). */
    private fun durationNoun(seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> plural(seconds / 3600, "hour")
        seconds >= 60 && seconds % 60 == 0 -> plural(seconds / 60, "minute")
        else -> plural(seconds, "second")
    }

    private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
}
