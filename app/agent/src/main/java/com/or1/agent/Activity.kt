package com.or1.agent

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** How an effect can be undone (docs/ui-ledger.md §4). */
enum class UndoTier(val level: Int) {
    /** The app reverses it itself, instantly, with no API call. */
    LOCAL(1),
    /** A change inside an app the agent drove: undo asks the agent to reverse it. */
    AGENT(2),
    /** Sent, posted, deleted elsewhere, a shell command: no undo. */
    NONE(3),
}

enum class UndoState {
    AVAILABLE,
    /** Undo is running (tier 1) or the agent is working on it (tier 2). Never persisted. */
    PENDING,
    UNDONE,
    FAILED,
    /** Too late: the timer ran out, the alarm rang, the setting was changed again since. */
    EXPIRED,
    /** Tier 3. */
    NONE,
}

/**
 * A side effect of a write tool call, in the chat or a routine run: what the receipt and the Today tab list.
 * `undo` is the tier-1 inverse as JSON (see [Undo]); `detail` says what the undo did or why it failed.
 */
data class Effect(
    val id: String,
    val time: Long,
    /** chat, routine, card (the user, on a card: [AgentHub.cardChange]) or eval (debug-driver requests, kept out of [AgentHub.effects]). */
    val source: String,
    /** The chat's transcript name, or the routine's id. */
    val sourceId: String?,
    /** For routines, what the chat shows for the run: "Name · trigger". */
    val sourceLabel: String?,
    val tool: String,
    /** The raw call, e.g. "device action=set setting=dnd value=on". */
    val call: String,
    /** Past tense, plain language: "Turned on Do Not Disturb". */
    val label: String,
    val tier: UndoTier,
    val undo: String?,
    val state: UndoState,
    val detail: String? = null,
    /** When undo stops being offered (epoch ms); 0 = never. */
    val expiresAt: Long = 0,
    /** The app the change happened in (tier 2 and 3 taps), as a label. */
    val app: String? = null,
    /**
     * Absolute path of the frame of the work display taken just before the committing tap (tier 2 and 3 `act` taps;
     * files/evidence/<id>.webp, see [Evidence]). The file may be gone (pruned after 30 days).
     */
    val evidence: String? = null,
) {
    /** [state], with an offer past [expiresAt] read as expired. */
    fun stateAt(now: Long = System.currentTimeMillis()): UndoState =
        if (state == UndoState.AVAILABLE && expiresAt in 1..now) UndoState.EXPIRED else state

    fun canUndo(now: Long = System.currentTimeMillis()) = stateAt(now) == UndoState.AVAILABLE

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", time).put("source", source).put("source_id", sourceId).put("source_label", sourceLabel)
        .put("tool", tool).put("call", call).put("label", label).put("tier", tier.level)
        .put("undo", undo?.let { JSONObject(it) }).put("state", state.name.lowercase()).put("detail", detail)
        .put("expires", expiresAt).put("app", app).put("evidence", evidence)

    companion object {
        fun fromJson(o: JSONObject) = Effect(
            id = o.getString("id"), time = o.getLong("t"), source = o.optString("source", "chat"),
            sourceId = o.str("source_id"), sourceLabel = o.str("source_label"), tool = o.getString("tool"),
            call = o.optString("call"), label = o.getString("label"),
            tier = UndoTier.entries.firstOrNull { it.level == o.optInt("tier") } ?: UndoTier.NONE,
            undo = o.optJSONObject("undo")?.toString(), state = parseState(o.optString("state")), detail = o.str("detail"),
            expiresAt = o.optLong("expires"), app = o.str("app"), evidence = o.str("evidence"),
        )
    }
}

private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

private fun parseState(s: String) = UndoState.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: UndoState.NONE

/** What a write tool call did; null from [Effects.classify] when it changed nothing. */
data class Classified(val label: String, val tier: UndoTier, val undo: JSONObject? = null, val expiresAt: Long = 0)

/**
 * What is known around a call besides its arguments: the tool's undo data ([com.or1.agent.tools.ToolOutput.undo]), and
 * for `act` the element's label and class and the app in front, read before the tap (the screen changes after).
 */
