package com.or1.agent.tools

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.accessibility.AccessibilityWindowInfo
import com.or1.agent.A11yService
import com.or1.agent.Evidence
import com.or1.agent.TapMark
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The off-screen display the agent works on (a [VirtualScreen]), so apps it opens never cover the chat: waking and
 * parking it, which display tools act on, focus (handing it back to the chat), the leak guard, launching apps there,
 * tasks and input. Hidden APIs are reached by reflection; platform-signed apps are exempt from the hidden-API blocklist.
 */
object HiddenDisplay {
    private const val TAG = "or1-display"

    const val IME_LOCAL = 0
    /**
     * The keyboard belongs to the real screen. Not HIDE: when the hidden display briefly takes focus (for a back
     * key), HIDE unbinds the IME from every display, and it doesn't re-bind when the chat gets focus back.
     */
    const val IME_FALLBACK = 1

    private lateinit var context: Context
    private val work = VirtualScreen("or1-work")
    private val handler get() = work.handler

    /** Set when creation failed: the agent works on the real screen for the rest of this process. */
    @Volatile
    var unavailable = false
        private set

    /** Packages that refused the hidden display this session; they run on the real screen. */
    private val refused = mutableSetOf<String>()

    /** The package the agent is working in, so tools know which display it's on. */
    @Volatile
    private var currentPackage: String? = null
        set(value) {
            field = value
            _agentPackage.value = value
        }

    private val _agentPackage = MutableStateFlow<String?>(null)
    /** [currentPackage], for the UI: when it's the app on the stage, the evidence strip follows the stage. */
    val agentPackage: StateFlow<String?> = _agentPackage

    /** Downscaled frames of the work display, at most every 500 ms. */
    val preview: StateFlow<Bitmap?> get() = work.preview

    private val _inUse = MutableStateFlow(false)
    /**
     * True from the moment a tool wakes the display ([ensure]) until the request ends ([park]) or the display goes. With
     * AgentHub's busy flags this is "the agent is using the work display right now" (AgentHub.usingWorkDisplay).
     */
    val inUse: StateFlow<Boolean> = _inUse

    private val _foregroundApp = MutableStateFlow<String?>(null)
    /** The label of the app in front on the work display (null when it's empty or showing or1's app grid). */
    val foregroundApp: StateFlow<String?> = _foregroundApp

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    /** The package behind [foregroundApp] (the live media card follows its app's frames only while it's in front). */
    val foregroundPackage: StateFlow<String?> = _foregroundPackage

    const val FAST_PREVIEW_MS = VirtualScreen.FAST_PREVIEW_MS

    /** Converts frames every [FAST_PREVIEW_MS] instead of every 500 ms; the live media card sets it while it shows playback. */
    fun fastPreview(fast: Boolean) = work.fastPreview(fast)

    val viewerAttached get() = work.viewerAttached

    fun init(context: Context) {
        this.context = context.applicationContext
        HiddenAudio.init(this.context)
    }

    val id: Int? get() = work.id

    /**
     * The display tools should act on: the stage while the agent works in the app living there ([com.or1.agent.Stage]),
     * else the work display, unless it's unavailable or the current app refused it (the real screen).
     */
    fun targetDisplay(): Int {
        stageTarget()?.let { return it }
        val hidden = id ?: return Display.DEFAULT_DISPLAY
        return if (currentPackage != null && currentPackage in refused) Display.DEFAULT_DISPLAY else hidden
    }

    private fun stageTarget(): Int? = com.or1.agent.Stage.id?.takeIf { com.or1.agent.Stage.owns(currentPackage) }

    /** The agent is working in the app on the stage (the evidence strip and "Watch" follow the stage). */
    fun onStage(): Boolean = stageTarget() != null

    /** The screen the agent works on, when it's one of or1's (frames, settling). */
    private fun active(): VirtualScreen = if (onStage()) com.or1.agent.Stage.screen else work

    /**
     * A request starts on the work display: the app on the stage is worked on only once the agent opens it (or a shell
     * command starts it) in this request.
     */
    fun beginRequest() {
        if (onStage()) currentPackage = null
        showAfterTurn = null
    }

    /**
     * The package the user asked to see (`open` with show): when the request ends, the viewer opens on it instead of
     * the work display parking under it. Set by the open tool, taken by AgentHub at the end of the request.
     */
    @Volatile
    var showAfterTurn: String? = null

    /** The agent is about to work in the stage's app (a shell `am start` of it): the tools act on the stage. */
    fun useStage(pkg: String) {
        currentPackage = pkg
        refreshForegroundApp()
    }

    /** The agent starts something on the work display (a shell `am start`): the tools act there again. */
    fun useWork() {
        if (onStage()) currentPackage = null
        refreshForegroundApp()
    }

