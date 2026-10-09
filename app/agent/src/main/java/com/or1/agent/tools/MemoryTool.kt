package com.or1.agent.tools

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Long-term notes about the user, kept across conversations. */
class MemoryTool(context: Context) {
    private val file = File(context.filesDir, "memory.json")

    fun run(args: JSONObject): String {
        val notes = if (file.exists()) JSONArray(file.readText()) else JSONArray()
        when (val action = args.getString("action")) {
            "view" -> Unit
            "add" -> notes.put(args.optString("text").ifBlank { throw IllegalArgumentException("add needs `text`.") })
            "remove" -> {
                val i = args.optInt("index", -1)
                require(i in 0 until notes.length()) { "remove needs a valid `index` (see view)." }
                notes.remove(i)
            }
            else -> throw IllegalArgumentException("Unknown action '$action'.")
        }
        if (args.getString("action") != "view") file.writeText(notes.toString(1))
        if (notes.length() == 0) return "No notes yet."
        return (0 until notes.length()).joinToString("\n") { "$it. ${notes.getString(it)}" }
    }
}
