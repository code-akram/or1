package com.or1.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.or1.agent.tools.HiddenAudio
import com.or1.agent.tools.HiddenDisplay
import com.or1.agent.tools.Tasks
import com.or1.agent.tools.VirtualScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The stage: a second off-screen display where media lives, so the agent's work display is free for everything else
 * (stopping a timer, reading a message) without covering the video, which would stop it.
 *
 * Roles, not moves: media stays here and the agent works on it here. `media play` with a query launches straight onto
 * the stage ([host]); a media app found playing in front of the work display when a request ends (or starts, or the
 * viewer closes) moves here once ([admit]). Nothing moves back while it plays: when the agent opens the kept app, the
 * tools act on the stage ([com.or1.agent.tools.HiddenDisplay.targetDisplay]), and the viewer shows the stage when it's
 * opened from the media card or chip. Here the app is never muted and stays awake while it plays; [kept] is that app
 * (the Now strip's media chip, the live media card, the `media` tool). After [IDLE_MS] without playing (and nobody
 * watching it in the viewer) it goes back to the parked work display and the stage is removed ([retire]).
 */
object Stage {
    private const val TAG = "or1-media"
    /** Paused (or not started) this long on the stage: retire it. */
    private const val IDLE_MS = 2 * 60_000L
    /** A media app in front that isn't playing yet (buffering, the play tap just landed) gets this long to start. */
    private const val START_MS = 5_000L
    private const val CHECK_MS = 5_000L

    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var decide: Job? = null
    private var watch: Job? = null

    internal val screen = VirtualScreen("or1-stage")

    private val _kept = MutableStateFlow<String?>(null)
    /** The package playing on the stage, or null. */
    val kept: StateFlow<String?> = _kept

    private val _starts = MutableStateFlow(0)
    /** Bumped each time media starts on the stage (a new app here, or `media play`/next/previous): voice mode's cue. */
    val starts: StateFlow<Int> = _starts

    val id: Int? get() = screen.id

    /** Downscaled frames of the stage, for the act card's evidence strip while the agent works here. */
    val preview: StateFlow<android.graphics.Bitmap?> get() = screen.preview

    /**
     * The live media card shows the stage's video: the display renders straight into [surface] (a TextureView's), so
     * it plays smoothly; [hide] hands the display back to its frame reader. The viewer, when open, goes over both.
     */
    fun show(surface: android.view.Surface) = screen.attachCard(surface)

    fun hide() = screen.detachCard()

    /** The viewer is showing the stage (opened from the media card or chip): the user watches and can use the app. */
    fun attachViewer(surface: android.view.Surface): Boolean = synchronized(lock) {
        val stageId = screen.id ?: return false
        screen.attachViewer(surface)
        screen.keepAwake()
        // Its keyboard shows inside the viewer (searching YouTube), like the work screen's.
        HiddenDisplay.setImePolicy(HiddenDisplay.IME_LOCAL, stageId)
        // Keys go to the stage while it's watched (see HiddenDisplay.attachViewer).
        HiddenDisplay.putOnTop(stageId)
        true
    }

    fun detachViewer() {
        screen.detachViewer()
        screen.id?.let { HiddenDisplay.setImePolicy(HiddenDisplay.IME_FALLBACK, it) }
        HiddenDisplay.restoreFocus()
    }

    val viewerAttached: Boolean get() = screen.viewerAttached

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun noteStarted() {
        _starts.value++
    }

    private fun playing(pkg: String) = NowState.media.value.any { it.pkg == pkg && it.playing }

    private fun busy() = AgentHub.busy.value || AgentHub.routineBusy.value

    private fun workFront(): String? = HiddenDisplay.id?.let { runCatching { HiddenDisplay.topAppPackage(it) }.getOrNull() }

    /**
     * At the end of a request (or when the viewer closes): media playing in front of the work display moves to the
     * stage, then the work display parks. Nothing happens while a request runs (it calls this when it ends).
     */
    fun park() {
        if (busy()) return
        synchronized(lock) {
            decide?.cancel()
            val front = workFront()
            if (front == null || front == _kept.value || !StagePolicy.mayPlay(front, playing(front), MediaApps.KNOWN + MediaApps.seen)) {
                HiddenDisplay.park()
                return
            }
            decide = scope.launch {
                val until = SystemClock.elapsedRealtime() + START_MS
                while (!playing(front) && SystemClock.elapsedRealtime() < until) delay(250)
                synchronized(lock) {
                    // A new request has the work display: it starts by moving what plays ([beforeRequest]).
                    if (busy()) return@launch
                    if (playing(front)) admit(front)
                    HiddenDisplay.park()
                }
            }
        }
    }

    /** A request is starting: media still playing in front of the work display moves out of its way first. */
    fun beforeRequest() = synchronized(lock) {
        decide?.cancel()
        val front = workFront() ?: return@synchronized
        if (front != _kept.value && playing(front)) admit(front)
    }

