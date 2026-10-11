package com.or1.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.StatusBarNotification
import android.util.Log
import com.or1.agent.tools.ToolOutput
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A request the agent runs on its own later: at a time (once or repeating) or when a matching notification arrives. */
data class Routine(
    val id: String,
    val prompt: String,
    val hour: Int = -1,
    val minute: Int = 0,
    /** once, daily, weekdays or weekly. */
    val repeat: String = "once",
    /** For weekly routines: 1 (Monday) to 7. */
    val weekday: Int = 0,
    /** Next run (epoch ms) for time routines. */
    val next: Long = 0,
    val app: String? = null,
    val textContains: String? = null,
    val lastRun: Long = 0,
    /** A short name the user or the agent gave it; the trigger describes it otherwise. */
    val name: String? = null,
) {
    val onNotification get() = app != null || textContains != null

    val title: String get() = name ?: describe()

    fun describe(): String {
        if (onNotification) {
            val from = app?.let { "from $it" }
            val with = textContains?.let { "containing “$it”" }
            return "On a notification " + listOfNotNull(from, with).joinToString(" ")
        }
        val time = "%02d:%02d".format(hour, minute)
        return when (repeat) {
            "daily" -> "Every day at $time"
            "weekdays" -> "Weekdays at $time"
            "weekly" -> "Every ${DayOfWeek.of(weekday).name.lowercase().replaceFirstChar { it.uppercase() }} at $time"
            else -> "Once, " + DateTimeFormatter.ofPattern("EEE dd MMM HH:mm", Locale.US).format(Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault()))
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("prompt", prompt).put("hour", hour).put("minute", minute).put("repeat", repeat)
        .put("weekday", weekday).put("next", next).put("app", app).put("text_contains", textContains)
        .put("last_run", lastRun).put("name", name)

    companion object {
        fun fromJson(o: JSONObject) = Routine(
            id = o.getString("id"), prompt = o.getString("prompt"), hour = o.optInt("hour", -1), minute = o.optInt("minute"),
            repeat = o.optString("repeat", "once"), weekday = o.optInt("weekday"), next = o.optLong("next"),
            app = o.optString("app").takeIf { o.has("app") && !o.isNull("app") && it.isNotBlank() },
            textContains = o.optString("text_contains").takeIf { o.has("text_contains") && !o.isNull("text_contains") && it.isNotBlank() },
            lastRun = o.optLong("last_run"),
            name = o.optString("name").takeIf { o.has("name") && !o.isNull("name") && it.isNotBlank() },
        )
    }
}

/** Stores routines in files/routines.json, schedules their alarms and matches notifications against them. */
object Routines {
    private const val TAG = "or1-routines"
    private const val ACTION_FIRE = "com.or1.agent.ROUTINE"
    /** A notification routine runs at most once per this interval, so a burst of messages is one run. */
    private const val COOLDOWN_MS = 60_000L

    private lateinit var context: Context
    private val file get() = File(context.filesDir, "routines.json")
    /**
     * Content of each live notification by key, so re-posts and updates with the same content don't trigger again.
     * Forgotten when the notification goes ([forget]): the same message arriving later is new.
     */
    private val seen = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    private val _list = MutableStateFlow<List<Routine>>(emptyList())
    /** The routines, for the Routines screen. */
    val list: StateFlow<List<Routine>> = _list

    fun init(context: Context) {
        this.context = context.applicationContext
        _list.value = all()
        rescheduleAll()
    }

    @Synchronized
    fun all(): List<Routine> = if (!file.exists()) emptyList() else
        JSONArray(file.readText()).let { a -> (0 until a.length()).map { Routine.fromJson(a.getJSONObject(it)) } }

    @Synchronized
    private fun save(routines: List<Routine>) {
        file.writeText(JSONArray(routines.map { it.toJson() }).toString(1))
        _list.value = routines
    }

    @Synchronized
    fun update(id: String, transform: (Routine) -> Routine) = save(all().map { if (it.id == id) transform(it) else it })

    fun get(id: String) = all().firstOrNull { it.id == id }

    // --- The routines tool -----------------------------------------------------------------------------------------

    fun tool(args: JSONObject): ToolOutput = when (val action = args.getString("action")) {
        "list" -> ToolOutput(
            all().joinToString("\n") { r -> "- [${r.id}] ${r.name?.let { "“$it”, " }.orEmpty()}${r.describe()}: ${r.prompt}" }.ifEmpty { "No routines." }
        )
        "add" -> add(args)
        "remove" -> {
            val id = args.optString("id").ifBlank { throw IllegalArgumentException("remove needs `id` (see list).") }
            val routine = remove(id) ?: throw IllegalArgumentException("No routine with id $id.")
            ToolOutput(
                "Removed routine $id (${routine.describe()}: ${routine.prompt}).",
                undo = JSONObject().put("op", "routine_restore").put("title", routine.title).put("routine", routine.toJson()),
            )
        }
        else -> throw IllegalArgumentException("Unknown action '$action'.")
    }

    /**
     * Puts a removed routine back (undo), under its id. A repeating one gets its next run from now; a one-off whose time
     * has passed can't come back.
     */
    fun restore(routine: Routine): String {
        require(get(routine.id) == null) { "That routine is already there." }
        val r = when {
            routine.onNotification || routine.next > System.currentTimeMillis() -> routine
            routine.repeat == "once" -> throw IllegalArgumentException("Its time has passed.")
            else -> routine.copy(next = nextRun(routine, ZonedDateTime.now(ZoneId.systemDefault())).toInstant().toEpochMilli())
        }
        synchronized(this) { save(all() + r) }
        if (!r.onNotification) schedule(r)
        return "Put back routine “${r.title}”."
    }

    /** Deletes a routine and its alarm (the tool, or the Routines screen). Returns what was removed. */
    fun remove(id: String): Routine? {
        val routine = get(id) ?: return null
        cancel(id)
        synchronized(this) { save(all().filter { it.id != id }) }
        return routine
    }

    private fun add(args: JSONObject): ToolOutput {
        val routine = parseRoutine(args, UUID.randomUUID().toString().take(6), ZonedDateTime.now(ZoneId.systemDefault()))
        synchronized(this) { save(all() + routine) }
        if (!routine.onNotification) schedule(routine)
        return ToolOutput(
            "Created routine ${routine.id}: ${routine.describe()}: ${routine.prompt}",
            undo = JSONObject().put("op", "routine_remove").put("id", routine.id).put("title", routine.title),
        )
    }

    /**
     * Replaces a routine's name, request and trigger (the Routines screen), keeping its id. `args` takes the same
     * fields as the tool's add. Throws IllegalArgumentException with a readable message on bad input.
     */
    fun edit(id: String, args: JSONObject): Routine {
        val old = get(id) ?: throw IllegalArgumentException("No routine with id $id.")
        val routine = parseRoutine(args, id, ZonedDateTime.now(ZoneId.systemDefault()), keep = old)
        cancel(id)
        synchronized(this) { save(all().map { if (it.id == id) routine else it }) }
        if (!routine.onNotification) schedule(routine)
        return routine
    }

    /** Sets or (blank) clears a routine's name. */
    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifBlank { null }) }

    // --- Time triggers ---------------------------------------------------------------------------------------------

    private fun pendingIntent(id: String, flags: Int = 0): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, RoutineReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("or1-routine:$id")).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or flags,
    )

    private fun schedule(r: Routine) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.next, pendingIntent(r.id, PendingIntent.FLAG_UPDATE_CURRENT)!!)
        Log.i(TAG, "scheduled ${r.id} for ${Instant.ofEpochMilli(r.next)}")
    }

    private fun cancel(id: String) {
        pendingIntent(id, PendingIntent.FLAG_NO_CREATE)?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    /**
     * After boot or a process start: re-arm every time routine. A one-off that came due while the phone was off runs
     * now; a repeating one skips the missed run.
     */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        for (r in all().filter { !it.onNotification }) {
            when {
                r.next > now -> schedule(r)
                r.repeat == "once" -> fire(r.id, late = true)
                else -> {
                    val next = nextRun(r, ZonedDateTime.now(ZoneId.systemDefault())).toInstant().toEpochMilli()
                    update(r.id) { it.copy(next = next) }
                    schedule(r.copy(next = next))
                }
            }
        }
    }

    /** Runs a routine now (alarm, boot catch-up or debug), then re-arms or retires it. */
    fun fire(id: String, late: Boolean = false, notification: String? = null) {
        val r = get(id) ?: return
        if (!r.onNotification) {
            if (r.repeat == "once") {
                // Its alarm may still be pending (run early from debug or a boot catch-up).
                cancel(id)
                synchronized(this) { save(all().filter { it.id != id }) }
                ActivityLog.expireRoutine(id)
            } else {
                val next = nextRun(r, ZonedDateTime.now(ZoneId.systemDefault())).toInstant().toEpochMilli()
                update(id) { it.copy(next = next, lastRun = System.currentTimeMillis()) }
                schedule(r.copy(next = next))
            }
        } else {
            update(id) { it.copy(lastRun = System.currentTimeMillis()) }
        }
        Log.i(TAG, "firing $id (late=$late)")
        AgentHub.submitRoutine(r, late, notification)
    }

    // --- Notification triggers -------------------------------------------------------------------------------------

    fun forget(sbn: StatusBarNotification) {
        synchronized(seen) { seen.remove(sbn.key) }
    }

    fun onNotification(sbn: StatusBarNotification) {
        if (!::context.isInitialized || sbn.packageName == context.packageName) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        synchronized(seen) {
            val content = "$title|$text"
            if (seen[sbn.key] == content) return
            seen[sbn.key] = content
        }
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val now = System.currentTimeMillis()
        for (r in all().filter { it.onNotification && now - it.lastRun > COOLDOWN_MS }) {
            val appMatches = r.app == null || r.app.equals(sbn.packageName, ignoreCase = true) || label.contains(r.app, ignoreCase = true)
            val textMatches = r.textContains == null || "$title\n$text".contains(r.textContains, ignoreCase = true)
            if (appMatches && textMatches) {
                fire(r.id, notification = "app: $label (${sbn.packageName})\ntitle: $title\ntext: $text")
            }
        }
    }
}

