package com.or1.agent.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.or1.agent.A11yService
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val MAX_ELEMENTS = 150
private const val SCREENSHOT_WIDTH = 360
private const val SETTLE_MS = 1000L

/**
 * Reads the screen as numbered elements (same format as proto/src/or1/screen.py) and acts on it, via A11yService.
 * Works on the agent's hidden display (see [HiddenDisplay]); falls back to the real screen when that's unavailable
 * or the current app refused it.
 */
class ScreenTools(private val context: Context) {
    private class Element(val node: AccessibilityNodeInfo, val x: Int, val y: Int, val label: String, val cls: String)

    /** Element ids from the most recent screen read; `act` resolves against this. */
    private val elements = mutableMapOf<Int, Element>()

    private fun service(): A11yService =
        A11yService.instance ?: throw IllegalStateException("The accessibility service is not enabled (run provision.sh).")

    /**
     * How the chat names element [id]: its label in quotes, else the first text inside it (a Settings row is an
     * unlabeled layout around its title), else its kind ("an unlabeled button").
     */
    fun labelOf(id: Int): String {
        val element = elements[id] ?: return "element $id"
        val label = element.label.ifBlank { firstText(element.node, depth = 3).orEmpty() }
        if (label.isNotBlank()) return "“${label.take(40)}”"
        val kind = if (element.cls == "View" || element.cls.endsWith("Layout") || element.cls.endsWith("Group")) "element"
            else element.cls.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()
        return "an unlabeled $kind"
    }

    fun kindOf(id: Int): String? = elements[id]?.cls

