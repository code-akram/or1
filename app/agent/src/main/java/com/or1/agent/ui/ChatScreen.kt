package com.or1.agent.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.or1.agent.RoutineAct
import com.or1.agent.Routines
import com.or1.agent.today
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The phone's home screen: the stream (the conversation with today's routine acts among its turns; when it's empty, the
 * idle home: routine acts, today's effects dimmed, the greeting and suggestions just above the composer), the
 * composer, the drawer with past chats, and the screens that open over them. `or1` in the rail goes home from
 * anywhere; Back closes the topmost layer. The Now strip's chips sit in the top bar, between the menu and new chat; the
 * notifications chip opens the triage sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen() {
    Or1Theme {
        val context = LocalContext.current
        val items by AgentHub.items.collectAsState()
        val conversation by AgentHub.conversation.collectAsState()
        val busy by AgentHub.busy.collectAsState()
        val totalCost by AgentHub.totalCost.collectAsState()
        val routineActs by AgentHub.routineActs.collectAsState()
        val notificationTick by NotifListener.changes.collectAsState()
        val routines by Routines.list.collectAsState()
        val showRoutines by AgentHub.showRoutines.collectAsState()

        // A native field (see ComposerInput): typing doesn't recompose anything.
        val input = remember { ComposerInput() }
        var expanded by rememberSaveable { mutableStateOf(false) }
        var viewerOpen by remember { mutableStateOf(false) }
        var voiceOpen by remember { mutableStateOf(false) }
        var pastChat by remember { mutableStateOf<PastChat?>(null) }
        // Routines, Today or What or1 knows, over the chat (one at a time).
        var page by remember { mutableStateOf<Page?>(null) }
        // Bumped by the home button: the conversation scrolls to its end.
        var homeTaps by remember { mutableIntStateOf(0) }
        // The triage sheet (notifications waiting), over everything else.
        var triageOpen by remember { mutableStateOf(false) }
        // A timer chip tapped: the stream scrolls to the turn with that effect.
        var scrollTo by remember { mutableStateOf<Focus?>(null) }
        // An evidence frame shown full size, over everything.
        var evidence by remember { mutableStateOf<com.or1.agent.Effect?>(null) }
        LaunchedEffect(showRoutines) {
            if (showRoutines) {
                page = Page.ROUTINES
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
            evidence = null
            triageOpen = false
            viewerOpen = false
            voiceOpen = false
            page = null
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
                evidence != null -> evidence = null
                triageOpen -> triageOpen = false
                // Drawn over everything below.
                drawer.isOpen -> scope.launch { drawer.close() }
                voiceOpen -> voiceOpen = false
                viewerOpen -> viewerOpen = false
                page != null -> page = null
                pastChat != null -> pastChat = null
                else -> closeComposer()
            }
        }

        // A routine act the user hasn't seen: one block in the rail until the stream has been in view at its bottom.
        var routinesSeenUntil by rememberSaveable { mutableLongStateOf(0L) }
        var streamAtBottom by remember { mutableStateOf(true) }
        val newestRoutine = routineActs.lastOrNull()?.finished ?: 0L
        val streamShown = page == null && pastChat == null && !viewerOpen && !voiceOpen && !triageOpen && evidence == null && !drawer.isOpen
        LaunchedEffect(streamShown, streamAtBottom, newestRoutine) {
            if (streamShown && streamAtBottom) routinesSeenUntil = newestRoutine
        }

        // The rail first, below the status bar and the notch; every screen and the drawer below it.
        Column(Modifier.fillMaxSize().background(Palette.Ink0)) {
            StatusRail(busy, onHome = ::goHome, unseen = newestRoutine > routinesSeenUntil)
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
                            onPage = { page = it; scope.launch { drawer.close() } },
                            onOpen = { pastChat = it; scope.launch { drawer.close() } },
                            open = drawer.isOpen,
                        )
                    },
                ) {
                    val openTriage = {
                        closeComposer()
                        keyboard?.hide()
                        triageOpen = true
                    }
                    val cardActions = remember { CardActions(openRoutines = { page = Page.ROUTINES }, openTriage = openTriage, openViewer = { closeComposer(); viewerOpen = true }) }
                    val openEvidence = remember { { e: com.or1.agent.Effect -> closeComposer(); evidence = e } }
                    val streamTap = remember { { closeComposer() } }
                    CompositionLocalProvider(LocalEvidenceViewer provides openEvidence, LocalStreamTap provides streamTap) {
                        Box(Modifier.fillMaxSize().background(Palette.Ink0)) {
                            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                                TopBar(
                                    onBlankTap = ::closeComposer,
                                    showNewChat = items.isNotEmpty(),
                                    onMenu = { scope.launch { drawer.open() } },
                                    onNewChat = { AgentHub.newConversation() },
                                ) {
                                    NowStrip(
                                        onTimer = { timer ->
                                            // To its card when it's in this conversation; otherwise the chip offers Cancel.
                                            val here = items.any { it is ChatItem.Effect && it.effect.id == timer.id }
                                            if (here) scrollTo = Focus(timer.id, (scrollTo?.n ?: 0) + 1)
                                            here
                                        },
                                        onTriage = openTriage,
                                        onRoutine = { page = Page.ROUTINES },
                                    )
                                }
                                // Tapping empty space closes the keyboard: with the system UI locked down there's no back button.
                                Box(
                                    Modifier.weight(1f).fillMaxWidth().clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                        onClick = ::closeComposer,
                                    ),
                                ) {
                                    if (items.isEmpty()) {
                                        IdleHome(
                                            suggestions, routineActs, greeting(), onSuggestion = { send(it.prompt) },
                                            onToday = { closeComposer(); page = Page.TODAY }, onAtBottom = { streamAtBottom = it },
                                        )
                                    } else {
                                        // A list per conversation: the old turns' exit animations otherwise get stuck when the
                                        // items are replaced in one go (a fresh conversation), leaving them drawn under the new one.
                                        key(conversation) {
                                            CompositionLocalProvider(LocalCardActions provides cardActions) {
                                                Conversation(
                                                    items, routineActs, busy, homeTaps, scrollTo, onOpenPreview = { viewerOpen = true },
                                                    onAtBottom = { streamAtBottom = it },
                                                )
                                            }
                                        }
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
                                    onVoiceMode = { closeComposer(); voiceOpen = true },
                                )
                            }
                            pastChat?.let { HistoryView(it, onClose = { pastChat = null }) }
                            when (page) {
                                Page.ROUTINES -> RoutinesView(onClose = { page = null }, onOpenPreview = { viewerOpen = true })
                                Page.TODAY -> ActivityView(onClose = { page = null })
                                Page.MEMORY -> MemoryView(onClose = { page = null })
                                null -> {}
                            }
                            if (viewerOpen) {
                                WorkViewer(
                                    onClose = { viewerOpen = false },
                                    // Back to the chat with the composer open, to ask about what's on the work screen.
                                    onAsk = { viewerOpen = false; page = null; expanded = true },
                                )
                            }
                            evidence?.let { EvidenceViewer(it, onClose = { evidence = null }) }
                            TriageSheet(triageOpen, onClose = { triageOpen = false })
                            VoiceModeHost(voiceOpen, onClose = { voiceOpen = false })
                        }
                    }
                }
            }
        }
    }
}

