package com.or1.agent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.Icon as AndroidIcon
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.ChatItem
import com.or1.agent.Effect
import com.or1.agent.NotificationGroup
import com.or1.agent.NowState
import com.or1.agent.NowTimer
import com.or1.agent.Routines
import com.or1.agent.TimerState
import com.or1.agent.UndoState
import com.or1.agent.WaitingNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.delay
import org.json.JSONObject

// --- What a turn's result cards are (pure, JVM-tested) ---------------------------------------------------------------

/**
 * A live result card (docs/ui-ledger.md §5), derived from a turn's tool calls and effects, never from the reply's
 * prose. A card that stands for effects ([effectIds]) carries their undo itself (Cancel, Off, Undo), so those rows
 * leave the receipt: one presentation per effect.
 */
sealed interface ResultCard {
    /** Stable within the stream. */
    val key: String
    val effectIds: List<String> get() = emptyList()
    /** Whether its live version follows a source ([NowState], the routines): those count toward [ResultCards.MAX_LIVE]. */
    val ticks: Boolean

    data class Timer(val effect: Effect, val seconds: Int, val label: String?) : ResultCard {
        override val key get() = "timer:${effect.id}"
        override val effectIds get() = listOf(effect.id)
        override val ticks get() = true
    }

    data class Alarm(val effect: Effect, val hour: Int, val minute: Int, val label: String?) : ResultCard {
        override val key get() = "alarm:${effect.id}"
        override val effectIds get() = listOf(effect.id)
        override val ticks get() = true
    }

    /** Settings the turn changed (`device set`): the promoted receipt rows. */
    data class Settings(val effects: List<Effect>) : ResultCard {
        override val key get() = "settings:${effects.first().id}"
        override val effectIds get() = effects.map { it.id }
        override val ticks get() = false
    }

    /** The turn read the notifications: what's waiting now. */
    data class Notifications(val callId: Long) : ResultCard {
        override val key get() = "notifications:$callId"
        override val ticks get() = true
    }

    /** People found (`contacts find`, [more] beyond those shown) or the one added ([added], with its undo). */
    data class Contacts(val people: List<ContactInfo>, val more: Int, val added: Effect?, val callId: Long) : ResultCard {
        override val key get() = added?.let { "contact:${it.id}" } ?: "contacts:$callId"
        override val effectIds get() = listOfNotNull(added?.id)
        override val ticks get() = false
    }

    data class RoutineCreated(val effect: Effect, val routineId: String, val title: String?) : ResultCard {
        override val key get() = "routine:${effect.id}"
        override val effectIds get() = listOf(effect.id)
        override val ticks get() = true
    }

    /** The turn left a media app ([pkg]) in front on the work display: a live crop of it ([callId]: that screen read). */
    data class Media(val callId: Long, val pkg: String) : ResultCard {
        override val key get() = "media:$callId"
        override val ticks get() = true
    }

    /** `calendar list` found events: the days it asked for ([date] and [days] as the call passed them), from the provider. */
    data class CalendarDay(val callId: Long, val date: String?, val days: String?) : ResultCard {
        override val key get() = "calendar:$callId"
        override val ticks get() = true
    }

    /** The turn read an email in Gmail on the work display. */
    data class Email(val callId: Long, val email: EmailRead) : ResultCard {
        override val key get() = "email:$callId"
        override val ticks get() = false
    }
}

data class ContactInfo(val name: String, val phones: List<String>, val emails: List<String>)

enum class TimerPhase { RUNNING, PAUSED, RINGING, DONE, CANCELLING, CANCELLED }

/** What a timer card shows. [endsAtElapsed] while running; [remainingMs] while paused or cancelling. */
data class TimerView(val phase: TimerPhase, val endsAtElapsed: Long? = null, val remainingMs: Long? = null)

