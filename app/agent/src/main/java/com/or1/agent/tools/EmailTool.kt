package com.or1.agent.tools

import android.view.Display
import com.or1.agent.ui.EmailReads
import com.or1.agent.ui.InboxReads
import com.or1.agent.ui.InboxRow
import com.or1.agent.ui.ScreenDump
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Gmail's newest emails in one call, instead of open, read, tap and read again: `latest` lists the inbox, `open` opens
 * one of them (`index`, 1 = the newest) and returns its screen, which the chat shows as an email card; `show` shows it
 * to the user full screen when the turn ends. Works on the screen like the model would, through [ScreenTools].
 */
class EmailTool(private val system: SystemTools) {
    /** Where the time goes (logcat tag or1-email): each step's ms. */
    private val steps = mutableListOf<String>()
    private var last = 0L

    private fun step(name: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        steps += "$name ${now - last}"
        last = now
    }

    suspend fun run(args: JSONObject, screen: ScreenTools): ToolOutput {
        steps.clear()
        last = android.os.SystemClock.elapsedRealtime()
        try {
            return go(args, screen)
        } finally {
            android.util.Log.i("or1-email", "${args.optString("action")}: ${steps.joinToString(", ")} ms")
        }
    }

    private suspend fun go(args: JSONObject, screen: ScreenTools): ToolOutput {
        val (rows, shown) = inbox(screen)
        if (rows.isEmpty()) throw IllegalStateException("Gmail's inbox is empty, or its list couldn't be read: use open and screen.")
        return when (args.optString("action", "latest")) {
            "latest" -> {
                val count = args.optInt("count", 5).coerceIn(1, rows.size)
                ToolOutput(
                    "Gmail inbox, newest first (open one with email open and its number):\n" +
                        rows.take(count).withIndex().joinToString("\n") { (i, r) -> "${i + 1}. ${r.line()}" } +
                        "\n\nThe screen:\n$shown",
                )
            }
            "open" -> open(rows, args.optInt("index", 1), args.optBoolean("show"), screen)
            else -> throw IllegalArgumentException("email takes action latest or open.")
        }
    }

    private suspend fun open(rows: List<InboxRow>, index: Int, show: Boolean, screen: ScreenTools): ToolOutput {
        val row = rows.getOrNull(index - 1)
            ?: throw IllegalArgumentException("The inbox shows ${rows.size} emails; index $index isn't one of them.")
        var result = screen.act(JSONObject().put("action", "tap").put("element", row.id)).text
        step("tap")
        // A long thread takes a moment to lay out.
        repeat(OPEN_READS) {
            if (isMessage(result)) return@repeat
            delay(OPEN_WAIT_MS)
            result = screen.screen(false).text
            step("wait")
        }
        if (show && HiddenDisplay.targetDisplay() != Display.DEFAULT_DISPLAY) HiddenDisplay.showAfterTurn = EmailReads.GMAIL
        val opened = if (isMessage(result)) "Opened email $index: ${row.line()}" else "Tapped email $index (${row.line()}), but the screen doesn't show the message yet."
        return ToolOutput("$result\n($opened)")
    }

    private fun isMessage(result: String) =
        runCatching { EmailReads.parse(result) }.getOrNull() != null || ScreenDump.parse(result)?.elements?.any { it.label == "Archive" } == true

    /** Gmail open on its inbox list: launched, then back out of a message or search it was left on, then to the top. */
    private suspend fun inbox(screen: ScreenTools): Pair<List<InboxRow>, String> {
        var shown = system.open(JSONObject().put("package", EmailReads.GMAIL), screen).text
        step("open")
        repeat(INBOX_TRIES) {
            val rows = InboxReads.parse(shown)
            if (rows.isNotEmpty()) {
                // Scrolled down earlier: the newest are above. The tab's name sits over the first row.
                val dump = ScreenDump.parse(shown)
                val top = dump?.elements?.any { it.cls == "TextView" && it.label in TABS } == true
                if (top || it == INBOX_TRIES - 1) return rows to shown
                shown = screen.act(JSONObject().put("action", "scroll").put("direction", "up")).text
                step("scroll")
                return@repeat
            }
            shown = when {
                ScreenDump.foreground(shown) != EmailReads.GMAIL -> {
                    delay(OPEN_WAIT_MS)
                    screen.screen(false).text
                }
                else -> screen.act(JSONObject().put("action", "key").put("key", "back")).text
            }
            step("back")
        }
        return InboxReads.parse(shown) to shown
    }

    private companion object {
        const val INBOX_TRIES = 4
        const val OPEN_READS = 3
        const val OPEN_WAIT_MS = 700L
        val TABS = setOf("Primary", "Inbox", "All inboxes")
    }
}
