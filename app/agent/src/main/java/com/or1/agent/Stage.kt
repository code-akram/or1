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
 * The stage: a second off-screen display where media the user asked for keeps playing, so the agent's work display is
 * free for the next request (stopping a timer, reading a message) without covering the video, which would stop it.
 *
 * When a request ends (or the viewer closes) with a media app playing in front of the work display (or starting
 * within [START_MS]), its task moves here ([admit]) and the work display parks as usual. Here it is never muted and
 * stays awake while it plays; [kept] is that app (the Now strip's media chip, the live media card, the `media` tool).
 * The agent controls it through its media session; when it needs the app's screen (it opens the app, or the viewer
 * opens), the task moves back to the work display ([recall]) and returns here at the end if it still plays. After
 * [IDLE_MS] without playing, it goes back to the (parked) work display and the stage is removed ([retire]).
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

    /** Frames are converted only while something shows them (the live media card). */
    @Volatile
    private var framesWanted = false
    private val screen = VirtualScreen("or1-stage") { framesWanted }

    private val _kept = MutableStateFlow<String?>(null)
    /** The package playing on the stage, or null. */
    val kept: StateFlow<String?> = _kept

    val id: Int? get() = screen.id
    val preview get() = screen.preview

    /** The live media card shows the stage: convert its frames, quickly. */
    fun follow(on: Boolean) {
        framesWanted = on
        screen.fastPreview(on)
    }

    fun init(context: Context) {
        this.context = context.applicationContext
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
            if (front == null || !StagePolicy.mayPlay(front, playing(front), MediaApps.KNOWN + MediaApps.seen)) {
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
        if (playing(front)) admit(front)
    }

    /** Moves [pkg]'s task from the work display to the stage. */
    private fun admit(pkg: String): Boolean {
        val workId = HiddenDisplay.id ?: return false
        val task = Tasks.topOf(pkg, workId) ?: return false
        val stageId = screen.id ?: try {
            screen.create(context) { scope.launch { synchronized(lock) { stopped() } } }
        } catch (e: Exception) {
            Log.e(TAG, "could not create the stage; $pkg stays on the work display", e)
            return false
        }
        // One app plays here at a time: the one before goes back to the work display, which parks (it stops).
        _kept.value?.takeIf { it != pkg }?.let { old ->
            Tasks.topOf(old, stageId)?.let { Tasks.move(it, workId) }
            Log.i(TAG, "$old leaves the stage for $pkg")
        }
        if (!Tasks.move(task, stageId)) return false
        screen.unpark()
        screen.holdAwake()
        // Moving the task brought the stage to the top, with focus: give it back to the chat.
        HiddenDisplay.restoreFocus()
        HiddenAudio.unmute(pkg)
        _kept.value = pkg
        Log.i(TAG, "$pkg plays on the stage (display $stageId); the work display is free")
        watch(pkg)
        return true
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
                if (playing(pkg)) {
                    idleSince = null
                    screen.keepAwake()
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (idleSince == null) idleSince = now
                // A request may be using the work display; retire once it's free.
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

    /**
     * The agent (or the user, through the viewer) needs [pkg]'s screen: if it's the kept app (any package when null),
     * its task moves back in front of the work display, heard, and the stage goes. True if it was on the stage.
     */
    fun recall(pkg: String? = null): Boolean = synchronized(lock) {
        val kept = _kept.value ?: return false
        if (pkg != null && pkg != kept) return false
        decide?.cancel()
        watch?.cancel()
        val workId = HiddenDisplay.ensure()
        val task = screen.id?.let { Tasks.topOf(kept, it) }
        if (workId != null && task != null) {
            // It's what the user asked to hear: the work display's muting leaves it alone.
            HiddenAudio.onPlaying(kept)
            Tasks.move(task, workId)
            HiddenDisplay.restoreFocus()
            Log.i(TAG, "$kept back on the work display")
        }
        screen.release()
        _kept.value = null
        true
    }

    /**
     * A shell command about to start an activity: if it targets the kept app (by package or by an address that opens
     * in it), [recall] it first. A launch onto the work display doesn't move a task that lives on another display.
     */
    fun recallFor(command: String) {
        val kept = _kept.value ?: return
        if (!command.contains("am start")) return
        val hit = command.contains(kept) || StagePolicy.addresses(command).any { resolve(it) == kept }
        if (hit) recall(kept)
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
