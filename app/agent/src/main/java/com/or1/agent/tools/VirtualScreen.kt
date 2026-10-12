package com.or1.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One off-screen display apps run on: the agent's work display ([HiddenDisplay]) or the stage that keeps media playing
 * ([com.or1.agent.Stage]). Same size and density as the panel, so coordinates and layouts match and moving a task
 * between displays (or to the real screen) doesn't relaunch it.
 *
 * Flags (AOSP 13): PUBLIC, because accessibility ignores private virtual displays; OWN_CONTENT_ONLY, so it never
 * mirrors the phone screen; TRUSTED, so activities its apps start (choosers, permission prompts) stay here and the IME
 * can be hidden; DESTROY_CONTENT_ON_REMOVAL, so a crash can't drop hidden apps on top of the chat; OWN_DISPLAY_GROUP,
 * so it has its own power state (awake while the phone sleeps, under [holdAwake]). SECURE (CAPTURE_SECURE_VIDEO_OUTPUT):
 * FLAG_SECURE windows render here too, instead of black in the previews, viewer and screenshots. The hidden flags need
 * signature permissions, which the platform key grants.
 *
 * Owns the display's lifecycle, its surface (the frame reader, or the viewer's), its per-display screen wake lock and
 * its frames ([preview]). With [framesWanted] off, frames are drained without being converted.
 */
class VirtualScreen(private val name: String, private val framesWanted: () -> Boolean = { true }) {
    private lateinit var context: Context
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread by lazy { HandlerThread(name).apply { start() } }
    val handler by lazy { Handler(thread.looper) }

    val id: Int? get() = display?.display?.displayId

    /** The display's size in pixels (the panel's). */
    @Volatile
    var size = android.graphics.Point(0, 0)
        private set

    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** Downscaled frames, at most every [PREVIEW_INTERVAL_MS] ([FAST_PREVIEW_MS] under [fastPreview]). */
    val preview: StateFlow<Bitmap?> = _preview

    @Volatile
    private var previewInterval = PREVIEW_INTERVAL_MS

    /** Converts frames every [FAST_PREVIEW_MS] instead of every 500 ms; the live media card sets it while it shows playback. */
    fun fastPreview(fast: Boolean) {
        previewInterval = if (fast) FAST_PREVIEW_MS else PREVIEW_INTERVAL_MS
    }

    @Volatile
    private var held: android.media.Image? = null
    @Volatile
    private var dirty = false
    private var lastConverted = 0L

    /** Off between uses: no surface, so AOSP puts the display to sleep and its apps stop. */
    @Volatile
    var parked = false
        private set

    /** The viewer renders straight from the display instead of the reader. */
    @Volatile
    var viewerAttached = false
        private set

