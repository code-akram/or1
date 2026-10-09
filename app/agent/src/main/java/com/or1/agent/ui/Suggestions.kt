package com.or1.agent.ui

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.BatteryManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.or1.agent.NotifListener

/** A just-in-time suggestion above the composer: an app icon or glyph, a short line, and the prompt it sends. */
data class Suggestion(val text: String, val prompt: String, val appIcon: ImageBitmap? = null, val glyph: ImageVector? = null)

/** Builds suggestions locally from notifications and device state; no API call. */
object Suggestions {
    private const val MAX = 3

    fun build(context: Context): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val notifications = NotifListener.instance?.activeNotifications.orEmpty()
            .filter { it.packageName != context.packageName && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
        for (sbn in notifications.take(2)) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val line = when {
                title.isEmpty() -> body
                body.isEmpty() -> title
                title.last() in ".!?:" -> "$title $body"
                else -> "$title: $body"
            }
            if (line.isEmpty()) continue
            val app = appLabel(context, sbn.packageName)
            out += Suggestion(
                text = line.take(60),
                prompt = "Help me with this notification from $app: \"$line\"",
                appIcon = appIcon(context, sbn.packageName),
            )
        }
        if (notifications.size > 2) {
            out += Suggestion("Summarise my ${notifications.size} notifications", "Summarise my notifications.", glyph = Icons.Bell)
        }
        battery(context)?.let { (level, charging) ->
            if (level in 0..19 && !charging) {
                out += Suggestion("Battery at $level%. Save power?", "My battery is at $level%. Help me save power.", glyph = Icons.Battery)
            }
        }
        val defaults = listOf(
            Suggestion("Set a 10-minute timer", "Set a 10-minute timer.", glyph = Icons.Timer),
            Suggestion("Turn on Do Not Disturb", "Turn on Do Not Disturb.", glyph = Icons.Moon),
            Suggestion("What's new in my notifications?", "What's new in my notifications?", glyph = Icons.Bell),
        )
        for (d in defaults) if (out.size < MAX && out.none { it.glyph == d.glyph }) out += d
        return out.take(MAX)
    }

    private fun battery(context: Context): Pair<Int, Boolean>? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        return level to (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun appIcon(context: Context, pkg: String): ImageBitmap? = try {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val size = (28 * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
