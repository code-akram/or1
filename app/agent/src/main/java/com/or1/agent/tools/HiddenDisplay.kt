package com.or1.agent.tools

import android.app.ActivityOptions
import androidx.activity.compose.setContent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.accessibility.AccessibilityWindowInfo
import com.or1.agent.A11yService
import com.or1.agent.Evidence
import com.or1.agent.TapMark
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An off-screen display the agent works on, so apps it opens never cover the chat. Same size and density as the
 * panel, so coordinates and layouts match and moving a task to the real screen doesn't relaunch it.
 *
 * Flags (AOSP 13): PUBLIC, because accessibility ignores private virtual displays; OWN_CONTENT_ONLY, so it never
 * mirrors the phone screen; TRUSTED, so activities the hidden app starts (choosers, permission prompts) stay here and
 * the IME can be hidden; DESTROY_CONTENT_ON_REMOVAL, so a crash can't drop hidden apps on top of the chat.
 * The hidden ones need signature permissions, which the platform key grants. Hidden APIs are reached by reflection;
 * platform-signed apps are exempt from the hidden-API blocklist.
 */
object HiddenDisplay {
    private const val TAG = "or1-display"
    private const val NAME = "or1-work"
    private const val PREVIEW_WIDTH = 360
    private const val PREVIEW_INTERVAL_MS = 500L

    private const val FLAG_PUBLIC = 1
    private const val FLAG_SECURE = 4
    private const val FLAG_OWN_CONTENT_ONLY = 8
    private const val FLAG_SUPPORTS_TOUCH = 64
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 256
    private const val FLAG_TRUSTED = 1024
    private const val FLAG_OWN_DISPLAY_GROUP = 2048
    private const val FLAG_ALWAYS_UNLOCKED = 4096
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 8192

    /** MotionEvent.FLAG_NO_FOCUS_CHANGE (hidden). */
    private const val FLAG_NO_FOCUS_CHANGE = 0x40

    const val IME_LOCAL = 0
    /**
     * The keyboard belongs to the real screen. Not HIDE: when the hidden display briefly takes focus (for a back
     * key), HIDE unbinds the IME from every display, and it doesn't re-bind when the chat gets focus back.
     */
    const val IME_FALLBACK = 1

