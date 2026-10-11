package com.or1.agent.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * The status bar, redrawn: SystemUI draws nothing there (clock and icons are off, see Lockdown), and this bar sits just
 * below it, clear of the camera notch, taking [RAIL_HEIGHT] from the screens under it. Pixel blocks, in the boot
 * wordmark's style (brand/make_boot.py has the same glyphs): or1 on the left (blocks march while the agent works);
 * time, battery level and Wi-Fi bars on the right. or1 is the home button ([onHome]); the rail is as tall as its
 * 48 dp target.
 */
@Composable
fun StatusRail(busy: Boolean, onHome: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState(read(context)) {
        while (true) {
            // Cheap reads; skipped while the screen is off.
            if (context.getSystemService(PowerManager::class.java).isInteractive) value = read(context)
            delay(5_000)
        }
    }
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(RAIL_HEIGHT).padding(start = 4.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Home", onClick = onHome)
                .semantics { contentDescription = "or1" }
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(BLOCK * 3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark()
            // Bottom-aligned with the wordmark, in a slot that's there whether or not it marches.
            Box(Modifier.size(BLOCK * 5, BLOCK * PixelFont.HEIGHT), contentAlignment = Alignment.BottomStart) {
                if (busy) March(block = BLOCK)
            }
        }
        Spacer(Modifier.weight(1f))
        Module { Glyphs(state.time, Palette.Text) }
        // The battery is just its number: bright while charging, grey otherwise.
        Module { Glyphs(if (state.battery >= 0) "${state.battery}" else "--", if (state.charging) Palette.Text else Palette.Text2) }
        Module { WifiBars(state.wifiLevel) }
    }
}

private data class RailState(val time: String, val wifiLevel: Int, val battery: Int, val charging: Boolean)

/** wifiLevel: -1 not on Wi-Fi, else 0..4. battery: -1 unknown, else 0..100. */
private fun read(context: Context): RailState {
    // 24-hour, always: unambiguous in five blocks.
    val now = Calendar.getInstance()
    val time = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    val wifiLevel = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
        val wifi = context.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val rssi = wifi.connectionInfo.rssi
        (wifi.calculateSignalLevel(rssi) * 4 / wifi.maxSignalLevel.coerceAtLeast(1)).coerceIn(0, 4)
    } else -1

    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let {
        val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l >= 0 && s > 0) l * 100 / s else -1
    } ?: -1
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return RailState(time, wifiLevel, level, charging)
}

private val RAIL_HEIGHT: Dp = 48.dp

private val BLOCK: Dp = 2.dp
private val GAP: Dp = 6.dp

@Composable
private fun Module(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.padding(horizontal = GAP),
        horizontalArrangement = Arrangement.spacedBy(BLOCK * 3),
        verticalAlignment = Alignment.Bottom,
        content = content,
    )
}

/** Text in the pixel font, bottoms aligned (the x glyph is 3 rows, the rest 5). */
@Composable
private fun Glyphs(text: String, color: Color) {
    val glyphs = text.map { PixelFont.glyph(it) }
    val cols = glyphs.sumOf { it[0].length } + (glyphs.size - 1).coerceAtLeast(0)
    Canvas(Modifier.size(BLOCK * cols, BLOCK * PixelFont.HEIGHT)) {
        val b = BLOCK.toPx()
        var x = 0
        for (g in glyphs) {
            val top = PixelFont.HEIGHT - g.size
            g.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch -> if (ch == '#') block(x + c, top + r, b, color) }
            }
            x += g[0].length + 1
        }
    }
}

/** The boot wordmark: one square per pixel, in the status bar or on its own (the drawer's). */
@Composable
fun Wordmark(block: Dp = BLOCK, color: Color = Palette.Text, modifier: Modifier = Modifier) {
    val rows = PixelFont.WORDMARK
    Canvas(modifier.size(block * rows[0].length, block * rows.size)) {
        val b = block.toPx()
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> if (ch == '#') block(c, r, b, color) } }
    }
}

/** Four bars, 2 to 5 blocks tall; unlit ones in the hairline colour, a cross when not on Wi-Fi. */
@Composable
private fun WifiBars(level: Int) {
    if (level < 0) {
        Glyphs("x", Palette.Text3)
        return
    }
    Canvas(Modifier.size(BLOCK * 7, BLOCK * PixelFont.HEIGHT)) {
        val b = BLOCK.toPx()
        for (i in 0..3) {
            val color = if (i < level) Palette.Text else Palette.Line
            for (r in 0..i + 1) block(i * 2, PixelFont.HEIGHT - 1 - r, b, color)
        }
    }
}

private fun DrawScope.block(col: Int, row: Int, b: Float, color: Color) =
    drawRect(color, topLeft = Offset(col * b, row * b), size = Size(b, b))

/** The pixel font: square blocks, no diagonals. Keep in step with brand/make_boot.py. */
object PixelFont {
    const val HEIGHT = 5

    /** The wordmark, as on the boot screens: o, r (with a drop at its end) and 1, as tall as the digits (5 rows). */
    val WORDMARK = listOf(
        "### ###  #",
        "# # # # ##",
        "# # #    #",
        "# # #    #",
        "### #    #",
    )

    private val GLYPHS = mapOf(
        // Digits and signs, 5 rows; the 1 is the wordmark's, a row taller.
        '0' to listOf("###", "#.#", "#.#", "#.#", "###"),
        '1' to listOf(".#", "##", ".#", ".#", ".#"),
        '2' to listOf("###", "..#", "###", "#..", "###"),
        '3' to listOf("###", "..#", "###", "..#", "###"),
        '4' to listOf("#.#", "#.#", "###", "..#", "..#"),
        '5' to listOf("###", "#..", "###", "..#", "###"),
        '6' to listOf("###", "#..", "###", "#.#", "###"),
        '7' to listOf("###", "..#", "..#", "..#", "..#"),
        '8' to listOf("###", "#.#", "###", "#.#", "###"),
        '9' to listOf("###", "#.#", "###", "..#", "###"),
        ':' to listOf(".", "#", ".", "#", "."),
        '%' to listOf("#.#", "..#", ".#.", "#..", "#.#"),
        '+' to listOf("...", ".#.", "###", ".#.", "..."),
        '-' to listOf("...", "...", "###", "...", "..."),
        'x' to listOf("#.#", ".#.", "#.#"),
    )

    fun glyph(c: Char): List<String> = GLYPHS[c] ?: listOf("...", "...", "...", "...", "...")
}
