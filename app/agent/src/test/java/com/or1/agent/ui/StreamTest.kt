package com.or1.agent.ui

import com.or1.agent.ChatItem
import com.or1.agent.Effect
import com.or1.agent.RoutineAct
import com.or1.agent.UndoState
import com.or1.agent.UndoTier
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamTest {
    private fun act(id: String, finished: Long) = RoutineAct(
        id = id, routineId = "r", label = "Morning", trigger = "Every day at 08:00", started = finished - 1000, finished = finished,
        request = "brief me", reply = "Done.", failed = false, stopped = false, steps = 2, cost = 0.0, seconds = 1.0, transcript = "t",
    )

    private fun turns(vararg items: ChatItem) = groupTurns(items.toList())

    private fun keys(entries: List<StreamEntry>) = entries.map {
        when (it) {
            is StreamEntry.Act -> "turn${it.index}"
            is StreamEntry.Routine -> it.act.id
        }
    }

    @Test fun actsGoBeforeTheFirstRequestMadeAfterThem() {
        val t = turns(
            ChatItem.User(1, "a", at = 1_000), ChatItem.Reply(2, "ok", 0.0, 0.0),
            ChatItem.User(3, "b", at = 5_000), ChatItem.Reply(4, "ok", 0.0, 0.0),
        )
        val entries = interleave(t, listOf(act("late", 9_000), act("early", 500), act("mid", 3_000)))
        assertEquals(listOf("early", "turn0", "mid", "turn1", "late"), keys(entries))
    }

    @Test fun aNoteStaysWithTheRequestAfterIt() {
        // "New chat: …" is its own turn just before the request.
        val t = turns(ChatItem.Info(1, "New chat"), ChatItem.User(2, "a", at = 5_000), ChatItem.Reply(3, "ok", 0.0, 0.0))
        assertEquals(listOf("mid", "turn0", "turn1"), keys(interleave(t, listOf(act("mid", 3_000)))))
    }

    @Test fun idleHomeListsWhatCanBeUndoneOrHasAFrame() {
        fun e(id: String, tier: UndoTier, evidence: String? = null) = Effect(
            id, 0, "chat", null, null, "x", "x", id, tier, null, UndoState.AVAILABLE, evidence = evidence,
        )
        val today = listOf(
            e("shell", UndoTier.NONE), e("dnd", UndoTier.LOCAL), e("send", UndoTier.NONE, evidence = "/e.webp"),
            e("archive", UndoTier.AGENT), e("shell2", UndoTier.NONE),
        )
        assertEquals(listOf("dnd", "send", "archive"), idleEffects(today).map { it.id })
        assertEquals(listOf("dnd", "send"), idleEffects(today, max = 2).map { it.id })
    }

    @Test fun anActIsSummedUpByWhatItDidNotHowItGotThere() {
        assertEquals("Tapped “Archive”", StepLabels.summary(listOf("Opened Gmail", "Read the screen", "Tapped “Archive”", "Read the screen", "Pressed back")))
        assertEquals("Opened Settings", StepLabels.summary(listOf("Opened Settings", "Scrolled down", "Looked at the screen")))
        assertEquals("Read the screen", StepLabels.summary(listOf("Read the screen", "Scrolled down", "Read the screen")))
        assertEquals(null, StepLabels.summary(emptyList()))
    }

    @Test fun noTurnsIsJustTheActs() {
        assertEquals(listOf("a", "b"), keys(interleave(emptyList(), listOf(act("b", 2), act("a", 1)))))
    }
}