    private lateinit var context: Context
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread by lazy { HandlerThread("or1-display").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    /** Set when creation failed: the agent works on the real screen for the rest of this process. */
    @Volatile
    var unavailable = false
        private set

    /** Packages that refused the hidden display this session; they run on the real screen. */
    private val refused = mutableSetOf<String>()

    /** The package the agent is working in, so tools know which display it's on. */
    @Volatile
    private var currentPackage: String? = null

    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** Downscaled frames of the hidden display, at most every PREVIEW_INTERVAL_MS. */
    val preview: StateFlow<Bitmap?> = _preview

    private val _inUse = MutableStateFlow(false)
    /**
     * True from the moment a tool wakes the display ([ensure]) until the request ends ([park]) or the display goes. With
     * AgentHub's busy flags this is "the agent is using the work display right now" (AgentHub.usingWorkDisplay).
     */
    val inUse: StateFlow<Boolean> = _inUse

    private val _foregroundApp = MutableStateFlow<String?>(null)
    /** The label of the app in front on the work display (null when it's empty or showing or1's app grid). */
    val foregroundApp: StateFlow<String?> = _foregroundApp

    /** The display's size in pixels (the panel's), for [TapMark]s. */
    @Volatile
    private var size = android.graphics.Point(0, 0)

    @Volatile
    private var held: android.media.Image? = null
    @Volatile
    private var dirty = false
    private var lastConverted = 0L
    private var viewerAttached = false
    private var parked = false

    fun init(context: Context) {
        this.context = context.applicationContext
        HiddenAudio.init(this.context)
    }

    val id: Int? get() = display?.display?.displayId

    /** The display tools should act on: the hidden one, unless it's unavailable or the current app refused it. */
    fun targetDisplay(): Int {
        val hidden = id ?: return Display.DEFAULT_DISPLAY
        return if (currentPackage != null && currentPackage in refused) Display.DEFAULT_DISPLAY else hidden
    }

    /** Creates the display if needed. Returns its id, or null if the agent should use the real screen. */
    @Synchronized
    fun ensure(): Int? {
        id?.let { unpark(); holdAwake(it); _inUse.value = true; return it }
        if (unavailable) return null
        return try {
            create().also { holdAwake(it); _inUse.value = true }
        } catch (e: Exception) {
            Log.e(TAG, "could not create the hidden display; using the real screen", e)
            unavailable = true
            null
        }
    }

    private fun create(): Int {
        releaseStaleDisplays()
        val metrics = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = android.graphics.Point().also { @Suppress("DEPRECATION") metrics.getRealSize(it) }
        this.size = size
        val dpi = context.resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ onFrame(it) }, handler)
        // SECURE (CAPTURE_SECURE_VIDEO_OUTPUT): FLAG_SECURE windows render here too, instead of black in the thumbnail,
        // viewer and screenshots.
        val flags = FLAG_PUBLIC or FLAG_SECURE or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED or FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        val created = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            NAME, size.x, size.y, dpi, imageReader.surface, flags,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.w(TAG, "hidden display stopped")
                    reset()
                }
            },
            handler,
        ) ?: error("createVirtualDisplay returned null")
        reader = imageReader
        display = created
        val newId = created.display.displayId
        setImePolicy(IME_FALLBACK)
        Log.i(TAG, "hidden display $newId created (${size.x}x${size.y} @ $dpi dpi)")
        return newId
    }

    @Synchronized
    private fun reset() {
        display?.release()
        display = null
        parked = false
        wakeLock?.takeIf { it.isHeld }?.release()
        held?.close()
        held = null
        reader?.close()
        reader = null
        _preview.value = null
        _inUse.value = false
        _foregroundApp.value = null
    }

    /** Keyboard policy for the hidden display: FALLBACK while the agent works, LOCAL while the user is viewing it. */
    fun setImePolicy(policy: Int) {
        val displayId = id ?: return
        try {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, policy)
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayImePolicy failed", e)
        }
    }

    /** Turns the display off between requests: its apps stop (AOSP puts an off display to sleep) until [unpark]. */
    @Synchronized
    fun park() {
        // The request is over either way; with the viewer open the user has the display.
        _inUse.value = false
        if (viewerAttached || display == null) return
        display?.surface = null
        parked = true
        wakeLock?.takeIf { it.isHeld }?.release()
        HiddenAudio.unmuteAll()
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * The display has its own power group (so it works while the phone is off or locked), and that group sleeps after
     * the screen timeout like the phone does. A screen wake lock bound to this display (hidden newWakeLock overload,
     * Android 13) keeps only this group awake while the agent works; [park] releases it.
     */
    private fun holdAwake(displayId: Int) {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        @Suppress("DEPRECATION")
        val flags = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
        wakeLock = try {
            pm.javaClass.getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(pm, flags, "or1:work-display", displayId) as android.os.PowerManager.WakeLock
        } catch (e: Exception) {
            Log.w(TAG, "no per-display wake lock; the work display may sleep during long tasks", e)
            null
        }
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(10 * 60_000L)
    }

    @Synchronized
    private fun unpark() {
        if (!parked) return
        parked = false
        reader?.surface?.let { display?.surface = it }
        // Give the apps a moment to resume and redraw before tools read the screen.
        Thread.sleep(400)
        id?.let { HiddenAudio.mute(topAppPackage(it)) }
        refreshForegroundApp()
    }

    // --- Leak guard ---------------------------------------------------------------------------------------------

    /** Until when (uptime ms) a new app window on the real screen is assumed to come from the agent's last action. */
    @Volatile
    private var guardUntil = 0L

    fun markActivity() {
        guardUntil = SystemClock.uptimeMillis() + 3000
    }

    /**
     * Some launches (from services, notifications, some deep links) ignore the launch display and open on the real
     * screen. Right after an agent action, move any such app over to the hidden display and put the chat back.
     */
    fun onWindowsChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY && displayId == id) refreshForegroundApp()
        if (displayId != Display.DEFAULT_DISPLAY && displayId == id && !deliveringKey) {
            // Whatever runs here stays quiet unless the user is watching (launches by open, clock and shell all land
            // here, and so do apps' own new screens).
            if (!viewerAttached) HiddenAudio.mute(topAppPackage(displayId))
            // Something on the hidden display came to the front (e.g. the app opened a new screen), which moves the
            // display to the top and takes focus from the chat. Hand it straight back.
            handler.removeCallbacks(restoreFocusRunnable)
            handler.post(restoreFocusRunnable)
            return
        }
        if (displayId != Display.DEFAULT_DISPLAY || SystemClock.uptimeMillis() > guardUntil) return
        val target = id ?: return
        val pkg = topAppPackage(Display.DEFAULT_DISPLAY) ?: return
        if (pkg == context.packageName || pkg in refused || pkg in SYSTEM_SURFACES) return
        val task = topTaskOf(pkg, Display.DEFAULT_DISPLAY) ?: return
        if (moveTaskToDisplay(task, target)) {
            Log.i(TAG, "moved $pkg (task $task) from the real screen to the hidden display")
            currentPackage = pkg
            onLeakMoved?.invoke()
        }
    }

    // --- Focus -------------------------------------------------------------------------------------------------

    private val restoreFocusRunnable = Runnable { restoreFocus() }

    /** Set while a key is delivered to the hidden display, which needs focus for that moment. */
    @Volatile
    private var deliveringKey = false

    /**
     * Puts the real screen back on top, so the chat keeps input focus and its keyboard stays open. AOSP 13 moves a
     * trusted display to the top whenever a task on it comes to the front, and the top display owns focus (there's no
     * per-display focus or "don't steal focus" flag until Android 14). Skipped while the user is in the viewer.
     */
    private val _focusReturned = MutableStateFlow(0)
    /** Bumped each time focus is handed back to the real screen, so the chat can reopen its keyboard if it closed. */
    val focusReturned: StateFlow<Int> = _focusReturned

    fun restoreFocus() {
        if (viewerAttached || id == null) return
        // Re-starting the real screen's top task (the chat, or an app the user brought to the front) goes through
        // the activity starter, which moves its display back on top without relaunching anything. The direct routes
        // don't work in 13: WMS.moveDisplayToTop isn't on the binder interface, and setFocusedTask/moveTaskToFront
        // return early when the task is already its display's top task.
        val intent = topTaskIntent(Display.DEFAULT_DISPLAY) ?: return
        try {
            context.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not bring the real screen back on top", e)
        }
        _focusReturned.value++
    }

    /** The base intent of the top task on [displayId]. */
    private fun topTaskIntent(displayId: Int): Intent? = try {
        val infos = atm!!.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType).invoke(atm, displayId) as List<*>
        infos.firstOrNull()?.let { Class.forName("android.app.TaskInfo").getField("baseIntent").get(it) as Intent? }?.let { Intent(it) }
    } catch (e: Exception) {
        Log.w(TAG, "no top task on display $displayId", e)
        null
    }

    private fun setFocusedTask(taskId: Int): Boolean = try {
        atm!!.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType).invoke(atm, taskId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "setFocusedTask($taskId) failed", e)
        false
    }

    /** Called after the leak guard moved a task, so the chat can come back to the front. */
    var onLeakMoved: (() -> Unit)? = null

    private val SYSTEM_SURFACES = setOf("com.android.systemui", "com.android.launcher3", "com.android.permissioncontroller")

    // --- Frames -------------------------------------------------------------------------------------------------

    /** Always drain the reader so the display never stalls; keep the newest image and convert it at most twice a second. */
    private fun onFrame(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        held?.close()
        held = image
        dirty = true
        val wait = PREVIEW_INTERVAL_MS - (SystemClock.uptimeMillis() - lastConverted)
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
     * The newest frame of the display, downscaled like [preview]: converts the newest image now (on the display thread,
     * waiting at most [maxWaitMs]) if the preview is behind it, else returns the preview as it is. For evidence frames
     * taken just before a committing tap, which mustn't hold the tap up.
     */
    fun latestFrame(maxWaitMs: Long = 40): Bitmap? {
        if (held != null && dirty && android.os.Looper.myLooper() != thread.looper) {
            val done = CountDownLatch(1)
            handler.postAtFrontOfQueue { convert(); done.countDown() }
            done.await(maxWaitMs, TimeUnit.MILLISECONDS)
        }
        return _preview.value
    }

    /** The full-screen viewer renders straight from the display; the reader takes over again when it closes. */
    @Synchronized
    fun attachViewer(surface: Surface) {
        display?.surface = surface
        viewerAttached = true
        parked = false
        setImePolicy(IME_LOCAL)
        // The user is watching: let the app be heard.
        id?.let { HiddenAudio.unmute(topAppPackage(it)) }
    }

    @Synchronized
    fun detachViewer() {
        viewerAttached = false
        reader?.surface?.let { display?.surface = it }
        setImePolicy(IME_FALLBACK)
        id?.let { HiddenAudio.mute(topAppPackage(it)) }
        // Touching the work screen moved focus (and its display) to the top; give the chat its keyboard back.
        handler.post(restoreFocusRunnable)
    }

    private val foregroundRunnable = Runnable {
        val pkg = id?.let { topAppPackage(it) }?.takeIf { it != context.packageName }
        _foregroundApp.value = pkg?.let { p ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        }
    }

    /** Re-reads [foregroundApp] shortly (accessibility's window list lags its events a little); coalesced. */
    private fun refreshForegroundApp() {
        handler.removeCallbacks(foregroundRunnable)
        handler.postDelayed(foregroundRunnable, 150)
    }

    // --- Launching ----------------------------------------------------------------------------------------------

    /**
     * Starts [intent] on the hidden display and waits for a window of [expectPackage] (or any new app window) there.
     * If it lands on the real screen instead, moves its task over; if that fails, the package runs on the real screen
     * for the rest of the session. Returns the display it ended up on.
     */
    suspend fun launch(intent: Intent, expectPackage: String?): Int {
        markActivity()
        val target = ensure()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target == null || expectPackage != null && expectPackage in refused) {
            currentPackage = expectPackage
            context.startActivity(intent)
            return Display.DEFAULT_DISPLAY
        }
        val before = tasksOn(target).firstOrNull()?.second
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        try {
            context.startActivity(intent, options.toBundle())
        } catch (e: SecurityException) {
            Log.w(TAG, "launch on hidden display refused", e)
            return fallBack(intent, expectPackage)
        }
        // Before the new window draws, so it never takes focus from the chat.
        restoreFocus()
        val landed = waitForApp(target, expectPackage, before)
        restoreFocus()
        if (landed != null) {
            currentPackage = landed
            refreshForegroundApp()
            return target
        }
        // It may have opened on the real screen (an existing task that wasn't moved): move it.
        val pkg = expectPackage ?: topAppPackage(Display.DEFAULT_DISPLAY)
        val task = pkg?.let { topTaskOf(it, Display.DEFAULT_DISPLAY) }
        if (task != null && moveTaskToDisplay(task, target) && waitForApp(target, pkg, before) != null) {
            restoreFocus()
            currentPackage = pkg
            return target
        }
        Log.w(TAG, "$pkg did not open on the hidden display; using the real screen for it")
        pkg?.let { refused += it }
        currentPackage = pkg
        return Display.DEFAULT_DISPLAY
    }

    private fun fallBack(intent: Intent, expectPackage: String?): Int {
        expectPackage?.let { refused += it }
        currentPackage = expectPackage
        context.startActivity(intent)
        return Display.DEFAULT_DISPLAY
    }

    /** Waits for [pkg] (or any app other than [before]) to be the top task on [displayId], then for its window. */
    private suspend fun waitForApp(displayId: Int, pkg: String?, before: String?): String? {
        var landed: String? = null
        repeat(15) {
            delay(200)
            // The task list is authoritative; accessibility windows lag behind it.
            val top = tasksOn(displayId).firstOrNull()?.second ?: topAppPackage(displayId)
            if (top != null && top != context.packageName && (if (pkg != null) top == pkg else top != before)) {
                landed = top
                if (topAppPackage(displayId) == top) return top
            }
        }
        return landed
    }

    /** The package of the topmost application window on [displayId], from accessibility. */
    fun topAppPackage(displayId: Int): String? = topAppWindow(displayId)?.root?.packageName?.toString()

    fun topAppWindow(displayId: Int): AccessibilityWindowInfo? {
        val service = A11yService.instance ?: return null
        // Not service.windows: that follows the focused display, which is the hidden one while the phone sleeps.
        val windows = service.windowsOnAllDisplays[displayId]
        return windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName != "com.android.systemui" }
    }

    /** True if accessibility tracks the hidden display (it ignores private displays). */
    fun accessibilitySees(): Boolean {
        val displayId = id ?: return false
        return A11yService.instance?.windowsOnAllDisplays?.indexOfKey(displayId)?.let { it >= 0 } == true
    }

    // --- Tasks (IActivityTaskManager, hidden) ------------------------------------------------------------------

    private val atm: Any? by lazy {
        try {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        } catch (e: Exception) {
            Log.w(TAG, "no ActivityTaskManager service", e)
            null
        }
    }

    /** Task ids of root tasks on [displayId], topmost first, paired with their top activity's package. */
    fun tasksOn(displayId: Int): List<Pair<Int, String?>> = try {
        val infos = atm!!.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType).invoke(atm, displayId) as List<*>
        infos.mapNotNull { info ->
            info ?: return@mapNotNull null
            val cls = Class.forName("android.app.TaskInfo")
            val taskId = cls.getField("taskId").getInt(info)
            val top = cls.getField("topActivity").get(info) as android.content.ComponentName?
            taskId to top?.packageName
        }
    } catch (e: Exception) {
        Log.w(TAG, "getAllRootTaskInfosOnDisplay failed", e)
        emptyList()
    }

    /**
     * Frees hidden displays left behind by an earlier process of this app. DisplayManager drops a virtual display
     * when its owner dies, but WindowManager keeps the display until its last root task is gone. Apps that embed
     * activities (Settings) leave an empty task there: their activities finish, but the TaskFragments they organize
     * stay until the organizer removes them, which it never does for a removed display. Force-stopping the app
     * makes WindowManager drop its TaskFragments, the task, and then the display. Skipped for an app that still has a
     * task on a live display, so this never closes something the user has open.
     */
    private fun releaseStaleDisplays() {
        try {
            val live = context.getSystemService(DisplayManager::class.java).displays.map { it.displayId }.toSet()
            val infos = atm!!.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
            val cls = Class.forName("android.app.TaskInfo")
            val tasks = infos.filterNotNull().map { info ->
                val intent = cls.getField("baseIntent").get(info) as Intent?
                val pkg = intent?.component?.packageName ?: (cls.getField("realActivity").get(info) as android.content.ComponentName?)?.packageName
                cls.getField("displayId").getInt(info) to pkg
            }
            val (stale, current) = tasks.partition { it.first !in live }
            val inUse = current.mapNotNull { it.second }.toSet()
            val packages = stale.mapNotNull { it.second }.toSet() - inUse - context.packageName
            if (stale.isEmpty()) return
            Log.i(TAG, "stale displays ${stale.map { it.first }.distinct()}; force-stopping $packages")
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            for (pkg in packages) am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
        } catch (e: Exception) {
            Log.w(TAG, "could not release stale displays", e)
        }
    }

    private fun topTaskOf(pkg: String, displayId: Int): Int? = tasksOn(displayId).firstOrNull { it.second == pkg }?.first

    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean = try {
        atm!!.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(atm, taskId, displayId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "moveRootTaskToDisplay($taskId, $displayId) failed", e)
        false
    }

    /** Gives the hidden display's top task input focus, so injected keys reach it. */
    fun focus(): Boolean {
        val task = tasksOn(id ?: return false).firstOrNull()?.first ?: return false
        return setFocusedTask(task)
    }

    /** Moves the hidden display's top task to the real screen; the agent stops tracking it. */
    fun bringToFront(): Boolean {
        val (task, pkg) = tasksOn(id ?: return false).firstOrNull() ?: return false
        currentPackage = null
        HiddenAudio.unmute(pkg)
        return moveTaskToDisplay(task, Display.DEFAULT_DISPLAY).also { refreshForegroundApp() }
    }

    // --- Input (InputManager.injectInputEvent, hidden; needs INJECT_EVENTS) ------------------------------------

    private val inputManager: Any? by lazy {
        try {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        } catch (e: Exception) {
            Log.w(TAG, "no InputManager", e)
            null
        }
    }

    /** [waitForFinish]: block until the target window has handled the event (InputManager mode WAIT_FOR_FINISH). */
    fun inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        inputManager!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(inputManager, event, if (waitForFinish) 2 else 0) as Boolean
    } catch (e: Exception) {
        Log.w(TAG, "injectInputEvent failed", e)
        false
    }

    /**
     * Keys go to the focused window, and only the top display has one, so the hidden display takes focus for the
     * key (synchronously delivered) and the chat gets it back afterwards, unless the viewer is open.
     */
    fun injectKey(keyCode: Int, displayId: Int): Boolean {
        deliveringKey = true
        try {
            focus()
            Thread.sleep(100) // let the focus change reach input before the key does
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            return inject(down, displayId, waitForFinish = true) && inject(up, displayId, waitForFinish = true)
        } finally {
            deliveringKey = false
            restoreFocus()
        }
    }

    /**
     * A tap (same start and end) or swipe as injected touch events, for when accessibility gestures don't land.
     * Marked FLAG_NO_FOCUS_CHANGE (honoured for trusted injections), so the touch doesn't move focus to the hidden
     * display.
     */
    fun injectStroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, displayId: Int): Boolean {
        if (displayId == id) {
            // Where the stroke starts, for the ember ring over the work display's thumbnail.
            val kind = if (x1 != x2 || y1 != y2) "swipe" else if (durationMs >= 300) "long_press" else "tap"
            Evidence.tap(TapMark(x1, y1, size.x, size.y, SystemClock.elapsedRealtime(), kind))
        }
        val down = SystemClock.uptimeMillis()
        val pointer = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        fun ev(action: Int, t: Long, x: Float, y: Float): MotionEvent {
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
            return MotionEvent.obtain(
                down, t, action, 1, pointer, coords, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, FLAG_NO_FOCUS_CHANGE,
            )
        }
        if (!inject(ev(MotionEvent.ACTION_DOWN, down, x1, y1), displayId)) return false
        val steps = if (x1 == x2 && y1 == y2) 0 else 10
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            Thread.sleep(durationMs / steps)
            inject(ev(MotionEvent.ACTION_MOVE, down + durationMs * i / steps, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f), displayId)
        }
        if (steps == 0) Thread.sleep(durationMs)
        return inject(ev(MotionEvent.ACTION_UP, down + durationMs, x2, y2), displayId)
    }

    /** Starts [intent] on the hidden display without waiting for a window (e.g. AlarmClock intents with SKIP_UI). */
    fun start(intent: Intent) {
        markActivity()
        val target = ensure()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target == null) {
            context.startActivity(intent)
        } else {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
            restoreFocus()
        }
    }

    /**
     * A new (or emptied) display has nothing on it, so the viewer would show black: start its app grid. Accessibility
     * lists a display only once it has windows, so "no app window" covers both.
     */
    fun showHomeIfEmpty() {
        val displayId = id ?: return
        if (A11yService.instance != null && topAppWindow(displayId) == null) goHome()
    }

    /** Starts [WorkHomeActivity] on the hidden display: its "home screen" (it has no launcher). */
    fun goHome() {
        val target = id ?: return
        currentPackage = null
        context.startActivity(
            Intent(context, WorkHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle(),
        )
        restoreFocus()
    }
}

/**
 * The hidden display's home screen (it has no launcher): a grid of the phone's apps, so the user can start one when
 * they use the work screen themselves (see WorkViewer). Apps open on the same display.
 */
class WorkHomeActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        setContent { com.or1.agent.ui.AppGrid(onOpen = ::open) }
    }

    private fun open(intent: Intent) {
        val displayId = window.decorView.display?.displayId ?: return
        startActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
        )
    }
}
