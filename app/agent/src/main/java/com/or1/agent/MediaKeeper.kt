package com.or1.agent

import android.os.SystemClock
import android.util.Log
import com.or1.agent.tools.HiddenAudio
import com.or1.agent.tools.HiddenDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Media keeps playing after the turn: when a request ends with a media app playing in front of the work display (a
 * song or video the user asked for), the display stays awake and the app is heard, so the playback goes on and the live
 * media card follows it ([kept] is that app; the Now strip shows it with Pause/Play). The display parks once the app
 * has gone [IDLE_MS] without playing, another app is in front, or a request ends with nothing playing.
 */
object MediaKeeper {
    private const val TAG = "or1-media"
    /** Paused (or not started) this long: park. */
    private const val IDLE_MS = 2 * 60_000L
    /** A media app in front that isn't playing yet (buffering, the play tap just landed) gets this long to start. */
    private const val START_MS = 5_000L
    private const val CHECK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _kept = MutableStateFlow<String?>(null)
    /** The package kept playing on the work display, or null. */
    val kept: StateFlow<String?> = _kept

    private fun playing(pkg: String) = NowState.media.value.any { it.pkg == pkg && it.playing }

    private fun front(): String? = HiddenDisplay.id?.let { runCatching { HiddenDisplay.topAppPackage(it) }.getOrNull() }

    /** At the end of a request (or when the viewer closes): park the work display, unless media plays in front of it. */
    @Synchronized
    fun park() {
        job?.cancel()
        val pkg = front()
        if (pkg == null || (!playing(pkg) && !MediaApps.KNOWN.contains(pkg) && pkg !in MediaApps.seen)) {
            stop()
            return
        }
        job = scope.launch {
            if (!playing(pkg)) {
                val until = SystemClock.elapsedRealtime() + START_MS
                while (!playing(pkg) && SystemClock.elapsedRealtime() < until) delay(250)
                if (!playing(pkg)) {
                    stop()
                    return@launch
                }
            }
            Log.i(TAG, "keeping the work display awake: $pkg is playing")
            HiddenDisplay.release()
            // Its new screens (the next video) don't mute it again.
            HiddenAudio.onPlaying(pkg)
            _kept.value = pkg
            var idleSince: Long? = null
            var why = "the display went away"
            while (true) {
                delay(CHECK_MS)
                if (HiddenDisplay.id == null) break
                // A request has the display; it calls park() again when it ends.
                if (HiddenDisplay.inUse.value) {
                    idleSince = null
                    continue
                }
                val front = front()
                val inFront = front == pkg
                if (inFront && playing(pkg)) {
                    idleSince = null
                    HiddenDisplay.keepAwake()
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (idleSince == null) idleSince = now
                if (!inFront || now - idleSince >= IDLE_MS) {
                    why = if (!inFront) "$front is in front" else "$pkg hasn't played for ${IDLE_MS / 1000} s"
                    break
                }
            }
            Log.i(TAG, "parking the work display: $why")
            HiddenDisplay.parkIfIdle()
            _kept.value = null
        }
    }

    @Synchronized
    private fun stop() {
        HiddenDisplay.parkIfIdle()
        _kept.value = null
    }
}
