package com.or1.agent.ui

import com.or1.agent.ChatItem
import com.or1.agent.Effect
import com.or1.agent.UndoState
import com.or1.agent.UndoTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenSummaryTest {
    private var ids = 0L

    private fun effect(label: String, tier: UndoTier = UndoTier.LOCAL, state: UndoState = UndoState.AVAILABLE) =
        ChatItem.Effect(++ids, Effect("e$ids", 0, "chat", null, null, "device", "call", label, tier, null, state))

    private fun turn(reply: String?, vararg effects: ChatItem.Effect, infos: List<String> = emptyList()) = Turn(
        user = ChatItem.User(++ids, "do it"),
        steps = emptyList(),
        infos = infos.map { ChatItem.Info(++ids, it) },
        reply = reply?.let { ChatItem.Reply(++ids, it, 0.0, 0.0) },
        effects = effects.toList(),
    )

    @Test fun replyOnly() {
        assertEquals("It's 12 degrees and sunny.", spokenSummary(turn("It's 12 degrees and sunny.")))
    }

    @Test fun effectsTheReplyDoesntMentionComeFirst() {
        assertEquals("Done. Turned on Do Not Disturb. Anything else?", spokenSummary(turn("Anything else?", effect("Turned on Do Not Disturb"))))
    }

    @Test fun effectsTheReplyMentionsAreNotRepeated() {
        assertEquals("Do Not Disturb is on now.", spokenSummary(turn("Do Not Disturb is on now.", effect("Turned on Do Not Disturb"))))
        assertEquals("Your alarm is set for 7 AM.", spokenSummary(turn("Your alarm is set for 7 AM.", effect("Set alarm 07:00"))))
    }

    @Test fun irreversibleEffectsAreAlwaysSaidAndMarked() {
        assertEquals(
            "Done. Ran: rm notes.txt, irreversible. I ran rm notes.txt.",
            spokenSummary(turn("I ran rm notes.txt.", effect("Ran: rm notes.txt", UndoTier.NONE))),
        )
    }

    @Test fun undoneEffectsAreLeftOutAndManyAreCounted() {
        val t = turn(
            null,
            effect("Turned on Wi-Fi"), effect("Set media volume to 3"), effect("Turned on dark mode"),
            effect("Set brightness to 40%"), effect("Set screen timeout to 1 minute"), effect("Turned on Bluetooth", state = UndoState.UNDONE),
        )
        assertEquals(
            "Done. Turned on Wi-Fi. Set media volume to 3. Turned on dark mode. And 2 more changes.",
            spokenSummary(t),
        )
    }

    @Test fun noReplySaysWhy() {
        assertEquals("Stopped.", spokenSummary(turn(null, infos = listOf("Stopped."))))
        assertEquals("Sorry, I didn't get an answer.", spokenSummary(turn(null)))
    }

    @Test fun mentions() {
        assertTrue(Mentions.covered("Set a 10 minute timer", "Ten-minute timer, starting now: 10 minutes."))
        assertFalse(Mentions.covered("Added “Dentist” to the calendar", "Sure."))
        assertFalse(Mentions.covered("Turned on", "Turned on"))
    }
}
