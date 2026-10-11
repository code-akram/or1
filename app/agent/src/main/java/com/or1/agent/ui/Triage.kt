package com.or1.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.or1.agent.NowState
import kotlin.math.roundToInt

/**
 * The triage sheet (docs/ui-ledger.md §2): notifications waiting, grouped by app, each with Dismiss and Handle (the
 * same rows as the notifications card), and "Handle all" as one request. Slides up 24 dp with a fade over a flat dim
 * of the stream (no blur). Back, the `or1` home button, a tap on the dim or dragging the top edge down closes it.
 * Handling closes it too, so the request shows in the chat.
 */
@Composable
fun TriageSheet(open: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onClose),
        )
    }
    val slide = with(LocalDensity.current) { Motion.ENTER_SLIDE.roundToPx() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            open,
            enter = slideInVertically(Motion.enter()) { slide } + fadeIn(Motion.enter()),
            exit = slideOutVertically(Motion.layout()) { slide } + fadeOut(Motion.layout()),
        ) {
            Sheet(onClose)
        }
    }
}

@Composable
private fun Sheet(onClose: () -> Unit) {
    val groups by NowState.notifications.collectAsState()
    val count = groups.sumOf { it.items.size }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    val closeAfter = with(LocalDensity.current) { 64.dp.toPx() }
    // Dragging the top edge down: the sheet follows, and closes past 64 dp.
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .offset { IntOffset(0, drag.roundToInt()) }
            .clip(Shapes.sheet)
            .background(Palette.Ink3)
            .border(1.dp, Palette.Line, Shapes.sheet)
            // Taps on the sheet stay on it (the dim behind closes it); no click action in the accessibility tree.
            .pointerInput(Unit) { detectTapGestures() }
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxWidth().draggable(
                rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) },
                Orientation.Vertical,
                onDragStopped = { if (drag > closeAfter) onClose() else drag = 0f },
            ),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Line))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Waiting", style = Type.Label)
                Spacer(Modifier.width(8.dp))
                Text("$count", style = Type.Step.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f))
                if (count > 0) UndoChip("Handle all") { if (NowState.handleAll()) onClose() }
            }
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text("Nothing waiting.", style = Type.Step, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 16.dp).itemMotion(this))
                }
            }
            for (group in groups) {
                item(key = "app:${group.pkg}") {
                    NotificationGroupHeader(group, Modifier.itemMotion(this).hairlineTop().padding(start = 20.dp, end = 20.dp))
                }
                items(group.items, key = { it.key }) { n ->
                    NotificationRow(
                        n,
                        onDismiss = { NowState.dismiss(n.key) },
                        onHandle = { NowState.handle(n); onClose() },
                        modifier = Modifier.itemMotion(this).padding(start = 20.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}
