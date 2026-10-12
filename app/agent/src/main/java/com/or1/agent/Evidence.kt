package com.or1.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.or1.agent.tools.HiddenDisplay
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Where the agent touched the work display: work-display pixels ([displayW] x [displayH], 720x1600 on the RAVOZ), at
 * [atElapsed] (SystemClock.elapsedRealtime). [kind] is tap, long_press or swipe (for a swipe, x/y is where it started).
 */
data class TapMark(val x: Float, val y: Float, val displayW: Int, val displayH: Int, val atElapsed: Long, val kind: String = "tap")

/** A frame being saved for an effect that isn't recorded yet (see [Evidence.capture]). */
class EvidenceFrame(val effectId: String, val path: String, internal val job: Job)

/**
 * Proof of what the agent did on the work display (docs/ui-ledger.md §4): tap marks for the Act card's evidence strip,
 * and for tier-2/3 `act` taps a frame of the work display taken just before the committing tap, saved as
 * files/evidence/<effectId>.webp and linked from [Effect.evidence].
 */
object Evidence {
    private const val TAG = "or1-evidence"
    const val DIR = "evidence"
    /** How long the UI shows a tap's ring. */
    const val RING_MS = 600L
    private const val TAPS_KEPT = 50
    private const val PRUNE_EVERY_MS = 6 * 3600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dir: File? = null
    @Volatile
    private var lastPrune = 0L

    private val _lastTap = MutableStateFlow<TapMark?>(null)
    /** The agent's latest touch on the work display (each one is a new value: [TapMark.atElapsed] differs). */
    val lastTap: StateFlow<TapMark?> = _lastTap
    private val _taps = MutableSharedFlow<TapMark>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every touch as an event, for a UI that animates each one. */
    val taps: SharedFlow<TapMark> = _taps
    private val recent = ArrayDeque<TapMark>()

    fun init(filesDir: File) {
        if (dir != null) return
        dir = File(filesDir, DIR).also { it.mkdirs() }
    }

    /** Called by [HiddenDisplay] as each injected stroke starts. */
    fun tap(mark: TapMark) {
        synchronized(recent) {
            recent.addLast(mark)
            while (recent.size > TAPS_KEPT) recent.removeFirst()
        }
        _lastTap.value = mark
        _taps.tryEmit(mark)
    }

    /** The last [n] tap marks, oldest first. */
    fun recentTaps(n: Int = TAPS_KEPT): List<TapMark> = synchronized(recent) { recent.toList().takeLast(n) }

    /**
     * Grabs the work display as it is now (a fresh conversion of its newest frame if that takes under ~40 ms, else the
     * last preview), scales it to [FRAME_WIDTH] px RGB565 and starts saving it for [effectId] in the background.
     * Null when there's no frame (the work display isn't in use, or the app runs on the real screen).
     */
    fun capture(effectId: String): EvidenceFrame? {
        val dir = dir ?: return null
        // Nothing may be watching the work display, so its newest frame may not be converted yet: allow a little longer.
        val source = HiddenDisplay.latestFrame(120) ?: return null
        val frame = runCatching { scale(source) }.onFailure { Log.w(TAG, "could not scale a frame", it) }.getOrNull() ?: return null
        val file = File(dir, "$effectId.webp")
        val job = scope.launch {
            val tmp = File(dir, "$effectId.webp.tmp")
            runCatching {
                tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, it) }
                check(tmp.renameTo(file))
            }.onFailure { Log.w(TAG, "could not save $file", it); tmp.delete() }
            frame.recycle()
            if (System.currentTimeMillis() - lastPrune > PRUNE_EVERY_MS) prune(ActivityLog.effects.value)
        }
        return EvidenceFrame(effectId, file.absolutePath, job)
    }

    /** Drops a frame whose tap failed or turned out not to be an effect. */
    fun discard(frame: EvidenceFrame) {
        scope.launch {
            frame.job.join()
            File(frame.path).delete()
        }
    }

    /** Deletes frames past [EVIDENCE_KEEP_MS] and ones no effect links to; see [prunableFrames]. */
    fun prune(effects: List<Effect>) {
        val dir = dir ?: return
        lastPrune = System.currentTimeMillis()
        val files = dir.listFiles().orEmpty()
        val referenced = effects.mapNotNull { e -> e.evidence?.let { File(it).name } }.toSet()
        val doomed = prunableFrames(files.map { it.name to it.lastModified() }, referenced, lastPrune).toSet()
        files.filter { it.name in doomed }.forEach { it.delete() }
        if (doomed.isNotEmpty()) Log.i(TAG, "pruned ${doomed.size} frames")
    }

    /** Prune in the background (on start). */
    fun pruneLater(effects: () -> List<Effect>) {
        scope.launch { prune(effects()) }
    }

    private fun scale(source: Bitmap): Bitmap {
        val height = (source.height * FRAME_WIDTH.toFloat() / source.width).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(FRAME_WIDTH, height, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(source, null, Rect(0, 0, FRAME_WIDTH, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}

/** Width of an evidence frame (px); the height follows the work display's aspect. */
const val FRAME_WIDTH = 240
/** At most this many evidence frames per act (one request). */
const val MAX_FRAMES_PER_ACT = 8
/** Evidence frames go after this long, like the effects in activity.jsonl. */
const val EVIDENCE_KEEP_MS = 30L * 24 * 3600_000
/** A frame no effect links to (its tap failed, or the process died first) goes after this long. */
const val ORPHAN_KEEP_MS = 3600_000L

/** Whether a committing tap of [tier] gets a frame, given [taken] frames so far in this act. */
fun shouldCaptureFrame(tier: UndoTier, taken: Int): Boolean = tier != UndoTier.LOCAL && taken < MAX_FRAMES_PER_ACT

/**
 * Which files in files/evidence/ to delete, given (name, last modified) pairs: anything older than [EVIDENCE_KEEP_MS],
 * and frames (or half-written .tmp files) that no effect links to once they're older than [ORPHAN_KEEP_MS].
 * [referenced] holds the file names effects link to.
 */
fun prunableFrames(files: List<Pair<String, Long>>, referenced: Set<String>, now: Long): List<String> =
    files.filter { (name, modified) ->
        val age = now - modified
        age > EVIDENCE_KEEP_MS || name !in referenced && age > ORPHAN_KEEP_MS
    }.map { it.first }
