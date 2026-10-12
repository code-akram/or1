package com.or1.agent.tools

import android.content.Context
import org.json.JSONObject

/**
 * A tool result: text, optionally with a screenshot (base64 JPEG). `undo` is how to reverse what a write did, for the
 * effects log ([com.or1.agent.Effects], [com.or1.agent.Undo]); it never goes to the model. Null when nothing changed.
 */
data class ToolOutput(val text: String, val jpegBase64: String? = null, val undo: JSONObject? = null)

/** Dispatches tool calls to their implementations. Schemas live in assets/agent.json. */
class Tools(context: Context) {
    internal val system = SystemTools(context)
    private val screen = ScreenTools(context)
    private val shell = ShellTool()
    private val memory = MemoryTool(context)
    internal val calendar = CalendarTool(context)
    internal val contacts = ContactsTool(context)
    private val media = MediaTool(context)
    private val email = EmailTool(system)

    fun elementLabel(id: Int): String = screen.labelOf(id)

    /** The class of element [id] from the last screen read ("Switch", "Button"), if known. */
    fun elementKind(id: Int): String? = screen.kindOf(id)

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
        "device" -> if (args.optString("action") == "get") ToolOutput(system.deviceGet()) else system.deviceSet(args)
        "clock" -> system.clock(args)
        "notifications" -> ToolOutput(system.notifications())
        "apps" -> ToolOutput(system.apps(args))
        "shell" -> ToolOutput(shell.run(args.getString("command")))
        "memory" -> memory.run(args)
        "calendar" -> calendar.run(args)
        "contacts" -> contacts.run(args)
        "routines" -> com.or1.agent.Routines.tool(args)
        "media" -> media.run(args, screen)
        "email" -> email.run(args, screen)
        else -> throw IllegalArgumentException("Unknown tool '$name'.")
    }
}
