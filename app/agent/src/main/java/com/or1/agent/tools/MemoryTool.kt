package com.or1.agent.tools

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Long-term notes about the user, kept across conversations (the model's side of [MemoryNotes]). */
class MemoryTool(context: Context) {
    init {
        MemoryNotes.init(context.filesDir)
    }

    fun run(args: JSONObject): ToolOutput {
        var undo: JSONObject? = null
        when (val action = args.getString("action")) {
            "view" -> Unit
            "add" -> {
                val text = args.optString("text").ifBlank { throw IllegalArgumentException("add needs `text`.") }
                val existing = MemoryNotes.add(text)
                if (existing != null) return ToolOutput("Already noted as $existing. ${MemoryNotes.all()[existing]}")
                undo = JSONObject().put("op", "memory_remove").put("text", text)
            }
            "remove" -> {
                val i = args.optInt("index", -1)
                val removed = MemoryNotes.remove(i) ?: throw IllegalArgumentException("remove needs a valid `index` (see view).")
                undo = JSONObject().put("op", "memory_restore").put("text", removed).put("index", i)
            }
            else -> throw IllegalArgumentException("Unknown action '$action'.")
        }
        val notes = MemoryNotes.all()
        if (notes.isEmpty()) return ToolOutput("No notes yet.", undo = undo)
        return ToolOutput(notes.withIndex().joinToString("\n") { (i, note) -> "$i. $note" }, undo = undo)
    }
}

/**
 * The memory notes in files/memory.json (a JSON array of strings, which the eval reads), shared by the memory tool, the
 * "What or1 knows" screen and undo. Indexes are positions in the list, as the tool shows them.
 */
object MemoryNotes {
    private var file: File? = null
    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes

    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        file = File(dir, "memory.json")
        _notes.value = read()
    }

    @Synchronized
    fun all(): List<String> = read().also { _notes.value = it }

    /** Adds [text] unless the same note is there; returns that note's index if it was. */
    @Synchronized
    fun add(text: String): Int? {
        val notes = read()
        notes.indexOfFirst { sameNote(it, text) }.takeIf { it >= 0 }?.let { return it }
        write(notes + text)
        return null
    }

    /** Removes the note at [index]; returns it, or null if there is none. */
    @Synchronized
    fun remove(index: Int): String? {
        val notes = read()
        if (index !in notes.indices) return null
        write(notes.filterIndexed { i, _ -> i != index })
        return notes[index]
    }

    /** Replaces the note at [index] (blank removes it). False if there is no such note. */
    @Synchronized
    fun edit(index: Int, text: String): Boolean {
        val notes = read()
        if (index !in notes.indices) return false
        val t = text.trim()
        write(if (t.isEmpty()) notes.filterIndexed { i, _ -> i != index } else notes.mapIndexed { i, n -> if (i == index) t else n })
        return true
    }

    /** Removes the note equal to [text] (undo of an add). False if it was edited or removed since. */
    @Synchronized
    fun removeText(text: String): Boolean {
        val notes = read()
        val i = notes.indexOfFirst { it == text }.takeIf { it >= 0 } ?: notes.indexOfFirst { sameNote(it, text) }
        if (i < 0) return false
        write(notes.filterIndexed { j, _ -> j != i })
        return true
    }

    /** Puts [text] back at [index] (undo of a remove); nothing if the same note is already there. */
    @Synchronized
    fun insert(index: Int, text: String) {
        val notes = read()
        if (notes.any { sameNote(it, text) }) return
        write(notes.toMutableList().apply { add(index.coerceIn(0, size), text) })
    }

    private fun read(): List<String> {
        val f = file ?: error("Memory isn't set up.")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun write(notes: List<String>) {
        file!!.writeText(JSONArray(notes).toString(1))
        _notes.value = notes
    }

    private fun sameNote(a: String, b: String) = normalizeNote(a) == normalizeNote(b)
}

/** "The user prefers X." and "User prefers x" are the same note. */
internal fun normalizeNote(note: String) =
    note.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().removePrefix("the ").removePrefix("user ").replace(Regex(" +"), " ")
