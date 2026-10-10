package com.or1.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemoryNotesTest {
    @Test fun sameNoteDespiteCasePunctuationAndUserPrefix() {
        assertEquals(normalizeNote("The user prefers short replies and dark mode."), normalizeNote("User prefers short replies and dark mode"))
        assertEquals(normalizeNote("Prefers  tea"), normalizeNote("prefers tea!"))
    }

    @Test fun differentFactsStayDifferent() =
        assertNotEquals(normalizeNote("User prefers short replies"), normalizeNote("User prefers dark mode"))
}
