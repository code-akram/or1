package com.or1.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineActsTest {
    private val path = "/files/routine-transcripts/20261011-080000.jsonl"

    private fun run(t: Double, id: String, trigger: String, prompt: String, label: String? = null) =
        JSONObject().put("routine_run", JSONObject().put("id", id).put("trigger", trigger).put("prompt", prompt).apply { label?.let { put("label", it) } })
            .put("t", t).toString()

    private fun reply(t: Double, text: String, failed: Boolean? = null, effects: List<String>? = null) =
        JSONObject().put(
            "routine_reply",
            JSONObject().put("text", text).put("cost", 0.01).put("seconds", 12.5).apply {
                failed?.let { put("failed", it) }
                effects?.let { put("effects", JSONArray(it)) }
            },
        ).put("t", t).toString()

    private fun tool(t: Double, name: String) = JSONObject().put("tool", name).put("args", JSONObject()).put("result", "ok").put("t", t).toString()
    private fun user(t: Double) = JSONObject().put("user", "x").put("t", t).toString()

    private fun effect(id: String, time: Long, sourceId: String? = "r1", source: String = "routine") = Effect(
        id = id, time = time, source = source, sourceId = sourceId, sourceLabel = null, tool = "device", call = "device action=set",
        label = "Turned on Do Not Disturb", tier = UndoTier.LOCAL, undo = "{}", state = UndoState.AVAILABLE,
    )

    @Test fun parsesRunsWithStepsAndReplies() {
        val lines = sequenceOf(
            run(1000.0, "r1", "Every day at 08:00", "Weather?", label = "Morning · Every day at 08:00"),
            user(1000.1), tool(1001.0, "screen"), tool(1002.0, "act"), "not json",
            reply(1010.0, "Sunny, 31°.", failed = false, effects = listOf("e1")),
            run(2000.0, "r2", "On a notification from WhatsApp", "Summarise it"),
            reply(2005.0, "[API error 529: overloaded]", failed = true),
        )
        val acts = parseRoutineActs(lines, path)
        assertEquals(2, acts.size)
        val a = acts[0]
        assertEquals("20261011-080000#1", a.id)
        assertEquals("r1", a.routineId)
        assertEquals("Morning · Every day at 08:00", a.label)
        assertEquals("Every day at 08:00", a.trigger)
        assertEquals(1_000_000L, a.started)
        assertEquals(1_010_000L, a.finished)
        assertEquals("Weather?", a.request)
        assertEquals("Sunny, 31°.", a.reply)
        assertEquals(2, a.steps)
        assertEquals(listOf("e1"), a.effectIds)
        assertFalse(a.failed)
        assertEquals(path, a.transcript)
        val b = acts[1]
        assertEquals("20261011-080000#2", b.id)
        assertEquals("On a notification from WhatsApp", b.label)
        assertTrue(b.failed)
        assertEquals(0, b.steps)
    }

    @Test fun aRunWithoutAReplyIsLeftOut() {
        val lines = sequenceOf(run(1000.0, "r1", "T", "p"), tool(1001.0, "screen"), run(2000.0, "r1", "T", "p"), reply(2001.0, "done"))
        val acts = parseRoutineActs(lines, path)
        assertEquals(1, acts.size)
        // Numbered by position in the file, so ids stay the same however the file is read.
        assertEquals("20261011-080000#2", acts[0].id)
        assertEquals(0, acts[0].steps)
    }

    @Test fun oldRecordsWithoutEffectIdsMatchByRoutineAndTime() {
        val act = parseRoutineActs(sequenceOf(run(1000.0, "r1", "T", "p"), reply(1010.0, "done")), path).single()
        assertNull(act.effectIds)
        val all = listOf(
            effect("in2", 1_008_000), effect("in1", 1_002_000), effect("before", 999_000), effect("after", 1_011_000),
            effect("other", 1_005_000, sourceId = "r2"), effect("chat", 1_005_000, sourceId = null, source = "chat"),
        )
        assertEquals(listOf("in1", "in2"), act.withEffects(all).effects.map { it.id })
    }

    @Test fun loggedEffectIdsWin() {
        val act = parseRoutineActs(sequenceOf(run(1000.0, "r1", "T", "p"), reply(1010.0, "done", effects = listOf("x"))), path).single()
        val all = listOf(effect("x", 5_000_000), effect("in", 1_005_000))
        assertEquals(listOf("x"), act.withEffects(all).effects.map { it.id })
    }
}
