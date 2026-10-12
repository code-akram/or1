package com.or1.agent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.Effect
import com.or1.agent.Evidence
import com.or1.agent.TapMark
import com.or1.agent.tools.HiddenDisplay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A tap older than this no longer decides which part of the work display the strip shows. */
private const val FOCUS_MS = 60_000L

/**
 * The evidence strip (docs/ui-ledger.md §4): a 96 dp full-width crop of the live preview (2 fps) of the screen or1
 * works on (the work display, or the stage while it works in the app playing there),
 * centred on the agent's last tap, with a 10 dp ember ring there for [Evidence.RING_MS]. Frames and the ring are read
 * in the draw phase, so a new frame only redraws this canvas; a tap recomposes it once. No semantics: the
 * "Watch or take over" row under it is the accessible way in.
 */
@Composable
fun EvidenceStrip(onOpen: () -> Unit) {
    val agentPkg by HiddenDisplay.agentPackage.collectAsState()
    val kept by com.or1.agent.Stage.kept.collectAsState()
    val onStage = agentPkg != null && agentPkg == kept
    val preview = remember(onStage) { if (onStage) com.or1.agent.Stage.preview else HiddenDisplay.preview }.collectAsState()
    val lastTap = Evidence.lastTap.collectAsState()
    val ring = remember { mutableStateOf<TapMark?>(null) }
    val mark = lastTap.value
    LaunchedEffect(mark) {
        val m = mark ?: return@LaunchedEffect
        val left = Evidence.RING_MS - (SystemClock.elapsedRealtime() - m.atElapsed)
        if (left <= 0) return@LaunchedEffect
        ring.value = m
        delay(left)
        ring.value = null
    }
    Canvas(
        Modifier.fillMaxWidth().height(96.dp).background(Palette.Ink1)
            .clearAndSetSemantics {}
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
    ) {
        val frame = preview.value
        if (frame != null && !frame.isRecycled && frame.width > 0) {
            val scale = size.width / frame.width
            val srcH = (size.height / scale).coerceAtMost(frame.height.toFloat())
            val focus = lastTap.value?.takeIf { SystemClock.elapsedRealtime() - it.atElapsed < FOCUS_MS && it.displayH > 0 }
                ?.let { it.y / it.displayH * frame.height } ?: (srcH / 2)
            val top = (focus - srcH / 2).coerceIn(0f, frame.height - srcH)
            drawImage(
                frame.asImageBitmap(),
                srcOffset = IntOffset(0, top.roundToInt()), srcSize = IntSize(frame.width, srcH.roundToInt()),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
            ring.value?.takeIf { it.displayW > 0 && it.displayH > 0 }?.let { m ->
                val center = Offset(m.x / m.displayW * size.width, (m.y / m.displayH * frame.height - top) * scale)
                drawCircle(Palette.Ember, radius = 10.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
            }
        }
        drawRect(Palette.Line, size = Size(size.width, 1.dp.toPx()))
    }
}

/** "Watch or take over · <app>": opens the viewer, where the first touch stops the agent. */
@Composable
fun WatchRow(onOpen: () -> Unit) {
    val app by AgentHub.workApp.collectAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).hairlineTop().clickable(onClick = onOpen).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Screen, null, tint = Palette.Text2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Watch or take over" + (app?.let { " · $it" } ?: ""), style = Type.Step.copy(color = Palette.Text),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Opens an effect's evidence frame full size; provided by the chat screen (null: thumbnails don't open). */
val LocalEvidenceViewer = staticCompositionLocalOf<((Effect) -> Unit)?> { null }

/** Evidence frames decoded once, off the main thread (240 px wide RGB565, ~250 KB each); a dozen kept. */
private object EvidenceFrames {
    private val cache = LruCache<String, ImageBitmap>(12)

    fun cached(path: String): ImageBitmap? = cache.get(path)

    suspend fun load(path: String): ImageBitmap? = cache.get(path) ?: withContext(Dispatchers.IO) {
        if (!File(path).isFile) return@withContext null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()?.asImageBitmap()
    }?.also { cache.put(path, it) }
}

/** The frame at [path], or null while it decodes and when the file is gone (pruned). */
@Composable
private fun rememberEvidenceFrame(path: String?): ImageBitmap? =
    produceState(path?.let { EvidenceFrames.cached(it) }, path) { value = path?.let { EvidenceFrames.load(it) } }.value

/** A receipt row's 40×28 dp thumbnail of its evidence frame, in a 48 dp target; nothing when the frame is gone. */
@Composable
fun EvidenceThumb(effect: Effect) {
    val frame = rememberEvidenceFrame(effect.evidence) ?: return
    val open = LocalEvidenceViewer.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(48.dp).then(
            if (open != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "View the screen before this") { open(effect) }
            else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            frame, contentDescription = "Screen before: ${effect.label}", contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 40.dp, height = 28.dp).clip(shape).border(1.dp, Palette.Line, shape),
        )
    }
}

private val frameClock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/**
 * An evidence frame full size, over everything on ink0: the frame fit to the width (or the height, if taller), and
 * the effect's label and time under it. A tap anywhere or Back closes it.
 */
@Composable
fun EvidenceViewer(effect: Effect, onClose: () -> Unit) {
    val frame = rememberEvidenceFrame(effect.evidence)
    val time = remember(effect.time) { frameClock.format(Instant.ofEpochMilli(effect.time).atZone(ZoneId.systemDefault())) }
    Box(
        Modifier.fillMaxSize().background(Palette.Ink0)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onClose)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            if (frame != null) {
                Image(
                    frame, contentDescription = "The screen before: ${effect.label}", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).border(1.dp, Palette.Line),
                )
            } else {
                Text("The frame is gone.", style = Type.Step, modifier = Modifier.padding(horizontal = 20.dp))
            }
            Text(
                "${effect.label} · $time", style = Type.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}