    /** Moves [pkg]'s task, playing in front of the work display, to the stage. */
    private fun admit(pkg: String): Boolean {
        val workId = HiddenDisplay.id ?: return false
        if (Tasks.topOf(pkg, workId) == null) return false
        return host(pkg) != null
    }

    /**
     * Makes the stage [pkg]'s: creates (or wakes) it, moves [pkg]'s task over from the work display if it's there, and
     * keeps it ([kept], heard, awake while it plays). `media play` calls this before launching its search here, so
     * nothing moves afterwards. Returns the stage's display id, or null if there's no stage (the caller uses the work
     * display).
     */
    fun host(pkg: String): Int? = synchronized(lock) {
        val stageId = screen.id ?: try {
            screen.create(context) { scope.launch { synchronized(lock) { stopped() } } }
        } catch (e: Exception) {
            Log.e(TAG, "could not create the stage; $pkg stays on the work display", e)
            return null
        }
        // The keyboard belongs to the real screen unless the user is in the viewer (see HiddenDisplay.IME_FALLBACK).
        HiddenDisplay.setImePolicy(HiddenDisplay.IME_FALLBACK, stageId)
        val workId = HiddenDisplay.id
        // One app plays here at a time: the one before goes back to the work display, which parks (it stops).
        _kept.value?.takeIf { it != pkg }?.let { old ->
            if (workId != null) Tasks.topOf(old, stageId)?.let { Tasks.move(it, workId) }
            Log.i(TAG, "$old leaves the stage for $pkg")
        }
        val moved = workId != null && Tasks.topOf(pkg, workId)?.let { Tasks.move(it, stageId) } == true
        screen.unpark()
        screen.holdAwake()
        // Moving the task brought the stage to the top, with focus: give it back to the chat.
        if (moved) HiddenDisplay.restoreFocus()
        HiddenAudio.unmute(pkg)
        if (_kept.value != pkg) noteStarted()
        _kept.value = pkg
        Log.i(TAG, "$pkg ${if (moved) "moved to" else "lives on"} the stage (display $stageId); the work display is free")
        watch(pkg)
        stageId
    }

    /** Keeps the stage awake while [pkg] plays; [retire]s it after [IDLE_MS] without playing, or when it's gone. */
    private fun watch(pkg: String) {
        watch?.cancel()
        watch = scope.launch {
            var idleSince: Long? = null
            var why: String
            while (true) {
                delay(CHECK_MS)
                val stageId = screen.id
                if (stageId == null || Tasks.topOf(pkg, stageId) == null) {
                    why = "it left the stage"
                    break
                }
                // Playing, or someone is using it in the viewer.
                if (playing(pkg) || screen.viewerAttached) {
                    idleSince = null
                    screen.keepAwake()
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (idleSince == null) idleSince = now
                // A request may be working on the stage; retire once it's free.
                if (now - idleSince >= IDLE_MS && !busy()) {
                    why = "it hasn't played for ${IDLE_MS / 1000} s"
                    break
                }
            }
            synchronized(lock) {
                if (_kept.value == pkg) retire(pkg, why)
            }
        }
    }

    /** [pkg] goes back to the work display (parked: it stops there, ready for the agent to reopen) and the stage goes. */
    private fun retire(pkg: String, why: String) {
        Log.i(TAG, "retiring the stage: $pkg $why")
        val stageId = screen.id
        val workId = HiddenDisplay.id
        if (stageId != null && workId != null && !busy()) {
            Tasks.topOf(pkg, stageId)?.let { Tasks.move(it, workId) }
            HiddenDisplay.park()
            HiddenDisplay.restoreFocus()
        }
        screen.release()
        _kept.value = null
    }

    private fun stopped() {
        watch?.cancel()
        screen.release()
        _kept.value = null
    }

    /** Whether [pkg] is the app living on the stage (the agent's tools then act there). */
    fun owns(pkg: String?): Boolean = pkg != null && pkg == _kept.value && screen.id != null

    /**
     * Whether a shell command starts an activity of the kept app (by package or by an address that opens in it): it
     * then runs against the stage, where that app lives (a launch onto the work display doesn't move a task that lives
     * on another display).
     */
    fun claims(command: String): Boolean {
        val kept = _kept.value ?: return false
        if (screen.id == null || !command.contains("am start")) return false
        return command.contains(kept) || StagePolicy.addresses(command).any { resolve(it) == kept }
    }

    /** The package an address opens in, if one handles it. */
    fun resolve(uri: String): String? = runCatching {
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).resolveActivity(context.packageManager)?.packageName
    }.getOrNull()
}

/** The stage's decisions, kept pure for the JVM tests. */
object StagePolicy {
    /** Whether [front] may be media worth keeping: it plays, or it's a media app (it may be about to start). */
    fun mayPlay(front: String, playing: Boolean, mediaApps: Set<String>): Boolean = playing || front in mediaApps

    private val ADDRESS = Regex("""\b(?:https?://|vnd\.[a-z]+:)[^\s"']+""")

    /** Web and app addresses in a shell command. */
    fun addresses(command: String): List<String> = ADDRESS.findAll(command).map { it.value }.toList()
}
