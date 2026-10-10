package com.or1.agent.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
    text: String,
    onText: (String) -> Unit,
    busy: Boolean,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    model: String,
    effort: String,
    onEffort: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPlusAction: (PlusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val action = when {
        busy -> PrimaryAction.STOP
        text.isNotBlank() -> PrimaryAction.SEND
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

    AnimatedContent(
        targetState = expanded,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        modifier = modifier,
        label = "composer",
    ) { isExpanded ->
        if (!isExpanded) {
            // Pill: + · placeholder · mic · round button.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(CircleShape)
                    .background(Palette.Surface)
                    .border(1.dp, Palette.Border, CircleShape)
                    .clickable { onExpand(true) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlusButton(onPlusAction, background = Color.Transparent)
                Text(
                    text.ifBlank { "Ask or1…" },
                    style = Type.Input.copy(color = if (text.isBlank()) Palette.Placeholder else Palette.Text),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                IconCircle(Icons.Mic, "Voice input", onMic, tint = Palette.TextDim, background = Color.Transparent)
                Spacer(Modifier.width(4.dp))
                PrimaryButton(action, onPrimary)
            }
        } else {
            ComposerCard(text, onText, action, onPrimary, onMic, onExpand, model, effort, onEffort, onSend, onPlusAction)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerCard(
    text: String,
    onText: (String) -> Unit,
    action: PrimaryAction,
    onPrimary: () -> Unit,
    onMic: () -> Unit,
    onExpand: (Boolean) -> Unit,
    model: String,
    effort: String,
    onEffort: (String) -> Unit,
    onSend: () -> Unit,
    onPlusAction: (PlusAction) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // The agent's work display can briefly take window focus (when an app it drives opens a new screen), and
    // Android closes the keyboard when that happens. Reopen it as soon as the chat has focus again.
    // The agent's work display briefly takes focus for some actions (a back key, an app opening a new screen),
    // and Android closes the keyboard when that happens. When focus is handed back, reopen it if it was up a
    // moment ago. Focus can leave and return within a frame, so this listens to the hand-back rather than to
    // window focus.
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleNow by rememberUpdatedState(imeVisible)
    val focusedNow by rememberUpdatedState(focused)
    val imeHiddenAt = remember { longArrayOf(0L) }
    LaunchedEffect(imeVisible) { if (!imeVisible) imeHiddenAt[0] = SystemClock.uptimeMillis() }
    LaunchedEffect(Unit) {
        HiddenDisplay.focusReturned.drop(1).collect {
            // Was the keyboard up just before? (Insets lag, so it may still read as visible here.)
            val wasUp = imeVisibleNow || SystemClock.uptimeMillis() - imeHiddenAt[0] < 2500
            if (!focusedNow || !wasUp) return@collect
            // A request made before the IME has re-bound to this window is dropped, so keep asking for a moment.
            repeat(8) {
                delay(200)
                if (!imeVisibleNow) {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
        }
    }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Border, shape)
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 28.dp, max = 180.dp)) {
            if (text.isEmpty()) Text("Ask or1…", style = Type.Input.copy(color = Palette.Placeholder))
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = Type.Input,
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged {
                        // Collapse back to the pill when focus leaves an empty composer.
                        if (focused && !it.isFocused && text.isBlank()) onExpand(false)
                        focused = it.isFocused
                    }
                    .onPreviewKeyEvent {
                        // Hardware keyboards: Enter sends, Shift+Enter adds a line.
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) {
                            if (text.isNotBlank()) onSend()
                            true
                        } else false
                    },
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlusButton(onPlusAction, background = Palette.SurfaceHigh)
            ModelChip(model, effort, onEffort)
            Spacer(Modifier.weight(1f))
            IconCircle(Icons.Mic, "Voice input", onMic, tint = Palette.TextDim, background = Palette.SurfaceHigh)
            PrimaryButton(action, onPrimary)
        }
    }
}

enum class PlusAction { NEW_CHAT, WORK_SCREEN }

@Composable
private fun PlusButton(onAction: (PlusAction) -> Unit, background: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconCircle(Icons.Plus, "More", { open = true }, tint = Palette.Text, background = background)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.SurfaceHigh) {
            DropdownMenuItem(
                text = { Text("New chat", style = Type.Body) },
                leadingIcon = { Icon(Icons.NewChat, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.NEW_CHAT) },
            )
            DropdownMenuItem(
                text = { Text("Show work screen", style = Type.Body) },
                leadingIcon = { Icon(Icons.Screen, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.WORK_SCREEN) },
            )
        }
    }
}

/** "Haiku 5.5 Medium": tap to pick the reasoning effort. */
@Composable
private fun ModelChip(model: String, effort: String, onEffort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(Palette.SurfaceHigh)
                .clickable { open = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    append(modelName(model))
                    withStyle(SpanStyle(color = Palette.TextDim)) { append(" " + effort.replaceFirstChar { it.uppercase() }) }
                },
                style = Type.Chip,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.SurfaceHigh) {
            for (level in listOf("low", "medium", "high")) {
                DropdownMenuItem(
                    text = { Text(level.replaceFirstChar { it.uppercase() }, style = Type.Body) },
                    trailingIcon = {
                        if (level == effort) Icon(Icons.Check, null, tint = Palette.Text, modifier = Modifier.size(18.dp))
                    },
                    onClick = { open = false; onEffort(level) },
                )
            }
        }
    }
}

/** claude-haiku-5-5 -> "Haiku 5.5". */
fun modelName(id: String): String {
    val parts = id.removePrefix("claude-").split("-")
    val family = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: id
    val version = parts.drop(1).joinToString(".")
    return if (version.isEmpty()) family else "$family $version"
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
fun IconCircle(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color, background: Color, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}
