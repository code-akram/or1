package com.or1.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.Routine
import com.or1.agent.Routines
import com.or1.agent.tools.HiddenDisplay
import org.json.JSONObject

/**
 * The routines the user has set up (ask the agent to add one; edit or remove them here, and revoke the Always answers
 * a routine has collected) and their recent runs, which have their own thread so they don't interleave with the chat.
 * Approvals a run waits on are answered here too, and a run can be carried into the chat to follow up on.
 */
@Composable
fun RoutinesView(onClose: () -> Unit, onOpenPreview: () -> Unit) {
    BackHandler(onBack = onClose)
    val routines by Routines.list.collectAsState()
    val items by AgentHub.routineItems.collectAsState()
    val busy by AgentHub.routineBusy.collectAsState()
    val chatBusy by AgentHub.busy.collectAsState()
    val preview by HiddenDisplay.preview.collectAsState()
    val turns = remember(items) { groupTurns(items) }
    var editing by remember { mutableStateOf<String?>(null) }
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
            items(routines, key = { "routine-${it.id}" }) { routine ->
                if (editing == routine.id) {
                    RoutineEditor(routine, onDone = { editing = null })
                } else {
                    RoutineRow(routine, onEdit = { editing = routine.id })
                }
            }
            if (turns.isNotEmpty()) {
                item(key = "runs") { Text("Recent runs", style = Type.Caption, modifier = Modifier.padding(top = 16.dp)) }
                items(turns, key = { "turn-${it.key}" }) { turn ->
                    val last = turn === turns.last()
                    val active = last && busy
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnView(turn, active = active, preview = if (last) preview else null, onOpenPreview)
                        val user = turn.user
                        val reply = turn.reply
                        if (user != null && reply != null && !active && !chatBusy) {
                            PillButton("Continue in chat", Palette.Surface, Palette.Text) {
                                AgentHub.continueInChat(user.text, user.source, reply.text)
                                onClose()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(routine: Routine, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).clickable(onClick = onEdit)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(routine.describe(), style = Type.Label)
            Text(routine.prompt, style = Type.Step, maxLines = 3)
            if (routine.allowed.isNotEmpty()) {
                Text("Always allowed: ${routine.allowed.sorted().joinToString { grantLabel(it) }}", style = Type.Caption)
            }
        }
        IconCircle(Icons.Close, "Remove routine", { Routines.remove(routine.id) }, tint = Palette.TextDim, background = Color.Transparent)
    }
}

/** Edits the request and trigger in place; Always answers can be revoked here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(routine: Routine, onDone: () -> Unit) {
    var prompt by remember { mutableStateOf(routine.prompt) }
    var at by remember { mutableStateOf(if (routine.hour >= 0) "%02d:%02d".format(routine.hour, routine.minute) else "") }
    var repeat by remember { mutableStateOf(routine.repeat) }
    var app by remember { mutableStateOf(routine.app.orEmpty()) }
    var contains by remember { mutableStateOf(routine.textContains.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface)
            .border(1.dp, Palette.Border, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field("What to do", prompt, { prompt = it }, singleLine = false)
        if (routine.onNotification) {
            Field("From app (package or name)", app, { app = it })
            Field("Text contains", contains, { contains = it })
        } else {
            Field("Time (HH:MM)", at, { at = it })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in listOf("once", "daily", "weekdays", "weekly")) {
                    val selected = option == repeat
                    Text(
                        option,
                        style = Type.Chip.copy(color = if (selected) Palette.OnPrimary else Palette.Text),
                        modifier = Modifier.clip(CircleShape).background(if (selected) Palette.Primary else Palette.SurfaceHigh)
                            .clickable { repeat = option }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (routine.allowed.isNotEmpty()) {
            Text("Always allowed (tap to revoke)", style = Type.Caption)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in routine.allowed.sorted()) {
                    Row(
                        Modifier.clip(CircleShape).background(Palette.SurfaceHigh).clickable { Routines.revoke(routine.id, key) }
                            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(grantLabel(key), style = Type.Chip)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Close, "Revoke", tint = Palette.TextDim, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        error?.let { Text(it, style = Type.Caption.copy(color = Palette.Error)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            PillButton("Cancel", Palette.Surface, Palette.TextDim, onDone)
            PillButton("Save", Palette.Primary, Palette.OnPrimary) {
                val args = JSONObject().put("prompt", prompt.trim())
                if (routine.onNotification) {
                    args.put("app", app.trim()).put("text_contains", contains.trim())
                } else {
                    args.put("at", at.trim()).put("repeat", repeat)
                }
                try {
                    Routines.edit(routine.id, args)
                    onDone()
                } catch (e: IllegalArgumentException) {
                    // The messages name the tool's arguments; show them in the editor's words.
                    error = (e.message ?: "Couldn't save.").replace("`at`", "Time").replace("add needs `prompt`.", "Say what to do.")
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = singleLine,
        textStyle = Type.Body,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.TextDim, unfocusedBorderColor = Palette.Border,
            focusedLabelColor = Palette.TextDim, unfocusedLabelColor = Palette.TextFaint, cursorColor = Palette.Text,
        ),
    )
}

/** "device:set" -> "device set". */
private fun grantLabel(key: String) = key.split(':').filter { it.isNotBlank() }.joinToString(" ")