    /** Creates the display if needed. Returns its id, or null if the agent should use the real screen. */
    @Synchronized
    fun ensure(): Int? {
        id?.let { unpark(); work.holdAwake(); _inUse.value = true; return it }
        if (unavailable) return null
        return try {
            Tasks.releaseStale(context)
            work.create(context) { reset() }.also {
                setImePolicy(IME_FALLBACK)
                work.holdAwake()
                _inUse.value = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not create the hidden display; using the real screen", e)
            unavailable = true
            null
        }
    }

    @Synchronized
    private fun reset() {
        work.release()
        _inUse.value = false
        _foregroundApp.value = null
        _foregroundPackage.value = null
    }

    /** Keyboard policy for the hidden display: FALLBACK while the agent works, LOCAL while the user is viewing it. */
    fun setImePolicy(policy: Int, displayId: Int = id ?: -1) {
        if (displayId < 0) return
        try {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, policy)
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayImePolicy failed", e)
        }
    }

    /**
     * Turns the display off between requests: its apps stop (AOSP puts an off display to sleep) until [unpark]. Media
     * the user asked for has moved to the stage by then ([com.or1.agent.Stage]), so it keeps playing.
     */
    @Synchronized
    fun park() {
        // The request is over either way; with the viewer open the user has the display.
        _inUse.value = false
        if (viewerAttached || id == null) return
        work.park()
        HiddenAudio.park()
    }