object ResultCards {
    /** At most this many cards follow their sources; older ones render frozen (static text). */
    const val MAX_LIVE = 4
    const val MAX_PEOPLE = 3
    /** Right after `set_timer`, [NowState] may not have picked the timer up yet: count down from the effect meanwhile. */
    const val TRACK_GRACE_MS = 5_000L

    /** A turn's cards, in the order their calls ran. [isMedia]: whether a package plays media ([MediaCards.isMedia]). */
    fun of(turn: Turn, isMedia: (String) -> Boolean = { MediaCards.isMedia(it) }): List<ResultCard> {
        val out = mutableListOf<Pair<Long, ResultCard>>()
        val settings = mutableListOf<Effect>()
        var settingsAt = 0L
        for (item in turn.effects) {
            val e = item.effect
            val undo = e.undo?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            when {
                e.tool == "clock" && undo.optString("op") == "timer" ->
                    out += item.id to ResultCard.Timer(e, undo.optInt("seconds"), undo.text("label"))
                e.tool == "clock" && undo.optString("op") == "alarm" ->
                    out += item.id to ResultCard.Alarm(e, undo.optInt("hour"), undo.optInt("minute"), undo.text("label"))
                e.tool == "device" -> {
                    if (settings.isEmpty()) settingsAt = item.id
                    settings += e
                }
                e.tool == "contacts" && undo.optString("op") == "contact_delete" -> {
                    val args = callArgs(e.call)
                    val name = args["name"]?.ifBlank { null } ?: continue
                    val person = ContactInfo(name, listOfNotNull(args["phone"]?.ifBlank { null }), listOfNotNull(args["email"]?.ifBlank { null }))
                    out += item.id to ResultCard.Contacts(listOf(person), 0, e, item.id)
                }
                e.tool == "routines" && undo.optString("op") == "routine_remove" ->
                    out += item.id to ResultCard.RoutineCreated(e, undo.optString("id"), undo.text("title"))
            }
        }
        if (settings.isNotEmpty()) out += settingsAt to ResultCard.Settings(settings)

        val calls = turn.toolCalls.filter { it.result != null && !it.isError }
        calls.firstOrNull { callTool(it.detail) == "notifications" && !it.result!!.startsWith("Notification access") }
            ?.let { out += it.id to ResultCard.Notifications(it.id) }
        val finds = calls.filter { callTool(it.detail) == "contacts" && callArgs(it.detail)["action"] == "find" }
        val people = finds.flatMap { parseFound(it.result!!) }.distinctBy { it.name.lowercase() }
        if (people.isNotEmpty()) {
            out += finds.first().id to ResultCard.Contacts(people.take(MAX_PEOPLE), (people.size - MAX_PEOPLE).coerceAtLeast(0), null, finds.first().id)
        }
        // The last `calendar list` that found something (an empty day gets no card: the reply says so).
        calls.lastOrNull { callTool(it.detail) == "calendar" && callArgs(it.detail)["action"] == "list" && it.result!!.startsWith("Events from") }
            ?.let { val args = callArgs(it.detail); out += it.id to ResultCard.CalendarDay(it.id, args["date"], args["days"]) }
        // What the work display was left showing: a media app gets the live crop.
        val screens = calls.filter { callTool(it.detail) in SCREEN_TOOLS }
        screens.lastOrNull()?.let { last ->
            val pkg = ScreenDump.foreground(last.result!!)
            if (pkg != null && isMedia(pkg)) out += last.id to ResultCard.Media(last.id, pkg)
        }
        // The last message read in Gmail.
        screens.asReversed().firstNotNullOfOrNull { call ->
            if (ScreenDump.foreground(call.result!!) != EmailReads.GMAIL) null else EmailReads.parse(call.result)?.let { call.id to it }
        }?.let { (id, email) -> out += id to ResultCard.Email(id, email) }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** The receipt rows left once [cards] took theirs: an effect shows in one place only. */
    fun receipt(turn: Turn, cards: List<ResultCard>): List<ChatItem.Effect> {
        val promoted = cards.flatMap { it.effectIds }.toSet()
        return turn.effects.filter { it.effect.id !in promoted }
    }

    /**
     * Which cards are live: the newest [max] that follow a source, newest turn first; only the newest notifications
     * card (they'd all show the same thing), and only the newest media card (there's one work display).
     */
    fun liveKeys(turns: List<List<ResultCard>>, max: Int = MAX_LIVE): Set<String> {
        val live = mutableSetOf<String>()
        var notifications = false
        var media = false
        for (cards in turns.asReversed()) for (card in cards.asReversed()) {
            if (live.size >= max) return live
            if (!card.ticks) continue
            if (card is ResultCard.Notifications) {
                if (notifications) continue
                notifications = true
            }
            if (card is ResultCard.Media) {
                if (media) continue
                media = true
            }
            live += card.key
        }
        return live
    }

    /** Calls whose result is a screen read (`Foreground: <pkg>. Screen …`). */
    private val SCREEN_TOOLS = setOf("open", "act", "screen")

    /** The tool of a raw call ("contacts action=find query=Sam" → "contacts"). */
    fun callTool(detail: String): String = detail.substringBefore(' ')

    /**
     * The arguments of a raw call as [com.or1.agent.tools.Tools.describe] writes it: `tool key=value key=value`. A value
     * ends where the next ` key=` begins, so values with spaces survive.
     */
    fun callArgs(detail: String): Map<String, String> {
        val rest = detail.substringAfter(' ', "")
        if (rest.isEmpty()) return emptyMap()
        return rest.split(Regex(" (?=[a-z_]+=)")).mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }.toMap()
    }

