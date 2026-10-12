package com.or1.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInterruptTest {
    @Test fun wakePhraseAsRecognisersWriteIt() {
        listOf("hey or one", "Hey, OR1", "okay or 1 set a timer", "hi oar one", "hey all one", "Hey or-one what's next").forEach {
            assertTrue(it, WakePhrase.heard(it))
        }
        listOf("one or two", "hey everyone", "or one more thing", "I want this or 1 of those").forEach {
            assertFalse(it, WakePhrase.heard(it))
        }
    }

    @Test fun wakePhraseIsStripped() {
        assertEquals("set a timer for 5 minutes", WakePhrase.strip("Hey or one, set a timer for 5 minutes"))
        assertEquals("", WakePhrase.strip("hey or1"))
        assertEquals("what's the weather", WakePhrase.strip("what's the weather"))
    }

    @Test fun anEchoOfTheReplyIsNotABargeIn() {
        val spoken = "Timer set for three minutes. Anything else?"
        assertFalse(Barge.isUser("timer set for 3 minutes", spoken))
        assertFalse(Barge.isUser("anything else", spoken))
        assertTrue(Barge.isUser("stop", spoken))
        assertTrue(Barge.isUser("make it five minutes instead", spoken))
        assertTrue(Barge.isUser("wait", spoken))
    }

    @Test fun theEchoIsTakenOffTheFront() {
        val spoken = "Timer set for three minutes."
        assertEquals("cancel it", Barge.userPart("timer set for three minutes cancel it", spoken))
        // One telling word in common isn't an echo: the user's own request stays whole.
        assertEquals("set a timer for five", Barge.userPart("set a timer for five", spoken))
        assertEquals("timer set for three minutes", Barge.userPart("timer set for three minutes", spoken))
    }
}
