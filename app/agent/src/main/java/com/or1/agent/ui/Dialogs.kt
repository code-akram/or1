package com.or1.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One entry in a [RowMenu]. */
class MenuAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/** A ⋯ button that opens a small menu (rename, delete and the like) for a row in a list. */
@Composable
fun RowMenu(actions: List<MenuAction>, label: String = "More") {
    var open by remember { mutableStateOf(false) }
    Box {
        IconCircle(Icons.More, label, { open = true }, tint = Palette.TextFaint, background = Color.Transparent, size = 36)
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            containerColor = Palette.SurfaceHigh, shape = RoundedCornerShape(14.dp),
        ) {
            for (action in actions) {
                val tint = if (action.destructive) Palette.Error else Palette.Text
                DropdownMenuItem(
                    text = { Text(action.label, style = Type.Step.copy(color = tint)) },
                    leadingIcon = { Icon(action.icon, null, tint = if (action.destructive) Palette.Error else Palette.TextDim, modifier = Modifier.size(18.dp)) },
                    onClick = { open = false; action.onClick() },
                )
            }
        }
    }
}

/**
 * Renames in place: the row's title turns into a field with everything selected. Done on the keyboard, the check, or
 * tapping elsewhere saves; a blank name goes back to the default (shown as the placeholder).
 */
@Composable
fun InlineRename(initial: String, placeholder: String, style: TextStyle, onDone: (String) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var done by remember { mutableStateOf(false) }
    // onFocusChanged also reports "unfocused" once before the field first gets focus; only a later loss saves.
    var hadFocus by remember { mutableStateOf(false) }
    val finish = { if (!done) { done = true; onDone(value.text.trim()) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface).border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (value.text.isEmpty()) Text(placeholder, style = style.copy(color = Palette.Placeholder), maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier.focusRequester(focus).onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) finish() },
            )
        }
        IconCircle(Icons.Check, "Save", { finish() }, tint = Palette.Text, background = Color.Transparent, size = 36)
    }
}

/** "Chat deleted · Undo" at the bottom of a list, for a few seconds; the deletion happens when it goes away. */
class PendingDelete(val key: String, val text: String, val commit: () -> Unit)

@Composable
fun UndoBar(pending: PendingDelete?, onUndo: () -> Unit, onExpired: () -> Unit, modifier: Modifier = Modifier) {
    val expired by rememberUpdatedState(onExpired)
    LaunchedEffect(pending?.key) {
        if (pending != null) {
            delay(4000)
            expired()
        }
    }
    // Leaving the screen with a deletion pending carries it out.
    val current by rememberUpdatedState(pending)
    DisposableEffect(Unit) { onDispose { current?.commit?.invoke() } }
    AnimatedVisibility(
        visible = pending != null, modifier = modifier,
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
    ) {
        Row(
            Modifier.clip(CircleShape).background(Palette.SurfaceHigh).border(1.dp, Palette.Border, CircleShape)
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pending?.text.orEmpty(), style = Type.Step.copy(color = Palette.Text), maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Text(
                "Undo", style = Type.Label.copy(color = Palette.Accent),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onUndo).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
