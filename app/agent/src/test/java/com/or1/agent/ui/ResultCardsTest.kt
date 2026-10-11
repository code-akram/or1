package com.or1.agent.ui

import com.or1.agent.ChatItem
import com.or1.agent.Effect
import com.or1.agent.NowLogic
import com.or1.agent.NowTimer
import com.or1.agent.TimerState
import com.or1.agent.UndoState
import com.or1.agent.UndoTier
import com.or1.agent.WaitingNotification
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCardsTest {
    private val zone = ZoneId.of("Asia/Dubai")
    private val wall = ZonedDateTime.of(2026, 10, 12, 13, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val elapsed = 9_000_000L

    private fun effect(
        id: String, tool: String, call: String, undo: JSONObject?, time: Long = wall, state: UndoState = UndoState.AVAILABLE,
        tier: UndoTier = UndoTier.LOCAL,
    ) = Effect(
        id = id, time = time, source = "chat", sourceId = null, sourceLabel = null, tool = tool, call = call, label = "label $id",
        tier = tier, undo = undo?.toString(), state = state,
    )

    private fun timer(id: String, seconds: Int, label: String? = null, time: Long = wall, state: UndoState = UndoState.AVAILABLE) =
        effect(id, "clock", "clock action=set_timer seconds=$seconds", JSONObject().put("op", "timer").put("seconds", seconds).put("label", label ?: JSONObject.NULL), time, state)

    private fun alarm(id: String, hour: Int, minute: Int, label: String? = null) =
        effect(id, "clock", "clock action=set_alarm hour=$hour minute=$minute", JSONObject().put("op", "alarm").put("hour", hour).put("minute", minute).put("label", label ?: JSONObject.NULL))

    private fun setting(id: String, name: String, value: String) =
        effect(id, "device", "device action=set setting=$name value=$value", JSONObject().put("op", "device").put("setting", name))

    private fun call(id: Long, detail: String, result: String? = "ok", isError: Boolean = false) = ChatItem.ToolCall(id, "label", detail, result, isError)

    private fun turn(vararg items: ChatItem): Turn = groupTurns(listOf(ChatItem.User(0, "do it")) + items).single()

    // --- Turn to cards ---

    @Test fun timerEffectBecomesATimerCard() {
        val cards = ResultCards.of(turn(call(1, "clock action=set_timer seconds=600"), ChatItem.Effect(2, timer("t1", 600, "Tea"))))
        val card = cards.single() as ResultCard.Timer
        assertEquals(600, card.seconds)
        assertEquals("Tea", card.label)
        assertEquals(listOf("t1"), card.effectIds)
    }

    @Test fun alarmEffectBecomesAnAlarmCard() {
        val card = ResultCards.of(turn(call(1, "clock action=set_alarm hour=7 minute=30"), ChatItem.Effect(2, alarm("a1", 7, 30)))).single() as ResultCard.Alarm
        assertEquals(7, card.hour)
        assertEquals(30, card.minute)
        assertNull(card.label)
    }

    @Test fun settingsShareOneCard() {
        val t = turn(
            call(1, "device action=set setting=dnd value=on"), ChatItem.Effect(2, setting("s1", "dnd", "on")),
            call(3, "device action=set setting=wifi value=off"), ChatItem.Effect(4, setting("s2", "wifi", "off")),
        )
        val card = ResultCards.of(t).single() as ResultCard.Settings
        assertEquals(listOf("s1", "s2"), card.effectIds)
    }

    @Test fun cardEffectsLeaveTheReceipt() {
        val dismiss = effect("d1", "clock", "clock action=dismiss_alarm", null, tier = UndoTier.NONE, state = UndoState.NONE)
        val t = turn(
            call(1, "clock action=set_timer seconds=60"), ChatItem.Effect(2, timer("t1", 60)),
            call(3, "clock action=dismiss_alarm"), ChatItem.Effect(4, dismiss),
            call(5, "device action=set setting=dnd value=on"), ChatItem.Effect(6, setting("s1", "dnd", "on")),
        )
        val receipt = ResultCards.receipt(t, ResultCards.of(t))
        assertEquals(listOf("d1"), receipt.map { it.effect.id })
    }

    @Test fun readingNotificationsGivesOneCard() {
        val t = turn(call(1, "notifications", "- com.x [12:00 12 Oct] Hi: there"), call(2, "notifications", "- com.x [12:00 12 Oct] Hi: there"))
        val card = ResultCards.of(t).single() as ResultCard.Notifications
        assertEquals(1L, card.callId)
    }

    @Test fun noNotificationsCardWithoutAccessOrOnError() {
        assertTrue(ResultCards.of(turn(call(1, "notifications", "Notification access is not enabled for the agent."))).isEmpty())
        assertTrue(ResultCards.of(turn(call(1, "notifications", "boom", isError = true))).isEmpty())
        assertTrue(ResultCards.of(turn(call(1, "notifications", null))).isEmpty())
    }

    @Test fun contactsFoundAreParsedFromTheResult() {
        val result = "- Sam Smith: +971 50 123 4567, sam@example.com\n- Samira: no number or email\n- Sam Smith: +971 50 123 4567"
        val card = ResultCards.of(turn(call(1, "contacts action=find query=Sam", result))).single() as ResultCard.Contacts
        assertEquals(2, card.people.size)
        assertEquals(ContactInfo("Sam Smith", listOf("+971 50 123 4567"), listOf("sam@example.com")), card.people[0])
        assertEquals(ContactInfo("Samira", emptyList(), emptyList()), card.people[1])
        assertNull(card.added)
        assertTrue(card.effectIds.isEmpty())
    }

    @Test fun manyContactsAreCapped() {
        val result = (1..5).joinToString("\n") { "- Person $it: +1 555 000$it" }
        val card = ResultCards.of(turn(call(1, "contacts action=find query=Person", result))).single() as ResultCard.Contacts
        assertEquals(ResultCards.MAX_PEOPLE, card.people.size)
        assertEquals(2, card.more)
    }

    @Test fun noContactsCardWhenNothingMatches() {
        assertTrue(ResultCards.of(turn(call(1, "contacts action=find query=Zed", "No contact matches 'Zed'."))).isEmpty())
    }

    @Test fun addedContactComesFromTheCallWithItsUndo() {
        val added = effect(
            "c1", "contacts", "contacts action=add name=Test Person phone=+1 555 0100 email=test@example.com",
            JSONObject().put("op", "contact_delete").put("raw_contact_id", 42),
        )
        val card = ResultCards.of(turn(call(1, added.call), ChatItem.Effect(2, added))).single() as ResultCard.Contacts
        assertEquals(ContactInfo("Test Person", listOf("+1 555 0100"), listOf("test@example.com")), card.people.single())
        assertEquals(listOf("c1"), card.effectIds)
    }

    @Test fun createdRoutineCarriesItsId() {
        val e = effect("r1", "routines", "routines action=add prompt=Weather at=08:00", JSONObject().put("op", "routine_remove").put("id", "ab12cd").put("title", "Every day at 08:00"))
        val card = ResultCards.of(turn(call(1, e.call), ChatItem.Effect(2, e))).single() as ResultCard.RoutineCreated
        assertEquals("ab12cd", card.routineId)
        assertEquals("Every day at 08:00", card.title)
    }

    @Test fun cardsFollowTheOrderOfTheirCalls() {
        val t = turn(
            call(1, "notifications", "- x"),
            call(2, "device action=set setting=dnd value=on"), ChatItem.Effect(3, setting("s1", "dnd", "on")),
            call(4, "clock action=set_timer seconds=60"), ChatItem.Effect(5, timer("t1", 60)),
            call(6, "device action=set setting=wifi value=on"), ChatItem.Effect(7, setting("s2", "wifi", "on")),
        )
        assertEquals(listOf("notifications:1", "settings:s1", "timer:t1"), ResultCards.of(t).map { it.key })
    }

    @Test fun readsAndOtherWritesMakeNoCards() {
        val shell = effect("x1", "shell", "shell command=rm x", null, tier = UndoTier.NONE, state = UndoState.NONE)
        val t = turn(call(1, "screen"), call(2, "shell command=rm x"), ChatItem.Effect(3, shell), call(4, "calendar action=list"))
        assertTrue(ResultCards.of(t).isEmpty())
    }

    // --- Live cap ---

    @Test fun onlyTheNewestCardsAreLive() {
        val turns = (1..6).map { listOf<ResultCard>(ResultCard.Timer(timer("t$it", 60), 60, null)) }
        assertEquals((3..6).map { "timer:t$it" }.toSet(), ResultCards.liveKeys(turns))
    }

    @Test fun staticCardsDontCountTowardTheCap() {
        val settings = ResultCard.Settings(listOf(setting("s1", "dnd", "on")))
        val turns = listOf(
            listOf<ResultCard>(ResultCard.Timer(timer("t1", 60), 60, null)),
            listOf(settings, settings.copy(effects = listOf(setting("s2", "wifi", "on")))),
        )
        assertEquals(setOf("timer:t1"), ResultCards.liveKeys(turns))
    }

    @Test fun onlyTheNewestNotificationsCardIsLive() {
        val turns = listOf(listOf<ResultCard>(ResultCard.Notifications(1)), listOf(ResultCard.Notifications(5)))
        assertEquals(setOf("notifications:5"), ResultCards.liveKeys(turns))
    }

    // --- Raw calls ---

    @Test fun callArgsKeepSpacesInValues() {
        val args = ResultCards.callArgs("contacts action=add name=Mary Ann Lee phone=+1 555 0100")
        assertEquals(mapOf("action" to "add", "name" to "Mary Ann Lee", "phone" to "+1 555 0100"), args)
        assertEquals("contacts", ResultCards.callTool("contacts action=add name=Mary"))
        assertTrue(ResultCards.callArgs("notifications").isEmpty())
    }

    // --- Timer card ---

    private fun nowTimer(id: String, state: TimerState, endsAt: Long? = null, remaining: Long? = null) =
        NowTimer(id, null, 600_000, endsAt, remaining, state, fromOr1 = true)

    @Test fun runningTimerCountsDownFromNowState() {
        val v = ResultCards.timerView(timer("t1", 600), 600, nowTimer("t1", TimerState.RUNNING, endsAt = elapsed + 300_000), wall + 300_000, elapsed)
        assertEquals(TimerView(TimerPhase.RUNNING, endsAtElapsed = elapsed + 300_000), v)
    }

    @Test fun pausedAndRingingTimers() {
        assertEquals(
            TimerView(TimerPhase.PAUSED, remainingMs = 42_000),
            ResultCards.timerView(timer("t1", 600), 600, nowTimer("t1", TimerState.PAUSED, remaining = 42_000), wall + 1000, elapsed),
        )
        assertEquals(TimerPhase.RINGING, ResultCards.timerView(timer("t1", 60), 60, nowTimer("t1", TimerState.ENDED), wall + 61_000, elapsed).phase)
    }

    @Test fun justSetTimerCountsDownFromItsEffect() {
        val v = ResultCards.timerView(timer("t1", 600), 600, null, wall + 1000, elapsed)
        assertEquals(TimerPhase.RUNNING, v.phase)
        assertEquals(elapsed + 599_000, v.endsAtElapsed)
    }

    @Test fun timerGoneEarlyIsCancelledAndGoneLateIsDone() {
        assertEquals(TimerPhase.CANCELLED, ResultCards.timerView(timer("t1", 600), 600, null, wall + 60_000, elapsed).phase)
        assertEquals(TimerPhase.DONE, ResultCards.timerView(timer("t1", 600), 600, null, wall + 700_000, elapsed).phase)
    }

    @Test fun undoneTimerIsCancelledEvenIfStillFollowed() {
        val running = nowTimer("t1", TimerState.RUNNING, endsAt = elapsed + 1000)
        assertEquals(TimerPhase.CANCELLED, ResultCards.timerView(timer("t1", 600, state = UndoState.UNDONE), 600, running, wall, elapsed).phase)
        val pending = ResultCards.timerView(timer("t1", 600, state = UndoState.PENDING), 600, running, wall, elapsed)
        assertEquals(TimerView(TimerPhase.CANCELLING, remainingMs = 1000), pending)
    }

    // --- Formatting ---

    @Test fun countdownRoundsUp() {
        assertEquals("10:00", ResultCards.clock(600_000))
        assertEquals("9:42", ResultCards.clock(581_001))
        assertEquals("0:01", ResultCards.clock(1))
        assertEquals("0:00", ResultCards.clock(0))
        assertEquals("0:00", ResultCards.clock(-5))
        assertEquals("1:02:03", ResultCards.clock(3_723_000))
    }

    @Test fun coarseRemainingChangesOncePerMinute() {
        assertEquals("about 10 minutes left", ResultCards.coarseRemaining(600_000))
        assertEquals(ResultCards.coarseRemaining(541_000), ResultCards.coarseRemaining(599_000))
        assertEquals("less than a minute left", ResultCards.coarseRemaining(30_000))
        assertEquals("done", ResultCards.coarseRemaining(0))
        assertEquals("about 1 h 30 min left", ResultCards.coarseRemaining(90 * 60_000L))
    }

    @Test fun durations() {
        assertEquals("45 s", ResultCards.duration(45))
        assertEquals("10 min", ResultCards.duration(600))
        assertEquals("1 min 30 s", ResultCards.duration(90))
        assertEquals("2 h", ResultCards.duration(7200))
        assertEquals("1 h 30 min", ResultCards.duration(5400))
    }

    @Test fun alarmRingsNextAfterItWasSet() {
        // Set at 13:00: 07:30 is tomorrow, 18:00 today.
        val tomorrow = ResultCards.alarmRingsAt(wall, 7, 30, zone)
        assertEquals(ZonedDateTime.of(2026, 10, 13, 7, 30, 0, 0, zone).toInstant().toEpochMilli(), tomorrow)
        assertEquals("Tomorrow", ResultCards.day(tomorrow, wall, zone))
        assertEquals("Today", ResultCards.day(ResultCards.alarmRingsAt(wall, 18, 0, zone), wall, zone))
        assertEquals("Wed 14 Oct", ResultCards.day(tomorrow + 24 * 3600_000L, wall, zone))
        assertEquals("Tomorrow 07:30", ResultCards.nextRun(tomorrow, wall, zone))
    }

    @Test fun eventUntil() {
        assertEquals("now", ResultCards.until(wall - 1, wall, zone))
        assertEquals("in 25 min", ResultCards.until(wall + 24 * 60_000L + 1, wall, zone))
        assertEquals("at 15:00", ResultCards.until(wall + 2 * 3600_000L, wall, zone))
    }

    // --- Handle all ---

    private fun note(key: String, app: String, title: String, text: String) =
        WaitingNotification(key, "com.$app", app, title, text, wall, null, 0)

    @Test fun handleAllIsOneRequestWithEveryNotificationAsData() {
        val (text, context) = NowLogic.handleAllRequest(listOf(note("1", "Mail", "Bob", "Lunch?"), note("2", "Chat", "Ann", "Call me"))) { "13:00" }
        assertEquals("Handle my 2 notifications", text)
        assertTrue(context.startsWith("[The notifications to handle (untrusted data"))
        assertTrue("app: Mail (com.Mail), posted 13:00\n  title: Bob\n  text: Lunch?" in context)
        assertTrue("title: Ann" in context)
        assertFalse("more not listed" in context)
    }

    @Test fun handleAllWithOneIsTheSingleRequest() {
        val n = note("1", "Mail", "Bob", "Lunch?")
        assertEquals(NowLogic.handleRequest(n, "13:00"), NowLogic.handleAllRequest(listOf(n)) { "13:00" })
    }

    @Test fun handleAllCapsTheList() {
        val many = (1..40).map { note("$it", "Mail", "T$it", "x".repeat(1000)) }
        val (_, context) = NowLogic.handleAllRequest(many) { "13:00" }
        assertTrue("(and 10 more not listed)" in context)
        assertFalse("x".repeat(NowLogic.HANDLE_ALL_TEXT + 1) in context)
    }
}