/** Fires time routines, and re-arms them after a reboot (alarms don't survive one). */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentHub.init(context.applicationContext)
        // Keep the process in the foreground while the run takes its time.
        runCatching { context.startForegroundService(Intent(context, AgentService::class.java)) }
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Unit // AgentHub.init re-armed everything.
            else -> intent.getStringExtra("id")?.let { Routines.fire(it) }
        }
    }
}

/**
 * A routine from the tool's add arguments: `prompt` and exactly one trigger (`at` with optional `repeat`/`date`,
 * `in_minutes`, or `app`/`text_contains`), and an optional `name`. `keep` is the routine being edited: its last run,
 * its name (unless `name` is given; blank clears it) and (for weekly ones without a `date`) weekday carry over.
 */
internal fun parseRoutine(args: JSONObject, id: String, now: ZonedDateTime, keep: Routine? = null): Routine {
    val prompt = args.optString("prompt").ifBlank { throw IllegalArgumentException("add needs `prompt`.") }
    val app = args.optString("app").takeIf { it.isNotBlank() }
    val text = args.optString("text_contains").takeIf { it.isNotBlank() }
    val at = args.optString("at").takeIf { it.isNotBlank() }
    val inMinutes = args.optInt("in_minutes", 0)
    val kinds = listOf(app != null || text != null, at != null, inMinutes > 0).count { it }
    require(kinds == 1) { "Give exactly one trigger: `at` (HH:MM), `in_minutes`, or a notification match (`app` and/or `text_contains`)." }
    val routine = when {
        app != null || text != null -> Routine(id, prompt, app = app, textContains = text)
        inMinutes > 0 -> {
            val next = now.plusMinutes(inMinutes.toLong()).withSecond(0).withNano(0)
            Routine(id, prompt, next.hour, next.minute, "once", next = next.toInstant().toEpochMilli())
        }
        else -> {
            val time = try {
                LocalTime.parse(at!!.trim())
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("`at` is HH:MM (24-hour), got '$at'.")
            }
            val repeat = args.optString("repeat", "once").ifBlank { "once" }
            require(repeat in setOf("once", "daily", "weekdays", "weekly")) { "`repeat` is once, daily, weekdays or weekly." }
            val date = args.optString("date").takeIf { it.isNotBlank() }?.let {
                try { LocalDate.parse(it.trim()) } catch (e: DateTimeParseException) { throw IllegalArgumentException("`date` is YYYY-MM-DD, got '$it'.") }
            }
            val weekday = date?.dayOfWeek?.value ?: keep?.weekday?.takeIf { it in 1..7 && keep.repeat == "weekly" } ?: now.dayOfWeek.value
            val base = Routine(id, prompt, time.hour, time.minute, repeat, weekday)
            val next = if (repeat == "once" && date != null) date.atTime(time).atZone(now.zone) else nextRun(base, now)
            require(next.isAfter(now)) { "That time has already passed." }
            base.copy(next = next.toInstant().toEpochMilli())
        }
    }
    val name = if (args.has("name")) args.optString("name").trim().ifBlank { null } else keep?.name
    return if (keep == null) routine.copy(name = name) else routine.copy(lastRun = keep.lastRun, name = name)
}