    /** `contacts find` output: "- Name: +1 555, sam@x.com" per person (or "no number or email"). */
    fun parseFound(result: String): List<ContactInfo> = result.lines().mapNotNull { line ->
        if (!line.startsWith("- ")) return@mapNotNull null
        val body = line.removePrefix("- ")
        val cut = body.lastIndexOf(": ")
        if (cut <= 0) return@mapNotNull null
        val name = body.substring(0, cut).trim()
        val details = body.substring(cut + 2).trim().takeIf { it != "no number or email" }
            ?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        ContactInfo(name, details.filter { '@' !in it }, details.filter { '@' in it })
    }

    /** A timer card's state from its effect and the timer [NowState] follows for it (null when it follows none). */
    fun timerView(effect: Effect, seconds: Int, timer: NowTimer?, wallNow: Long, elapsedNow: Long): TimerView {
        val endWall = effect.time + seconds * 1000L
        return when {
            effect.state == UndoState.UNDONE -> TimerView(TimerPhase.CANCELLED)
            effect.state == UndoState.PENDING -> TimerView(
                TimerPhase.CANCELLING,
                remainingMs = timer?.remainingMs ?: timer?.endsAtElapsed?.let { it - elapsedNow }?.coerceAtLeast(0),
            )
            timer != null -> when (timer.state) {
                TimerState.RUNNING -> TimerView(TimerPhase.RUNNING, endsAtElapsed = timer.endsAtElapsed ?: (elapsedNow + endWall - wallNow))
                TimerState.PAUSED -> TimerView(TimerPhase.PAUSED, remainingMs = timer.remainingMs ?: 0)
                TimerState.ENDED -> TimerView(TimerPhase.RINGING)
            }
            wallNow < endWall && wallNow < effect.time + TRACK_GRACE_MS -> TimerView(TimerPhase.RUNNING, endsAtElapsed = elapsedNow + endWall - wallNow)
            // Gone before its end: cancelled in the Clock app (or1's own cancel is the undo, above).
            wallNow < endWall -> TimerView(TimerPhase.CANCELLED)
            else -> TimerView(TimerPhase.DONE)
        }
    }

