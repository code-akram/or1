package com.or1.agent

import com.or1.agent.tools.deviceUndo
import com.or1.agent.tools.normalizeSetting
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTest {
    private val zone = ZoneId.of("Asia/Dubai")
    // Saturday 10 Oct 2026, 13:00.
    private val now = ZonedDateTime.of(2026, 10, 10, 13, 0, 0, 0, zone)

    private fun args(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private fun undo(op: String, vararg pairs: Pair<String, Any>) = args("op" to op, *pairs)
    private fun classify(tool: String, a: JSONObject, undo: JSONObject? = null) = Effects.classify(tool, a, CallFacts(undo = undo), now)
    private fun tap(target: String, kind: String = "Button") =
        Effects.classify("act", args("action" to "tap", "element" to 3), CallFacts(target = target, targetKind = kind, app = "Gmail"), now)

    // --- Classification and labels ------------------------------------------------------------------------------

    @Test fun readsAreNotEffects() {
        assertNull(classify("device", args("action" to "get")))
        assertNull(classify("screen", args("screenshot" to true)))
        assertNull(classify("calendar", args("action" to "list")))
        assertNull(classify("contacts", args("action" to "find", "query" to "a")))
        assertNull(classify("memory", args("action" to "view")))
        assertNull(classify("routines", args("action" to "list")))
        assertNull(classify("clock", args("action" to "next_alarm")))
        assertNull(classify("notifications", args()))
        assertNull(classify("apps", args("action" to "list")))
        assertNull(classify("open", args("package" to "com.android.settings")))
        assertNull(classify("act", args("action" to "scroll")))
        assertNull(classify("act", args("action" to "type", "text" to "hi")))
    }

    @Test fun deviceSetIsLocalWithPastTenseLabel() {
        val u = deviceUndo("dnd", "on") { mapOf("dnd" to "off", "dnd_filter" to "1")[it]!! }!!
        val c = classify("device", args("action" to "set", "setting" to "dnd", "value" to "true"), u)!!
        assertEquals("Turned on Do Not Disturb", c.label)
        assertEquals(UndoTier.LOCAL, c.tier)
        assertEquals("Set media volume to 3", Effects.deviceLabel("volume", "3"))
        assertEquals("Set screen timeout to 2 minutes", Effects.deviceLabel("screen_timeout", "120"))
    }

    @Test fun unchangedSettingIsNoEffect() {
        assertNull(deviceUndo("volume", "3") { "3" })
        assertNull(classify("device", args("action" to "set", "setting" to "volume", "value" to "3"), null))
    }

    @Test fun alarmLabelAndExpiryAtRingTime() {
        val c = classify("clock", args("action" to "set_alarm", "hour" to 7, "minute" to 30, "label" to "Gym"), undo("alarm", "hour" to 7, "minute" to 30))!!
        assertEquals("Set alarm 07:30 “Gym”", c.label)
        assertEquals(UndoTier.LOCAL, c.tier)
        // 07:30 has passed today, so it rings tomorrow.
        assertEquals(now.plusDays(1).withHour(7).withMinute(31).toInstant().toEpochMilli(), c.expiresAt)
    }

    @Test fun timerExpiresWhenItEnds() {
        val c = classify("clock", args("action" to "set_timer", "seconds" to 300), undo("timer", "seconds" to 300))!!
        assertEquals("Set a 5-minute timer", c.label)
        assertEquals(now.toInstant().toEpochMilli() + 300_000, c.expiresAt)
    }

    @Test fun dismissingAlarmsIsIrreversible() {
        val c = classify("clock", args("action" to "dismiss_alarm", "label" to "Gym"))!!
        assertEquals(UndoTier.NONE, c.tier)
        assertEquals("Turned off the “Gym” alarm", c.label)
    }

    @Test fun providerWritesAreLocal() {
        assertEquals(
            "Added “Dentist” to the calendar, 2026-10-12 10:00",
            classify("calendar", args("action" to "add", "title" to "Dentist", "start" to "2026-10-12T10:00"), undo("calendar_delete", "event_id" to 5, "title" to "Dentist"))!!.label,
        )
        assertEquals(
            "Moved “Dentist” to Tue 13 Oct 16:00",
            classify("calendar", args("action" to "move", "event_id" to 5, "start" to "2026-10-13T16:00"), undo("calendar_move", "event_id" to 5, "title" to "Dentist", "to" to "Tue 13 Oct 16:00"))!!.label,
        )
        assertEquals("Added contact Test Person", classify("contacts", args("action" to "add", "name" to "Test Person"), undo("contact_delete", "raw_contact_id" to 9))!!.label)
        assertEquals("Created routine “Morning”", classify("routines", args("action" to "add", "prompt" to "p"), undo("routine_remove", "id" to "abc", "title" to "Morning"))!!.label)
        assertEquals("Saved a note: “Likes tea”", classify("memory", args("action" to "add", "text" to "Likes tea"), undo("memory_remove", "text" to "Likes tea"))!!.label)
        // A duplicate note or a missing event changes nothing, so the tool gives no undo.
        assertNull(classify("memory", args("action" to "add", "text" to "Likes tea")))
    }

    @Test fun shellAndUrisAreIrreversible() {
        assertEquals(UndoTier.NONE, classify("shell", args("command" to "pm uninstall com.x"))!!.tier)
        assertEquals(UndoTier.NONE, classify("open", args("uri" to "https://example.com"))!!.tier)
        assertNull(classify("shell", args("command" to "content query --uri content://sms/inbox | head -5")))
        assertNull(classify("shell", args("command" to "dumpsys battery 2>/dev/null")))
        assertTrue(classify("shell", args("command" to "echo hi > /sdcard/x")) != null)
        assertTrue(classify("shell", args("command" to "ls; settings put system x 1")) != null)
        assertNull(classify("shell", args("command" to "am start --display 3 -a android.intent.action.VIEW -d \"vnd.youtube:abc\"")))
        assertTrue(classify("shell", args("command" to "am start -a android.intent.action.VIEW -d https://x.example/?q=1")) != null)
    }

    @Test fun shellLabelsSayWhatTheCommandDid() {
        fun label(command: String) = classify("shell", args("command" to command))!!.label
        assertEquals("Changed setting screen_brightness", label("settings put system screen_brightness 128"))
        // The first part that writes names it.
        assertEquals("Changed setting x", label("ls; settings put system x 1"))
        assertEquals("Sent com.example.app a command", label("am broadcast -a com.example.PING -n com.example.app/.Receiver"))
        assertEquals("Sent com.example.app a command", label("am startservice -p com.example.app -a X"))
        assertEquals("Sent an app a command", label("am broadcast -a android.intent.action.X"))
        assertEquals("Changed app com.android.gallery3d", label("pm uninstall com.android.gallery3d"))
        assertEquals("Changed app com.x", label("pm disable-user --user 0 com.x"))
        assertEquals("Changed an app", label("pm trim-caches 1G"))
        assertEquals("Pressed keys", label("input keyevent 26"))
        assertEquals("Opened x.example", label("am start -a android.intent.action.VIEW -d https://x.example/?q=1"))
        assertEquals("Ran a shell command", label("echo hi > /sdcard/x"))
        assertEquals("Ran a shell command", label("rm /sdcard/notes.txt"))
    }

    @Test fun tapsByLabel() {
        assertEquals(UndoTier.NONE, tap("“Send”")!!.tier)
        assertEquals("Tapped “Send” in Gmail", tap("“Send”")!!.label)
        assertEquals(UndoTier.AGENT, tap("“Archive”")!!.tier)
        assertEquals(UndoTier.AGENT, tap("“Wi-Fi”", "Switch")!!.tier)
        // A tier-2 row names the app in its caption ("via or1 · Gmail"), not twice.
        assertEquals("Toggled “Wi-Fi”", tap("“Wi-Fi”", "Switch")!!.label)
        assertEquals("Tapped “Archive”", tap("“Archive”")!!.label)
        assertNull(tap("“Inbox”"))
        assertNull(tap("an unlabeled button"))
        assertNull(Effects.classify("act", args("action" to "tap", "x" to 1, "y" to 2), CallFacts(), now))
    }

    // --- Undo data ----------------------------------------------------------------------------------------------

    @Test fun deviceUndoRestoresPreviousValue() {
        val state = mutableMapOf("volume" to "15")
        val u = deviceUndo("volume", normalizeSetting("volume", "40", maxVolume = 25)) { state.getValue(it) }!!
        assertEquals("25", u.getString("value"))
        assertEquals("15", u.getString("was"))
        val restore = u.getJSONArray("restore").getJSONObject(0)
        assertEquals("volume", restore.getString("setting"))
        assertEquals("15", restore.getString("value"))
    }

    @Test fun dndRestoresTheExactFilter() {
        val u = deviceUndo("dnd", "off") { mapOf("dnd" to "on", "dnd_filter" to "3")[it]!! }!!
        val r = u.getJSONArray("restore").getJSONObject(0)
        assertEquals("dnd_filter", r.getString("setting"))
        assertEquals("3", r.getString("value"))
    }

    @Test fun brightnessAlsoRestoresAutoBrightness() {
        val u = deviceUndo("brightness", "128") { mapOf("brightness" to "128", "auto_brightness" to "on")[it]!! }!!
        val r = u.getJSONArray("restore")
        assertEquals(2, r.length())
        assertEquals("auto_brightness", r.getJSONObject(1).getString("setting"))
    }

    @Test fun normalizedValues() {
        assertEquals("on", normalizeSetting("wifi", "True", 25))
        assertEquals("0", normalizeSetting("brightness", "-5", 25))
        assertEquals("120", normalizeSetting("screen_timeout", " 120 ", 25))
    }

    // --- Persistence ------------------------------------------------------------------------------------------

    private fun effect(id: String, time: Long = 1_000, tool: String = "device", undo: JSONObject? = null) = Effect(
        id, time, "chat", "20261010-130000", null, tool, "device action=set setting=dnd value=on", "Turned on Do Not Disturb",
        UndoTier.LOCAL, undo?.toString(), UndoState.AVAILABLE, expiresAt = 5_000,
    )

    @Test fun effectRoundTrips() {
        val e = effect("a", undo = undo("device", "setting" to "dnd")).copy(app = "Settings", sourceLabel = "Morning · Every day at 08:00")
        val back = Effect.fromJson(JSONObject(e.toJson().toString()))
        assertEquals(e.copy(undo = null), back.copy(undo = null))
        assertEquals("dnd", JSONObject(back.undo!!).getString("setting"))
    }

    @Test fun foldAppliesLatestUndoStateAndSkipsBadLines() {
        val lines = sequenceOf(
            JSONObject().put("effect", effect("a").toJson()).toString(),
            "not json",
            JSONObject().put("effect", effect("b", time = 2_000).toJson()).toString(),
            undoRecord("a", UndoState.FAILED, "No timer is running.").toString(),
            undoRecord("a", UndoState.UNDONE, "Cancelled the timer.").toString(),
            undoRecord("b", UndoState.PENDING, null).toString(),
            undoRecord("zzz", UndoState.UNDONE, null).toString(),
        )
        val effects = foldActivity(lines)
        assertEquals(listOf("a", "b"), effects.map { it.id })
        assertEquals(UndoState.UNDONE, effects[0].state)
        assertEquals("Cancelled the timer.", effects[0].detail)
        // A pending state that never finished isn't persisted as one.
        assertEquals(UndoState.AVAILABLE, effects[1].state)
    }

    @Test fun offersExpire() {
        val e = effect("a")
        assertTrue(e.canUndo(now = 4_999))
        assertEquals(UndoState.EXPIRED, e.stateAt(now = 5_000))
        assertFalse(e.copy(state = UndoState.UNDONE).canUndo(now = 0))
    }

    @Test fun laterChangeToTheSameSettingSupersedes() {
        val dnd = effect("a", undo = deviceUndo("dnd", "on") { mapOf("dnd" to "off", "dnd_filter" to "1")[it]!! })
        val bright = effect("b", undo = deviceUndo("brightness", "10") { mapOf("brightness" to "100", "auto_brightness" to "on")[it]!! })
        assertEquals(setOf("dnd"), Effects.restoredSettings(dnd))
        assertEquals(setOf("brightness", "auto_brightness"), Effects.restoredSettings(bright))
        assertTrue(Effects.restoredSettings(effect("c", tool = "memory", undo = undo("memory_remove", "text" to "x"))).isEmpty())
    }

    @Test fun todayIsTheLocalDay() {
        val today = now.toInstant().toEpochMilli()
        val yesterday = now.minusDays(1).toInstant().toEpochMilli()
        val list = listOf(effect("a", time = today), effect("b", time = yesterday))
        assertEquals(listOf("a"), list.today(now = today, zone = zone).map { it.id })
    }

    @Test fun idsAreUnique() {
        val t = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli()
        assertTrue(ActivityLog.newId(t) != ActivityLog.newId(t))
    }
}
