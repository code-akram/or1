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
import org.json.JSONArray
import org.json.JSONObject

/**
 * A request the agent runs on its own later: at a time (once or repeating) or when a matching notification arrives.
 * `allowed` holds the tool:action approvals the user answered with Always during this routine's runs.
 */
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
    val allowed: Set<String> = emptySet(),
    val lastRun: Long = 0,
) {
    val onNotification get() = app != null || textContains != null

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
        .put("allowed", JSONArray(allowed.toList())).put("last_run", lastRun)

    companion object {
        fun fromJson(o: JSONObject) = Routine(
            id = o.getString("id"), prompt = o.getString("prompt"), hour = o.optInt("hour", -1), minute = o.optInt("minute"),
            repeat = o.optString("repeat", "once"), weekday = o.optInt("weekday"), next = o.optLong("next"),
            app = o.optString("app").takeIf { o.has("app") && !o.isNull("app") && it.isNotBlank() },
            textContains = o.optString("text_contains").takeIf { o.has("text_contains") && !o.isNull("text_contains") && it.isNotBlank() },
            allowed = o.optJSONArray("allowed")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            lastRun = o.optLong("last_run"),
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
    /** Recently seen notifications (key + content), so updates to one notification don't trigger again. */
    private val seen = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 100
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        rescheduleAll()
    }

    @Synchronized
    fun all(): List<Routine> = if (!file.exists()) emptyList() else
        JSONArray(file.readText()).let { a -> (0 until a.length()).map { Routine.fromJson(a.getJSONObject(it)) } }

    @Synchronized
    private fun save(routines: List<Routine>) = file.writeText(JSONArray(routines.map { it.toJson() }).toString(1))

    @Synchronized
    fun update(id: String, transform: (Routine) -> Routine) = save(all().map { if (it.id == id) transform(it) else it })

    fun get(id: String) = all().firstOrNull { it.id == id }

    // --- The routines tool -----------------------------------------------------------------------------------------

    fun tool(args: JSONObject): String = when (val action = args.getString("action")) {
        "list" -> all().joinToString("\n") { "- [${it.id}] ${it.describe()}: ${it.prompt}" }.ifEmpty { "No routines." }
        "add" -> add(args)
        "remove" -> {
            val id = args.optString("id").ifBlank { throw IllegalArgumentException("remove needs `id` (see list).") }
            val routine = get(id) ?: throw IllegalArgumentException("No routine with id $id.")
            cancel(id)
            synchronized(this) { save(all().filter { it.id != id }) }
            "Removed routine $id (${routine.describe()}: ${routine.prompt})."
        }
        else -> throw IllegalArgumentException("Unknown action '$action'.")
    }

    private fun add(args: JSONObject): String {
        val prompt = args.optString("prompt").ifBlank { throw IllegalArgumentException("add needs `prompt`.") }
        val id = UUID.randomUUID().toString().take(6)
        val app = args.optString("app").takeIf { it.isNotBlank() }
        val text = args.optString("text_contains").takeIf { it.isNotBlank() }
        val at = args.optString("at").takeIf { it.isNotBlank() }
        val inMinutes = args.optInt("in_minutes", 0)
        val kinds = listOf(app != null || text != null, at != null, inMinutes > 0).count { it }
        require(kinds == 1) { "Give exactly one trigger: `at` (HH:MM), `in_minutes`, or a notification match (`app` and/or `text_contains`)." }
        val zone = ZoneId.systemDefault()
        val routine = when {
            app != null || text != null -> Routine(id, prompt, app = app, textContains = text)
            inMinutes > 0 -> {
                val next = ZonedDateTime.now(zone).plusMinutes(inMinutes.toLong()).withSecond(0).withNano(0)
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
                val weekday = (date ?: LocalDate.now(zone)).dayOfWeek.value
                val base = Routine(id, prompt, time.hour, time.minute, repeat, weekday)
                val next = if (repeat == "once" && date != null) date.atTime(time).atZone(zone) else nextRun(base, ZonedDateTime.now(zone))
                require(next.isAfter(ZonedDateTime.now(zone))) { "That time has already passed." }
                base.copy(next = next.toInstant().toEpochMilli())
            }
        }
        synchronized(this) { save(all() + routine) }
        if (!routine.onNotification) schedule(routine)
        return "Created routine ${routine.id}: ${routine.describe()}: $prompt"
    }

    // --- Time triggers ---------------------------------------------------------------------------------------------

    /** The first run strictly after [after]. */
    private fun nextRun(r: Routine, after: ZonedDateTime): ZonedDateTime {
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
                synchronized(this) { save(all().filter { it.id != id }) }
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

    fun onNotification(sbn: StatusBarNotification) {
        if (!::context.isInitialized || sbn.packageName == context.packageName) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        synchronized(seen) {
            val fingerprint = "${sbn.key}|$title|$text"
            if (fingerprint in seen) return
            seen[fingerprint] = Unit
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
