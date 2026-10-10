package com.or1.agent.ui

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.ChatItem
import com.or1.agent.NotifListener
import com.or1.agent.tools.HiddenDisplay
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The phone's home screen: greeting and suggestions when empty, the conversation otherwise, and the composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen() {
    Or1Theme {
        val context = LocalContext.current
        val items by AgentHub.items.collectAsState()
        val busy by AgentHub.busy.collectAsState()
        val totalCost by AgentHub.totalCost.collectAsState()
        val effort by AgentHub.effort.collectAsState()
        val preview by HiddenDisplay.preview.collectAsState()
        val notificationTick by NotifListener.changes.collectAsState()

        var text by rememberSaveable { mutableStateOf("") }
        var expanded by rememberSaveable { mutableStateOf(false) }
        var viewerOpen by remember { mutableStateOf(false) }
        var pastChat by remember { mutableStateOf<PastChat?>(null) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

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
            if (wasVisible && !imeVisible && text.isBlank()) {
                val hiddenAt = SystemClock.uptimeMillis()
                delay(600) // the focus change is reported around the time the IME hides
                val focusSteal = kotlin.math.abs(focusLostAt - hiddenAt) < 1500
                if (!focusSteal && !WindowInsetsHolder.imeVisible) {
                    expanded = false
                    focusManager.clearFocus()
                }
            }
        }
        WindowInsetsHolder.imeVisible = imeVisible

        fun send(message: String) {
            if (message.isBlank() || busy) return
            AgentHub.submit(message.trim())
            text = ""
            // The agent takes a while: give the screen back to the conversation.
            expanded = false
            focusManager.clearFocus()
        }

        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                Drawer(
                    totalCost = totalCost,
                    onNewChat = { AgentHub.newConversation(); scope.launch { drawer.close() } },
                    onOpen = { pastChat = it; scope.launch { drawer.close() } },
                    open = drawer.isOpen,
                )
            },
        ) {
            Box(Modifier.fillMaxSize().background(Palette.Background)) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    TopBar(
                        showDot = suggestions.any { it.appIcon != null },
                        showNewChat = items.isNotEmpty(),
                        onMenu = { scope.launch { drawer.open() } },
                        onNewChat = { AgentHub.newConversation() },
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (items.isEmpty()) {
                            EmptyState(suggestions, onSuggestion = { send(it.prompt) })
                        } else {
                            Conversation(items, busy, preview, onOpenPreview = { viewerOpen = true })
                        }
                    }
                    Composer(
                        text = text,
                        onText = { text = it },
                        busy = busy,
                        expanded = expanded || text.isNotEmpty(),
                        onExpand = { expanded = it },
                        model = AgentHub.model,
                        effort = effort,
                        onEffort = AgentHub::setEffort,
                        onSend = { send(text) },
                        onStop = AgentHub::stop,
                        onPlusAction = {
                            when (it) {
                                PlusAction.NEW_CHAT -> AgentHub.newConversation()
                                PlusAction.WORK_SCREEN -> viewerOpen = true
                            }
                        },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                    )
                }
                if (viewerOpen) WorkViewer(onClose = { viewerOpen = false })
                pastChat?.let { HistoryView(it, onClose = { pastChat = null }) }
            }
        }
    }
}

@Composable
private fun TopBar(showDot: Boolean, showNewChat: Boolean, onMenu: () -> Unit, onNewChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconCircle(Icons.Menu, "Menu", onMenu, tint = Palette.Text, background = Color.Transparent, size = 48)
            if (showDot) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 9.dp).size(8.dp)
                        .clip(CircleShape).background(Palette.Accent),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (showNewChat) IconCircle(Icons.NewChat, "New chat", onNewChat, tint = Palette.Text, background = Color.Transparent, size = 48)
    }
}

@Composable
private fun EmptyState(suggestions: List<Suggestion>, onSuggestion: (Suggestion) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.weight(1f))
        Text(greeting(), style = Type.Greeting, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1.3f))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            for (s in suggestions) SuggestionRow(s) { onSuggestion(s) }
        }
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                s.appIcon != null -> Image(s.appIcon, null, Modifier.size(26.dp))
                s.glyph != null -> Icon(s.glyph, null, tint = Palette.TextDim, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(s.text, style = Type.Suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun Conversation(items: List<ChatItem>, busy: Boolean, preview: android.graphics.Bitmap?, onOpenPreview: () -> Unit) {
    val turns = remember(items) { groupTurns(items) }
    val list = rememberLazyListState()
    LaunchedEffect(items.size, items.lastOrNull()) {
        if (turns.isNotEmpty()) list.animateScrollToItem(turns.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(turns, key = { it.key }) { turn ->
            val last = turn === turns.last()
            val active = last && busy
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.animateItem()) {
                turn.user?.let { UserBubble(it) }
                if (turn.steps.isNotEmpty() || (active && turn.approvals.none { it.state == null })) {
                    WorkCard(turn, active, if (last) preview else null, onOpenPreview)
                }
                for (approval in turn.approvals) ApprovalCard(approval) { AgentHub.decide(approval, it) }
                for (info in turn.infos) InfoLine(info)
                turn.reply?.let { ReplyBlock(it) }
            }
        }
    }
}

@Composable
private fun Drawer(totalCost: Double, onNewChat: () -> Unit, onOpen: (PastChat) -> Unit, open: Boolean) {
    val context = LocalContext.current
    val chats = remember(open) { if (open) History.list(context) else emptyList() }
    ModalDrawerSheet(drawerContainerColor = Palette.Background, drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
            Text("or1", style = Type.Greeting, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 20.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onNewChat).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.NewChat, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text("New chat", style = Type.Body)
            }
            Spacer(Modifier.height(18.dp))
            Text("Recent", style = Type.Caption, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(chats, key = { it.file.name }) { chat ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(chat) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(chat.title, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(chat.time, style = Type.Caption)
                    }
                }
            }
            Text(
                "This session · $%.4f".format(totalCost),
                style = Type.Caption,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** The latest IME visibility, readable from inside effects that started before it changed. */
private object WindowInsetsHolder {
    @Volatile
    var imeVisible = false
}