data class CallFacts(val undo: JSONObject? = null, val target: String? = null, val targetKind: String? = null, val app: String? = null)

/**
 * Turns write tool calls into effects. Reads and navigation (screen, notifications, apps, list/find/view/get, scroll,
 * keys, type, open by package or settings page) are not effects.
 *
 * `act` taps are a heuristic: the element's label (first word) or its kind must say it commits something. A send/
 * post/pay/delete-like label is irreversible (tier 3); a save/archive/add/turn-like label or a switch, checkbox or
 * radio button is an in-app change the agent can reverse (tier 2). Taps by coordinates, unlabeled buttons and
 * typing are not effects (the tap that sends the typed text is).
 */
object Effects {
    private val settingNames = mapOf(
        "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "dnd" to "Do Not Disturb", "airplane" to "airplane mode",
        "auto_brightness" to "auto-brightness", "dark_mode" to "dark mode", "volume" to "media volume",
        "brightness" to "brightness", "screen_timeout" to "screen timeout",
    )

    /** First words of labels whose tap can't be taken back. */
    private val irreversible = setOf(
        "send", "post", "publish", "reply", "share", "tweet", "pay", "buy", "purchase", "order", "checkout", "transfer",
        "delete", "uninstall", "call", "submit", "discard", "erase", "empty",
    )

    /** First words of labels whose tap changes something the agent could change back. */
    private val commits = setOf(
        "save", "done", "apply", "confirm", "ok", "archive", "add", "create", "accept", "allow", "install", "enable",
        "disable", "turn", "mark", "move", "mute", "unmute", "block", "unblock", "subscribe", "unsubscribe", "follow",
        "unfollow", "like", "remove", "update", "set", "star", "unstar", "pin", "unpin", "snooze", "rename",
        "join", "leave", "decline", "reject", "deny", "restore",
    )

    /** Shell commands (each part of a pipeline or list) that only read. */
    private val readOnlyShell = listOf(
        "content query", "dumpsys", "getprop", "pm list", "pm path", "pm dump", "cmd package list", "settings get",
        "settings list", "ls", "cat", "echo", "grep", "head", "tail", "wc", "ps", "df", "du", "date", "id", "whoami",
        "uptime", "stat", "which", "find", "logcat -d", "wm size", "wm density", "cmd uimode night", "sleep",
        "monkey -p", "uiautomator dump", "screencap",
    )

    fun classify(tool: String, args: JSONObject, facts: CallFacts, now: ZonedDateTime): Classified? {
        val action = args.optString("action")
        val undo = facts.undo
        val title = undo?.optString("title")?.ifBlank { null }
        return when (tool) {
            "device" -> if (action != "set" || undo == null) null else
                Classified(deviceLabel(args.optString("setting"), undo.optString("value", args.optString("value"))), UndoTier.LOCAL, undo,
                    now.plusHours(24).toEpochMs())
            "clock" -> when (action) {
                "set_alarm" -> undo?.let {
                    val hour = args.optInt("hour")
                    val minute = args.optInt("minute")
                    // Once it has rung there's nothing to turn off.
                    var rings = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    if (!rings.isAfter(now)) rings = rings.plusDays(1)
                    Classified("Set alarm %02d:%02d".format(hour, minute) + quoted(args.optString("label")), UndoTier.LOCAL, it,
                        rings.plusMinutes(1).toEpochMs())
                }
                "set_timer" -> undo?.let {
                    val seconds = args.optInt("seconds")
                    Classified("Set a ${duration(seconds)} timer" + quoted(args.optString("label")), UndoTier.LOCAL, it,
                        now.toEpochMs() + seconds * 1000L)
                }
                "dismiss_alarm" -> Classified(
                    args.optString("label").takeIf { it.isNotBlank() }?.let { "Turned off the “$it” alarm" } ?: "Turned off all alarms",
                    UndoTier.NONE,
                )
                else -> null
            }
            "calendar" -> when {
                undo == null -> null
                action == "add" -> Classified("Added “${clip(title ?: args.optString("title"))}” to the calendar" +
                    args.optString("start").takeIf { it.isNotBlank() }?.let { ", ${it.replace('T', ' ')}" }.orEmpty(), UndoTier.LOCAL, undo)
                action == "delete" -> Classified("Deleted “${clip(title ?: "event ${args.optLong("event_id")}")}” from the calendar", UndoTier.LOCAL, undo)
                action == "move" -> Classified("Moved “${clip(title ?: "event ${args.optLong("event_id")}")}” to ${undo.optString("to")}", UndoTier.LOCAL, undo)
                else -> null
            }
            "contacts" -> if (action == "add" && undo != null) Classified("Added contact ${clip(args.optString("name"))}", UndoTier.LOCAL, undo) else null
            "routines" -> when {
                undo == null -> null
                action == "add" -> Classified("Created routine “${clip(title ?: args.optString("prompt"))}”", UndoTier.LOCAL, undo)
                action == "remove" -> Classified("Removed routine “${clip(title ?: args.optString("id"))}”", UndoTier.LOCAL, undo)
                else -> null
            }
            "memory" -> when {
                undo == null -> null
                action == "add" -> Classified("Saved a note: “${clip(undo.optString("text"), 60)}”", UndoTier.LOCAL, undo)
                action == "remove" -> Classified("Removed a note: “${clip(undo.optString("text"), 60)}”", UndoTier.LOCAL, undo)
                else -> null
            }
            "act" -> if (action == "tap") tap(facts, now) else null
            "open" -> args.optString("uri").takeIf { it.isNotBlank() && args.optString("package").isBlank() && carriesData(it) }
                ?.let { Classified("Opened ${clip(it, 60)}", UndoTier.NONE) }
            "shell" -> args.optString("command").takeIf { it.isNotBlank() && !isReadOnlyShell(it) }
                ?.let { Classified(shellLabel(it), UndoTier.NONE) }
            else -> null
        }
    }