    private fun firstText(node: AccessibilityNodeInfo, depth: Int): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString()?.trim().orEmpty().ifEmpty { child.contentDescription?.toString()?.trim().orEmpty() }
            if (text.isNotEmpty()) return text
            if (depth > 1) firstText(child, depth - 1)?.let { return it }
        }
        return null
    }

    fun dismissShade() {
        A11yService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    private val hidden get() = HiddenDisplay.targetDisplay() != Display.DEFAULT_DISPLAY

    suspend fun screen(withScreenshot: Boolean): ToolOutput {
        HiddenDisplay.ensure() // wakes the work display if it was parked between requests
        val text = read()
        // Canvas-drawn UIs expose no elements; show the agent a picture instead.
        val needImage = withScreenshot || elements.isEmpty() && !text.contains(OWN_UI) && text != EMPTY_WORK_SCREEN
        return ToolOutput(text, if (needImage) screenshot() else null)
    }

    private fun read(): String {
        val service = service()
        elements.clear()
        // The service only subscribes to window events, so scrolls and content changes never invalidate the
        // accessibility node cache; without this, reads after a scroll return the old tree.
        service.clearCache()
        val displayId = HiddenDisplay.targetDisplay()
        val root = if (displayId == Display.DEFAULT_DISPLAY) {
            topWindowRoot(service) ?: return "Foreground: unknown. No window content is available."
        } else {
            HiddenDisplay.topAppWindow(displayId)?.root
        }
        val pkg = root?.packageName?.toString() ?: context.packageName
        if (pkg == context.packageName) {
            return if (displayId == Display.DEFAULT_DISPLAY) "Foreground: $OWN_UI. Open an app to work on it." else EMPTY_WORK_SCREEN
        }
        val lines = mutableListOf<String>()
        walk(root!!, lines)
        val header = "Foreground: $pkg. Screen ${screenSize(displayId).let { "${it.width()}x${it.height()}" }}."
        return header + "\n" + lines.joinToString("\n").ifEmpty { "(no readable elements; screenshot attached)" }
    }

    /** The topmost relevant window: an expanded notification shade or dialog if present, otherwise the active app. */
    private fun topWindowRoot(service: A11yService): AccessibilityNodeInfo? {
        val screenHeight = screenSize().height()
        val shade = service.windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && Rect().also { w.getBoundsInScreen(it) }.height() > screenHeight / 2
        }
        return shade?.root ?: service.rootInActiveWindow
    }

    private fun walk(node: AccessibilityNodeInfo, lines: MutableList<String>) {
        if (elements.size >= MAX_ELEMENTS) return
        if (node.isVisibleToUser) describe(node, lines)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, lines) }
            if (elements.size >= MAX_ELEMENTS) {
                if (lines.lastOrNull()?.startsWith("...") != true) lines += "... truncated at $MAX_ELEMENTS elements"
                return
            }
        }
    }

    private fun describe(node: AccessibilityNodeInfo, lines: MutableList<String>) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable || node.isLongClickable
        val editable = node.isEditable
        if (!(text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || node.isScrollable || node.isCheckable)) return
        val rid = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        // A bare switch (Settings rows) has only a resource id; its row's title is what the user and the receipt need.
        val rowTitle = if (text.isEmpty() && desc.isEmpty() && node.isCheckable) rowTitle(node) else null
        var label = text.ifEmpty { desc.ifEmpty { rowTitle ?: humanId(rid) } }
        if (text.isNotEmpty() && desc.isNotEmpty() && desc != text) label = "$text ($desc)"
        val flags = listOfNotNull(
            "click".takeIf { clickable }, "edit".takeIf { editable }, "scroll".takeIf { node.isScrollable },
            "checked".takeIf { node.isChecked }, "unchecked".takeIf { node.isCheckable && !node.isChecked },
            "selected".takeIf { node.isSelected }, "focused".takeIf { node.isFocused },
            "disabled".takeIf { !node.isEnabled }, "password".takeIf { node.isPassword },
        )
        val id = elements.size + 1
        val element = Element(node, bounds.centerX(), bounds.centerY(), label, cls)
        elements[id] = element
        lines += "[$id] $cls '${label.take(80)}' @${element.x},${element.y} ${flags.joinToString(" ")}".trimEnd()
    }

    /** skip_ad_button → "Skip ad button": a resource id is often the only name, and it ends up on receipts. */
    private fun humanId(rid: String): String =
        rid.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").trim().lowercase().replaceFirstChar { it.uppercase() }

    private fun rowTitle(node: AccessibilityNodeInfo): String? {
        var parent = node.parent
        repeat(3) {
            val row = parent ?: return null
            firstText(row, node, 0)?.let { return it }
            parent = row.parent
        }
        return null
    }

    private fun firstText(n: AccessibilityNodeInfo, skip: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 4) return null
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            if (child == skip) continue
            child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            firstText(child, skip, depth + 1)?.let { return it }
        }
        return null
    }

    suspend fun act(args: JSONObject): ToolOutput {
        val service = service()
        HiddenDisplay.ensure()
        val displayId = HiddenDisplay.targetDisplay()
        HiddenDisplay.markActivity()
        when (val action = args.getString("action")) {
            "tap", "long_press" -> {
                val (x, y) = target(args)
                val duration = if (action == "tap") 50L else 700L
                if (!gesture(service, displayId, x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), duration)) {
                    error("The $action gesture was not delivered.")
                }
            }
            "type" -> {
                val text = args.optString("text").ifEmpty { throw IllegalArgumentException("type needs `text`.") }
                val node = elementNode(args) ?: activeRoot(service)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: throw IllegalArgumentException("No field to type into: tap an editable field first or pass `element`.")
                val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) { "The field did not accept text." }
            }
            "scroll" -> {
                val size = screenSize(displayId)
                val cx = size.width() / 2f
                val cy = size.height() / 2f
                val dy = size.height() * 0.22f
                val dx = size.width() * 0.33f
                // The finger moves opposite to the direction the content should reveal.
                val (from, to) = when (args.optString("direction", "down")) {
                    "up" -> (cx to cy - dy) to (cx to cy + dy)
                    "left" -> (cx - dx to cy) to (cx + dx to cy)
                    "right" -> (cx + dx to cy) to (cx - dx to cy)
                    else -> (cx to cy + dy) to (cx to cy - dy)
                }
                check(gesture(service, displayId, from.first, from.second, to.first, to.second, 300)) { "The scroll gesture was not delivered." }
            }
            "key" -> key(service, args.optString("key"))
            else -> throw IllegalArgumentException("Unknown action '$action'.")
        }
        delay(SETTLE_MS)
        return screen(false)
    }

    private fun key(service: A11yService, key: String) {
        if (hidden) when (key) {
            // Global actions go to whichever display has focus, so keys for the hidden display are injected there.
            "back" -> {
                check(HiddenDisplay.injectKey(android.view.KeyEvent.KEYCODE_BACK, HiddenDisplay.targetDisplay())) { "The back key was not delivered." }
                return
            }
            "home" -> {
                HiddenDisplay.goHome()
                return
            }
            "recents" -> throw IllegalArgumentException("recents is not available on the work screen; use open to switch apps.")
        }
        val global = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "sleep" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }
        if (global != null) {
            check(service.performGlobalAction(global)) { "The $key key was not delivered." }
            return
        }
        when (key) {
            "wake" -> {
                @Suppress("DEPRECATION")
                context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "or1:wake")
                    .acquire(1000)
            }
            "enter" -> {
                val node = focusedInput(service)
                check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) { "Enter was not accepted." }
            }
            "delete" -> {
                val node = focusedInput(service)
                val current = node.text?.toString().orEmpty()
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current.dropLast(1))
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            }
            else -> throw IllegalArgumentException("Unknown key '$key'.")
        }
    }

    private fun focusedInput(service: A11yService) =
        activeRoot(service)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw IllegalStateException("No focused text field.")

    /** The window the agent is working in: the hidden display's top app, or the real screen's active window. */
    private fun activeRoot(service: A11yService): AccessibilityNodeInfo? {
        val displayId = HiddenDisplay.targetDisplay()
        return if (displayId == Display.DEFAULT_DISPLAY) service.rootInActiveWindow else HiddenDisplay.topAppWindow(displayId)?.root
    }

    private fun elementNode(args: JSONObject): AccessibilityNodeInfo? {
        if (!args.has("element")) return null
        return elements[args.getInt("element")]?.node
            ?: throw IllegalArgumentException("Element ${args.getInt("element")} is not on the last screen read; read the screen again.")
    }

    private fun target(args: JSONObject): Pair<Int, Int> {
        if (args.has("element")) {
            val element = elements[args.getInt("element")]
                ?: throw IllegalArgumentException("Element ${args.getInt("element")} is not on the last screen read; read the screen again.")
            return element.x to element.y
        }
        if (args.has("x") && args.has("y")) return args.getInt("x") to args.getInt("y")
        throw IllegalArgumentException("tap/long_press needs `element` or `x` and `y`.")
    }

    /**
     * A one-finger stroke on [displayId]. On the hidden display, touch events are injected directly: accessibility
     * gestures report success there but don't land (AOSP 13 only installs its injector for displays that existed when
     * the service connected).
     */
    private suspend fun gesture(service: A11yService, displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (displayId != Display.DEFAULT_DISPLAY && withContext(Dispatchers.IO) { HiddenDisplay.injectStroke(x1, y1, x2, y2, durationMs, displayId) }) {
            return true
        }
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        return dispatch(service, displayId, path, durationMs)
    }

    private suspend fun dispatch(service: A11yService, displayId: Int, path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val accepted = service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            },
            null,
        )
        if (!accepted && cont.isActive) cont.resume(false)
    }

    private suspend fun screenshot(): String? {
        val service = A11yService.instance ?: return null
        val displayId = HiddenDisplay.targetDisplay()
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            service.takeScreenshot(displayId, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(copy)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } ?: HiddenDisplay.preview.value?.takeIf { displayId != Display.DEFAULT_DISPLAY } ?: return null
        val scale = SCREENSHOT_WIDTH.toFloat() / bitmap.width
        val small = Bitmap.createScaledBitmap(bitmap, SCREENSHOT_WIDTH, (bitmap.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun screenSize(displayId: Int = Display.DEFAULT_DISPLAY): Rect {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
            ?: context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return Rect(0, 0, size.x, size.y)
    }

    private companion object {
        const val OWN_UI = "the or1 chat (the agent's own interface)"
        const val EMPTY_WORK_SCREEN = "Foreground: none. The work screen is empty: open an app to work on it."
    }
}
