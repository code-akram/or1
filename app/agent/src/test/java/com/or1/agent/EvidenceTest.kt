package com.or1.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTest {
    private val now = 1_800_000_000_000L
    private val hour = 3600_000L
    private val day = 24 * hour

    @Test fun framesOlderThan30DaysGoEvenIfLinked() {
        val files = listOf("old.webp" to now - 31 * day, "recent.webp" to now - 29 * day)
        assertEquals(listOf("old.webp"), prunableFrames(files, setOf("old.webp", "recent.webp"), now))
    }

    @Test fun unlinkedFramesGoAfterAnHour() {
        val files = listOf("fresh.webp" to now - 10 * 60_000, "orphan.webp" to now - 2 * hour, "x.webp.tmp" to now - 2 * hour)
        assertEquals(listOf("orphan.webp", "x.webp.tmp"), prunableFrames(files, emptySet(), now))
    }

    @Test fun linkedRecentFramesStay() {
        assertTrue(prunableFrames(listOf("a.webp" to now - 3 * day), setOf("a.webp"), now).isEmpty())
    }

    @Test fun onlyTier2And3TapsGetFramesUpToTheCap() {
        assertFalse(shouldCaptureFrame(UndoTier.LOCAL, 0))
        assertTrue(shouldCaptureFrame(UndoTier.AGENT, 0))
        assertTrue(shouldCaptureFrame(UndoTier.NONE, MAX_FRAMES_PER_ACT - 1))
        assertFalse(shouldCaptureFrame(UndoTier.NONE, MAX_FRAMES_PER_ACT))
    }

    private fun effect(evidence: String?) = Effect(
        id = "e1", time = now, source = "chat", sourceId = "t", sourceLabel = null, tool = "act", call = "act action=tap element=3",
        label = "Tapped “Send” in Messages", tier = UndoTier.NONE, undo = null, state = UndoState.NONE, app = "Messages", evidence = evidence,
    )

    @Test fun evidenceRoundTripsThroughTheLog() {
        val e = effect("/data/user/0/com.or1.agent/files/evidence/e1.webp")
        assertEquals(e, Effect.fromJson(JSONObject(e.toJson().toString())))
    }

    @Test fun oldRecordsHaveNoEvidence() {
        val json = effect(null).toJson()
        assertFalse(json.has("evidence"))
        assertNull(Effect.fromJson(json).evidence)
        assertNull(foldActivity(sequenceOf(JSONObject().put("effect", json).toString())).single().evidence)
    }
}
