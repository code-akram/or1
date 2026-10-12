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
                else -> "Opened ${args.optString("uri").take(60)}"
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
                "stop_timer" -> "Stop the timer"
                else -> "Checked the next alarm"
            }
            "media" -> when (action) {
                "play" -> "Play"
                "pause" -> "Pause"
                "next" -> "Skip to the next"
                "previous" -> "Back to the previous"
                else -> "Checked what's playing"
            }
            "calendar" -> when (action) {
                "add" -> "Add “${args.optString("title").take(40)}” to the calendar on ${args.optString("start").replace('T', ' ')}"
                "delete" -> "Delete calendar event ${args.optLong("event_id")}"
                "move" -> "Move calendar event ${args.optLong("event_id")} to ${args.optString("start").replace('T', ' ')}"
                else -> "Checked the calendar"
            }
            "contacts" -> if (action == "add") {
                "Add contact ${args.optString("name").take(40)}" + listOf("phone", "email").mapNotNull { k ->
                    args.optString(k).takeIf { it.isNotBlank() }
                }.joinToString("") { ", $it" }
            } else "Looked up “${args.optString("query").take(40)}” in contacts"
            "routines" -> when (action) {
                "add" -> "Create a routine: ${routineTrigger(args)}, “${args.optString("prompt").take(60)}”"
                "remove" -> "Remove routine ${args.optString("id")}"
                else -> "Checked routines"
            }
            "notifications" -> "Read notifications"
            "apps" -> if (action == "list") "Listed apps" else "Looked up ${appName(args.optString("package"))}"
            "shell" -> "Run: ${args.optString("command").take(60)}"
            "memory" -> when (action) {
                "add" -> "Save a note: “${args.optString("text").take(60)}”"
                "remove" -> "Remove note ${args.optInt("index")}"
                else -> "Checked notes"
            }
            else -> tool
        }
    }

    /** Labels of steps that only move around or look (read, scroll, a key press): never what an act did. */
    fun isNavigation(label: String): Boolean =
        label == "Read the screen" || label == "Looked at the screen" || label.startsWith("Scrolled ") || label.startsWith("Pressed ")

    /** What a finished act's header names: its last step that isn't navigation, else its last step. */
    fun summary(labels: List<String>): String? = labels.lastOrNull { !isNavigation(it) } ?: labels.lastOrNull()

    private fun routineTrigger(args: JSONObject): String = when {
        args.optInt("in_minutes", 0) > 0 -> "in ${durationNoun(args.getInt("in_minutes") * 60)}"
        args.optString("at").isNotBlank() -> when (args.optString("repeat", "once")) {
            "daily" -> "every day at ${args.getString("at")}"
            "weekdays" -> "weekdays at ${args.getString("at")}"
            "weekly" -> "weekly at ${args.getString("at")}" + (args.optString("date").takeIf { it.isNotBlank() }?.let { " from $it" } ?: "")
            else -> "at ${args.getString("at")}" + (args.optString("date").takeIf { it.isNotBlank() }?.let { " on $it" } ?: "")
        }
        else -> "on notifications" + (args.optString("app").takeIf { it.isNotBlank() }?.let { " from $it" } ?: "") +
            (args.optString("text_contains").takeIf { it.isNotBlank() }?.let { " containing “$it”" } ?: "")
    }

    private fun target(args: JSONObject, elementLabel: (Int) -> String): String =
        if (args.has("element")) elementLabel(args.getInt("element")) else "the screen at ${args.optInt("x")},${args.optInt("y")}"

    private fun appName(pkg: String): String = when (pkg) {
        "com.android.settings" -> "Settings"
        "com.android.deskclock" -> "Clock"
        "com.android.contacts" -> "Contacts"
        "com.android.calendar", "ws.xsoh.etar" -> "Calendar"
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