    /** A countdown: "9:41", "12:05", "1:02:03" (seconds rounded up, so it shows 0:00 only at the end). */
    fun clock(remainingMs: Long): String {
        val s = ceil(remainingMs.coerceAtLeast(0) / 1000.0).toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** For the accessibility tree, which mustn't change every second: "about 12 minutes left". */
    fun coarseRemaining(remainingMs: Long): String {
        val minutes = ceil(remainingMs.coerceAtLeast(0) / 60_000.0).toLong()
        return when {
            remainingMs <= 0 -> "done"
            minutes <= 1 -> "less than a minute left"
            minutes < 60 -> "about $minutes minutes left"
            else -> "about ${minutes / 60} h ${minutes % 60} min left"
        }
    }

    /** "45 s", "10 min", "1 h 30 min". */
    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> if (seconds % 60 == 0) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        else -> "${seconds / 3600} h ${seconds / 60 % 60} min"
    }

    /** When an alarm set at [setAt] for [hour]:[minute] rings: the next such time after it was set (epoch ms). */
    fun alarmRingsAt(setAt: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val set = Instant.ofEpochMilli(setAt).atZone(zone)
        var at = set.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!at.isAfter(set)) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    /** "Today", "Tomorrow", or "Tue 14 Oct", for [at] seen from [now]. */
    fun day(at: Long, now: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.US).format(d)
        }
    }

    /** "Tomorrow 08:00". */
    fun nextRun(at: Long, now: Long, zone: ZoneId): String =
        day(at, now, zone) + " " + DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(Instant.ofEpochMilli(at).atZone(zone))

    /** How far off an event is, for the Now strip: "now" (under way), "in 25 min", or its start time past an hour. */
    fun until(begin: Long, now: Long, zone: ZoneId): String {
        if (begin <= now) return "now"
        val minutes = ceil((begin - now) / 60_000.0).toLong()
        return if (minutes < 60) "in $minutes min" else "at " + DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(Instant.ofEpochMilli(begin).atZone(zone))
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
}

// --- Ticking ----------------------------------------------------------------------------------------------------------

/**
 * [SystemClock.elapsedRealtime], refreshed at each second boundary of a countdown to [endsAt] while [running] (one
 * coroutine per card or chip). Read it only where the countdown is drawn, so a tick recomposes that one Text.
 */
@Composable
fun rememberCountdownClock(endsAt: Long?, running: Boolean): State<Long> {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (running && endsAt != null) {
        LaunchedEffect(endsAt) {
            while (true) {
                val t = SystemClock.elapsedRealtime()
                now.longValue = t
                val left = endsAt - t
                if (left <= 0) break
                // To the moment the shown second changes (it's rounded up).
                delay((left - 1) % 1000 + 1)
            }
        }
    }
    return now
}

/**
 * The one Text that ticks. Its semantics are cleared and set to a coarse description that changes once a minute, so
 * the always-on accessibility service doesn't see a change every second.
 */
@Composable
fun CountdownText(remaining: () -> Long, style: TextStyle, prefix: String = "Timer", modifier: Modifier = Modifier) {
    val left = remaining()
    val coarse = ResultCards.coarseRemaining(left)
    val semantics = remember(coarse, prefix) { Modifier.clearAndSetSemantics { contentDescription = "$prefix, $coarse" } }
    Text(ResultCards.clock(left), style = style, maxLines = 1, modifier = modifier.then(semantics))
}

private const val METER_COLUMNS = 30

/** Remaining time as columns of 3 dp blocks (two high), lit in `live`; read in the draw phase, so ticks only redraw. */
@Composable
private fun BlockMeter(fraction: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(CARD_BLOCK * (METER_COLUMNS * 2 - 1), CARD_BLOCK * 3)) {
        val b = CARD_BLOCK.toPx()
        val lit = ceil(fraction().coerceIn(0f, 1f) * METER_COLUMNS).toInt()
        for (i in 0 until METER_COLUMNS) {
            val color = if (i < lit) Palette.Live else Palette.Line
            drawRect(color, topLeft = Offset(i * 2 * b, 0f), size = Size(b, b))
            drawRect(color, topLeft = Offset(i * 2 * b, 2 * b), size = Size(b, b))
        }
    }
}

