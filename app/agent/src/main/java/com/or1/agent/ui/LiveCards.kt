package com.or1.agent.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.CalendarContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.or1.agent.AgentHub
import com.or1.agent.NowState
import com.or1.agent.tools.CalendarEvent
import com.or1.agent.tools.CalendarTool
import com.or1.agent.tools.HiddenDisplay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The M6 live result cards (docs/ui-ledger.md §5): media, calendar day, email. Their pure side is LiveCardLogic.kt.

// --- Media ------------------------------------------------------------------------------------------------------------

/** The last frame and title each media card showed, so it can freeze on them after it leaves the live set. */
private object MediaFrames {
    private val frames = object : LinkedHashMap<String, Pair<ImageBitmap, String?>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ImageBitmap, String?>>?) = size > 6
    }

    fun get(key: String): Pair<ImageBitmap, String?>? = synchronized(frames) { frames[key] }
    fun put(key: String, frame: ImageBitmap, title: String?) = synchronized(frames) { frames[key] = frame to title }
}

/**
 * A 16:9 crop of the work display while the turn's media app is in front there: plain image swaps of the preview
 * (500 ms, 400 ms while this card follows it), no SurfaceView (the viewer keeps the only one). Paused: a play badge;
 * playing: steady `live` blocks on an ink0 60% mat (so they show on bright frames). Frozen (an older card, another app
 * in front, or the media stopped): the last frame under an ink0 40% veil. The whole card opens the viewer, which is the
 * player.
 */
