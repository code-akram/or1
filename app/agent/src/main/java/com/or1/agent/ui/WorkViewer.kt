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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.or1.agent.AgentHub
import com.or1.agent.tools.HiddenDisplay
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Full-screen view of the agent's work screen, rendered straight from the hidden display, and usable: touches are
 * forwarded to it (while or1 works, the first touch stops it and the user takes over), its keyboard shows inside it,
 * and the bar underneath has Back, the work screen's app grid, and a way back to the chat to ask or1 about it.
 */
@Composable
fun WorkViewer(onClose: () -> Unit, onAsk: () -> Unit) {
    BackHandler(onBack = onClose)
    val busy by AgentHub.busy.collectAsState()
    val routineBusy by AgentHub.routineBusy.collectAsState()
    val working = busy || routineBusy
    val app = rememberWorkScreenApp()
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
            .background(Palette.SurfaceSunken)
            .clickable(enabled = false) {}
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Close, "Close", onClose, tint = Palette.Text, background = Palette.Surface)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app ?: "Work screen", style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (working) "or1 is working · touch to take over" else "Your turn · tap and type as usual", style = Type.Caption)
            }
            StatusDot(working)
        }
        Spacer(Modifier.size(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { WorkSurface(it) },
                modifier = Modifier
                    .aspectRatio(720f / 1600f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, if (working) Palette.Accent.copy(alpha = 0.6f) else Palette.Border, RoundedCornerShape(18.dp)),
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ViewerButton(Icons.Back, "Back") {
                takeOverIfWorking()
                thread { HiddenDisplay.id?.let { HiddenDisplay.injectKey(KeyEvent.KEYCODE_BACK, it) } }
            }
            ViewerButton(Icons.Apps, "Apps") {
                takeOverIfWorking()
                thread { HiddenDisplay.goHome() }
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

/** The label of the app in front on the work screen, refreshed while the viewer is open. */
@Composable
private fun rememberWorkScreenApp(): String? {
    val context = LocalContext.current
    val app by produceState<String?>(null) {
        while (true) {
            value = withContext(Dispatchers.Default) {
                val pkg = HiddenDisplay.id?.let { HiddenDisplay.topAppPackage(it) }
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
private fun StatusDot(working: Boolean) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Box(
        Modifier.padding(end = 8.dp).size(10.dp).alpha(if (working) pulse else 1f).clip(CircleShape)
            .background(if (working) Palette.Accent else Palette.Success),
    )
}

@Composable
private fun RowScope.ViewerButton(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .height(48.dp)
            .clip(CircleShape)
            .background(if (primary) Palette.Primary else Palette.Surface)
            .border(1.dp, if (primary) Palette.Primary else Palette.Border, CircleShape)
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

/** Shows the hidden display and forwards touches to it, scaled to its 720x1600 coordinates. */
@SuppressLint("ViewConstructor")
private class WorkSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        // Buffers at the display's own size; the compositor scales them to the view.
        holder.setFixedSize(720, 1600)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread {
            HiddenDisplay.ensure() ?: return@thread
            post { if (holder.surface.isValid) HiddenDisplay.attachViewer(holder.surface) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        HiddenDisplay.detachViewer()
        if (!AgentHub.busy.value) HiddenDisplay.park()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val displayId = HiddenDisplay.id ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && AgentHub.busy.value) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            AgentHub.takeOver()
        }
        val copy = MotionEvent.obtain(event)
        copy.transform(Matrix().apply { setScale(720f / width, 1600f / height) })
        copy.source = InputDevice.SOURCE_TOUCHSCREEN
        HiddenDisplay.inject(copy, displayId)
        copy.recycle()
        return true
    }
}
