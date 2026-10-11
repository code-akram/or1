package com.or1.agent.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.ChatItem
import com.or1.agent.NotifListener
import com.or1.agent.Routines
import com.or1.agent.tools.HiddenDisplay
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The phone's home screen: the conversation (or a one-line greeting and suggestions just above the composer), the
 * composer, the drawer with past chats, and the screens that open over them. `or1` in the rail goes home from
 * anywhere; Back closes the topmost layer. [nowStrip] sits under the top bar, above the stream.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(nowStrip: @Composable () -> Unit = {}) {
    Or1Theme {
        val context = LocalContext.current
        val items by AgentHub.items.collectAsState()
        val conversation by AgentHub.conversation.collectAsState()
        val alerts by AgentHub.alerts.collectAsState()
        val busy by AgentHub.busy.collectAsState()
        val totalCost by AgentHub.totalCost.collectAsState()
        val preview by HiddenDisplay.preview.collectAsState()
        val notificationTick by NotifListener.changes.collectAsState()
        val routines by Routines.list.collectAsState()
        val showRoutines by AgentHub.showRoutines.collectAsState()

        // A native field (see ComposerInput): typing doesn't recompose anything.
        val input = remember { ComposerInput() }
        var expanded by rememberSaveable { mutableStateOf(false) }
        var viewerOpen by remember { mutableStateOf(false) }
        var pastChat by remember { mutableStateOf<PastChat?>(null) }
        var routinesOpen by remember { mutableStateOf(false) }
        // Bumped by the home button: the conversation scrolls to its end.
        var homeTaps by remember { mutableIntStateOf(0) }
        LaunchedEffect(showRoutines) {
            if (showRoutines) {
                routinesOpen = true
                AgentHub.routinesShown()
            }
        }
        // The chat's keyboard would stay up over the viewer, covering the work screen.
        LaunchedEffect(viewerOpen) { if (viewerOpen) input.dismiss() }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current

        val suggestions = remember(notificationTick, items.isEmpty()) { Suggestions.build(context) }

        // Keep the screen on while the agent works, so its work display doesn't go to sleep with the phone.
        val view = LocalView.current
        DisposableEffect(busy) {
            view.keepScreenOn = busy
            onDispose { view.keepScreenOn = false }
        }

        // Back to the pill when the user dismisses the keyboard with nothing typed. Not when it closed because the
        // window briefly lost focus to the agent's work display: the composer brings it back (see ComposerCard).
        val imeVisible = WindowInsets.isImeVisible
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        var focusLostAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(windowFocused) { if (!windowFocused) focusLostAt = SystemClock.uptimeMillis() }
        var imeWasVisible by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            val wasVisible = imeWasVisible
            imeWasVisible = imeVisible
            if (wasVisible && !imeVisible && !input.hasText) {
                val hiddenAt = SystemClock.uptimeMillis()
                delay(600) // the focus change is reported around the time the IME hides
                val focusSteal = kotlin.math.abs(focusLostAt - hiddenAt) < 1500
                if (!focusSteal && !WindowInsetsHolder.imeVisible) {
                    expanded = false
                    input.dismiss()
                }
            }
        }
        WindowInsetsHolder.imeVisible = imeVisible

        fun send(message: String) {
            if (message.isBlank() || busy) return
            AgentHub.submit(message.trim())
            input.clear()
            // The agent takes a while: give the screen back to the conversation.
            expanded = false
            input.dismiss()
        }

        fun closeComposer() {
            input.dismiss()
            if (!input.hasText) expanded = false
        }

        // Everything that can be open over the idle chat closes; typed text stays in the composer.
        fun goHome() {
            scope.launch { drawer.close() }
            viewerOpen = false
            routinesOpen = false
            pastChat = null
            focus.clearFocus(force = true)
            keyboard?.hide()
            closeComposer()
            homeTaps++
        }

        // One handler for the whole screen, so the order doesn't depend on which screen registered last. Menus are
        // popups and close themselves first. Always enabled: Back at home stays home (this is the HOME app).
        BackHandler {
            when {
                // Drawn over everything below.
                drawer.isOpen -> scope.launch { drawer.close() }
                viewerOpen -> viewerOpen = false
                routinesOpen -> routinesOpen = false
                pastChat != null -> pastChat = null
                else -> closeComposer()
            }
        }

        // The rail first, below the status bar and the notch; every screen and the drawer below it.
        Column(Modifier.fillMaxSize().background(Palette.Ink0)) {
            StatusRail(busy, onHome = ::goHome)
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    // An edge swipe in the viewer belongs to the app on the work screen.
                    gesturesEnabled = !viewerOpen,
                    drawerContent = {
                        Drawer(
                            totalCost = totalCost,
                            routineCount = routines.size,
                            onNewChat = { AgentHub.newConversation(); scope.launch { drawer.close() } },
                            onRoutines = { routinesOpen = true; scope.launch { drawer.close() } },
                            onOpen = { pastChat = it; scope.launch { drawer.close() } },
                            open = drawer.isOpen,
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize().background(Palette.Ink0)) {
                        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                            TopBar(
                                onBlankTap = ::closeComposer,
                                showDot = suggestions.any { it.appIcon != null },
                                showNewChat = items.isNotEmpty(),
                                onMenu = { scope.launch { drawer.open() } },
                                onNewChat = { AgentHub.newConversation() },
                            )
                            for (alert in alerts) {
                                AlertBanner(alert, onOpen = { routinesOpen = true }, onDismiss = { AgentHub.dismissAlert(alert.id) })
                            }
                            nowStrip()
                            // Tapping empty space closes the keyboard: with the system UI locked down there's no back button.
                            Box(
                                Modifier.weight(1f).fillMaxWidth().clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    onClick = ::closeComposer,
                                ),
                            ) {
                                if (items.isEmpty()) {
                                    EmptyState(suggestions, onSuggestion = { send(it.prompt) })
                                } else {
                                    // A list per conversation: the old turns' exit animations otherwise get stuck when the
                                    // items are replaced in one go (a fresh conversation), leaving them drawn under the new one.
                                    key(conversation) { Conversation(items, busy, preview, homeTaps, onOpenPreview = { viewerOpen = true }) }
                                }
                            }
                            Composer(
                                input = input,
                                busy = busy,
                                expanded = expanded,
                                onExpand = { expanded = it },
                                onSend = { send(input.text) },
                                onStop = AgentHub::stop,
                                onPlusAction = {
                                    when (it) {
                                        PlusAction.NEW_CHAT -> AgentHub.newConversation()
                                        PlusAction.WORK_SCREEN -> viewerOpen = true
                                        PlusAction.DRAWER -> { closeComposer(); scope.launch { drawer.open() } }
                                    }
                                },
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                            )
                        }
                        pastChat?.let { HistoryView(it, onClose = { pastChat = null }) }
                        if (routinesOpen) RoutinesView(onClose = { routinesOpen = false }, onOpenPreview = { viewerOpen = true })
                        if (viewerOpen) {
                            WorkViewer(
                                onClose = { viewerOpen = false },
                                // Back to the chat with the composer open, to ask about what's on the work screen.
                                onAsk = { viewerOpen = false; routinesOpen = false; expanded = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(onBlankTap: () -> Unit, showDot: Boolean, showNewChat: Boolean, onMenu: () -> Unit, onNewChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onBlankTap)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconCircle(Icons.Menu, "Menu", onMenu, tint = Palette.Text, background = Color.Transparent, size = 48)
            if (showDot) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 9.dp).size(8.dp)
                        .clip(CircleShape).background(Palette.Ember),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (showNewChat) IconCircle(Icons.NewChat, "New chat", onNewChat, tint = Palette.Text, background = Color.Transparent, size = 48)
    }
}

/** A routine result; tapping opens the Routines screen. */
@Composable
private fun AlertBanner(alert: com.or1.agent.Alert, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Ink2)
            .border(1.dp, Palette.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Routine, null, tint = Palette.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        // Two lines: the full text is on the Routines screen, a tap away.
        Text(alert.text, style = Type.Step.copy(color = Palette.Text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconCircle(Icons.Close, "Dismiss", onDismiss, tint = Palette.Text2, background = Color.Transparent)
    }
}

@Composable
private fun EmptyState(suggestions: List<Suggestion>, onSuggestion: (Suggestion) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.weight(1f))
        Text(greeting(), style = Type.CaptionSerif, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        Column(Modifier.padding(bottom = 4.dp)) {
            for (s in suggestions) SuggestionRow(s) { onSuggestion(s) }
        }
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                s.appIcon != null -> Image(s.appIcon, null, Modifier.size(26.dp))
                s.glyph != null -> Icon(s.glyph, null, tint = Palette.Text2, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(s.text, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Time-of-day greeting in or1's own words. */
private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Morning. Where do we start?"
    in 12..16 -> "Afternoon. What's next?"
    in 17..21 -> "Evening. What can I take off your plate?"
    else -> "Late night. What's on your mind?"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Conversation(items: List<ChatItem>, busy: Boolean, preview: android.graphics.Bitmap?, homeTaps: Int, onOpenPreview: () -> Unit) {
    val turns = remember(items) { groupTurns(items) }
    val list = rememberLazyListState()
    LaunchedEffect(items.size, items.lastOrNull()) {
        if (turns.isNotEmpty()) list.animateScrollToItem(turns.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }
    // A jump, not an animated scroll: it can be a long way.
    LaunchedEffect(homeTaps) {
        if (homeTaps > 0 && turns.isNotEmpty()) list.scrollToItem(turns.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(turns, key = { it.key }) { turn ->
            val last = turn === turns.last()
            TurnView(turn, active = last && busy, preview = if (last) preview else null, onOpenPreview, Modifier.itemMotion(this))
        }
    }
}

/** One request and its work card, notes and reply (the chat, and the routines thread). */
@Composable
fun TurnView(turn: Turn, active: Boolean, preview: android.graphics.Bitmap?, onOpenPreview: () -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        turn.user?.let { UserBubble(it) }
        if (turn.steps.isNotEmpty() || active) {
            WorkCard(turn, active, preview, onOpenPreview)
        }
        for (info in turn.infos) InfoLine(info)
        turn.reply?.let { ReplyBlock(it) } ?: turn.draft?.let { ReplyBlock(ChatItem.Reply(it.id, it.text, 0.0, 0.0)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(totalCost: Double, routineCount: Int, onNewChat: () -> Unit, onRoutines: () -> Unit, onOpen: (PastChat) -> Unit, open: Boolean) {
    val context = LocalContext.current
    // Bumped after a rename or delete, to re-read the list.
    var version by remember { mutableIntStateOf(0) }
    val chats = remember(open, version) { if (open) History.list(context) else emptyList() }
    var renaming by remember { mutableStateOf<PastChat?>(null) }
    // Deleted from the list at once; the file goes when the Undo pill does.
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    // Flat, on the window's black, with a hairline on its open edge.
    ModalDrawerSheet(
        drawerContainerColor = Palette.Ink0,
        drawerShape = RectangleShape,
        drawerTonalElevation = 0.dp,
        modifier = Modifier.drawWithContent {
            drawContent()
            drawRect(Palette.Line, topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height))
        },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
            Wordmark(block = 5.dp, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 20.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onNewChat).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.NewChat, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text("New chat", style = Type.Body)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onRoutines).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Routine, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text("Routines", style = Type.Body, modifier = Modifier.weight(1f))
                if (routineCount > 0) Text("$routineCount", style = Type.Caption)
            }
            Spacer(Modifier.height(18.dp))
            Text("Recent", style = Type.Caption, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(chats.filter { it.file.name != pending?.key }, key = { it.file.name }) { chat ->
                        if (renaming == chat) {
                            InlineRename(
                                initial = if (chat.renamed) chat.title else "",
                                placeholder = chat.title,
                                style = Type.Body,
                                onDone = { History.rename(context, chat, it); renaming = null; version++ },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).itemMotion(this),
                            )
                        } else {
                            Row(
                                Modifier.fillMaxWidth().itemMotion(this).clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(onClick = { onOpen(chat) }, onLongClick = { renaming = chat })
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(chat.title, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(chat.time, style = Type.Caption)
                                }
                                RowMenu(
                                    listOf(
                                        MenuAction("Rename", Icons.Rename) { renaming = chat },
                                        MenuAction("Delete", Icons.Delete, destructive = true) {
                                            pending?.commit?.invoke()
                                            pending = PendingDelete(chat.file.name, "Chat deleted") { History.delete(context, chat); version++ }
                                        },
                                    ),
                                    label = "Chat options",
                                )
                            }
                        }
                    }
                }
                UndoBar(
                    pending,
                    onUndo = { pending = null },
                    onExpired = { pending?.commit?.invoke(); pending = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                )
            }
            DeveloperSwitch()
            if (DeveloperDetails.on) {
                Text(
                    "This session · $%.4f".format(totalCost),
                    style = Type.Caption.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** Shows cost, seconds and raw tool calls (see [DeveloperDetails]). */
@Composable
private fun DeveloperSwitch() {
    val context = LocalContext.current
    val on = DeveloperDetails.on
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .toggleable(value = on, role = Role.Switch, onValueChange = { DeveloperDetails.set(context, it) })
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Developer details", style = Type.Body, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink0, checkedTrackColor = Palette.Text, checkedBorderColor = Palette.Text,
                uncheckedThumbColor = Palette.Text3, uncheckedTrackColor = Palette.Ink2, uncheckedBorderColor = Palette.Line,
            ),
        )
    }
}

/** The latest IME visibility, readable from inside effects that started before it changed. */
private object WindowInsetsHolder {
    @Volatile
    var imeVisible = false
}