    private fun tap(facts: CallFacts, now: ZonedDateTime): Classified? {
        val raw = facts.target ?: return null
        // ScreenTools.labelOf quotes real labels; "an unlabeled button" and "element 7" aren't.
        val label = raw.takeIf { it.startsWith("“") }?.trim('“', '”')?.trim()
        val first = label?.lowercase()?.split(Regex("[^a-z]+"))?.firstOrNull { it.isNotEmpty() }
        val kind = facts.targetKind.orEmpty()
        val toggle = kind.endsWith("Switch") || kind.contains("CheckBox") || kind.contains("Toggle") || kind == "RadioButton"
        val tier = when {
            first in irreversible -> UndoTier.NONE
            first in commits || toggle -> UndoTier.AGENT
            else -> return null
        }
        // A tier-2 row's caption already names the app ("via or1 · Gmail").
        val app = facts.app?.takeIf { tier == UndoTier.NONE }?.let { " in $it" }.orEmpty()
        val text = (if (toggle) "Toggled " else "Tapped ") + (label?.let { "“$it”" } ?: raw) + app
        return Classified(text, tier, expiresAt = if (tier == UndoTier.AGENT) now.plusHours(24).toEpochMs() else 0)
    }

    fun isReadOnlyShell(command: String): Boolean {
        if (Regex("(^|[^0-9&])>").containsMatchIn(command) || "-delete" in command || "-exec" in command) return false
        return shellParts(command).all { isReadOnlyPart(it) }
    }

