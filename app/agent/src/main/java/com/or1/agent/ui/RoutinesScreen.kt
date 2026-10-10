package com.or1.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.Routine
import com.or1.agent.Routines
import com.or1.agent.tools.HiddenDisplay

/**
 * The routines the user has set up (ask the agent to add one; remove them here) and their recent runs, which have
 * their own thread so they don't interleave with the chat. Approvals a run waits on are answered here too.
 */
@Composable
fun RoutinesView(onClose: () -> Unit, onOpenPreview: () -> Unit) {
    BackHandler(onBack = onClose)
    val routines by Routines.list.collectAsState()
    val items by AgentHub.routineItems.collectAsState()
    val busy by AgentHub.routineBusy.collectAsState()
    val preview by HiddenDisplay.preview.collectAsState()
    val turns = remember(items) { groupTurns(items) }
    val list = rememberLazyListState()
    // Header + routines + "Recent runs" come first; follow the latest run as it grows.
    LaunchedEffect(items.size, items.lastOrNull()) {
        if (turns.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1, scrollOffset = Int.MAX_VALUE / 2)
    }
    Column(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Back, "Back", onClose, tint = Palette.Text, background = Palette.Surface)
            Spacer(Modifier.width(12.dp))
            Text("Routines", style = Type.Label)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (routines.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No routines yet. Ask in the chat, e.g. “every weekday at 7:30, tell me my battery and today's calendar”, " +
                            "or “when I get a message from Sam, tell me what it says”.",
                        style = Type.Step,
                    )
                }
            }
            items(routines, key = { "routine-${it.id}" }) { RoutineRow(it) }
            if (turns.isNotEmpty()) {
                item(key = "runs") { Text("Recent runs", style = Type.Caption, modifier = Modifier.padding(top = 16.dp)) }
                items(turns, key = { "turn-${it.key}" }) { turn ->
                    val last = turn === turns.last()
                    TurnView(turn, active = last && busy, preview = if (last) preview else null, onOpenPreview)
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(routine: Routine) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(routine.describe(), style = Type.Label)
            Text(routine.prompt, style = Type.Step, maxLines = 3)
        }
        IconCircle(Icons.Close, "Remove routine", { Routines.remove(routine.id) }, tint = Palette.TextDim, background = Color.Transparent)
    }
}
