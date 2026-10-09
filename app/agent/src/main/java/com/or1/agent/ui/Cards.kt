package com.or1.agent.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.or1.agent.ChatItem
import com.or1.agent.Decision

/** One request and everything it produced, so a turn's tool steps collapse into a single working card. */
data class Turn(
    val user: ChatItem.User?,
    val steps: List<ChatItem>,          // Thinking and ToolCall, in order
    val approvals: List<ChatItem.Approval>,
    val infos: List<ChatItem.Info>,
    val reply: ChatItem.Reply?,
) {
    val key: Long get() = user?.id ?: steps.firstOrNull()?.id ?: reply?.id ?: infos.firstOrNull()?.id ?: 0L
    val toolCalls get() = steps.filterIsInstance<ChatItem.ToolCall>()
    /** Did this turn drive an app (so the work-screen thumbnail is relevant)? */
    val usedScreen get() = toolCalls.any { it.detail.substringBefore(' ') in setOf("open", "act", "screen") }
}

fun groupTurns(items: List<ChatItem>): List<Turn> {
    val turns = mutableListOf<Turn>()
    var user: ChatItem.User? = null
    val steps = mutableListOf<ChatItem>()
    val approvals = mutableListOf<ChatItem.Approval>()
    val infos = mutableListOf<ChatItem.Info>()
    var reply: ChatItem.Reply? = null
    fun flush() {
        if (user != null || steps.isNotEmpty() || approvals.isNotEmpty() || infos.isNotEmpty() || reply != null) {
            turns += Turn(user, steps.toList(), approvals.toList(), infos.toList(), reply)
        }
        user = null; steps.clear(); approvals.clear(); infos.clear(); reply = null
    }
    for (item in items) when (item) {
        is ChatItem.User -> { flush(); user = item }
        is ChatItem.Thinking, is ChatItem.ToolCall -> steps += item
        is ChatItem.Approval -> approvals += item
        is ChatItem.Info -> infos += item
        is ChatItem.Reply -> { reply = item; flush() }
    }
    flush()
    return turns
}

@Composable
fun UserBubble(item: ChatItem.User) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            item.text,
            style = Type.Body,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.SurfaceHigh)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

/** The agent's reply: prose, no bubble. Long-press copies it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReplyBlock(item: ChatItem.Reply) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { clipboard.setText(AnnotatedString(item.text)) }),
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
                        .background(Palette.SurfaceSunken)
                        .padding(12.dp),
                )
            } else if (block.text.isNotBlank()) {
                Text(inline(block.text), style = Type.Reply, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        if (item.cost > 0 || item.seconds > 0) {
            Spacer(Modifier.height(6.dp))
            Text("$%.4f · %.1f s".format(item.cost, item.seconds), style = Type.Caption)
        }
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
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Palette.SurfaceSunken)) { append(m.groups[2]!!.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/**
 * One card per turn for the agent's work: a header ("Working…" / "3 steps"), the latest step, and the live
 * thumbnail of the hidden work screen. Expands to every step; tapping a step shows the raw call and result.
 */
@Composable
fun WorkCard(turn: Turn, active: Boolean, preview: Bitmap?, onOpenPreview: () -> Unit) {
    var expanded by rememberSaveable(turn.key) { mutableStateOf(false) }
    val calls = turn.toolCalls
    val shape = RoundedCornerShape(20.dp)
    val showThumbnail = preview != null && turn.usedScreen
    // The text sets the card's height; the thumbnail fills it (cropped to the top of the work screen), so the
    // card stays as compact as its content.
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Border, shape)
            .animateContentSize(tween(220))
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(end = if (showThumbnail) 62.dp else 0.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val waiting = active && turn.approvals.any { it.state == null }
                if (waiting) {
                    Box(Modifier.padding(4.dp).size(8.dp).clip(CircleShape).background(Palette.Accent))
                } else if (active) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Accent, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Check, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                val count = if (calls.size == 1) "1 step" else "${calls.size} steps"
                val title = when {
                    waiting -> "Waiting for your OK"
                    active -> "Working…"
                    else -> count
                }
                Text(title, style = Type.Label, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.ExpandLess else Icons.ExpandMore, null, tint = Palette.TextFaint, modifier = Modifier.size(20.dp))
            }
            if (!expanded) {
                val latest = calls.lastOrNull()?.label ?: (turn.steps.lastOrNull() as? ChatItem.Thinking)?.let { "Thinking" }
                if (latest != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(latest, style = Type.Step, maxLines = 2, modifier = Modifier.padding(start = 26.dp).let { if (active) it.pulse() else it })
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 26.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (step in turn.steps) when (step) {
                        is ChatItem.Thinking -> Text(step.text, style = Type.Thinking, maxLines = 6)
                        is ChatItem.ToolCall -> StepRow(step)
                        else -> {}
                    }
                }
            }
        }
        if (showThumbnail) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopEnd) {
                Image(
                    preview!!.asImageBitmap(),
                    contentDescription = "Work screen. Tap to view.",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .width(50.dp)
                        .heightIn(min = 48.dp, max = 111.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenPreview),
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: ChatItem.ToolCall) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            step.isError -> Palette.Error
                            step.result == null -> Palette.Accent
                            else -> Palette.TextFaint
                        }
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Text(step.label, style = Type.Step)
        }
        AnimatedVisibility(open) {
            Text(
                step.detail + (step.result?.let { "\n\n" + it.take(1200) } ?: ""),
                style = Type.Mono,
                modifier = Modifier
                    .padding(start = 16.dp, top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.SurfaceSunken)
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun Modifier.pulse(): Modifier {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    return alpha(alpha)
}

/** A change waiting for the user's OK: Allow / Always / Deny, then the decision. */
@Composable
fun ApprovalCard(item: ChatItem.Approval, onDecide: (Decision) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, if (item.state == null) Palette.ApprovalBorder else Palette.Border, shape)
            .padding(16.dp),
    ) {
        Text(item.label, style = Type.Label)
        Spacer(Modifier.height(6.dp))
        Text(item.detail, style = Type.Mono, maxLines = 4)
        Spacer(Modifier.height(14.dp))
        when (item.state) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Deny", Palette.Surface, Palette.TextDim) { onDecide(Decision.DENY) }
                Spacer(Modifier.weight(1f))
                PillButton("Always", Palette.SurfaceHigh, Palette.Text) { onDecide(Decision.ALWAYS) }
                PillButton("Allow", Palette.Primary, Palette.OnPrimary) { onDecide(Decision.ALLOW) }
            }
            Decision.DENY -> DecisionLine(Icons.Close, "Denied", Palette.Error)
            Decision.ALWAYS -> DecisionLine(Icons.Check, "Always allowed in this chat", Palette.Success)
            Decision.ALLOW -> DecisionLine(Icons.Check, "Allowed", Palette.Success)
        }
    }
}

@Composable
private fun DecisionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.Step)
    }
}

@Composable
fun PillButton(text: String, background: androidx.compose.ui.graphics.Color, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text,
        style = Type.Label.copy(color = color),
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    )
}

@Composable
fun InfoLine(item: ChatItem.Info) {
    Text(item.text, style = Type.Caption, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}
