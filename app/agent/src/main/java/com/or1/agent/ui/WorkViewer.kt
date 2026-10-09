package com.or1.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.or1.agent.AgentHub
import com.or1.agent.tools.HiddenDisplay
import kotlin.concurrent.thread

/**
 * Full-screen view of the agent's hidden work screen, rendered straight from the display. Touches are forwarded to
 * it (the first one stops the agent: the user is taking over); "Bring to front" moves the app to the real screen.
 */
@Composable
fun WorkViewer(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
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
                Text("Work screen", style = Type.Label)
                Text("Touch to take over from or1", style = Type.Caption)
            }
        }
        Spacer(Modifier.size(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { WorkSurface(it) },
                modifier = Modifier
                    .aspectRatio(720f / 1600f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Palette.Border, RoundedCornerShape(18.dp)),
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ViewerButton(Icons.Back, "Back") {
                thread {
                    HiddenDisplay.focus()
                    HiddenDisplay.id?.let { HiddenDisplay.injectKey(android.view.KeyEvent.KEYCODE_BACK, it) }
                }
            }
            ViewerButton(Icons.OpenInFront, "Bring to front") {
                AgentHub.takeOver()
                onClose()
                thread { HiddenDisplay.bringToFront() }
            }
        }
    }
}

@Composable
private fun ViewerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.Label)
    }
}

/** Shows the hidden display and forwards touches to it, scaled to its 720x1600 coordinates. */
@SuppressLint("ViewConstructor")
private class WorkSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var tookOver = false

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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !tookOver && AgentHub.busy.value) {
            tookOver = true
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
