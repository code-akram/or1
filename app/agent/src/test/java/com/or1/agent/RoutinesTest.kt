package com.or1.agent

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutinesTest {
    private val zone = ZoneId.of("Asia/Dubai")
    // Saturday 10 Oct 2026, 13:00.
    private val saturday = ZonedDateTime.of(2026, 10, 10, 13, 0, 0, 0, zone)

    private fun at(time: String, repeat: String, now: ZonedDateTime = saturday, date: String? = null) =
        parseRoutine(JSONObject().put("prompt", "p").put("at", time).put("repeat", repeat).apply { date?.let { put("date", it) } }, "id", now)

    private fun next(r: Routine) = java.time.Instant.ofEpochMilli(r.next).atZone(zone)

    @Test fun onceLaterTodayRunsToday() = assertEquals(saturday.withHour(18), next(at("18:00", "once")))

    @Test fun oncePastTimeRunsTomorrow() = assertEquals(saturday.plusDays(1).withHour(9), next(at("09:00", "once")))

    @Test fun weekdaysSkipTheWeekend() {
        val n = next(at("07:15", "weekdays"))
        assertEquals(DayOfWeek.MONDAY, n.dayOfWeek)
        assertEquals(12, n.dayOfMonth)
    }

    @Test fun weekdaysFromFridayEveningGoToMonday() {
        val friday = saturday.minusDays(1).withHour(20)
        assertEquals(DayOfWeek.MONDAY, next(at("07:15", "weekdays", friday)).dayOfWeek)
    }

    @Test fun weeklyUsesTheDatesWeekday() {
        val r = at("10:00", "weekly", date = "2026-10-14") // a Wednesday
        assertEquals(3, r.weekday)
        assertEquals(DayOfWeek.WEDNESDAY, next(r).dayOfWeek)
    }

    @Test fun weeklyOnTodayAfterItsTimeIsNextWeek() = assertEquals(17, next(at("09:00", "weekly")).dayOfMonth)

    @Test fun dailyRollsOverMonthEnd() {
        val lastDay = ZonedDateTime.of(2026, 10, 31, 23, 30, 0, 0, zone)
        val n = next(at("06:00", "daily", lastDay))
        assertEquals(11, n.monthValue)
        assertEquals(1, n.dayOfMonth)
    }

    @Test fun inMinutesIsAOneOff() {
        val r = parseRoutine(JSONObject().put("prompt", "p").put("in_minutes", 90), "id", saturday)
        assertEquals("once", r.repeat)
        assertEquals(saturday.plusMinutes(90), next(r))
    }

    @Test fun exactlyOneTriggerIsRequired() {
        assertThrows(IllegalArgumentException::class.java) { parseRoutine(JSONObject().put("prompt", "p"), "id", saturday) }
        assertThrows(IllegalArgumentException::class.java) {
            parseRoutine(JSONObject().put("prompt", "p").put("at", "08:00").put("app", "Messaging"), "id", saturday)
        }
    }

    @Test fun badTimeAndPastDateAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { at("25:00", "once") }
        assertThrows(IllegalArgumentException::class.java) { at("08:00", "once", date = "2026-10-01") }
    }

    @Test fun editingKeepsAlwaysAnswersAndWeeklyDay() {
        val old = at("10:00", "weekly", date = "2026-10-14").copy(allowed = setOf("device:set"), lastRun = 42)
        val edited = parseRoutine(JSONObject().put("prompt", "q").put("at", "11:30").put("repeat", "weekly"), "id", saturday, keep = old)
        assertEquals(setOf("device:set"), edited.allowed)
        assertEquals(42L, edited.lastRun)
        assertEquals(3, edited.weekday)
    }

    @Test fun jsonRoundTrip() {
        val r = Routine("abc123", "p", app = "Messaging", textContains = "Sam", allowed = setOf("clock:set_timer"), lastRun = 7)
        assertEquals(r, Routine.fromJson(JSONObject(r.toJson().toString())))
        val t = at("07:15", "weekdays")
        assertEquals(t, Routine.fromJson(JSONObject(t.toJson().toString())))
    }

    @Test fun describeReadsNaturally() {
        assertEquals("Weekdays at 07:15", at("07:15", "weekdays").describe())
        assertTrue(Routine("x", "p", textContains = "or1test").describe().contains("containing “or1test”"))
    }
}