/** The first run of a time routine strictly after [after]. */
internal fun nextRun(r: Routine, after: ZonedDateTime): ZonedDateTime {
    var candidate = after.withHour(r.hour).withMinute(r.minute).withSecond(0).withNano(0)
    if (!candidate.isAfter(after)) candidate = candidate.plusDays(1)
    while (when (r.repeat) {
            "weekdays" -> candidate.dayOfWeek.value > 5
            "weekly" -> candidate.dayOfWeek.value != r.weekday
            else -> false
        }
    ) candidate = candidate.plusDays(1)
    return candidate
}

/**
 * A finished routine run, for the chat stream (docs/ui-ledger.md §2, "Routine result"): an act tagged
 * `routine · <trigger>`. Built from the routine transcripts (files/routine-transcripts/), so runs survive a restart.
 */
data class RoutineAct(
    /** Stable across restarts: "<transcript file name without .jsonl>#<run number in that file>". */
    val id: String,
    val routineId: String,
    /** What the chat shows for the run: "Name · trigger", or just the trigger. */
    val label: String,
    /** The trigger as described when it ran ("Every day at 08:00", "… (late: …)"). */
    val trigger: String,
    /** When the run started and finished (epoch ms). */
    val started: Long,
    val finished: Long,
    /** The routine's request and the agent's reply. */
    val request: String,
    val reply: String,
    /** The run errored (API or network) even after its retry. */
    val failed: Boolean,
    /** The user stopped it. */
    val stopped: Boolean,
    /** Tool calls made (reads included), for "N steps". */
    val steps: Int,
    val cost: Double,
    val seconds: Double,
    /** Absolute path of the routine transcript holding the run. */
    val transcript: String,
    /** Its side effects, oldest first, kept current with their undo state (see [withEffects]). */
    val effects: List<Effect> = emptyList(),
    /** Ids of its effects as logged; null for runs logged before they were (matched by routine and time instead). */
    val effectIds: List<String>? = null,
) {
    /** This run with [effects] picked from [all] (any order): by id, else the routine's effects during the run. */
    fun withEffects(all: List<Effect>): RoutineAct {
        val mine = if (effectIds != null) {
            val wanted = effectIds.toSet()
            all.filter { it.id in wanted }
        } else {
            all.filter { it.source == "routine" && it.sourceId == routineId && it.time in started..finished }
        }
        return copy(effects = mine.sortedBy { it.time })
    }
}

