package com.or1.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.or1.agent.ChatItem
import kotlinx.coroutines.delay

/** Pixel blocks in cards are 4 dp (the rail's are 2, the drawer wordmark's 5): 3 dp read as specks at 320 dpi. */
val CARD_BLOCK: Dp = 4.dp

enum class StepState(val color: Color) {
    UNLIT(Palette.Line), RUNNING(Palette.Ember), DONE(Palette.Text2), FAILED(Palette.Error);

    companion object {
        /** A call with no result is running while the turn is; one cut short (Stop, take-over) stays unlit. */
        fun of(call: ChatItem.ToolCall, active: Boolean): StepState = when {
            call.isError -> FAILED
            call.result != null -> DONE
            active -> RUNNING
            else -> UNLIT
        }
    }
}

private const val PER_ROW = 16

/** The app's only progress indicator: one block per tool call, a block's width apart, wrapping every [PER_ROW]. */
@Composable
fun StepBlocks(states: List<StepState>, modifier: Modifier = Modifier) {
    if (states.isEmpty()) return
    val cols = minOf(states.size, PER_ROW)
    val rows = (states.size + PER_ROW - 1) / PER_ROW
    Canvas(modifier.size(CARD_BLOCK * (cols * 2 - 1), CARD_BLOCK * (rows * 2 - 1))) {
        val b = CARD_BLOCK.toPx()
        states.forEachIndexed { i, state ->
            drawRect(state.color, topLeft = Offset((i % PER_ROW) * 2 * b, (i / PER_ROW) * 2 * b), size = Size(b, b))
        }
    }
}

/**
 * Three blocks, one lit and stepping along every [Motion.MARCH_MS] while this is shown: or1 is working. The step is
 * read in the draw phase, so the march redraws the canvas without recomposing anything.
 */
@Composable
fun March(modifier: Modifier = Modifier, block: Dp = CARD_BLOCK, color: Color = Palette.Ember) {
    val step = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Motion.MARCH_MS)
            step.intValue = (step.intValue + 1) % 3
        }
    }
    Canvas(modifier.size(block * 5, block)) {
        val b = block.toPx()
        for (i in 0..2) drawRect(if (i == step.intValue) color else Palette.Line, topLeft = Offset(i * 2 * b, 0f), size = Size(b, b))
    }
}

/** Three steady blocks: the counterpart of [March] when it's the user's turn. */
@Composable
fun Steady(modifier: Modifier = Modifier, block: Dp = CARD_BLOCK, color: Color = Palette.Live) {
    Canvas(modifier.size(block * 5, block)) {
        val b = block.toPx()
        for (i in 0..2) drawRect(color, topLeft = Offset(i * 2 * b, 0f), size = Size(b, b))
    }
}

/** The voice meter steps every 100 ms (docs/ui-ledger.md §3: meters 100 ms). */
const val METER_MS = 100L
private const val METER_COLUMNS = 9
private const val METER_ROWS = 5

/** 4 dp: a 3 dp block is a speck at this phone's 320 dpi when it's the only thing on a line. */
val METER_BLOCK: Dp = 4.dp

/**
 * The voice level as columns of blocks: 9 columns, a block apart, of up to 5 blocks each, sampled from [level] every
 * [METER_MS]. The newest sample is the middle column and older ones move outwards, so it reads as a voice; the bottom
 * block is always lit and unlit ones are `line`. `live` while listening, `ember` while or1 speaks. Samples are read in
 * the draw phase, so stepping redraws the canvas without recomposing, and a Canvas adds no semantics.
 */
@Composable
fun LevelMeter(level: () -> Float, color: Color, modifier: Modifier = Modifier, block: Dp = METER_BLOCK) {
    val history = remember { FloatArray(METER_COLUMNS / 2 + 1) }
    val tick = remember { mutableIntStateOf(0) }
    val sample by rememberUpdatedState(level)
    LaunchedEffect(Unit) {
        while (true) {
            for (i in history.lastIndex downTo 1) history[i] = history[i - 1]
            history[0] = sample().coerceIn(0f, 1f)
            tick.intValue++
            delay(METER_MS)
        }
    }
    Canvas(modifier.size(block * (METER_COLUMNS * 2 - 1), block * (METER_ROWS * 2 - 1))) {
        tick.intValue // redraw on every sample
        val b = block.toPx()
        for (c in 0 until METER_COLUMNS) {
            val v = history[kotlin.math.abs(c - METER_COLUMNS / 2)]
            val lit = 1 + (v * (METER_ROWS - 1) + 0.5f).toInt()
            for (r in 0 until METER_ROWS) {
                drawRect(if (r < lit) color else Palette.Line, topLeft = Offset(c * 2 * b, (METER_ROWS - 1 - r) * 2 * b), size = Size(b, b))
            }
        }
    }
}