    private fun shellParts(command: String) = command.split(Regex("&&|\\|\\||[;|\\n]")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun isReadOnlyPart(part: String) = readOnlyShell.any { part == it || part.startsWith("$it ") } ||
        // Starting an app is navigation, like open; a web address that carries data could send it out, like open with
        // such a URI. ("am startservice" is not: it commands an app.)
        ((part == "am start" || part.startsWith("am start ")) && WEB_URL.findAll(part).none { carriesData(it.value) })

    private val WEB_URL = Regex("https?://[^\\s\"']+")

    /**
     * Opening an address is navigation (a search, a video) unless it could carry the user's data out: a query or
     * fragment value of [DATA_CHARS]+ characters, as a link that "sends your notes" would. Only those get a receipt, so
     * everyday opens ("Opened YouTube") don't fill the receipts with irreversible rows.
     */
    fun carriesData(uri: String): Boolean {
        val rest = uri.substringAfter('?', "").ifEmpty { null }.orEmpty() + "&" + uri.substringAfter('#', "")
        return rest.split('&', ';').any { it.substringAfter('=').length >= DATA_CHARS }
    }

    private const val DATA_CHARS = 48
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * A shell command's effect in words, by the verb of its first part that writes: "Changed setting screen_brightness",
     * "Sent com.x a command", "Changed app com.x", "Pressed keys", "Opened example.com"; else "Ran a shell command".
     * The command itself is the effect's [Effect.call], shown with developer details on.
     */
    fun shellLabel(command: String): String {
        val part = shellParts(command).firstOrNull { !isReadOnlyPart(it) } ?: command.trim()
        val words = part.split(Regex("\\s+")).map { it.trim('"', '\'') }
        fun after(flag: String) = words.indexOf(flag).takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }
        return when {
            words.size >= 4 && words[0] == "settings" && words[1] == "put" -> "Changed setting ${clip(words[3])}"
            words.getOrNull(0) == "am" && words.getOrNull(1) in setOf("broadcast", "startservice", "start-service", "start-foreground-service") -> {
                val pkg = (after("-n")?.substringBefore('/') ?: after("-p"))?.takeIf { PACKAGE.matches(it) }
                "Sent ${pkg ?: "an app"} a command"
            }
            words[0] == "pm" -> words.drop(2).lastOrNull { PACKAGE.matches(it.substringBefore('/')) }
                ?.let { "Changed app ${clip(it.substringBefore('/'))}" } ?: "Changed an app"
            words[0] == "input" -> "Pressed keys"
            words.getOrNull(0) == "am" && words.getOrNull(1) == "start" && WEB_URL.containsMatchIn(part) ->
                Regex("https?://([^/?#\"' :]+)").find(part)?.groupValues?.get(1)?.let { "Opened ${clip(it)}" } ?: "Opened a web address"
            else -> "Ran a shell command"
        }
    }

    /** "Turned on Do Not Disturb", "Set media volume to 3". `value` is the normalised new value. */
    fun deviceLabel(setting: String, value: String): String {
        val name = settingNames[setting] ?: setting
        return when {
            value == "on" || value == "off" -> "Turned $value $name"
            setting == "screen_timeout" -> "Set screen timeout to ${durationNoun(value.toIntOrNull() ?: 0)}"
            else -> "Set $name to $value"
        }
    }

    /**
     * The device settings an effect's undo would put back ("dnd_filter" counts as dnd), so a later change to the same
     * setting can retire the older undo: restoring it then would clobber the newer change.
     */
    fun restoredSettings(effect: Effect): Set<String> {
        if (effect.tool != "device" || effect.undo == null) return emptySet()
        val restore = JSONObject(effect.undo).optJSONArray("restore") ?: return emptySet()
        return (0 until restore.length()).map { restore.getJSONObject(it).optString("setting").removeSuffix("_filter") }.toSet()
    }

    private fun quoted(label: String) = label.takeIf { it.isNotBlank() }?.let { " “${clip(it)}”" }.orEmpty()

    private fun clip(text: String, max: Int = 40) = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    private fun ZonedDateTime.toEpochMs() = toInstant().toEpochMilli()

    /** "5-minute" (as in "a 5-minute timer"). */
    private fun duration(seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> "${seconds / 3600}-hour"
        seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60}-minute"
        else -> "$seconds-second"
    }

    private fun durationNoun(seconds: Int): String {
        fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
        return when {
            seconds >= 3600 && seconds % 3600 == 0 -> plural(seconds / 3600, "hour")
            seconds >= 60 && seconds % 60 == 0 -> plural(seconds / 60, "minute")
            else -> plural(seconds, "second")
        }
    }
}

/**
 * The effects log, files/activity.jsonl: one `{"effect": {...}}` record per effect and one `{"undo": {"id", "state",
 * "detail"}}` record per change of its undo state, appended (a crash loses at most a line). Loaded on start;
 * rewritten without old effects once it grows past [COMPACT_BYTES].
 */
object ActivityLog {
    const val FILE = "activity.jsonl"
    private const val KEEP_MS = 30L * 24 * 3600_000
    private const val COMPACT_BYTES = 512 * 1024L
    private const val KEPT_IN_MEMORY = 1000
    private val seq = AtomicInteger()

