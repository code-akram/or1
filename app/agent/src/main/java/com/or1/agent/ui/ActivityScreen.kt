package com.or1.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.Effect
import com.or1.agent.UndoTier
import com.or1.agent.today
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today: every side effect of today's chats and routine runs, newest first, with its undo state; the previous week
 * under "Earlier", collapsed. A tier-2 undo is a new request in the chat, so it closes this screen to show it.
 */
@Composable
fun ActivityView(onClose: () -> Unit) {
    val effects by AgentHub.effects.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    val (today, earlier) = remember(effects) {
        val midnight = LocalDate.now(zone).atStartOfDay(zone)
        val weekAgo = midnight.minusDays(7).toInstant().toEpochMilli()
        effects.today(zone = zone) to effects.filter { it.time in weekAgo until midnight.toInstant().toEpochMilli() }
    }
    var showEarlier by rememberSaveable { mutableStateOf(false) }
    val undo = { e: Effect -> if (AgentHub.undo(e.id) && e.tier == UndoTier.AGENT) onClose() }
    Column(Modifier.fillMaxSize().background(Palette.Ink0).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Back, "Back", onClose, tint = Palette.Text, background = Palette.Ink3)
            Spacer(Modifier.width(12.dp))
            Text("Today", style = Type.Label)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 16.dp)) {
            if (today.isEmpty()) {
                item(key = "empty") { Text("Nothing changed today.", style = Type.Caption, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            effectRows(today, zone, withDay = false, undo)
            if (earlier.isNotEmpty()) {
                item(key = "earlier") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).clickable { showEarlier = !showEarlier },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Earlier this week · ${earlier.size}", style = Type.Caption, modifier = Modifier.weight(1f))
                        Icon(if (showEarlier) Icons.ExpandLess else Icons.ExpandMore, null, tint = Palette.Text3, modifier = Modifier.size(20.dp))
                    }
                }
                if (showEarlier) effectRows(earlier, zone, withDay = true, undo)
            }
        }
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.US)

/** Flat rows on the window's black, with hairlines between them. */
private fun LazyListScope.effectRows(effects: List<Effect>, zone: ZoneId, withDay: Boolean, onUndo: (Effect) -> Unit) {
    itemsIndexed(effects, key = { _, e -> e.id }) { i, effect ->
        val at = Instant.ofEpochMilli(effect.time).atZone(zone)
        ReceiptRow(
            effect, onUndo,
            modifier = (if (i > 0) Modifier.hairlineTop() else Modifier).itemMotion(this),
            // Earlier rows say the day in the source line, so the time column keeps its width.
            time = clock.format(at),
            source = (if (withDay) at.format(weekday) + " · " else "") + sourceOf(effect),
        )
    }
}

private fun sourceOf(effect: Effect): String = when (effect.source) {
    "routine" -> effect.sourceLabel?.let { "Routine · $it" } ?: "Routine"
    else -> "Chat"
}