@Composable
internal fun MediaCard(card: ResultCard.Media, live: Boolean) {
    val context = LocalContext.current
    val open = LocalCardActions.current.openViewer
    val app = remember(card.pkg) { appLabel(context, card.pkg) }
    val front by HiddenDisplay.foregroundPackage.collectAsState()
    val sessions by NowState.media.collectAsState()
    val session = sessions.firstOrNull { it.pkg == card.pkg }
    val following = live && front == card.pkg && session?.stopped != true
    val playing = following && session?.playing == true
    var frame by remember(card.key) { mutableStateOf(MediaFrames.get(card.key)) }
    var onScreen by remember { mutableStateOf(false) }
    if (following && onScreen) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(card.key, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                HiddenDisplay.fastPreview(true)
                try {
                    HiddenDisplay.preview.filterNotNull().collectLatest { bitmap ->
                        val cropped = withContext(Dispatchers.Default) { cropFrame(bitmap, card.pkg) }
                        val title = NowState.media.value.firstOrNull { it.pkg == card.pkg }?.title ?: frame?.second
                        MediaFrames.put(card.key, cropped, title)
                        frame = cropped to title
                    }
                } finally {
                    HiddenDisplay.fastPreview(false)
                }
            }
        }
    }
    val title = (if (live) session?.title else null) ?: frame?.second
    val state = when {
        !following -> "stopped"
        playing -> "playing"
        else -> "paused"
    }
    CardFrame(
        Modifier.onGloballyPositioned { onScreen = it.boundsInWindow().height > 0f }
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the agent screen", onClick = open) else Modifier),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(MediaCards.ASPECT).background(Palette.Ink1)
                .clearAndSetSemantics { contentDescription = "$app, $state" },
        ) {
            val image = frame?.first
            if (image != null) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Screen, null, tint = Palette.Text3, modifier = Modifier.size(24.dp).align(Alignment.Center))
            }
            if (!following && image != null) Box(Modifier.fillMaxSize().background(Palette.Ink0.copy(alpha = 0.4f)))
            if (playing) {
                Steady(
                    Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(Palette.Ink0.copy(alpha = 0.6f)).padding(4.dp),
                )
            } else {
                Box(
                    Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Palette.Ink0.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PlayIcon, null, tint = Palette.Text, modifier = Modifier.size(24.dp))
                }
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
            Text(app, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            title?.let { Text(it, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun cropFrame(src: Bitmap, pkg: String): ImageBitmap {
    val (left, top, w, h) = MediaCards.crop(src.width, src.height, pkg)
    return Bitmap.createBitmap(src, left, top, w.coerceAtLeast(1), h.coerceAtLeast(1)).asImageBitmap()
}

private fun appLabel(context: Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

private val PlayIcon: ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(PathParser().parsePathString("M8,5v14l11,-7z").toNodes(), fill = SolidColor(Color.Black))
        .build()

// --- Calendar day -----------------------------------------------------------------------------------------------------

private const val MINUTE = 60_000L

/**
 * The days a `calendar list` asked for, read from the provider (never from the reply): 48 dp rows, the time in
 * NumeralS in a 64 dp column, all-day events first, past rows in text3, a 1 dp `live` now-line between rows moved on the
 * minute (above the first row it ends in a "now" caption). Live: re-read on every provider change. At most [CalendarDays.MAX_ROWS] rows, then "N more" (opens the
 * calendar in the viewer). Nothing found: no card.
 */
@Composable
internal fun CalendarDayCard(card: ResultCard.CalendarDay, live: Boolean) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val (from, days) = remember(card.key) { CalendarTool.listRange(card.date, card.days, LocalDate.now(zone)) } ?: return
    val changes = if (live) NowState.calendarChanges.collectAsState().value else 0
    val events by produceState<List<CalendarEvent>?>(null, from, days, changes) {
        value = withContext(Dispatchers.IO) {
            runCatching { CalendarTool.instances(context.contentResolver, from, days, zone) }.getOrNull()
        } ?: value
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (live) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(MINUTE - System.currentTimeMillis() % MINUTE + 50)
                now = System.currentTimeMillis()
            }
        }
    }
    val list = events ?: return
    val view = remember(list, now) { CalendarDays.view(list, from, days, now, zone) }
    if (view.rows.isEmpty()) return
    val today = LocalDate.now(zone)
    val actions = LocalCardActions.current
    val scope = rememberCoroutineScope()
    val count = view.rows.size + view.more
    CardFrame {
        CardHeader(Icons.Event, CalendarDays.title(from, days, today)) {
            Text(
                if (count == 1) "1 event" else "$count events", style = Type.Caption.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        val line = Palette.Live
        // Above the first row the line would read as a divider: it says "now" at its right end.
        val measurer = rememberTextMeasurer()
        val nowLabel = remember(measurer) { measurer.measure("now", Type.Caption.copy(color = Palette.Live)) }
        view.rows.forEachIndexed { i, row ->
            if (view.multiDay && (i == 0 || view.rows[i - 1].day != row.day)) {
                Text(
                    CalendarDays.dayName(row.day, today), style = Type.Caption,
                    modifier = Modifier.padding(start = 14.dp, top = if (i == 0) 0.dp else 8.dp, bottom = 2.dp),
                )
            }
            val above = view.nowLine == i
            val below = view.nowLine == view.rows.size && i == view.rows.lastIndex
            DayRowView(
                row,
                Modifier.drawBehind {
                    if (above || below) {
                        val inset = 14.dp.toPx()
                        val h = 1.dp.toPx()
                        val y = if (above) 0f else size.height - h
                        val labelled = above && i == 0
                        val labelX = size.width - inset - nowLabel.size.width
                        val end = if (labelled) labelX - 6.dp.toPx() else size.width - inset
                        drawRect(line, topLeft = Offset(inset, y), size = Size(end - inset, h))
                        if (labelled) drawText(nowLabel, topLeft = Offset(labelX, y + h / 2 - nowLabel.size.height / 2f))
                    }
                },
            )
        }
        if (view.more > 0) {
            val open = actions.openViewer
            Row(
                Modifier.hairlineTop().fillMaxWidth().heightIn(min = 48.dp)
                    .then(
                        if (open != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the calendar") {
                            if (AgentHub.busy.value || AgentHub.routineBusy.value) open()
                            else scope.launch { openCalendar(context, from, zone); open() }
                        } else Modifier,
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${view.more} more", style = Type.Step.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f))
                if (open != null) Text("Calendar", style = Type.Caption)
            }
        }
    }
}

@Composable
private fun DayRowView(row: DayRow, modifier: Modifier) {
    val past = row.past
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(64.dp)) {
            if (row.time != null) Text(row.time, style = Type.NumeralS.copy(fontSize = 18.sp, lineHeight = 24.sp, color = if (past) Palette.Text3 else Palette.Text), maxLines = 1)
            else Text("all day", style = Type.Caption.copy(color = if (past) Palette.Text3 else Palette.Text2), maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                row.event.title.ifBlank { "(No title)" }, style = Type.Label.copy(color = if (past) Palette.Text3 else Palette.Text),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            row.event.location?.let { Text(it, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Opens the calendar app at [day] on the work display (waiting for its window, so the viewer shows it). */
private suspend fun openCalendar(context: Context, day: LocalDate, zone: ZoneId) {
    val at = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val intent = Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(at.toString()).build())
    withContext(Dispatchers.IO) {
        val pkg = intent.resolveActivity(context.packageManager)?.packageName ?: return@withContext
        runCatching { HiddenDisplay.launch(intent, pkg) }
    }
}

// --- Email ------------------------------------------------------------------------------------------------------------

/**
 * An email the turn read in Gmail: from (Label), subject (Body, text2), up to three lines of it in the reply serif.
 * Open shows Gmail in the viewer; Archive asks or1 to archive it (a new act in the chat, with its own receipt).
 */
@Composable
internal fun EmailCard(card: ResultCard.Email) {
    val email = card.email
    val open = LocalCardActions.current.openViewer
    var archiving by rememberSaveable(card.key) { mutableStateOf(false) }
    CardFrame {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
            Text(email.from, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(email.subject, style = Type.Body.copy(color = Palette.Text2), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (email.quote.isNotBlank()) {
                Text(
                    email.quote, style = Type.Reply.copy(color = Palette.Text2), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).drawBehind {
                        drawRect(Palette.Line, size = Size(2.dp.toPx(), size.height))
                    }.padding(start = 12.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open != null) TextTarget("Open", onClick = open)
            if (archiving) {
                Text("Asked or1 to archive it", style = Type.Caption, modifier = Modifier.padding(horizontal = 10.dp))
            } else {
                TextTarget("Archive") {
                    archiving = true
                    AgentHub.submit("Archive this email: ${email.subject} from ${email.from}")
                }
            }
        }
    }
}

/** A plain-text action with a 48 dp target. */
@Composable
private fun TextTarget(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Step.copy(color = Palette.Text), maxLines = 1)
    }
}