    /** Creates the display; [onStopped] runs if the system stops it. Returns its id. */
    @Synchronized
    fun create(context: Context, onStopped: () -> Unit): Int {
        this.context = context.applicationContext
        val metrics = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = android.graphics.Point().also { @Suppress("DEPRECATION") metrics.getRealSize(it) }
        this.size = size
        val dpi = context.resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ onFrame(it) }, handler)
        val flags = FLAG_PUBLIC or FLAG_SECURE or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED or FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        val created = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            name, size.x, size.y, dpi, imageReader.surface, flags,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.w(TAG, "$name display stopped")
                    onStopped()
                }
            },
            handler,
        ) ?: error("createVirtualDisplay returned null")
        reader = imageReader
        display = created
        parked = false
        val newId = created.display.displayId
        Log.i(TAG, "$name display $newId created (${size.x}x${size.y} @ $dpi dpi)")
        return newId
    }

    /** Removes the display (its apps' tasks go with it) and frees everything it held. */
    @Synchronized
    fun release() {
        display?.release()
        display = null
        parked = false
        viewerAttached = false
        wakeLock?.takeIf { it.isHeld }?.release()
        held?.close()
        held = null
        reader?.close()
        reader = null
        _preview.value = null
    }

    @Synchronized
    fun park() {
        if (viewerAttached || display == null) return
        display?.surface = null
        parked = true
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    /** Turns a parked display back on. True if it was parked. */
    @Synchronized
    fun unpark(): Boolean {
        if (!parked) return false
        parked = false
        reader?.surface?.let { display?.surface = it }
        return true
    }

    // --- Power ----------------------------------------------------------------------------------------------------

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * The display has its own power group, which sleeps after the screen timeout like the phone does. A screen wake lock
     * bound to this display (hidden newWakeLock overload, Android 13) keeps only this group awake, for 10 minutes;
     * [keepAwake] extends it, [park] and [release] drop it.
     */
    @Synchronized
    fun holdAwake() {
        val displayId = id ?: return
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        wakeLock = try {
            pm.javaClass.getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(pm, flags, "or1:$name", displayId) as PowerManager.WakeLock
        } catch (e: Exception) {
            Log.w(TAG, "no per-display wake lock; the $name display may sleep", e)
            null
        }
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(WAKE_MS)
    }

    /** Extends the wake lock (10 minutes from now) while the display is on. */
    @Synchronized
    fun keepAwake() {
        if (display == null || parked) return
        val lock = wakeLock
        if (lock == null || !lock.isHeld) holdAwake() else lock.acquire(WAKE_MS)
    }

    // --- Viewer ---------------------------------------------------------------------------------------------------

    @Synchronized
    fun attachViewer(surface: Surface) {
        display?.surface = surface
        viewerAttached = true
        parked = false
    }

    @Synchronized
    fun detachViewer() {
        viewerAttached = false
        reader?.surface?.let { display?.surface = it }
    }

    // --- Frames ---------------------------------------------------------------------------------------------------

    /** Always drain the reader so the display never stalls; keep the newest image and convert it at most every interval. */
    private fun onFrame(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        held?.close()
        if (!framesWanted()) {
            image.close()
            held = null
            return
        }
        held = image
        dirty = true
        val wait = previewInterval - (SystemClock.uptimeMillis() - lastConverted)
        if (wait <= 0) convert() else if (!handler.hasCallbacks(convertRunnable)) handler.postDelayed(convertRunnable, wait)
    }

    private val convertRunnable = Runnable { convert() }

    private fun convert() {
        val image = held ?: return
        if (!dirty) return
        dirty = false
        lastConverted = SystemClock.uptimeMillis()
        try {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer.rewind())
            val cropped = if (rowPixels == image.width) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height)
            val scale = PREVIEW_WIDTH.toFloat() / image.width
            _preview.value = Bitmap.createScaledBitmap(cropped, PREVIEW_WIDTH, (image.height * scale).toInt(), true)
        } catch (e: Exception) {
            Log.w(TAG, "frame conversion failed", e)
        }
    }

    /**
     * The newest frame, downscaled like [preview]: converts the newest image now (on the display thread, waiting at
     * most [maxWaitMs]) if the preview is behind it, else returns the preview as it is.
     */
    fun latestFrame(maxWaitMs: Long = 40): Bitmap? {
        if (held != null && dirty && android.os.Looper.myLooper() != thread.looper) {
            val done = CountDownLatch(1)
            handler.postAtFrontOfQueue { convert(); done.countDown() }
            done.await(maxWaitMs, TimeUnit.MILLISECONDS)
        }
        return _preview.value
    }

    companion object {
        private const val TAG = "or1-display"
        private const val PREVIEW_WIDTH = 360
        const val PREVIEW_INTERVAL_MS = 500L
        const val FAST_PREVIEW_MS = 400L
        private const val WAKE_MS = 10 * 60_000L

        private const val FLAG_PUBLIC = 1
        private const val FLAG_SECURE = 4
        private const val FLAG_OWN_CONTENT_ONLY = 8
        private const val FLAG_SUPPORTS_TOUCH = 64
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 256
        private const val FLAG_TRUSTED = 1024
        private const val FLAG_OWN_DISPLAY_GROUP = 2048
        private const val FLAG_ALWAYS_UNLOCKED = 4096
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 8192
    }
}
