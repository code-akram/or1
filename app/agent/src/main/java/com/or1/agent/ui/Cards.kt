package com.or1.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.ChatItem
import com.or1.agent.Effect
import com.or1.agent.UndoState
import com.or1.agent.UndoTier
import kotlinx.coroutines.delay

/** One request and everything it produced, so a turn's tool steps collapse into a single act card. */
data class Turn(
    val user: ChatItem.User?,
    val steps: List<ChatItem>,          // Thinking and ToolCall, in order
    val infos: List<ChatItem.Info>,
    val reply: ChatItem.Reply?,
    val draft: ChatItem.Draft? = null,
    /** What the turn changed (write calls only), in order: the receipt. */
    val effects: List<ChatItem.Effect> = emptyList(),
) {
    val key: Long get() = user?.id ?: steps.firstOrNull()?.id ?: reply?.id ?: infos.firstOrNull()?.id ?: 0L
    val toolCalls get() = steps.filterIsInstance<ChatItem.ToolCall>()
}

fun groupTurns(items: List<ChatItem>): List<Turn> {
    val turns = mutableListOf<Turn>()
    var user: ChatItem.User? = null
    val steps = mutableListOf<ChatItem>()
    val infos = mutableListOf<ChatItem.Info>()
    var reply: ChatItem.Reply? = null
    var draft: ChatItem.Draft? = null
    val effects = mutableListOf<ChatItem.Effect>()
    fun flush() {
        if (user != null || steps.isNotEmpty() || infos.isNotEmpty() || reply != null || draft != null) {
            turns += Turn(user, steps.toList(), infos.toList(), reply, draft, effects.toList())
        }
        user = null; steps.clear(); infos.clear(); reply = null; draft = null; effects.clear()
    }
    for (item in items) when (item) {
        is ChatItem.User -> { flush(); user = item }
        is ChatItem.Thinking, is ChatItem.ToolCall -> steps += item
        is ChatItem.Info -> infos += item
        is ChatItem.Draft -> draft = item
        is ChatItem.Reply -> { reply = item; flush() }
        is ChatItem.Effect -> effects += item
    }
    flush()
    return turns
}

