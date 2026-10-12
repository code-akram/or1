package com.or1.agent.tools

import android.content.Context
import com.or1.agent.MediaNow
import com.or1.agent.NowState
import com.or1.agent.Stage
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The `media` tool: what's playing and play/pause/next/previous through the apps' media sessions, without their
 * screens. Media the agent started plays on the stage ([Stage]) after its turn, so this is how it's controlled.
 */
class MediaTool(private val context: Context) {
    suspend fun run(args: JSONObject): ToolOutput {
        val action = args.getString("action")
        val sessions = NowState.media.value
        if (action == "status") return ToolOutput(MediaStatus.describe(sessions, Stage.kept.value, ::label))
        val pkg = args.optString("package").ifBlank { null } ?: MediaStatus.target(sessions, Stage.kept.value)
            ?: return ToolOutput("Nothing is playing: no app has a media session.")
        require(action in setOf("play", "pause", "next", "previous")) { "Unknown action '$action'." }
        if (!NowState.transport(pkg, action)) return ToolOutput("${label(pkg)} has no media session; open it to use its controls.")
        delay(1200)
        return ToolOutput(MediaStatus.describe(NowState.media.value, Stage.kept.value, ::label))
    }

    private fun label(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** Pure parts of the media tool (JVM-tested). */
object MediaStatus {
    /** The session a control goes to without a `package`: the kept one, else the one playing, else the newest. */
    fun target(sessions: List<MediaNow>, kept: String?): String? =
        kept?.takeIf { k -> sessions.any { it.pkg == k } } ?: sessions.firstOrNull { it.playing }?.pkg ?: sessions.firstOrNull()?.pkg

    /** One line per media session: app (package), state, title, and whether it plays on its own screen. */
    fun describe(sessions: List<MediaNow>, kept: String?, label: (String) -> String): String {
        if (sessions.isEmpty()) return "Nothing is playing: no app has a media session."
        return sessions.joinToString("\n") { m ->
            val state = when {
                m.playing -> "playing"
                m.stopped -> "stopped"
                m.state == android.media.session.PlaybackState.STATE_PAUSED -> "paused"
                else -> "idle"
            }
            val what = listOfNotNull(m.title?.let { "“$it”" }, m.artist?.let { "by $it" }).joinToString(" ")
            val where = if (m.pkg == kept) " (on its own screen; keeps playing while you work)" else ""
            "${label(m.pkg)} (${m.pkg}): $state${if (what.isNotEmpty()) " $what" else ""}$where"
        }
    }
}
