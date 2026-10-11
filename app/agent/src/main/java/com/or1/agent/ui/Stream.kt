package com.or1.agent.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.ChatItem
import com.or1.agent.Effect
import com.or1.agent.RoutineAct
import com.or1.agent.UndoTier
import com.or1.agent.today

/** One entry of the chat stream: a turn of the conversation, or a routine run from today. */
sealed interface StreamEntry {
    val key: Any

    /** [index] is the turn's place in the conversation's turns (its result cards). */
    data class Act(val turn: Turn, val index: Int) : StreamEntry {
        override val key: Any get() = turn.key
    }

    data class Routine(val act: RoutineAct) : StreamEntry {
        override val key: Any get() = "routine:${act.id}"
    }
}

/**
 * The stream: the conversation's turns with today's routine acts among them by time, each act before the first
 * request made after it finished (so runs from before the conversation lead it, and one that finishes while the user
 * is away comes last). A turn without a request (a note) takes the next request's time, so it stays with it.
 */
fun interleave(turns: List<Turn>, acts: List<RoutineAct>): List<StreamEntry> {
    val sorted = acts.sortedBy { it.finished }
    val times = LongArray(turns.size)
    var next = Long.MAX_VALUE
    for (i in turns.indices.reversed()) {
        turns[i].user?.let { next = it.at }
        times[i] = next
    }
    val out = ArrayList<StreamEntry>(turns.size + sorted.size)
    var a = 0
    turns.forEachIndexed { i, turn ->
        while (a < sorted.size && sorted[a].finished <= times[i]) out += StreamEntry.Routine(sorted[a++])
        out += StreamEntry.Act(turn, i)
    }
    while (a < sorted.size) out += StreamEntry.Routine(sorted[a++])
    return out
}

/**
 * A routine run as an act in the stream: "routine · <label>" with a 48 dp dismiss ([AgentHub.dismissRoutineAct]),
 * its act card (step blocks and "N steps"; the receipt with live undo), and the reply in serif.
 */
@Composable
fun RoutineActView(act: RoutineAct, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("routine · ${act.label}", style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconCircle(Icons.Close, "Dismiss", { AgentHub.dismissRoutineAct(act.id) }, tint = Palette.Text3, background = Color.Transparent)
        }
        if (act.steps > 0 || act.effects.isNotEmpty()) {
            ActFrame(
                blocks = List(act.steps) { StepState.DONE }, title = stepsTitle(act.steps, null), active = false,
                receipt = act.effects, evidence = false, onOpenPreview = {}, expandKey = act.id, steps = null,
            )
        }
        if (act.reply.isNotBlank()) ReplyBlock(ChatItem.Reply(act.finished, act.reply, act.cost, act.seconds))
    }
}

/** Today's effects shown on the idle home (the rest are a tap away, on Today). */
private const val IDLE_EFFECTS = 6

/**
 * The idle home's rows from today's effects (newest first): only what can be undone or has a frame to show, so shell
 * commands and sends don't crowd it. They stay on Today, and in its count.
 */
fun idleEffects(today: List<Effect>, max: Int = IDLE_EFFECTS): List<Effect> =
    today.filter { it.tier != UndoTier.NONE || it.evidence != null }.take(max)

/**
 * The idle home (an empty conversation; docs/ui-ledger.md §1, "no empty state"), bottom-anchored above the composer:
 * today's routine acts, then "Today · N" (opens Today) over the newest of today's effects as dimmed receipt rows with
 * live Undo chips ([idleEffects]; a routine act's own effects are left to its card), then the greeting and suggestions. Scrolls when
 * it's taller than the screen, starting at the bottom. [onAtBottom] follows whether the bottom is in view.
 */
@Composable
fun IdleHome(
    suggestions: List<Suggestion>, acts: List<RoutineAct>, greeting: String, onSuggestion: (Suggestion) -> Unit,
    onToday: () -> Unit, onAtBottom: (Boolean) -> Unit,
) {
    val effects by AgentHub.effects.collectAsState()
    val today = remember(effects) { effects.today() }
    val listed = remember(today, acts) {
        val inActs = acts.flatMap { a -> a.effects.map { it.id } }.toSet()
        today.filter { it.id !in inActs }
    }
    val shown = remember(listed) { idleEffects(listed) }
    val scroll = rememberScrollState()
    ReportAtBottom(scroll, onAtBottom)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll, reverseScrolling = true).heightIn(min = maxHeight).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            for (act in acts) key(act.id) {
                RoutineActView(act, Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp))
            }
            if (listed.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open Today", onClick = onToday).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Today · ${today.size}", style = Type.Caption.copy(fontFeatureSettings = "tnum"))
                }
                shown.forEachIndexed { i, effect ->
                    key(effect.id) {
                        ReceiptRow(
                            effect, { AgentHub.undo(it.id) }, (if (i > 0) Modifier.hairlineTop() else Modifier).padding(start = 8.dp),
                            dim = true,
                        )
                    }
                }
            }
            Text(
                greeting, style = Type.CaptionSerif, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, top = if (listed.isNotEmpty()) 16.dp else 0.dp, bottom = 4.dp),
            )
            Column(Modifier.padding(bottom = 4.dp)) {
                for (s in suggestions) SuggestionRow(s) { onSuggestion(s) }
            }
        }
    }
}

/** Reversed scrolling: 0 is the bottom. */
@Composable
private fun ReportAtBottom(scroll: ScrollState, onAtBottom: (Boolean) -> Unit) {
    val atBottom by remember { derivedStateOf { scroll.value == 0 } }
    LaunchedEffect(atBottom) { onAtBottom(atBottom) }
}
