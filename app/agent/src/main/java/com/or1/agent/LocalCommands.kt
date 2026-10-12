package com.or1.agent

import org.json.JSONObject

/**
 * Simple spoken commands done on the phone without the model: open an app, pause, play, next, previous, the volume,
 * a timer, an alarm, stop the timer, Do Not Disturb, Wi-Fi, Bluetooth, the time and the date. Matching is strict: the
 * whole request has to be one of these (anything with more in it, "open YouTube and play…", goes to the model), and
 * any doubt (an app name that isn't one app, an alarm time without am or pm) goes to the model too. JVM-tested.
 */
object LocalCommands {
    sealed interface Command

    /** One tool call; [reply] says what happened from its result (null: the result's first line). */
    data class Call(val tool: String, val args: JSONObject, val reply: (String) -> String?) : Command

    /** Answered from the phone's own state: the time or the date. */
    data class Answer(val kind: Kind) : Command {
        enum class Kind { TIME, DATE }
    }

    /**
     * The media volume: up or down by [steps] (an eighth of the range each), or to [percent] of it, or to [level] (0 to
     * the maximum). Exactly one is set.
     */
    data class Volume(val steps: Int = 0, val percent: Int? = null, val level: Int? = null) : Command

    /** Polite words around a command: "please", "can you", "for me". */
    private val lead = Regex("^(?:(?:hey|ok|okay)\\s+)?(?:(?:please|can you|could you|would you|will you|just|now)\\s+)*")
    private val tail = Regex("(?:\\s+(?:please|for me|now|again|thanks|thank you))+$")