@Composable
fun UserBubble(item: ChatItem.User) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        // A routine's request: say what started it, since the user didn't just type it.
        item.source?.let {
            val label = if (it.startsWith("From a routine")) it else "Routine · $it"
            Text(label, style = Type.Caption, modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
        }
        Text(
            item.text,
            style = Type.Body,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.Ink4)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

/** What a tap on the stream's own surface does (closes the composer); prose that takes taps passes them on. */
val LocalStreamTap = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The agent's reply: prose, no bubble. Long-press copies it ("Copied" under it for 1.2 s; no toast); a tap is a tap on
 * the stream ([LocalStreamTap]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReplyBlock(item: ChatItem.Reply) {
    val clipboard = LocalClipboardManager.current
    val onTap = LocalStreamTap.current
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        copied = true
        delay(1200)
        copied = false
    }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = { clipboard.setText(AnnotatedString(item.text)); copies++ }),
    ) {
        for (block in parseBlocks(item.text)) {
            if (block.code) {
                Text(
                    block.text,
                    style = Type.Mono.copy(color = Palette.Text),
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Ink1)
                        .padding(12.dp),
                )
            } else if (block.text.isNotBlank()) {
                Text(inline(block.text), style = Type.Reply, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        if (DeveloperDetails.on && (item.cost > 0 || item.seconds > 0)) {
            Spacer(Modifier.height(6.dp))
            Text("$%.4f · %.1f s".format(item.cost, item.seconds), style = Type.Caption.copy(fontFeatureSettings = "tnum"))
        }
        if (copied) Text("Copied", style = Type.Caption, modifier = Modifier.padding(top = 4.dp))
    }
}

private data class Block(val text: String, val code: Boolean)

/** Splits ``` fenced code from prose (the prompt asks for plain text, but code still turns up). */
private fun parseBlocks(text: String): List<Block> =
    text.split("```").mapIndexed { i, part ->
        if (i % 2 == 1) Block(part.substringAfter('\n', part).trimEnd(), code = true) else Block(part.trim(), code = false)
    }

/** **bold** and `code` inside prose. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
    var last = 0
    for (m in pattern.findAll(text)) {
        append(text.substring(last, m.range.first))
        val bold = m.groups[1]?.value
        if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Palette.Ink1)) { append(m.groups[2]!!.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/**
 * One act's work and what it changed, in one card (docs/ui-ledger.md §4): a header of step blocks (one per tool call)
 * with the current step while working, "N steps · <what it did>" once done (tap: every step; with developer details
 * on, tap a step for the raw call and result); while the work display is in use, the evidence strip and "Watch or take
 * over"; then the receipt. A turn with only reads (no effects, no work display) gets no card: the header line alone.
 */
@Composable
fun ActCard(turn: Turn, active: Boolean, receipt: List<Effect>, onOpenPreview: () -> Unit) {
    val calls = turn.toolCalls
    val blocks = calls.map { StepState.of(it, active) }.let { states ->
        // While or1 thinks between calls, an unlit block stands for the step to come.
        if (active && states.none { it == StepState.RUNNING }) states + StepState.UNLIT else states
    }
    val latest = calls.lastOrNull()?.label ?: (turn.steps.lastOrNull() as? ChatItem.Thinking)?.let { "Thinking" }
    val title = if (active) latest ?: "Working" else stepsTitle(calls.size, StepLabels.summary(calls.map { it.label }) ?: latest)
    // Only the turn in progress follows the work display.
    val usingDisplay = if (active) AgentHub.usingWorkDisplay.collectAsState().value else false
    ActFrame(blocks, title, active, receipt, usingDisplay, onOpenPreview, expandKey = turn.key) {
        for (step in turn.steps) when (step) {
            is ChatItem.Thinking -> Text(step.text, style = Type.Step.copy(fontStyle = FontStyle.Italic, color = Palette.Text3), maxLines = 6)
            is ChatItem.ToolCall -> StepRow(step, active)
            else -> {}
        }
    }
}

/** "1 step", "3 steps · Set an alarm for 07:30" (the last step that isn't navigation, [StepLabels.summary]). */
fun stepsTitle(count: Int, last: String?): String = (if (count == 1) "1 step" else "$count steps") + (last?.let { " · $it" } ?: "")

/**
 * The act card's frame, for chat turns and routine acts alike. Framed (ink2, 16 dp, hairline; `lineStrong` while
 * [active]) only when it holds more than its header: receipt rows or the evidence strip, which stays framed until its
 * collapse has finished. [steps] (null: not expandable) is what the header expands to.
 */
@Composable
internal fun ActFrame(
    blocks: List<StepState>, title: String, active: Boolean, receipt: List<Effect>, evidence: Boolean,
    onOpenPreview: () -> Unit, expandKey: Any, steps: (@Composable () -> Unit)?,
) {
    var expanded by rememberSaveable(expandKey) { mutableStateOf(false) }
    val strip = remember { MutableTransitionState(evidence) }
    strip.targetState = evidence
    val framed = receipt.isNotEmpty() || strip.currentState || strip.targetState
    val inset = if (framed) 14.dp else 0.dp
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (framed) Modifier.clip(Shapes.card).background(Palette.Ink2).border(1.dp, if (active) Palette.LineStrong else Palette.Line, Shapes.card)
                else Modifier,
            )
            .animateContentSize(Motion.layout()),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(if (steps != null) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(start = inset, end = if (framed) 12.dp else 0.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (blocks.isNotEmpty()) {
                StepBlocks(blocks)
                Spacer(Modifier.width(12.dp))
            }
            // The current step itself, steady: the blocks and the rail show that it's moving.
            Text(
                title, style = Type.Step.copy(color = if (active) Palette.Text else Palette.Text2), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (framed && steps != null) {
                Icon(if (expanded) Icons.ExpandLess else Icons.ExpandMore, null, tint = Palette.Text3, modifier = Modifier.size(20.dp))
            }
        }
        if (steps != null) {
            AnimatedVisibility(expanded, enter = Motion.expand, exit = Motion.collapse) {
                Column(Modifier.padding(start = inset, end = inset, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { steps() }
            }
        }
        AnimatedVisibility(strip, enter = Motion.expand, exit = Motion.collapse) {
            Column {
                EvidenceStrip(onOpenPreview)
                WatchRow(onOpenPreview)
            }
        }
        for (effect in receipt) key(effect.id) {
            ReceiptRow(effect, { AgentHub.undo(it.id) }, Modifier.hairlineTop().padding(start = 14.dp, end = 4.dp))
        }
    }
}

/** A step's label after its block; the raw call and result are a developer detail. */
@Composable
private fun StepRow(step: ChatItem.ToolCall, active: Boolean) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val details = DeveloperDetails.on
    Column(Modifier.fillMaxWidth().then(if (details) Modifier.heightIn(min = 48.dp).clickable { open = !open } else Modifier)) {
        Row(verticalAlignment = Alignment.Top) {
            // Centred on the first line (Step is 20 sp tall).
            StepBlocks(listOf(StepState.of(step, active)), Modifier.padding(top = 8.dp))
            Spacer(Modifier.width(10.dp))
            Text(step.label, style = Type.Step)
        }
        AnimatedVisibility(details && open, enter = Motion.expand, exit = Motion.collapse) {
            Text(
                step.detail + (step.result?.let { "\n\n" + it.take(1200) } ?: ""),
                style = Type.Mono,
                modifier = Modifier
                    .padding(start = 14.dp, top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Ink1)
                    .padding(10.dp),
            )
        }
    }
}

/** The receipt container on its own (Today's days): ink2, 16 dp, hairline; rows go in with [hairlineTop] between. */
@Composable
fun ReceiptContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(Shapes.card).background(Palette.Ink2).border(1.dp, Palette.Line, Shapes.card)
            .animateContentSize(Motion.layout()),
    ) { content() }
}