// --- Cards ------------------------------------------------------------------------------------------------------------

/**
 * What cards can open outside the stream. Null (the routines thread) hides the button. [openViewer] opens the work
 * screen viewer (the media, email and calendar cards).
 */
class CardActions(val openRoutines: (() -> Unit)? = null, val openTriage: (() -> Unit)? = null, val openViewer: (() -> Unit)? = null)

val LocalCardActions = staticCompositionLocalOf { CardActions() }

/** A turn's result card; [live] ones follow their source, the rest render frozen from what the turn recorded. */
@Composable
fun ResultCardView(card: ResultCard, live: Boolean) {
    when (card) {
        is ResultCard.Timer -> TimerCard(card, live)
        is ResultCard.Alarm -> AlarmCard(card, live)
        is ResultCard.Settings -> SettingsCard(card)
        is ResultCard.Notifications -> if (live) NotificationsCard() else FrozenNotificationsCard()
        is ResultCard.Contacts -> ContactsCard(card)
        is ResultCard.RoutineCreated -> RoutineCard(card, live)
        is ResultCard.Media -> MediaCard(card, live)
        is ResultCard.CalendarDay -> CalendarDayCard(card, live)
        is ResultCard.Email -> EmailCard(card)
    }
}

@Composable
internal fun CardFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(Shapes.card).background(Palette.Ink2).border(1.dp, Palette.Line, Shapes.card),
        content = content,
    )
}

/** Glyph, title and the effect's action (or its undo state) on the right: the top row of a card. */
@Composable
internal fun CardHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: androidx.compose.ui.graphics.Color = Palette.Text2, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = Type.Step, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action()
    }
}

/** An effect's state, woken once at its expiry (no ticking), like the receipt rows. */
@Composable
private fun effectState(effect: Effect): UndoState {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val state = effect.stateAt(now)
    if (state == UndoState.AVAILABLE && effect.expiresAt > 0) {
        LaunchedEffect(effect.expiresAt) {
            delay((effect.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 50)
            now = System.currentTimeMillis()
        }
    }
    return state
}

/** The effect's undo, as the card names it ("Cancel", "Off", "Undo"), or where it stands. */
@Composable
private fun EffectAction(effect: Effect, state: UndoState, label: String, undone: String, onClick: () -> Unit) {
    when (state) {
        UndoState.AVAILABLE -> UndoChip(label, onClick)
        UndoState.PENDING -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
            March()
            Spacer(Modifier.width(8.dp))
            Text("Undoing", style = Type.Caption)
        }
        UndoState.UNDONE -> StateNote(undone)
        UndoState.FAILED -> Text(
            "Couldn't undo", style = Type.Caption.copy(color = Palette.Error), modifier = Modifier.padding(horizontal = 10.dp),
        )
        else -> {}
    }
}

