package com.or1.agent.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.or1.agent.tools.HiddenDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

/** The ways the round primary button can act. */
private enum class PrimaryAction { VOICE, SEND, STOP }

/**
 * Composer, after both references: a single pill while idle (keyboard closed, nothing typed), and a card with the
 * field on top and a row of controls underneath while composing.
 */
@Composable
fun Composer(
    input: ComposerInput,
    busy: Boolean,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPlusAction: (PlusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasText = input.hasText
    val action = when {
        busy -> PrimaryAction.STOP
        hasText -> PrimaryAction.SEND
        else -> PrimaryAction.VOICE
    }
    val onPrimary = {
        when (action) {
            PrimaryAction.STOP -> onStop()
            PrimaryAction.SEND -> onSend()
            PrimaryAction.VOICE -> Toast.makeText(context, "Voice is coming soon", Toast.LENGTH_SHORT).show()
        }
    }
    val onMic = { Toast.makeText(context, "Voice is coming soon", Toast.LENGTH_SHORT).show() }

    // A plain swap, not AnimatedContent: the card's native field (an AndroidView) isn't redrawn when it comes back
    // inside a fading layer. The keyboard sliding in is the transition.
    Box(modifier) {
        val isExpanded = expanded || hasText
        if (!isExpanded) {
            // Pill: + · placeholder · mic · round button.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(CircleShape)
                    .background(Palette.Ink3)
                    .border(1.dp, Palette.Line, CircleShape)
                    .clickable { onExpand(true) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlusButton(onPlusAction, background = Color.Transparent)
                Text(
                    "Ask or1…",
                    style = Type.Body.copy(color = Palette.Text2),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                IconCircle(Icons.Mic, "Voice input", onMic, tint = Palette.Text2, background = Color.Transparent)
                Spacer(Modifier.width(4.dp))
                PrimaryButton(action, onPrimary)
            }
        } else {
            ComposerCard(input, busy, action, onPrimary, onMic, onExpand, onSend, onPlusAction)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerCard(
    input: ComposerInput,
    busy: Boolean,
    action: PrimaryAction,
    onPrimary: () -> Unit,
    onMic: () -> Unit,
    onExpand: (Boolean) -> Unit,
    onSend: () -> Unit,
    onPlusAction: (PlusAction) -> Unit,
) {
    LaunchedEffect(Unit) { input.focusAndShowKeyboard() }
    DisposableEffect(input) {
        // Collapse back to the pill when focus leaves an empty composer.
        input.onFocusChange = { focused -> if (!focused && !input.hasText) onExpand(false) }
        input.onEnter = onSend
        onDispose { input.onFocusChange = {}; input.onEnter = {} }
    }
    // The agent's work display briefly takes focus for some actions (a back key, an app opening a new screen),
    // and Android closes the keyboard when that happens. When focus is handed back, reopen it if it was up a
    // moment ago. Focus can leave and return within a frame, so this listens to the hand-back rather than to
    // window focus.
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleNow by rememberUpdatedState(imeVisible)
    val imeHiddenAt = remember { longArrayOf(0L) }
    LaunchedEffect(imeVisible) { if (!imeVisible) imeHiddenAt[0] = SystemClock.uptimeMillis() }
    LaunchedEffect(Unit) {
        HiddenDisplay.focusReturned.drop(1).collect {
            // Was the keyboard up just before? (Insets lag, so it may still read as visible here.)
            val wasUp = imeVisibleNow || SystemClock.uptimeMillis() - imeHiddenAt[0] < 2500
            if (!input.focused || !wasUp) return@collect
            // A request made before the IME has re-bound to this window is dropped, so keep asking for a moment.
            repeat(8) {
                delay(200)
                if (!imeVisibleNow) input.focusAndShowKeyboard()
            }
        }
    }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Ink3)
            .border(1.dp, Palette.Line, shape)
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
    ) {
        AndroidView(
            factory = input::createView,
            onRelease = input::release,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlusButton(onPlusAction, background = Palette.Ink4)
            AgentScreenChip(busy) { onPlusAction(PlusAction.WORK_SCREEN) }
            Spacer(Modifier.weight(1f))
            IconCircle(Icons.Mic, "Voice input", onMic, tint = Palette.Text2, background = Palette.Ink4)
            PrimaryButton(action, onPrimary)
        }
    }
}

enum class PlusAction { NEW_CHAT, WORK_SCREEN, DRAWER }

@Composable
private fun PlusButton(onAction: (PlusAction) -> Unit, background: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconCircle(Icons.Plus, "More", { open = true }, tint = Palette.Text, background = background)
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp), containerColor = Palette.Ink4,
            tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(1.dp, Palette.Line),
        ) {
            DropdownMenuItem(
                text = { Text("New chat", style = Type.Body) },
                leadingIcon = { Icon(Icons.NewChat, null, tint = Palette.Text2, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.NEW_CHAT) },
            )
            DropdownMenuItem(
                text = { Text("Show agent screen", style = Type.Body) },
                leadingIcon = { Icon(Icons.Screen, null, tint = Palette.Text2, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.WORK_SCREEN) },
            )
            // The drawer's edge swipe is unreliable on this digitiser; this is the other way in.
            DropdownMenuItem(
                text = { Text("Chats and routines", style = Type.Body) },
                leadingIcon = { Icon(Icons.Menu, null, tint = Palette.Text2, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.DRAWER) },
            )
        }
    }
}

/** Opens the agent's work screen; its icon becomes the march while or1 is working there. */
@Composable
private fun AgentScreenChip(busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(Palette.Ink4)
            .clickable(onClickLabel = "Show agent screen", onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same slot either way, so the row doesn't shift when or1 starts.
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (busy) March() else Icon(Icons.Screen, null, tint = Palette.Text2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("Agent screen", style = Type.Step.copy(color = Palette.Text), maxLines = 1)
    }
}

@Composable
private fun PrimaryButton(action: PrimaryAction, onClick: () -> Unit) {
    val (icon, label) = when (action) {
        PrimaryAction.STOP -> Icons.Stop to "Stop"
        PrimaryAction.SEND -> Icons.ArrowUp to "Send"
        PrimaryAction.VOICE -> Icons.Waveform to "Voice mode"
    }
    IconCircle(icon, label, onClick, tint = Palette.OnPrimary, background = Palette.Primary)
}

@Composable
fun IconCircle(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color, background: Color, size: Int = 48) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}
