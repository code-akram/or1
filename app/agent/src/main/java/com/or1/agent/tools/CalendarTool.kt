package com.or1.agent.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calendar events through the calendar provider (no UI driving). There is no Google account, so events go to a local
 * "Phone" calendar, created on first use; Etar shows and edits it.
 */
class CalendarTool(context: Context) {
    private val resolver = context.contentResolver
    private val shown = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm", Locale.US)
    private val shownDay = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.US)

    fun run(args: JSONObject): ToolOutput = when (val action = args.getString("action")) {
        "list" -> ToolOutput(list(args))
        "add" -> add(args)
        "delete" -> delete(args)
        else -> throw IllegalArgumentException("Unknown action '$action'.")
    }

    private fun list(args: JSONObject): String {
        val zone = ZoneId.systemDefault()
        val from = args.optString("date").takeIf { it.isNotBlank() }?.let(::parseDate) ?: LocalDate.now(zone)
        val days = args.optInt("days", 7).coerceIn(1, 366)
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, begin); ContentUris.appendId(it, end) }.build()
        val projection = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION)
        val lines = mutableListOf<String>()
        resolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(4) == 1
                // All-day events are stored at UTC midnight.
                val start = java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(if (allDay) ZoneOffset.UTC else zone)
                val stop = java.time.Instant.ofEpochMilli(c.getLong(3)).atZone(if (allDay) ZoneOffset.UTC else zone)
                val time = if (allDay) "${shownDay.format(start)} (all day)" else "${shown.format(start)}-${"%02d:%02d".format(stop.hour, stop.minute)}"
                val location = c.getString(5)?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""
                lines += "- [${c.getLong(0)}] $time ${c.getString(1).orEmpty()}$location"
            }
        }
        val range = if (days == 1) shownDay.format(from) else "${shownDay.format(from)} to ${shownDay.format(from.plusDays(days - 1L))}"
        return if (lines.isEmpty()) "No events from $range." else "Events from $range (event ids in brackets):\n" + lines.joinToString("\n")
    }

    private fun add(args: JSONObject): ToolOutput {
        val title = args.optString("title").ifBlank { throw IllegalArgumentException("add needs `title`.") }
        val startText = args.optString("start").ifBlank { throw IllegalArgumentException("add needs `start` (YYYY-MM-DDTHH:MM, or YYYY-MM-DD for all day).") }
        val allDay = args.optBoolean("all_day") || !startText.contains('T')
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId())
            put(Events.TITLE, title)
            args.optString("location").takeIf { it.isNotBlank() }?.let { put(Events.EVENT_LOCATION, it) }
            args.optString("notes").takeIf { it.isNotBlank() }?.let { put(Events.DESCRIPTION, it) }
        }
        val summary: String
        if (allDay) {
            val day = parseDate(startText.substringBefore('T'))
            val until = args.optString("end").takeIf { it.isNotBlank() }?.let { parseDate(it.substringBefore('T')) } ?: day
            require(!until.isBefore(day)) { "`end` is before `start`." }
            values.put(Events.ALL_DAY, 1)
            values.put(Events.EVENT_TIMEZONE, "UTC")
            values.put(Events.DTSTART, day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            values.put(Events.DTEND, until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            summary = "${shownDay.format(day)} (all day)"
        } else {
            val zone = ZoneId.systemDefault()
            val start = parseDateTime(startText)
            val end = args.optString("end").takeIf { it.isNotBlank() }?.let(::parseDateTime)
                ?: start.plusMinutes(args.optInt("duration_minutes", 60).coerceIn(1, 24 * 60).toLong())
            require(end.isAfter(start)) { "`end` must be after `start`." }
            values.put(Events.EVENT_TIMEZONE, zone.id)
            values.put(Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            values.put(Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            summary = "${shown.format(start)}-${"%02d:%02d".format(end.hour, end.minute)}"
        }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("The calendar provider did not save the event.")
        val id = ContentUris.parseId(uri)
        val reminder = args.optInt("reminder_minutes", -1)
        if (reminder >= 0) {
            resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, id)
                put(Reminders.MINUTES, reminder)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        return ToolOutput(
            "Added “$title” on $summary (event id $id)" + (if (reminder >= 0) ", with a reminder $reminder minutes before." else "."),
            undo = JSONObject().put("op", "calendar_delete").put("event_id", id).put("title", title),
        )
    }

    /** Deletes an event; the undo re-creates it from a copy of its fields and reminders (under a new id). */
    private fun delete(args: JSONObject): ToolOutput {
        val id = args.optLong("event_id", -1).takeIf { it >= 0 } ?: throw IllegalArgumentException("delete needs `event_id` (see list).")
        val event = snapshot(id) ?: return ToolOutput("No event with id $id.")
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
        val title = event.optString(Events.TITLE)
        return ToolOutput(
            "Deleted “$title” (event id $id).",
            undo = JSONObject().put("op", "calendar_restore").put("title", title).put("event", event).put("reminders", reminders(id)),
        )
    }

    /** Undo of add. */
    fun deleteEvent(id: Long): String {
        val event = snapshot(id) ?: error("The event was already deleted.")
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
        return "Deleted “${event.optString(Events.TITLE)}”."
    }

    /** Undo of delete: inserts the event saved by [delete] again, with its reminders. */
    fun restoreEvent(spec: JSONObject): String {
        val event = JSONObject(spec.getJSONObject("event").toString())
        // The provider wants DURATION for a recurring event and DTEND for a single one, not both.
        if (event.has(Events.RRULE) || event.has(Events.RDATE)) event.remove(Events.DTEND) else event.remove(Events.DURATION)
        val values = ContentValues()
        for (key in event.keys()) when (val v = event.get(key)) {
            is Number -> values.put(key, v.toLong())
            else -> values.put(key, v.toString())
        }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("The calendar provider did not save the event.")
        val id = ContentUris.parseId(uri)
        val reminders = spec.optJSONArray("reminders")
        for (i in 0 until (reminders?.length() ?: 0)) {
            val r = reminders!!.getJSONObject(i)
            resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, id)
                put(Reminders.MINUTES, r.getInt("minutes"))
                put(Reminders.METHOD, r.optInt("method", Reminders.METHOD_ALERT))
            })
        }
        return "Put back “${event.optString(Events.TITLE)}” (event id $id)."
    }

    /** The event's own fields (what [restoreEvent] needs), or null if there's no such event. */
    private fun snapshot(id: Long): JSONObject? {
        val columns = arrayOf(
            Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE,
            Events.EVENT_END_TIMEZONE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.RRULE, Events.RDATE, Events.EXRULE,
            Events.EXDATE, Events.HAS_ALARM, Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.DELETED,
        )
        return resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), columns, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(columns.indexOf(Events.DELETED)) != 0) return@use null
            JSONObject().also { o ->
                for ((i, name) in columns.withIndex()) {
                    if (name == Events.DELETED || name == Events.HAS_ALARM || c.isNull(i)) continue
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                        else -> o.put(name, c.getString(i))
                    }
                }
            }
        }
    }

    private fun reminders(eventId: Long): JSONArray {
        val out = JSONArray()
        resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null)
            ?.use { c -> while (c.moveToNext()) out.put(JSONObject().put("minutes", c.getInt(0)).put("method", c.getInt(1))) }
        return out
    }

    /** The first visible calendar we can write to, or a new local "Phone" calendar. */
    private fun calendarId(): Long {
        resolver.query(
            Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}", null,
            "${Calendars._ID} ASC",
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        // Local calendars are created through the sync-adapter URI, as the account that owns them.
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, LOCAL_ACCOUNT)
            put(Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            put(Calendars.CALENDAR_COLOR, 0xFFD97757.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return ContentUris.parseId(resolver.insert(uri, values) ?: error("Could not create a local calendar."))
    }

    private fun parseDate(text: String): LocalDate = try {
        LocalDate.parse(text.trim())
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Dates are YYYY-MM-DD, got '$text'.")
    }

    private fun parseDateTime(text: String): LocalDateTime = try {
        LocalDateTime.parse(text.trim())
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Times are YYYY-MM-DDTHH:MM in local time, got '$text'.")
    }

    private companion object {
        const val LOCAL_ACCOUNT = "Phone"
    }
}
