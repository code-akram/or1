package com.or1.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.Effect
import com.or1.agent.today
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today: every side effect of today's chats and routine runs, newest first, with its undo state, in one receipt
 * card; the previous week under "Earlier", collapsed, a card per day. A tier-2 undo starts a request in the chat; this
 * page stays open and the row says "Undoing in <app>…" until it's done.
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
    val earlierDays = remember(earlier) { earlier.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.toList() }
    var showEarlier by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Palette.Ink0).systemBarsPadding()) {
        PageHeader("Today", onClose)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (today.isEmpty()) {
                item(key = "empty") { Text("Nothing changed today.", style = Type.Caption, modifier = Modifier.padding(vertical = 8.dp)) }
            } else {
                item(key = "today") { DayCard(today, zone, Modifier.itemMotion(this)) }
            }
            if (earlier.isNotEmpty()) {
                item(key = "earlier") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).clickable { showEarlier = !showEarlier },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Earlier this week · ${earlier.size}", style = Type.Caption, modifier = Modifier.weight(1f))
                        Icon(if (showEarlier) Icons.ExpandLess else Icons.ExpandMore, null, tint = Palette.Text3, modifier = Modifier.size(20.dp))
                    }
                }
                if (showEarlier) {
                    items(earlierDays, key = { (day, _) -> "day:$day" }) { (day, list) ->
                        Column(Modifier.itemMotion(this)) {
                            Text(day.format(dayLabel), style = Type.Caption, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                            DayCard(list, zone)
                        }
                    }
                }
            }
        }
    }
}

/** Back and the page's title (Title 20/26): Today, Routines, What or1 knows. */
@Composable
internal fun PageHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(Icons.Back, "Back", onClose, tint = Palette.Text, background = Palette.Ink3)
        Spacer(Modifier.width(12.dp))
        Text(title, style = Type.Title)
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val dayLabel = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/** One day's effects in the receipt container: time, glyph, label, source, undo. */
@Composable
private fun DayCard(effects: List<Effect>, zone: ZoneId, modifier: Modifier = Modifier) {
    ReceiptContainer(modifier) {
        effects.forEachIndexed { i, effect ->
            key(effect.id) {
                ReceiptRow(
                    effect, { AgentHub.undo(it.id) },
                    modifier = (if (i > 0) Modifier.hairlineTop() else Modifier).padding(start = 14.dp, end = 4.dp),
                    time = clock.format(Instant.ofEpochMilli(effect.time).atZone(zone)),
                    source = sourceOf(effect),
                )
            }
        }
    }
}

private fun sourceOf(effect: Effect): String = when (effect.source) {
    "routine" -> effect.sourceLabel?.let { "Routine · $it" } ?: "Routine"
    else -> "Chat"
}
