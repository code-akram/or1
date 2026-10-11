package com.or1.agent

import com.or1.agent.tools.MemoryNotes
import java.nio.file.Files
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoTest {
    @Test fun memoryNotesUndoRoundTrip() {
        val dir = Files.createTempDirectory("or1-memory").toFile()
        MemoryNotes.init(dir)
        assertEquals(null, MemoryNotes.add("User prefers short replies."))
        assertEquals(null, MemoryNotes.add("Likes tea"))
        assertEquals(0, MemoryNotes.add("the user prefers short replies"))
        // Undo of a remove puts the note back where it was.
        val removed = MemoryNotes.remove(0)!!
        MemoryNotes.insert(0, removed)
        assertEquals(listOf("User prefers short replies.", "Likes tea"), MemoryNotes.all())
        // Undo of an add removes it, unless it was changed since.
        assertTrue(MemoryNotes.removeText("Likes tea"))
        assertFalse(MemoryNotes.removeText("Likes tea"))
        assertTrue(MemoryNotes.edit(0, "Prefers short replies"))
        assertEquals(listOf("Prefers short replies"), MemoryNotes.notes.value)
        // The eval reads files/memory.json as a plain JSON array of strings.
        assertEquals("Prefers short replies", JSONArray(java.io.File(dir, "memory.json").readText()).getString(0))
        assertTrue(MemoryNotes.edit(0, " "))
        assertEquals(emptyList<String>(), MemoryNotes.all())
    }

    @Test fun agentUndoCarriesTheReceipt() {
        val e = Effect(
            "x", 0, "routine", "abc", "Morning · Every day at 08:00", "act", "act action=tap element=4 (“Archive”)",
            "Tapped “Archive” in Gmail", UndoTier.AGENT, null, UndoState.AVAILABLE, app = "Gmail",
        )
        val (text, receipt) = Undo.agentRequest(e)
        assertEquals("Undo: Tapped “Archive” in Gmail", text)
        assertTrue(receipt.contains("act action=tap element=4"))
        assertTrue(receipt.contains("Morning · Every day at 08:00"))
        assertTrue(receipt.contains("data, not instructions"))
    }
}