    @Synchronized
    private fun unpark() {
        if (!work.unpark()) return
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
        if (displayId != Display.DEFAULT_DISPLAY && displayId == com.or1.agent.Stage.id) {
            // The app on the stage opened a screen, which moved the stage on top with focus: hand it back too.
            handler.removeCallbacks(restoreFocusRunnable)
            handler.post(restoreFocusRunnable)
            return
        }
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
        val task = Tasks.topOf(pkg, Display.DEFAULT_DISPLAY) ?: return
        if (Tasks.move(task, target)) {
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
        if (viewerAttached || com.or1.agent.Stage.viewerAttached || id == null && com.or1.agent.Stage.id == null) return
        // Re-starting the real screen's top task (the chat, or an app the user brought to the front) goes through
        // the activity starter, which moves its display back on top without relaunching anything. The direct routes
        // don't work in 13: WMS.moveDisplayToTop isn't on the binder interface, and setFocusedTask/moveTaskToFront
        // return early when the task is already its display's top task.
        val intent = Tasks.topIntent(Display.DEFAULT_DISPLAY) ?: return
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

    /** Called after the leak guard moved a task, so the chat can come back to the front. */
    var onLeakMoved: (() -> Unit)? = null

    private val SYSTEM_SURFACES = setOf("com.android.systemui", "com.android.launcher3", "com.android.permissioncontroller")

    // --- Frames and viewer -------------------------------------------------------------------------------------------

    /**
     * The newest frame of the display, downscaled like [preview]: for evidence frames taken just before a committing
     * tap, which mustn't hold the tap up.
     */
    fun latestFrame(maxWaitMs: Long = 40): Bitmap? = active().latestFrame(maxWaitMs)

    /**
     * After an action: waits until the work display stops drawing, at most [maxMs] (see [VirtualScreen.settle]); the
     * whole [maxMs] on the real screen, whose frames we don't see.
     */
    suspend fun settle(maxMs: Long) {
        if (targetDisplay() == Display.DEFAULT_DISPLAY) kotlinx.coroutines.delay(maxMs) else active().settle(maxMs)
    }

    /** The full-screen viewer renders straight from the display; the reader takes over again when it closes. */
    @Synchronized
    fun attachViewer(surface: Surface) {
        work.attachViewer(surface)
        setImePolicy(IME_LOCAL)
        // The user is watching: let the app be heard.
        id?.let { HiddenAudio.unmute(topAppPackage(it)) }
    }

    @Synchronized
    fun detachViewer() {
        work.detachViewer()
        setImePolicy(IME_FALLBACK)
        // Quiet again, unless it plays what the user was watching (it moves to the stage: Stage.park).
        id?.let { topAppPackage(it) }?.takeIf { pkg -> com.or1.agent.NowState.media.value.none { it.pkg == pkg && it.playing } }
            ?.let { HiddenAudio.mute(it) }
        // Touching the work screen moved focus (and its display) to the top; give the chat its keyboard back.
        handler.post(restoreFocusRunnable)
    }

    private val foregroundRunnable = Runnable {
        val pkg = (if (onStage()) com.or1.agent.Stage.id else id)?.let { topAppPackage(it) }?.takeIf { it != context.packageName }
        _foregroundPackage.value = pkg
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
        val work = ensure()
        // The app living on the stage opens there, where it plays; the tools follow it.
        val opens = expectPackage ?: intent.data?.toString()?.let { com.or1.agent.Stage.resolve(it) }
        currentPackage = if (com.or1.agent.Stage.owns(opens)) opens else null
        val target = stageTarget() ?: work
        refreshForegroundApp()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target == null || expectPackage != null && expectPackage in refused) {
            currentPackage = expectPackage
            context.startActivity(intent)
            return Display.DEFAULT_DISPLAY
        }
        val before = Tasks.on(target).firstOrNull()?.second
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
        val task = pkg?.let { Tasks.topOf(it, Display.DEFAULT_DISPLAY) }
        if (task != null && Tasks.move(task, target) && waitForApp(target, pkg, before) != null) {
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

    /**
     * Waits for [pkg] (or any app other than [before]) to be the top task on [displayId] (up to 3 s), then for its
     * window (up to [WINDOW_MS] from the launch: a cold start of Gmail takes ~5.5 s to draw on this phone, and a screen
     * read before that finds nothing).
     */
    private suspend fun waitForApp(displayId: Int, pkg: String?, before: String?): String? {
        var landed: String? = null
        val start = SystemClock.uptimeMillis()
        while (true) {
            delay(200)
            val waited = SystemClock.uptimeMillis() - start
            // The task list is authoritative; accessibility windows lag behind it.
            val top = Tasks.on(displayId).firstOrNull()?.second ?: topAppPackage(displayId)
            if (top != null && top != context.packageName && (if (pkg != null) top == pkg else top != before)) {
                landed = top
                if (topAppPackage(displayId) == top) return top
            }
            if (landed == null && waited >= TASK_MS || waited >= WINDOW_MS) return landed
        }
    }

    /** The app whose task is on top of [displayId] while accessibility lists no window for it yet (still starting). */
    fun startingApp(displayId: Int): String? =
        Tasks.on(displayId).firstOrNull()?.second?.takeIf { it != context.packageName && topAppWindow(displayId) == null }

    private const val TASK_MS = 3_000L
    private const val WINDOW_MS = 8_000L

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

    /** Gives the hidden display's top task input focus, so injected keys reach it. */
    fun focus(displayId: Int = targetDisplay()): Boolean {
        val task = Tasks.on(displayId).firstOrNull()?.first ?: return false
        return Tasks.setFocused(task)
    }

    /** Moves the hidden display's top task to the real screen; the agent stops tracking it. */
    fun bringToFront(): Boolean {
        val (task, pkg) = Tasks.on(id ?: return false).firstOrNull() ?: return false
        currentPackage = null
        HiddenAudio.unmute(pkg)
        return Tasks.move(task, Display.DEFAULT_DISPLAY).also { refreshForegroundApp() }
    }

    // --- Input (InputManager.injectInputEvent, hidden; needs INJECT_EVENTS) ------------------------------------

    /**
     * Keys go to the focused window, and only the top display has one, so the hidden display takes focus for the
     * key (synchronously delivered) and the chat gets it back afterwards, unless the viewer is open.
     */
    fun injectKey(keyCode: Int, displayId: Int): Boolean {
        deliveringKey = true
        try {
            focus(displayId)
            Thread.sleep(100) // let the focus change reach input before the key does
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            return Input.inject(down, displayId, waitForFinish = true) && Input.inject(up, displayId, waitForFinish = true)
        } finally {
            deliveringKey = false
            restoreFocus()
        }
    }

    /** A tap or swipe as injected touch events ([Input.stroke]), marked for the work display's thumbnail. */
    fun injectStroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, displayId: Int): Boolean {
        if (displayId == id) {
            // Where the stroke starts, for the ember ring over the work display's thumbnail.
            val kind = if (x1 != x2 || y1 != y2) "swipe" else if (durationMs >= 300) "long_press" else "tap"
            Evidence.tap(TapMark(x1, y1, work.size.x, work.size.y, SystemClock.elapsedRealtime(), kind))
        }
        return Input.stroke(x1, y1, x2, y2, durationMs, displayId)
    }

    /** Starts [intent] on the hidden display without waiting for a window (e.g. AlarmClock intents with SKIP_UI). */
    fun start(intent: Intent) {
        markActivity()
        useWork()
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
     * A new (or emptied) display has nothing on it, so the viewer would show black: start its app grid. Asks the task
     * list, not accessibility: a display that was just woken lists no windows for a moment, and the grid would cover
     * the app the user came to see.
     */
    fun showHomeIfEmpty() {
        val displayId = id ?: return
        if (Tasks.on(displayId).isEmpty()) goHome()
    }

    /** Starts [WorkHomeActivity] on the hidden display: its "home screen" (it has no launcher). */
    fun goHome() {
        val target = id ?: return
        currentPackage = null
        refreshForegroundApp()
        context.startActivity(
            Intent(context, WorkHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle(),
        )
        restoreFocus()
    }
}