/** Menu | the Now strip's chips | new chat, in one 48 dp row: no extra band, and nothing in the middle when nothing is live. */
@Composable
private fun TopBar(
    onBlankTap: () -> Unit, showNewChat: Boolean, onMenu: () -> Unit, onNewChat: () -> Unit,
    chips: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onBlankTap)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No badge: ember only ever means or1 acting now.
        IconCircle(Icons.Menu, "Menu", onMenu, tint = Palette.Text, background = Color.Transparent, size = 48)
        Box(Modifier.weight(1f).padding(start = 4.dp)) { chips() }
        if (showNewChat) IconCircle(Icons.NewChat, "New chat", onNewChat, tint = Palette.Text, background = Color.Transparent, size = 48)
    }
}

@Composable
internal fun SuggestionRow(s: Suggestion, onClick: () -> Unit) {
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

/** The conversation's turns with today's routine acts among them by time ([interleave]). */
@Composable
private fun Conversation(
    items: List<ChatItem>, routineActs: List<RoutineAct>, busy: Boolean, homeTaps: Int, focus: Focus?, onOpenPreview: () -> Unit,
    onAtBottom: (Boolean) -> Unit,
) {
    val turns = remember(items) { groupTurns(items) }
    // Result cards per turn; only the newest few follow their sources (ResultCards.MAX_LIVE).
    val cards = remember(turns) { turns.map { ResultCards.of(it) } }
    val live = remember(cards) { ResultCards.liveKeys(cards) }
    val entries = remember(turns, routineActs) { interleave(turns, routineActs) }
    val list = rememberLazyListState()
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(atBottom) { onAtBottom(atBottom) }
    LaunchedEffect(focus) {
        val id = focus?.effectId ?: return@LaunchedEffect
        val index = entries.indexOfFirst { e -> e is StreamEntry.Act && e.turn.effects.any { it.effect.id == id } }
        if (index >= 0) list.animateScrollToItem(index)
    }
    LaunchedEffect(items.size, items.lastOrNull(), routineActs.size) {
        if (entries.isNotEmpty()) list.animateScrollToItem(entries.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }
    // A jump, not an animated scroll: it can be a long way.
    LaunchedEffect(homeTaps) {
        if (homeTaps > 0 && entries.isNotEmpty()) list.scrollToItem(entries.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is StreamEntry.Act -> TurnView(
                    entry.turn, active = entry.index == turns.lastIndex && busy, onOpenPreview, Modifier.itemMotion(this),
                    cards[entry.index], live,
                )
                is StreamEntry.Routine -> RoutineActView(entry.act, Modifier.itemMotion(this))
            }
        }
    }
}

/**
 * One act: the request, its act card (steps, evidence while working, receipt), its result cards, notes and the reply
 * (the chat, and the routines thread). Effects a card stands for (a timer's Cancel, an alarm's Off, a setting, a
 * routine) leave the receipt. Cards whose key is in [live] follow their sources; the rest render frozen.
 */
@Composable
fun TurnView(
    turn: Turn, active: Boolean, onOpenPreview: () -> Unit, modifier: Modifier = Modifier,
    cards: List<ResultCard> = remember(turn) { ResultCards.of(turn) }, live: Set<String> = emptySet(),
) {
    val receipt = remember(turn, cards) { ResultCards.receipt(turn, cards).map { it.effect } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        turn.user?.let { UserBubble(it) }
        if (turn.toolCalls.isNotEmpty() || active || receipt.isNotEmpty()) ActCard(turn, active, receipt, onOpenPreview)
        for (card in cards) key(card.key) {
            // While working, the evidence strip is the live view of the work display: the media card waits for the end.
            if (!(active && card is ResultCard.Media)) ResultCardView(card, card.key in live)
        }
        for (info in turn.infos) InfoLine(info)
        turn.reply?.let { ReplyBlock(it) } ?: turn.draft?.let { ReplyBlock(ChatItem.Reply(it.id, it.text, 0.0, 0.0)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(totalCost: Double, routineCount: Int, onNewChat: () -> Unit, onPage: (Page) -> Unit, onOpen: (PastChat) -> Unit, open: Boolean) {
    val context = LocalContext.current
    val effects by AgentHub.effects.collectAsState()
    val todayCount = remember(effects, open) { effects.today().size }
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
            DrawerEntry(Icons.History, "Today", todayCount) { onPage(Page.TODAY) }
            DrawerEntry(Icons.Routine, "Routines", routineCount) { onPage(Page.ROUTINES) }
            DrawerEntry(Icons.Notes, "What or1 knows") { onPage(Page.MEMORY) }
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
            SpeakRepliesSwitch()
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

@Composable
private fun DrawerEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, count: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = Type.Body, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", style = Type.Caption.copy(fontFeatureSettings = "tnum"))
    }
}

/** A request to scroll the stream to the turn with effect [effectId]; [n] makes a repeat tap a new request. */
private data class Focus(val effectId: String, val n: Int)

/** The screens the drawer opens over the chat. */
enum class Page { ROUTINES, TODAY, MEMORY }

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