    private var file: File? = null
    private val _effects = MutableStateFlow<List<Effect>>(emptyList())
    /** Newest first, every source (eval included). */
    val effects: StateFlow<List<Effect>> = _effects
    /** Called after an effect's undo state changes. */
    @Volatile
    var onChange: ((Effect) -> Unit)? = null

    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, FILE).also { file = it }
        if (!f.exists()) return
        val loaded = runCatching { f.useLines { foldActivity(it) } }.getOrDefault(emptyList())
        val recent = loaded.filter { it.time > System.currentTimeMillis() - KEEP_MS }
        if (f.length() > COMPACT_BYTES) runCatching {
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(recent.joinToString("") { JSONObject().put("effect", it.toJson()).toString() + "\n" })
            check(tmp.renameTo(f))
        }
        _effects.value = recent.takeLast(KEPT_IN_MEMORY).reversed()
    }

    /** A new effect id: unique across restarts (time-based) and within a millisecond (sequence). */
    fun newId(now: Long = System.currentTimeMillis()): String = now.toString(36) + "-" + seq.incrementAndGet().toString(36)

    fun get(id: String): Effect? = _effects.value.firstOrNull { it.id == id }

    /** Appends [effect]; an older device effect it overrides loses its undo (see [Effects.restoredSettings]). */
    @Synchronized
    fun record(effect: Effect) {
        val now = System.currentTimeMillis()
        val touched = Effects.restoredSettings(effect)
        val superseded = if (touched.isEmpty()) emptyList() else _effects.value.filter {
            it.canUndo(now) && Effects.restoredSettings(it).any { s -> s in touched }
        }
        append(JSONObject().put("effect", effect.toJson()))
        _effects.value = (listOf(effect) + _effects.value).take(KEPT_IN_MEMORY)
        superseded.forEach { update(it.id, UndoState.EXPIRED, "Changed again later.") }
    }

    /** Sets an effect's undo state; [UndoState.PENDING] is kept in memory only. Returns the updated effect. */
    @Synchronized
    fun update(id: String, state: UndoState, detail: String? = null): Effect? {
        val old = get(id) ?: return null
        val updated = old.copy(state = state, detail = detail ?: old.detail)
        if (state != UndoState.PENDING) append(undoRecord(id, state, detail))
        _effects.value = _effects.value.map { if (it.id == id) updated else it }
        onChange?.invoke(updated)
        return updated
    }

    /** A one-off routine retires itself after its run: "Created routine" has nothing left to undo. */
    fun expireRoutine(routineId: String) {
        _effects.value.filter { e ->
            e.canUndo() && e.undo?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.let { it.optString("op") == "routine_remove" && it.optString("id") == routineId } == true
        }.forEach { update(it.id, UndoState.EXPIRED, "The routine ran and retired.") }
    }

    private fun append(record: JSONObject) {
        runCatching { file?.appendText(record.toString() + "\n") }
    }
}

internal fun undoRecord(id: String, state: UndoState, detail: String?): JSONObject =
    JSONObject().put("undo", JSONObject().put("id", id).put("state", state.name.lowercase()).put("detail", detail).put("t", System.currentTimeMillis()))

/** Effects from activity.jsonl lines, oldest first, with their latest undo state. Bad lines are skipped. */
internal fun foldActivity(lines: Sequence<String>): List<Effect> {
    val byId = LinkedHashMap<String, Effect>()
    for (line in lines) {
        val record = runCatching { JSONObject(line) }.getOrNull() ?: continue
        record.optJSONObject("effect")?.let { o -> runCatching { Effect.fromJson(o) }.getOrNull()?.let { byId[it.id] = it } }
        record.optJSONObject("undo")?.let { u ->
            val id = u.optString("id")
            val state = parseState(u.optString("state"))
            // A pending undo that never finished (the process died) is left as it was.
            if (state != UndoState.PENDING) byId[id]?.let { byId[id] = it.copy(state = state, detail = u.str("detail") ?: it.detail) }
        }
    }
    return byId.values.toList()
}

/** Effects of the local day containing [now] (the Today tab), newest first as given. */
fun List<Effect>.today(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Effect> {
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return filter { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() == day }
}