    /** Lower case, words only ("Wi-Fi" → "wi-fi", "5:30 p.m." → "5:30 pm"), polite words off. */
    fun normalize(text: String): String {
        var t = text.lowercase().trim()
            .replace(Regex("\\b([ap])\\.m\\.?"), "$1m")
            .replace(Regex("[,.!?;\"“”]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        t = t.replace(lead, "").replace(tail, "").trim()
        return t
    }

    /**
     * The command [text] is, or null. [app] finds the one launchable package a spoken name means (null: none, or more
     * than one).
     */
    fun match(text: String, app: (String) -> String?): Command? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        Regex("^(?:open|launch|start) (?:the |my )?(.+?)(?: app)?$").matchEntire(t)?.groupValues?.get(1)
            ?.takeIf { " and " !in " $it " && it.split(' ').size <= 3 }
            ?.let { name -> app(name)?.let { pkg -> return Call("open", JSONObject().put("package", pkg).put("show", true)) { "Opening ${title(name)}." } } }
        if (Regex("^pause(?: (?:it|this|that|the (?:music|song|video|track|playback)|youtube|the youtube video))?$|^stop (?:the )?(?:music|song|video|playback)$").matches(t)) {
            return media("pause") { result -> if (result.startsWith("Nothing is playing")) "Nothing is playing." else "Paused." }
        }
        if (Regex("^(?:play|resume|unpause|continue)(?: (?:it|playing|the (?:music|song|video)|playback))?$").matches(t)) {
            return media("play") { result -> playing(result)?.let { "Playing $it." } ?: if (result.startsWith("Nothing is playing")) "Nothing is playing." else null }
        }
        if (Regex("^(?:next|skip)(?: (?:song|track|video|one|this|it))?$|^(?:play |go to )?(?:the )?next (?:song|track|video|one)$|^skip (?:this|the) (?:song|track|video)$").matches(t)) {
            return media("next") { result -> playing(result)?.let { "Playing $it." } }
        }
        if (Regex("^(?:previous|go back)(?: (?:song|track|video|one))?$|^(?:play |go to )?(?:the )?previous (?:song|track|video|one)$").matches(t)) {
            return media("previous") { result -> playing(result)?.let { "Playing $it." } }
        }
        volume(t)?.let { return it }
        timer(t)?.let { return it }
        if (Regex("^(?:stop|cancel|dismiss|silence|end|turn off) (?:the |my )?timer$").matches(t)) {
            return Call("clock", JSONObject().put("action", "stop_timer")) { result -> result.lineSequence().first().trim().ifEmpty { null } }
        }
        alarm(t)?.let { return it }
        toggle(t)?.let { return it }
        if (Regex("^(?:what(?:'s| is) the time(?: now| right now)?|what time is it(?: now| right now)?|tell me the time|time)$").matches(t)) {
            return Answer(Answer.Kind.TIME)
        }
        if (Regex("^(?:what(?:'s| is) (?:the )?(?:date|day)(?: today)?|what(?:'s| is) today's date|what day is (?:it|today)(?: today)?|what's today)$").matches(t)) {
            return Answer(Answer.Kind.DATE)
        }
        return null
    }

    private fun media(action: String, reply: (String) -> String?) = Call("media", JSONObject().put("action", action), reply)

    /** “Lofi radio” from a media status line that says it's playing. */
    private fun playing(result: String): String? = Regex("playing (“[^”]*”)").find(result)?.groupValues?.get(1)

    private fun title(name: String) = name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // --- Volume ---

    private fun volume(t: String): Command? {
        Regex("^(?:turn (?:the )?(?:volume|sound|it|music) (up|down)|turn (up|down) (?:the )?(?:volume|sound|music)|volume (up|down)|(?:make it )?(louder)|(?:make it )?(quieter|softer))$")
            .matchEntire(t)?.let { m ->
                val g = m.groupValues.drop(1).first { it.isNotEmpty() }
                return Volume(if (g == "up" || g == "louder") 1 else -1)
            }
        Regex("^(?:set |turn |change )?(?:the )?(?:media )?volume (?:to |at )?(\\d{1,3}|${NUMBER_WORDS})( percent| %|%)?$").matchEntire(t)?.let { m ->
            val n = number(m.groupValues[1]) ?: return null
            return if (m.groupValues[2].isNotEmpty()) Volume(percent = n.coerceIn(0, 100)) else Volume(level = n)
        }
        return null
    }

    // --- Timers and alarms ---

    private const val NUMBER_WORDS = "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
        "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|forty-five|fifty|sixty|ninety|a|an"
    private val WORDS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    ).withIndex().associate { (i, w) -> w to i } + mapOf("thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90, "a" to 1, "an" to 1)

    fun number(word: String): Int? = word.toIntOrNull() ?: WORDS[word]

    /** "5 minutes", "an hour and a half", "1 hour 30 minutes", "90 seconds", "half an hour": seconds, or null. */
    fun duration(text: String): Int? {
        val t = text.trim().replace("-", " ").replace(Regex("\\s+"), " ")
            .replace(Regex("^forty five\\b"), "forty-five")
        if (t == "half an hour" || t == "half hour") return 1800
        if (t == "quarter of an hour" || t == "a quarter of an hour" || t == "quarter hour") return 900
        val unit = "(hours?|hrs?|minutes?|mins?|seconds?|secs?)"
        val part = Regex("(\\d+|${NUMBER_WORDS.replace("forty-five|", "forty-five|forty five|")})(?: and a half)? $unit( and a half)?")
        var rest = t
        var total = 0
        var any = false
        while (rest.isNotEmpty()) {
            val m = part.find(rest) ?: return null
            if (m.range.first != 0) return null
            val n = number(m.groupValues[1].replace("forty five", "forty-five")) ?: return null
            val seconds = when (m.groupValues[2].first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
            val half = (" and a half" in m.value)
            total += n * seconds + if (half) seconds / 2 else 0
            any = true
            rest = rest.substring(m.range.last + 1).trim().removePrefix("and ").trim()
        }
        return if (any && total in 1..(24 * 3600)) total else null
    }

    private fun timer(t: String): Command? {
        val spec = Regex("^(?:set |start |make )?(?:a |an )?timer (?:for |of )?(.+)$").matchEntire(t)?.groupValues?.get(1)
            ?: Regex("^(?:set |start |make )?(?:a |an )?(.+?) timer$").matchEntire(t)?.groupValues?.get(1)
            ?: return null
        // "5 minute" (as in "a 5 minute timer") reads as 5 minutes: units take an optional s.
        val seconds = duration(spec) ?: return null
        return Call("clock", JSONObject().put("action", "set_timer").put("seconds", seconds)) { r ->
            if (r.startsWith("Timer set")) "Timer set for ${spoken(seconds)}." else null
        }
    }

    /** 300 → "5 minutes", 5400 → "1 hour and 30 minutes". */
    fun spoken(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it == 1) "" else "s"}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it == 1) "" else "s"}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it == 1) "" else "s"}" },
        )
        return if (parts.size <= 1) parts.firstOrNull().orEmpty() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    /** "set an alarm for 7 am", "wake me up at 6:30 pm", "alarm for 18:00". Hours without am/pm only past 12. */
    private fun alarm(t: String): Command? {
        val m = Regex("^(?:set (?:an |the |my )?alarm (?:for|at)|alarm (?:for|at)|wake me(?: up)? at) (\\d{1,2})(?::(\\d{2}))?(?: ?(am|pm))?(?: tomorrow(?: morning)?| in the morning| tonight)?$")
            .matchEntire(t) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3]
        if (minute > 59) return null
        when {
            half == "am" -> { if (hour !in 1..12) return null; if (hour == 12) hour = 0 }
            half == "pm" -> { if (hour !in 1..12) return null; if (hour != 12) hour += 12 }
            // "7" alone could be morning or evening: the model asks.
            hour in 13..23 || hour == 0 -> Unit
            else -> return null
        }
        return Call("clock", JSONObject().put("action", "set_alarm").put("hour", hour).put("minute", minute)) { r ->
            r.lineSequence().first().trim().ifEmpty { null }?.let { line -> if (line.contains("alarm", ignoreCase = true)) line else null }
        }
    }

    // --- Switches ---

    private fun toggle(t: String): Command? {
        val names = "(do not disturb|dnd|wi-fi|wifi|wi fi|bluetooth|dark mode|airplane mode|flight mode)"
        val m = Regex("^turn (on|off) (?:the )?$names$").matchEntire(t)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: Regex("^(?:turn )?(?:the )?$names (on|off)$").matchEntire(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: return null
        val (name, value) = m
        val setting = when (name) {
            "do not disturb", "dnd" -> "dnd"
            "bluetooth" -> "bluetooth"
            "dark mode" -> "dark_mode"
            "airplane mode", "flight mode" -> "airplane"
            else -> "wifi"
        }
        val label = when (setting) { "dnd" -> "Do Not Disturb"; "wifi" -> "Wi-Fi"; "dark_mode" -> "Dark mode"; "airplane" -> "Airplane mode"; else -> "Bluetooth" }
        return Call("device", JSONObject().put("action", "set").put("setting", setting).put("value", value)) { r ->
            when {
                "already" in r -> "$label is already $value."
                " set to " in r -> "$label is $value."
                else -> null
            }
        }
    }
}
