package com.or1.agent.ui

import com.or1.agent.MediaApps
import com.or1.agent.tools.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// The pure side of the M6 cards (media, calendar day, email): JVM-tested, no Android.

/** One element of a `screen` result: `[3] Button 'Archive' @456,104 click`. */
data class ScreenElement(val id: Int, val cls: String, val label: String, val x: Int, val y: Int, val flags: Set<String>)

/** A `screen` / `act` / `open` result as the screen tool writes it (ScreenTools.read): the foreground package and elements. */
data class ScreenDump(val pkg: String, val elements: List<ScreenElement>) {
    companion object {
        private val HEADER = Regex("^Foreground: ([A-Za-z0-9_.]+)\\. Screen \\d+x\\d+\\.")
        private val LINE = Regex("^\\[(\\d+)] (\\S+) '(.*)' @(-?\\d+),(-?\\d+)(?: (.*))?$")

        /** The foreground package of a screen result, or null (no app, or1's own UI, not a screen result). */
        fun foreground(result: String): String? = HEADER.find(result.substringBefore('\n'))?.groupValues?.get(1)

        fun parse(result: String): ScreenDump? {
            val pkg = foreground(result) ?: return null
            val elements = result.lineSequence().drop(1).mapNotNull { line ->
                val m = LINE.matchEntire(line.trimEnd()) ?: return@mapNotNull null
                val (id, cls, label, x, y) = m.destructured
                ScreenElement(id.toInt(), cls, label, x.toInt(), y.toInt(), m.groupValues[6].split(' ').filter { it.isNotEmpty() }.toSet())
            }.toList()
            return ScreenDump(pkg, elements)
        }
    }
}

// --- Media -----------------------------------------------------------------------------------------------------------

object MediaCards {
    const val ASPECT = 16f / 9f

    fun isMedia(pkg: String, seen: Set<String> = MediaApps.seen): Boolean = pkg in MediaApps.KNOWN || pkg in seen

    /**
     * The 16:9 region of a [width] x [height] frame of the work display to show: the full width at the top for video
     * apps in portrait (their player is there; the work display has no status bar), centred a little above the middle
     * for audio apps (cover art), the middle of a landscape frame. Returns (left, top, width, height) in pixels.
     */
    fun crop(width: Int, height: Int, pkg: String): IntArray {
        if (width <= 0 || height <= 0) return intArrayOf(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
        var w = width
        var h = (width / ASPECT).toInt()
        if (h > height) {
            h = height
            w = (height * ASPECT).toInt().coerceAtMost(width)
        }
        val left = (width - w) / 2
        val top = when {
            width >= height -> (height - h) / 2
            pkg in MediaApps.VIDEO -> 0
            else -> (height * 0.38f - h / 2f).toInt()
        }.coerceIn(0, height - h)
        return intArrayOf(left, top, w, h)
    }
}

// --- Calendar day ----------------------------------------------------------------------------------------------------

/** A row of the calendar-day card. [time] is "09:30", or null for an all-day event. */
data class DayRow(val event: CalendarEvent, val day: LocalDate, val time: String?, val past: Boolean)

/**
 * What the calendar-day card shows: the first [rows] (all-day first within each day), [more] not shown, and where the
 * `live` now-line goes: before row [nowLine] (== rows.size: after the last shown, i.e. before "N more"), or null
 * (nothing today, or it falls among the rows not shown).
 */
data class DayView(val rows: List<DayRow>, val more: Int, val nowLine: Int?, val multiDay: Boolean)

object CalendarDays {
    const val MAX_ROWS = 5
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** The day an instance belongs to: all-day ones at their UTC date, timed ones at their local start (clamped to the range). */
    fun dayOf(e: CalendarEvent, from: LocalDate, zone: ZoneId): LocalDate =
        if (e.allDay) Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate().let { if (it.isBefore(from)) from else it }

    fun view(events: List<CalendarEvent>, from: LocalDate, days: Int, now: Long, zone: ZoneId, max: Int = MAX_ROWS): DayView {
        val until = from.plusDays(days.toLong())
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val all = events.map { it to dayOf(it, from, zone) }
            .filter { (_, d) -> !d.isBefore(from) && d.isBefore(until) }
            .sortedWith(compareBy<Pair<CalendarEvent, LocalDate>> { it.second }.thenBy { !it.first.allDay }.thenBy { it.first.begin }.thenBy { it.first.end }.thenBy { it.first.title })
            .map { (e, d) ->
                val past = if (e.allDay) d.isBefore(today) else e.end <= now
                DayRow(e, d, if (e.allDay) null else HHMM.format(Instant.ofEpochMilli(e.begin).atZone(zone)), past)
            }
        val shown = all.take(max)
        val line = if (all.none { it.day == today }) null else {
            val i = all.indexOfFirst { it.day.isAfter(today) || it.day == today && it.time != null && it.event.begin > now }
            (if (i < 0) all.size else i).takeIf { it <= shown.size }
        }
        return DayView(shown, all.size - shown.size, line, days > 1)
    }

    /** "Today", "Tomorrow", "Yesterday" or "Tue 14 Oct". */
    fun dayName(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.US).format(day)
    }

    /** The card's title: "Today", or "Today – Sun 18 Oct" for a range. */
    fun title(from: LocalDate, days: Int, today: LocalDate): String =
        if (days <= 1) dayName(from, today) else "${dayName(from, today)} – ${dayName(from.plusDays(days - 1L), today)}"
}