/**
 * The finished runs in one routine transcript ([transcript] is its path), in order. A run is a `routine_run` record
 * up to its `routine_reply`; tool records in between count as steps. A run without a reply (the process died) is left
 * out. Lines that aren't JSON are skipped. Times are the records' `t` (epoch seconds).
 */
fun parseRoutineActs(lines: Sequence<String>, transcript: String): List<RoutineAct> {
    val name = File(transcript).name.removeSuffix(".jsonl")
    val acts = mutableListOf<RoutineAct>()
    var number = 0
    var run: JSONObject? = null
    var started = 0L
    var steps = 0
    for (line in lines) {
        val record = runCatching { JSONObject(line) }.getOrNull() ?: continue
        val t = (record.optDouble("t", 0.0) * 1000).toLong()
        record.optJSONObject("routine_run")?.let {
            number++
            run = it
            started = t
            steps = 0
        }
        if (record.has("tool") && run != null) steps++
        val reply = record.optJSONObject("routine_reply") ?: continue
        val r = run ?: continue
        run = null
        val trigger = r.optString("trigger")
        acts += RoutineAct(
            id = "$name#$number", routineId = r.optString("id"),
            label = r.optString("label").ifBlank { trigger }, trigger = trigger,
            started = started, finished = t, request = r.optString("prompt"), reply = reply.optString("text"),
            failed = reply.optBoolean("failed"), stopped = reply.optBoolean("stopped"), steps = steps,
            cost = reply.optDouble("cost", 0.0), seconds = reply.optDouble("seconds", 0.0), transcript = transcript,
            effectIds = reply.optJSONArray("effects")?.let { a -> (0 until a.length()).map { a.getString(it) } },
        )
    }
    return acts
}
