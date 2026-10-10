package com.or1.agent.tools

import android.content.Context
import org.json.JSONObject

/** A tool result: text, optionally with a screenshot (base64 JPEG). */
data class ToolOutput(val text: String, val jpegBase64: String? = null)

val NAVIGATION_KEYS = setOf("back", "home", "recents", "wake")

/** Dispatches tool calls to their implementations. Schemas live in assets/agent.json. */
class Tools(context: Context) {
    private val system = SystemTools(context)
    private val screen = ScreenTools(context)
    private val shell = ShellTool()
    private val memory = MemoryTool(context)
    private val calendar = CalendarTool(context)
    private val contacts = ContactsTool(context)

    /**
     * Reads and navigation run freely; anything that changes state needs the user's OK. Same rules as proto.
     * `open` with a URI is gated too: a URI can carry data out (https://…?q=<notes>) if untrusted text steers the
     * agent, and some act on their own; opening an app or a settings page can't. See also AgentHub.mustApprove.
     */
    fun needsApproval(name: String, args: JSONObject): Boolean = when (name) {
        "screen", "notifications", "apps", "memory" -> false
        "open" -> args.optString("uri").isNotBlank()
        "device" -> args.optString("action") == "set"
        "clock" -> args.optString("action") != "next_alarm"
        "calendar", "routines" -> args.optString("action") != "list"
        "contacts" -> args.optString("action") != "find"
        "act" -> {
            val action = args.optString("action")
            !(action == "scroll" || (action == "key" && args.optString("key") in NAVIGATION_KEYS))
        }
        else -> true
    }

    fun elementLabel(id: Int): String = screen.labelOf(id)

    /** One-line technical summary of a call (tool and arguments), shown under the plain-language label. */
    fun describe(name: String, args: JSONObject): String {
        val parts = args.keys().asSequence().map { key ->
            val value = args.get(key)
            if (name == "act" && key == "element") "element=$value (${screen.labelOf(args.optInt(key))})" else "$key=$value"
        }
        return (listOf(name) + parts.toList()).joinToString(" ")
    }

    suspend fun run(name: String, args: JSONObject): ToolOutput = when (name) {
        "screen" -> screen.screen(args.optBoolean("screenshot"))
        "act" -> screen.act(args)
        "open" -> system.open(args, screen)
        "device" -> ToolOutput(if (args.optString("action") == "get") system.deviceGet() else system.deviceSet(args))
        "clock" -> ToolOutput(system.clock(args))
        "notifications" -> ToolOutput(system.notifications())
        "apps" -> ToolOutput(system.apps(args))
        "shell" -> ToolOutput(shell.run(args.getString("command")))
        "memory" -> ToolOutput(memory.run(args))
        "calendar" -> ToolOutput(calendar.run(args))
        "contacts" -> ToolOutput(contacts.run(args))
        "routines" -> ToolOutput(com.or1.agent.Routines.tool(args))
        else -> throw IllegalArgumentException("Unknown tool '$name'.")
    }
}
