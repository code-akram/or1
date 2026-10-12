package com.or1.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.NowChip
import com.or1.agent.NowState
import com.or1.agent.NowTimer
import com.or1.agent.TimerState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** More chips than this collapse into "+N". */
private const val MAX_CHIPS = 4

/**
 * The Now strip (docs/ui-ledger.md §2): what's alive right now, as chips in the top bar (timers, the routine running,
 * the alarm, the next event, notifications waiting), from [NowState.chips]. Draws nothing when there are none. It
 * lives outside the stream's LazyColumn, and each chip ticks itself, so a tick recomposes one Text.
 *
 * [onTimer] goes to the timer's card and returns true, or returns false (no card in the stream) and the chip offers
 * Cancel. The alarm chip offers Off. [onTriage] opens the triage sheet; [onRoutine] the Routines page. The media chip
 * offers Pause/Play, and Watch ([onWatch]: the viewer on the stage) while its app plays there.
 */
@Composable
fun NowStrip(
    onTimer: (NowTimer) -> Boolean = { false },
    onTriage: () -> Unit = {},
    onRoutine: () -> Unit = {},
    onWatch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val chips by NowState.chips.collectAsState()
    if (chips.isEmpty()) return
    // A chip tapped for its action ("Cancel", "Off") shows it for a few seconds.
    var revealed by remember { mutableStateOf<String?>(null) }
    revealed?.let { LaunchedEffect(it) { delay(5000); revealed = null } }
    val shown = chips.take(MAX_CHIPS)
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in shown) key(chip.key) {
            val open = revealed == chip.key
            val toggle = { revealed = if (open) null else chip.key }
            when (chip) {
                is NowChip.Timer -> TimerChip(chip.timer, open, onTap = { if (!onTimer(chip.timer)) toggle() }, onCancel = {
                    NowState.cancelTimer(chip.timer.id)
                    revealed = null
                })
                is NowChip.Alarm -> {
                    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(Instant.ofEpochMilli(chip.alarm.at).atZone(ZoneId.systemDefault()))
                    Chip("Alarm $time" + (chip.alarm.label?.let { ", $it" } ?: ""), onClick = toggle) {
                        Glyph(Icons.Alarm)
                        Text(time, style = Type.Step.copy(color = Palette.Text, fontFeatureSettings = "tnum"))
                    }
                    if (open) ChipAction("Off") { NowState.alarmOff(); revealed = null }
                }
                is NowChip.Event -> EventChip(chip)
                is NowChip.RoutineRun -> Chip("Routine running: ${chip.label}", onClick = onRoutine) {
                    March()
                    Text(chip.label, style = Type.Step.copy(color = Palette.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                }
                is NowChip.Media -> {
                    val context = LocalContext.current
                    val name = chip.media.title ?: remember(chip.media.pkg) { appName(context, chip.media.pkg) }
                    val playing = chip.media.playing
                    Chip((if (playing) "Playing: " else "Paused: ") + name, onClick = toggle) {
                        Glyph(Icons.Music, if (playing) Palette.Live else Palette.Text2)
                        Text(name, style = Type.Step.copy(color = Palette.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                    }
                    if (open) ChipAction(if (playing) "Pause" else "Play") { NowState.playPause(chip.media.pkg) }
                    if (open && onWatch != null && chip.media.pkg == com.or1.agent.Stage.kept.value) {
                        ChipAction("Watch") { revealed = null; onWatch() }
                    }
                }
                is NowChip.Notifications -> Chip("${chip.count} new notifications. Open triage.", onClick = onTriage) {
                    Glyph(Icons.Bell)
                    Text("${chip.count} new", style = Type.Step.copy(color = Palette.Text, fontFeatureSettings = "tnum"))
                }
            }
        }
        if (chips.size > shown.size) {
            Chip("${chips.size - shown.size} more", onClick = null) {
                Text("+${chips.size - shown.size}", style = Type.Step.copy(fontFeatureSettings = "tnum"))
            }
        }
    }
}

/**
 * A chip: 36 dp of `ink4` with a hairline, in a 48 dp touch target. Its semantics are one coarse description (and
 * the click), so the text ticking inside doesn't churn the accessibility tree.
 */
@Composable
private fun Chip(description: String, onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    val semantics = remember(description, onClick) {
        Modifier.clearAndSetSemantics {
            contentDescription = description
            if (onClick != null) {
                role = Role.Button
                onClick { onClick(); true }
            }
        }
    }
    Box(
        Modifier.heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clip(Shapes.chip).clickable(onClick = onClick) else Modifier)
            .then(semantics),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(36.dp).clip(Shapes.chip).background(Palette.Ink4).border(1.dp, Palette.Line, Shapes.chip).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun Glyph(icon: ImageVector, tint: Color = Palette.Text2) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
}

/** The chip's action, next to it while revealed. */
@Composable
private fun ChipAction(text: String, onClick: () -> Unit) {
    UndoChip(text, onClick)
}

@Composable
private fun TimerChip(timer: NowTimer, open: Boolean, onTap: () -> Unit, onCancel: () -> Unit) {
    val end = timer.endsAtElapsed
    val running = timer.state == TimerState.RUNNING && end != null
    val now = rememberCountdownClock(end, running)
    // Recomposes this chip once a minute; the digits tick in their own Text.
    val coarse by remember(end, running) { derivedStateOf { if (running) ResultCards.coarseRemaining(end - now.value) else "" } }
    val name = timer.label?.let { "Timer $it" } ?: "Timer"
    when {
        timer.state == TimerState.ENDED -> Chip("$name done", onTap) {
            Glyph(Icons.Timer, Palette.Live)
            Text("Done", style = Type.Step.copy(color = Palette.Live))
        }
        running -> Chip("$name, $coarse", onTap) {
            Glyph(Icons.Timer, Palette.Live)
            // Step-size tabular digits, like the alarm chip: Numeral-s made this chip read larger than its siblings.
            CountdownText({ end - now.value }, Type.Step.copy(color = Palette.Text, fontFeatureSettings = "tnum"), prefix = name)
        }
        timer.state == TimerState.PAUSED -> Chip("$name paused", onTap) {
            Glyph(Icons.Timer)
            Text(timer.remainingMs?.let { ResultCards.clock(it) } ?: "Paused", style = Type.Step.copy(fontFeatureSettings = "tnum"))
        }
        // A timer set in the Clock app itself: its end isn't known.
        else -> Chip("$name running", onTap) {
            Glyph(Icons.Timer, Palette.Live)
            Text("Timer", style = Type.Step.copy(color = Palette.Text))
        }
    }
    if (open && timer.state != TimerState.ENDED) ChipAction("Cancel", onCancel)
}

/** The next event, with how far off it is: refreshed on the minute. */
@Composable
private fun EventChip(chip: NowChip.Event) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(chip.event.begin) {
        while (now < chip.event.begin) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    val until = ResultCards.until(chip.event.begin, now, ZoneId.systemDefault())
    Chip("${chip.event.title} $until", onClick = null) {
        Glyph(Icons.Event)
        Text(chip.event.title, style = Type.Step.copy(color = Palette.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
        Text(until, style = Type.Step.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

private fun appName(context: android.content.Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
