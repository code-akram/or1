package com.or1.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.or1.agent.AgentHub
import com.or1.agent.tools.HiddenDisplay
import com.or1.agent.tools.Input
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Full-screen view of the agent's work screen, rendered straight from the hidden display, and usable: touches are
 * forwarded to it (while or1 works, the first touch stops it and the user takes over), its keyboard shows inside it,
 * and the bar underneath has Back, the work screen's app grid, and a way back to the chat to ask or1 about it.
 * With [stage] it shows the stage instead (opened from the media card or chip): the player, where the media lives;
 * nothing moves between the screens for it, and there's no app grid there.
 */
@Composable
fun WorkViewer(onClose: () -> Unit, onAsk: () -> Unit, stage: Boolean = false) {
    val busy by AgentHub.busy.collectAsState()
    val routineBusy by AgentHub.routineBusy.collectAsState()
    val working = busy || routineBusy
    val kept by com.or1.agent.Stage.kept.collectAsState()
    // The stage went away (paused too long, or the app closed) while it was open: nothing left to show.
    LaunchedEffect(stage, kept) { if (stage && kept == null) onClose() }
    val app = rememberWorkScreenApp(stage)
    // Keep input focus on the work screen while the user drives it. A tap on the chat's window would otherwise pull
    // focus (and the display) back to the real screen, the work screen's keyboard would detach, and its next key
    // press would be dropped. Not focusable, the chat window still gets touches but never takes focus.
    val window = (LocalContext.current as? android.app.Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink1)
            .clickable(enabled = false) {}
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Close, "Close", onClose, tint = Palette.Text, background = Palette.Ink3)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app ?: if (stage) "Now playing" else "Agent screen", style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (working) "or1 is working · touch to take over" else "Your turn · tap and type as usual", style = Type.Caption)
            }
            if (working) March(Modifier.padding(end = 8.dp)) else Steady(Modifier.padding(end = 8.dp))
        }
        Spacer(Modifier.size(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { WorkSurface(it, stage) },
                modifier = Modifier
                    .aspectRatio(720f / 1600f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, if (working) Palette.Ember else Palette.Line, RoundedCornerShape(18.dp)),
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ViewerButton(Icons.Back, "Back") {
                takeOverIfWorking()
                thread { shownDisplay(stage)?.let { HiddenDisplay.injectKey(KeyEvent.KEYCODE_BACK, it) } }
            }
            if (!stage) {
                ViewerButton(Icons.Apps, "Apps") {
                    takeOverIfWorking()
                    thread { HiddenDisplay.goHome() }
                }
            }
            ViewerButton(Icons.Spark, "Ask or1", primary = true, onClick = onAsk)
        }
        Spacer(Modifier.size(4.dp))
    }
}

/** Stops a request in progress when the user starts using the work screen themselves. */
private fun takeOverIfWorking() {
    if (AgentHub.busy.value) AgentHub.takeOver()
}

/** The display the viewer shows: the stage, or the work display. */
private fun shownDisplay(stage: Boolean): Int? = if (stage) com.or1.agent.Stage.id else HiddenDisplay.id

/** The label of the app in front on the screen shown, refreshed while the viewer is open. */
@Composable
private fun rememberWorkScreenApp(stage: Boolean): String? {
    val context = LocalContext.current
    val app by produceState<String?>(null) {
        while (true) {
            value = withContext(Dispatchers.Default) {
                val pkg = shownDisplay(stage)?.let { HiddenDisplay.topAppPackage(it) }
                pkg?.takeIf { it != context.packageName }?.let { p ->
                    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull()
                }
            }
            delay(800)
        }
    }
    return app
}

@Composable
private fun RowScope.ViewerButton(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .height(48.dp)
            .clip(CircleShape)
            .background(if (primary) Palette.Primary else Palette.Ink3)
            .border(1.dp, if (primary) Palette.Primary else Palette.Line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (primary) Palette.OnPrimary else Palette.Text
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.Label.copy(color = tint), maxLines = 1)
    }
}

/** Shows the hidden display (or the stage) and forwards touches to it, scaled to its 720x1600 coordinates. */
@SuppressLint("ViewConstructor")
private class WorkSurface(context: Context, private val stage: Boolean) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        // Buffers at the display's own size; the compositor scales them to the view.
        holder.setFixedSize(720, 1600)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (stage) {
            com.or1.agent.Stage.attachViewer(holder.surface)
            return
        }
        thread {
            HiddenDisplay.ensure() ?: return@thread
            post { if (holder.surface.isValid) HiddenDisplay.attachViewer(holder.surface) }
            HiddenDisplay.showHomeIfEmpty()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (stage) {
            com.or1.agent.Stage.detachViewer()
            return
        }
        HiddenDisplay.detachViewer()
        com.or1.agent.Stage.park()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val displayId = shownDisplay(stage) ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && AgentHub.busy.value) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            AgentHub.takeOver()
        }
        val copy = MotionEvent.obtain(event)
        copy.transform(Matrix().apply { setScale(720f / width, 1600f / height) })
        copy.source = InputDevice.SOURCE_TOUCHSCREEN
        Input.inject(copy, displayId)
        copy.recycle()
        return true
    }
}