// --- Email -----------------------------------------------------------------------------------------------------------

/** An email open in Gmail on the work display, as read off its screen. [quote] may be empty. */
data class EmailRead(val from: String, val subject: String, val quote: String)

/**
 * Reads the message Gmail shows from a `screen` result, conservatively: null unless it's a message view (Archive in the
 * toolbar and a Reply button) with a subject on top and a sender above a "to me" line.
 */
object EmailReads {
    const val GMAIL = "com.google.android.gm"
    private const val MAX_QUOTE = 320
    /** How far (px) the "to me" line may sit from the sender row's Reply button, and how many elements above it the sender. */
    private const val ROW_SPAN = 160
    private const val SENDER_REACH = 3
    private val RECIPIENT = Regex("^to [^.!?:]{1,60}$", RegexOption.IGNORE_CASE)

    /** Toolbar and message chrome: never the subject, sender or body. */
    private val CHROME = setOf(
        "navigate up", "archive", "delete", "mark unread", "mark as unread", "more options", "add star", "remove star",
        "starred", "not starred", "move to", "snooze", "print", "reply", "reply all", "forward", "show details", "hide details",
        "show quoted text", "hide quoted text", "show trimmed content", "react with emoji", "add reaction", "summarize this email",
        "open in app", "load images", "always show images", "unsubscribe", "translate", "view entire message",
    )
    /** Gmail's system labels, shown as a chip after the subject. */
    private val LABELS = setOf("inbox", "important", "primary", "updates", "promotions", "social", "forums", "spam", "sent", "starred", "drafts", "all mail")
    private val TIME = Regex(
        "^(\\d{1,2}:\\d{2}( ?[AaPp]\\.?[Mm]\\.?)?|\\d{1,2} [A-Z][a-z]{2}( \\d{4})?|[A-Z][a-z]{2} \\d{1,2}(, \\d{4})?|Yesterday|Today|" +
            "\\d+ (min|mins|minutes|hour|hours|day|days) ago|\\d{1,2}/\\d{1,2}/\\d{2,4})( \\(.*\\))?$",
    )
    private val TEXT_CLASSES = setOf("TextView", "View", "Button", "WebView")

    fun parse(result: String): EmailRead? {
        val dump = ScreenDump.parse(result) ?: return null
        if (dump.pkg != GMAIL) return null
        val els = dump.elements
        val archive = els.indexOfFirst { base(it.label).equals("archive", ignoreCase = true) }
        val reply = els.indexOfFirst { base(it.label).equals("reply", ignoreCase = true) && it.y > (els.getOrNull(archive)?.y ?: Int.MAX_VALUE) }
        if (archive < 0 || reply < 0) return null
        val header = els.subList(archive + 1, reply)
        // The expanded message's sender: just above its "to me" line (earlier messages in a thread are collapsed rows).
        // Beside the sender row's Reply button, so a body line that starts with "To" can't pass for it.
        val to = header.indexOfLast { RECIPIENT.matches(base(it.label)) && kotlin.math.abs(it.y - els[reply].y) < ROW_SPAN }
        if (to < 0) return null
        val candidates = header.withIndex().filter { (_, e) -> isText(e) }
        val subject = candidates.firstOrNull()?.let { (i, e) -> i to stripLabel(base(e.label)) } ?: return null
        val from = candidates.lastOrNull { (i, _) -> i < to && i > subject.first && to - i <= SENDER_REACH }?.value?.label?.let(::base) ?: return null
        if (subject.second.isBlank() || from.isBlank() || from == subject.second) return null
        // The body: text after the sender row's buttons, up to the reply bar (or the end of what was read).
        val bodyStart = reply + 1
        val end = els.withIndex().indexOfFirst { (i, e) -> i > bodyStart && base(e.label).lowercase() in setOf("reply", "reply all", "forward") }
            .let { if (it < 0) els.size else it }
        val quote = els.subList(bodyStart.coerceAtMost(end), end).asSequence()
            .filter { isText(it) && it.cls != "WebView" && base(it.label) != subject.second && base(it.label) != from }
            .map { base(it.label) }.distinct().joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return EmailRead(from, subject.second, if (quote.length > MAX_QUOTE) quote.take(MAX_QUOTE).trimEnd() + "…" else quote)
    }

    /** The visible text of a label: "text (description)" is how the screen tool joins both. */
    private fun base(label: String): String {
        val i = label.lastIndexOf(" (")
        return (if (i > 0 && label.endsWith(")")) label.substring(0, i) else label).trim()
    }

    private fun isText(e: ScreenElement): Boolean {
        val text = base(e.label)
        if (text.isEmpty() || e.cls !in TEXT_CLASSES || "edit" in e.flags) return false
        val lower = text.lowercase()
        if (lower in CHROME || lower in LABELS || RECIPIENT.matches(text) || TIME.matches(text)) return false
        // Bare resource ids (the screen tool's last resort for a label) aren't text.
        if (Regex("^[a-z0-9_]+$").matches(text) && '_' in text) return false
        return true
    }

    /** "Lunch on Friday? Inbox" → "Lunch on Friday?": Gmail draws the label chip into the subject's text. */
    private fun stripLabel(subject: String): String {
        var s = subject
        while (true) {
            val cut = s.trimEnd().lastIndexOfAny(charArrayOf(' ', ' ', ' '))
            if (cut <= 0 || s.substring(cut + 1).trim().lowercase() !in LABELS) return s.trim()
            s = s.substring(0, cut)
        }
    }
}
