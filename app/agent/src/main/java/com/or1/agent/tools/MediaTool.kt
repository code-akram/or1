package com.or1.agent.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import com.or1.agent.A11yService
import com.or1.agent.MediaNow
import com.or1.agent.NowState
import com.or1.agent.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The `media` tool: what's playing and play/pause/next/previous through the apps' media sessions, without their
 * screens. Media the agent started plays on the stage ([Stage]) after its turn, so this is how it's controlled.
 * `play` with a `query` searches an app and starts the top result in one call ([playSearch]).
 */
class MediaTool(private val context: Context) {
    suspend fun run(args: JSONObject, screen: ScreenTools): ToolOutput {
        val action = args.getString("action")
        val sessions = NowState.media.value
        if (action == "status") return ToolOutput(MediaStatus.describe(sessions, Stage.kept.value, ::label))
        val query = args.optString("query").trim()
        if (query.isNotEmpty()) {
            require(action == "play") { "`query` goes with action play." }
            return playSearch(query, args.optString("package").ifBlank { null } ?: YOUTUBE, screen)
        }
        val pkg = args.optString("package").ifBlank { null } ?: MediaStatus.target(sessions, Stage.kept.value)
            ?: return ToolOutput("Nothing is playing: no app has a media session.")
        require(action in setOf("play", "pause", "next", "previous")) { "Unknown action '$action'." }
        if (!NowState.transport(pkg, action)) return ToolOutput("${label(pkg)} has no media session; open it to use its controls.")
        delay(1200)
        return ToolOutput(MediaStatus.describe(NowState.media.value, Stage.kept.value, ::label))
    }

    /**
     * Searches [pkg] for [query] on the work display and starts the top result, instead of open, search, read, tap
     * (four slow steps on this phone). Music apps play from the search intent directly; YouTube shows results, so the
     * first video in them (not an ad, a Short or a live stream) is clicked. Done when the app's session plays something
     * new. If nothing plays in time, the screen goes back so the agent can pick by hand.
     */
    private suspend fun playSearch(query: String, pkg: String, screen: ScreenTools): ToolOutput {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
                .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: return ToolOutput("${label(pkg)} can't search from outside: open it and search there.")
        // A fresh task: an open watch page would otherwise stay on top of the new results.
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val before = NowState.media.value.firstOrNull { it.pkg == pkg }?.title
        val displayId = HiddenDisplay.launch(intent, pkg)
        val until = SystemClock.elapsedRealtime() + SEARCH_MS
        var clicked: String? = null
        var clickedAt = 0L
        var nudged = false
        while (SystemClock.elapsedRealtime() < until) {
            val now = NowState.media.value.firstOrNull { it.pkg == pkg && it.playing }
            // A video already playing keeps playing (minimized) while the search shows: wait for a new one, or the one
            // clicked (the user asked for the same video again).
            if (now != null && MediaSearch.started(now.title, before, clicked)) {
                val what = now.title?.let { "“$it”" } ?: "the top result"
                return ToolOutput("Playing $what in ${label(pkg)} ($pkg), the top result for “$query”. It keeps playing on its own screen after your turn.")
            }
            if (clicked == null) {
                clicked = withContext(Dispatchers.IO) { clickTopResult(displayId, query) }
                clickedAt = SystemClock.elapsedRealtime()
            } else if (!nudged && SystemClock.elapsedRealtime() - clickedAt > NUDGE_MS) {
                // After a video was playing, YouTube opens the next one paused (its player was stopped with the old
                // task): play through its session, which starts what the watch page loaded.
                nudged = true
                NowState.transport(pkg, "play")
            }
            delay(POLL_MS)
        }
        val tried = if (clicked != null) "it opened “${clicked.take(80)}”, which hasn't started" else "no video was picked from the results"
        val shown = screen.screen(false)
        return shown.copy(text = "Not playing: $tried. Pick and start it from the screen below, and check it plays before saying so.\n${shown.text}")
    }

    /**
     * Clicks the first video in the search results for [query] on [displayId], returning its description, or null
     * while they aren't showing (the page before them, a watch page's related videos, can list videos too).
     */
    private fun clickTopResult(displayId: Int, query: String): String? {
        val service = A11yService.instance ?: return null
        service.clearCache()
        val root = HiddenDisplay.topAppWindow(displayId)?.root ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val texts = mutableListOf<String>()
        collect(root, nodes, texts, 0)
        if (!MediaSearch.showsQuery(texts, query)) return null
        val node = nodes.getOrNull(MediaSearch.pick(nodes.map { it.contentDescription.toString() }) ?: return null) ?: return null
        // Clicked through accessibility, not tapped: switching videos, YouTube shows the old one's player over the
        // results for a moment, and a tap would land on it.
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            HiddenDisplay.injectStroke(bounds.exactCenterX(), bounds.exactCenterY(), bounds.exactCenterX(), bounds.exactCenterY(), 50, displayId)
        }
        return node.contentDescription.toString()
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, texts: MutableList<String>, depth: Int) {
        if (depth > 40 || out.size > 200) return
        // Not a player's seek bar ("0 minutes 7 seconds of 1 minute 6 seconds"): it names durations too.
        if (node.isClickable && node.isVisibleToUser && !node.contentDescription.isNullOrBlank() && node.className?.contains("SeekBar") != true) out += node
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, texts, depth + 1) }
    }

    private fun label(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

private const val YOUTUBE = "com.google.android.youtube"
/** How long a search gets to start playing (a cold start, the results loading, the video buffering). */
private const val SEARCH_MS = 15_000L
private const val POLL_MS = 300L
/** A clicked video that hasn't started after this long gets a play through its media session. */
private const val NUDGE_MS = 3_000L

/** Picking the top video in a search result list (JVM-tested). */
object MediaSearch {
    /** A video row says how long it is ("… - 2 minutes, 31 seconds - …"); live streams and mixes don't. */
    private val DURATION = Regex("\\b\\d+ (?:hours?|minutes?|seconds?)\\b")

    /** Whether the screen's [texts] show [query] (the results page's search box), so its videos are its results. */
    fun showsQuery(texts: List<String>, query: String): Boolean = texts.any { it.trim().equals(query.trim(), ignoreCase = true) }

    /**
     * Whether the session now playing [title] is what the search started: a new title (not the one playing [before]),
     * or the [clicked] video's (its description starts with its title).
     */
    fun started(title: String?, before: String?, clicked: String?): Boolean =
        title != before || title != null && clicked?.startsWith(title) == true

    /**
     * The index of the first video among clickable elements' [descriptions]: a result row ("title - duration - channel
     * - …"), not an ad or a Short.
     */
    fun pick(descriptions: List<String>): Int? = descriptions.indexOfFirst { d ->
        " - " in d && DURATION.containsMatchIn(d) && !d.startsWith("Sponsored") && !d.contains("play Short", ignoreCase = true)
    }.takeIf { it >= 0 }
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