@Composable
private fun FailedReason(effect: Effect, state: UndoState) {
    if (state == UndoState.FAILED) {
        Text(
            effect.detail ?: "The undo didn't go through.", style = Type.Caption.copy(color = Palette.Error), maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun TimerCard(card: ResultCard.Timer, live: Boolean) {
    val state = effectState(card.effect)
    val title = card.label?.let { "Timer · $it" } ?: "${ResultCards.duration(card.seconds)} timer"
    if (!live) {
        // Frozen: where it stood when it scrolled out of the live set, as text.
        val view = ResultCards.timerView(card.effect, card.seconds, null, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        CardFrame {
            CardHeader(Icons.Timer, title) {
                Text(frozenTimerNote(view.phase), style = Type.Caption, modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
        return
    }
    val timers by NowState.timers.collectAsState()
    val timer = timers.firstOrNull { it.id == card.effect.id }
    val view = ResultCards.timerView(card.effect, card.seconds, timer, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    val running = view.phase == TimerPhase.RUNNING
    val now = rememberCountdownClock(view.endsAtElapsed, running)
    val total = card.seconds * 1000L
    CardFrame {
        CardHeader(Icons.Timer, title, tint = if (running) Palette.Live else Palette.Text2) {
            when (view.phase) {
                TimerPhase.RUNNING, TimerPhase.PAUSED, TimerPhase.CANCELLING ->
                    EffectAction(card.effect, state, "Cancel", "Cancelled") { NowState.cancelTimer(card.effect.id) }
                else -> {}
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            when (view.phase) {
                TimerPhase.RUNNING -> {
                    val end = view.endsAtElapsed!!
                    CountdownText({ end - now.value }, Type.Numeral)
                    Spacer(Modifier.size(10.dp))
                    BlockMeter({ if (total > 0) (end - now.value).toFloat() / total else 0f })
                }
                TimerPhase.PAUSED, TimerPhase.CANCELLING -> {
                    val left = view.remainingMs ?: 0
                    Text(
                        ResultCards.clock(left), style = Type.Numeral.copy(color = Palette.Text2),
                        modifier = Modifier.clearAndSetSemantics { contentDescription = "Timer, ${ResultCards.coarseRemaining(left)}" },
                    )
                    Spacer(Modifier.size(10.dp))
                    BlockMeter({ if (total > 0) left.toFloat() / total else 0f })
                    Text(if (view.phase == TimerPhase.PAUSED) "Paused" else "Cancelling", style = Type.Caption, modifier = Modifier.padding(top = 8.dp))
                }
                TimerPhase.RINGING -> Text("Time's up", style = Type.NumeralS.copy(color = Palette.Live))
                TimerPhase.DONE -> Text("Timer done", style = Type.NumeralS.copy(color = Palette.Text2))
                TimerPhase.CANCELLED -> Text("Cancelled", style = Type.NumeralS.copy(color = Palette.Text3))
            }
        }
        FailedReason(card.effect, state)
    }
}

private fun frozenTimerNote(phase: TimerPhase) = when (phase) {
    TimerPhase.RUNNING, TimerPhase.PAUSED -> "Running"
    TimerPhase.CANCELLING -> "Cancelling"
    TimerPhase.CANCELLED -> "Cancelled"
    TimerPhase.RINGING, TimerPhase.DONE -> "Done"
}

@Composable
private fun AlarmCard(card: ResultCard.Alarm, live: Boolean) {
    val state = effectState(card.effect)
    val time = "%02d:%02d".format(card.hour, card.minute)
    val zone = ZoneId.systemDefault()
    val ringsAt = remember(card.effect.time, card.hour, card.minute) { ResultCards.alarmRingsAt(card.effect.time, card.hour, card.minute, zone) }
    val title = card.label?.let { "Alarm · $it" } ?: "Alarm"
    if (!live) {
        CardFrame {
            CardHeader(Icons.Alarm, "$title $time") {
                EffectAction(card.effect, state, "Off", "Turned off") { AgentHub.undo(card.effect.id) }
            }
        }
        return
    }
    val next by NowState.alarm.collectAsState()
    val isNext = next?.let { it.effectId == card.effect.id || it.at == ringsAt } == true
    CardFrame {
        CardHeader(Icons.Alarm, title) {
            EffectAction(card.effect, state, "Off", "Turned off") {
                if (next?.effectId == card.effect.id) NowState.alarmOff() else AgentHub.undo(card.effect.id)
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            val off = state == UndoState.UNDONE
            Text(time, style = Type.Numeral.copy(color = if (off) Palette.Text3 else Palette.Text))
            val note = when {
                off -> "Off"
                state == UndoState.EXPIRED -> "Rang"
                isNext -> ResultCards.day(ringsAt, System.currentTimeMillis(), zone) + " · next alarm"
                else -> ResultCards.day(ringsAt, System.currentTimeMillis(), zone)
            }
            Text(note, style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
        }
        FailedReason(card.effect, state)
    }
}

/** The settings the turn changed: the receipt rows themselves, promoted into the stream (state + Undo). */
@Composable
private fun SettingsCard(card: ResultCard.Settings) {
    CardFrame {
        card.effects.forEachIndexed { i, effect ->
            key(effect.id) {
                ReceiptRow(effect, { AgentHub.undo(it.id) }, (if (i > 0) Modifier.hairlineTop() else Modifier).padding(start = 14.dp, end = 4.dp))
            }
        }
    }
}

@Composable
private fun ContactsCard(card: ResultCard.Contacts) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }
    copied?.let { value -> LaunchedEffect(value) { delay(1500); copied = null } }
    CardFrame {
        card.people.forEachIndexed { i, person ->
            Column(if (i > 0) Modifier.hairlineTop() else Modifier) {
                val added = card.added
                if (added != null) {
                    val state = effectState(added)
                    CardHeader(Icons.Person, person.name, tint = Palette.Text2) {
                        EffectAction(added, state, "Undo", "Removed") { AgentHub.undo(added.id) }
                    }
                } else {
                    CardHeader(Icons.Person, person.name)
                }
                for (value in person.phones + person.emails) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Copy") { clipboard.setText(AnnotatedString(value)); copied = value }
                            .padding(start = 40.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(value, style = Type.Body.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (copied == value) Text("Copied", style = Type.Caption)
                        else Icon(Icons.Copy, "Copy", tint = Palette.Text3, modifier = Modifier.size(16.dp))
                    }
                }
                if (person.phones.isEmpty() && person.emails.isEmpty()) {
                    Text("No number or email", style = Type.Caption, modifier = Modifier.padding(start = 40.dp, bottom = 12.dp))
                }
            }
        }
        if (card.more > 0) {
            Text("${card.more} more in contacts", style = Type.Caption, modifier = Modifier.hairlineTop().fillMaxWidth().padding(start = 40.dp, top = 12.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun RoutineCard(card: ResultCard.RoutineCreated, live: Boolean) {
    val state = effectState(card.effect)
    val actions = LocalCardActions.current
    val routine = if (live) {
        val list by Routines.list.collectAsState()
        list.firstOrNull { it.id == card.routineId }
    } else null
    CardFrame {
        CardHeader(Icons.Routine, "Routine") {
            EffectAction(card.effect, state, "Undo", "Removed") { AgentHub.undo(card.effect.id) }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = if (actions.openRoutines == null) 14.dp else 4.dp)) {
            val gone = state == UndoState.UNDONE
            Text(
                routine?.title ?: card.title ?: card.effect.label, style = Type.Label.copy(color = if (gone) Palette.Text3 else Palette.Text),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (routine != null) {
                // The trigger as a sentence, then the request when the name doesn't already say it.
                if (routine.name != null) Text(routine.describe(), style = Type.Step, modifier = Modifier.padding(top = 2.dp))
                Text(routine.prompt, style = Type.Step.copy(color = Palette.Text3), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (!routine.onNotification && routine.next > 0) {
                    Text(
                        "Next run " + ResultCards.nextRun(routine.next, System.currentTimeMillis(), ZoneId.systemDefault()),
                        style = Type.Caption.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else if (live && !gone) {
                Text("Removed since", style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
            }
        }
        actions.openRoutines?.let { open ->
            Row(Modifier.padding(start = 4.dp, bottom = 4.dp)) { UndoChip("Edit", open) }
        }
        FailedReason(card.effect, state)
    }
}

/** What's waiting now, by app (the same rows as the triage sheet); a few here, all of them in the sheet. */
@Composable
private fun NotificationsCard() {
    val groups by NowState.notifications.collectAsState()
    val actions = LocalCardActions.current
    val count = groups.sumOf { it.items.size }
    CardFrame {
        CardHeader(Icons.Bell, "Notifications") {
            Text(if (count == 0) "None waiting" else "$count waiting", style = Type.Caption.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(horizontal = 10.dp))
        }
        var shown = 0
        for (group in groups.take(CARD_GROUPS)) {
            key(group.pkg) {
                NotificationGroupHeader(group, Modifier.hairlineTop().padding(start = 14.dp, end = 14.dp))
                for (n in group.items.take(CARD_ROWS)) {
                    key(n.key) {
                        NotificationRow(n, onDismiss = { NowState.dismiss(n.key) }, onHandle = { NowState.handle(n) }, Modifier.padding(start = 14.dp, end = 4.dp))
                    }
                    shown++
                }
            }
        }
        val rest = count - shown
        val open = actions.openTriage
        if (rest > 0 && open != null) {
            Row(
                Modifier.hairlineTop().fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = open).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("All $count waiting", style = Type.Step.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                Icon(Icons.ExpandLess, null, tint = Palette.Text3, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private const val CARD_GROUPS = 3
private const val CARD_ROWS = 2

/** An older turn's notifications card: one row that opens the triage sheet. */
@Composable
private fun FrozenNotificationsCard() {
    val open = LocalCardActions.current.openTriage
    CardFrame(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier) {
        CardHeader(Icons.Bell, "Read the notifications") {
            if (open != null) Text("What's waiting", style = Type.Caption, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}

// --- Notification rows (the card and the triage sheet) ----------------------------------------------------------------

@Composable
fun NotificationGroupHeader(group: NotificationGroup, modifier: Modifier = Modifier) {
    val first = group.items.first()
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppGlyph(first.pkg, first.iconPackage, first.iconRes)
        Spacer(Modifier.width(10.dp))
        Text(group.app, style = Type.Caption.copy(color = Palette.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (group.items.size > 1) Text("${group.items.size}", style = Type.Caption.copy(fontFeatureSettings = "tnum"))
    }
}

/** Two lines (title, text) with Dismiss and Handle, both 48 dp. No swipe: the digitiser is flaky. */
@Composable
fun NotificationRow(n: WaitingNotification, onDismiss: () -> Unit, onHandle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 26.dp, top = 4.dp, bottom = 4.dp)) {
            Text(n.title.ifBlank { n.app }, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.text.isNotBlank()) Text(n.text, style = Type.Step, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconCircle(Icons.Close, "Dismiss", onDismiss, tint = Palette.Text2, background = androidx.compose.ui.graphics.Color.Transparent)
        UndoChip("Handle", onHandle)
    }
}

/** The notification's small icon in `text2` (most are white silhouettes), else the app's icon. */
@Composable
private fun AppGlyph(pkg: String, iconPackage: String?, iconRes: Int) {
    val context = LocalContext.current
    val glyph = remember(pkg, iconPackage, iconRes) { AppIcons.load(context, pkg, iconPackage, iconRes) }
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        glyph?.let { (bitmap, tint) ->
            Image(bitmap, null, Modifier.size(16.dp), colorFilter = if (tint) ColorFilter.tint(Palette.Text2) else null)
        }
    }
}

/** Small icon bitmaps, cached: rows come and go with every notification change. */
private object AppIcons {
    private val cache = object : LinkedHashMap<String, Pair<ImageBitmap, Boolean>?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ImageBitmap, Boolean>?>?) = size > 48
    }

    fun load(context: Context, pkg: String, iconPackage: String?, iconRes: Int): Pair<ImageBitmap, Boolean>? = synchronized(cache) {
        val k = "$pkg/$iconPackage/$iconRes"
        if (cache.containsKey(k)) return cache[k]
        val size = (16 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val small = if (iconPackage != null && iconRes != 0) runCatching { AndroidIcon.createWithResource(iconPackage, iconRes).loadDrawable(context) }.getOrNull() else null
        val drawable = small ?: runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        val result = drawable?.let { d ->
            runCatching {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(AndroidCanvas(bitmap))
                bitmap.asImageBitmap() to (small != null)
            }.getOrNull()
        }
        cache[k] = result
        result
    }
}