/** A 1 dp `line` rule along the top edge, between rows. */
fun Modifier.hairlineTop(): Modifier = drawBehind {
    drawRect(Palette.Line, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}

/**
 * One side effect: glyph (✓ done, `warn` for irreversible, an undo arrow once undone, the march while its undo runs),
 * the past-tense label, a caption (where it came from, on Today; "irreversible"; "via or1 · Gmail" for an undo the
 * agent does, "Undoing in Gmail…" while it does), the evidence frame's thumbnail if it has one, and the undo state: an
 * Undo chip while it can be undone, "Undone" with the label struck through, the reason when the undo failed, nothing
 * once it has expired. [time] and [source] are for the Today list; [dim] is the idle home's look (text3, chips live).
 */
@Composable
fun ReceiptRow(
    effect: Effect, onUndo: (Effect) -> Unit, modifier: Modifier = Modifier, time: String? = null, source: String? = null,
    dim: Boolean = false,
) {
    // An offer runs out on its own (the timer ended, the alarm rang): wake once at that moment, no ticking.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val state = effect.stateAt(now)
    if (state == UndoState.AVAILABLE && effect.expiresAt > 0) {
        LaunchedEffect(effect.expiresAt) {
            delay((effect.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 50)
            now = System.currentTimeMillis()
        }
    }
    val irreversible = effect.tier == UndoTier.NONE
    val byAgent = effect.tier == UndoTier.AGENT
    val undone = state == UndoState.UNDONE
    val note = when {
        irreversible -> "irreversible"
        byAgent && state == UndoState.AVAILABLE -> "via or1" + (effect.app?.let { " · $it" } ?: "")
        byAgent && state == UndoState.PENDING -> "Undoing in ${effect.app ?: "the app"}…"
        else -> null
    }
    val caption = listOfNotNull(source, note).joinToString(" · ").ifEmpty { null }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (time != null) {
            Text(time, style = Type.Step.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.width(48.dp))
        }
        Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
            when {
                state == UndoState.PENDING -> March()
                state == UndoState.FAILED -> Icon(Icons.Close, null, tint = Palette.Error, modifier = Modifier.size(16.dp))
                irreversible -> Icon(Icons.Warn, "Irreversible", tint = Palette.Warn, modifier = Modifier.size(16.dp))
                undone -> Icon(Icons.Undo, "Undone", tint = Palette.Text3, modifier = Modifier.size(16.dp))
                else -> Icon(Icons.Check, null, tint = if (dim) Palette.Text3 else Palette.Text2, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                effect.label,
                style = Type.Label.copy(
                    color = if (undone || dim) Palette.Text3 else Palette.Text,
                    textDecoration = if (undone) TextDecoration.LineThrough else null,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            caption?.let { Text(it, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            // The label says what a shell command did; the command itself is a developer detail.
            if (effect.tool == "shell" && DeveloperDetails.on) {
                Text(
                    effect.call.substringAfter("command=", effect.call), style = Type.Mono.copy(color = Palette.Text3),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (state == UndoState.FAILED) {
                Text(
                    "Couldn't undo" + (effect.detail?.let { ": $it" } ?: "."),
                    style = Type.Caption.copy(color = Palette.Error), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (effect.evidence != null) EvidenceThumb(effect)
        when (state) {
            UndoState.AVAILABLE -> UndoChip("Undo") { onUndo(effect) }
            // The caption says where the agent is undoing it.
            UndoState.PENDING -> if (byAgent) Spacer(Modifier.width(10.dp)) else StateNote("Undoing")
            UndoState.UNDONE -> StateNote("Undone")
            else -> Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
internal fun StateNote(text: String) {
    Text(text, style = Type.Caption, modifier = Modifier.padding(horizontal = 10.dp))
}

/** A 48 dp touch target around a smaller chip, so the row stays compact. */
@Composable
internal fun UndoChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Type.Step.copy(color = Palette.Text),
            maxLines = 1,
            modifier = Modifier.clip(CircleShape).background(Palette.Ink4).border(1.dp, Palette.Line, CircleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun PillButton(text: String, background: androidx.compose.ui.graphics.Color, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text,
        style = Type.Label.copy(color = color),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .wrapContentHeight()
            .padding(horizontal = 20.dp),
    )
}

@Composable
fun InfoLine(item: ChatItem.Info) {
    Text(item.text, style = Type.Caption, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}
