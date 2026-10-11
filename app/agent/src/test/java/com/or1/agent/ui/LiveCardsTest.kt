package com.or1.agent.ui

import com.or1.agent.ChatItem
import com.or1.agent.tools.CalendarEvent
import com.or1.agent.tools.CalendarTool
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCardsTest {
    private val zone = ZoneId.of("Asia/Dubai")
    private val day = LocalDate.of(2026, 10, 12)

    private fun call(id: Long, detail: String, result: String? = "ok", isError: Boolean = false) = ChatItem.ToolCall(id, "label", detail, result, isError)

    private fun turn(vararg items: ChatItem): Turn = groupTurns(listOf(ChatItem.User(0, "do it")) + items).single()

    private fun screen(pkg: String, vararg lines: String) = "Foreground: $pkg. Screen 720x1600.\n" + lines.joinToString("\n")

    private fun at(d: LocalDate, h: Int, m: Int) = ZonedDateTime.of(d.atTime(h, m), zone).toInstant().toEpochMilli()

    private fun event(id: Long, title: String, h: Int, m: Int, minutes: Int = 60, d: LocalDate = day, location: String? = null) =
        CalendarEvent(id, title, at(d, h, m), at(d, h, m) + minutes * 60_000L, false, location)

    private fun allDay(id: Long, title: String, d: LocalDate = day) = CalendarEvent(
        id, title, d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, null,
    )

    // --- Screen results ---

    @Test fun screenResultsParse() {
        val dump = ScreenDump.parse(screen("com.google.android.gm", "[1] ImageButton 'Navigate up' @48,104 click", "[2] TextView 'It's done' @300,220", "[3] View '' @1,2 scroll"))!!
        assertEquals("com.google.android.gm", dump.pkg)
        assertEquals(listOf("Navigate up", "It's done", ""), dump.elements.map { it.label })
        assertEquals(setOf("click"), dump.elements[0].flags)
        assertEquals(220, dump.elements[1].y)
        assertNull(ScreenDump.foreground("Foreground: none. The work screen is empty: open an app to work on it."))
        assertNull(ScreenDump.foreground("Foreground: the or1 chat (the agent's own interface). Open an app to work on it."))
    }

    // --- Media ---

    private val youtube = screen("com.google.android.youtube", "[1] TextView 'Lofi beats to study to' @360,520", "[2] Button 'Pause video' @360,200 click")

    @Test fun mediaAppLeftInFrontGetsAMediaCard() {
        val t = turn(call(1, "open package=com.google.android.youtube", youtube), call(2, "act action=tap element=2 (“Play”)", youtube))
        val card = ResultCards.of(t).single() as ResultCard.Media
        assertEquals("com.google.android.youtube", card.pkg)
        assertEquals(2L, card.callId)
    }

    @Test fun noMediaCardWhenTheTurnMovedOn() {
        val t = turn(call(1, "open package=com.google.android.youtube", youtube), call(2, "open package=com.android.settings", screen("com.android.settings", "[1] TextView 'Wi-Fi' @1,1")))
        assertTrue(ResultCards.of(t).none { it is ResultCard.Media })
    }

    @Test fun appsSeenWithASessionCountAsMedia() {
        val t = turn(call(1, "open package=org.example.radio", screen("org.example.radio", "[1] TextView 'Live' @1,1")))
        assertTrue(ResultCards.of(t, isMedia = { false }).isEmpty())
        assertTrue(ResultCards.of(t, isMedia = { it == "org.example.radio" }).single() is ResultCard.Media)
        assertTrue(MediaCards.isMedia("org.example.radio", seen = setOf("org.example.radio")))
    }

    @Test fun onlyTheNewestMediaCardIsLive() {
        val turns = listOf(listOf<ResultCard>(ResultCard.Media(1, "com.spotify.music")), listOf(ResultCard.Media(5, "com.google.android.youtube")))
        assertEquals(setOf("media:5"), ResultCards.liveKeys(turns))
    }

    @Test fun cropsAreSixteenByNine() {
        // Video apps: the top of the portrait screen.
        assertArrayEquals(intArrayOf(0, 0, 360, 202), MediaCards.crop(360, 800, "com.google.android.youtube"))
        // Audio apps: a little above the middle.
        assertArrayEquals(intArrayOf(0, 203, 360, 202), MediaCards.crop(360, 800, "com.spotify.music"))
        // Landscape: the middle, full height.
        val c = MediaCards.crop(800, 360, "com.google.android.youtube")
        assertEquals(360, c[3])
        assertEquals(640, c[2])
        assertEquals(80, c[0])
    }

    // --- Calendar ---

    @Test fun calendarListWithEventsGetsADayCard() {
        val t = turn(call(1, "calendar action=list date=2026-10-12 days=1", "Events from Mon 12 Oct (event ids in brackets):\n- [4] Mon 12 Oct 09:00-10:00 Standup"))
        val card = ResultCards.of(t).single() as ResultCard.CalendarDay
        assertEquals("2026-10-12", card.date)
        assertEquals("1", card.days)
    }

    @Test fun emptyDayOrFailedListGetsNoCard() {
        assertTrue(ResultCards.of(turn(call(1, "calendar action=list days=1", "No events from Mon 12 Oct."))).isEmpty())
        assertTrue(ResultCards.of(turn(call(1, "calendar action=list date=12/10", "Dates are YYYY-MM-DD, got '12/10'.", isError = true))).isEmpty())
    }

    @Test fun listRangeReadsTheCallLikeTheTool() {
        assertEquals(day to 7, CalendarTool.listRange(null, null, day))
        assertEquals(LocalDate.of(2026, 10, 20) to 1, CalendarTool.listRange("2026-10-20", "1", day))
        assertEquals(day to 366, CalendarTool.listRange("", "900", day))
        assertNull(CalendarTool.listRange("next monday", "1", day))
    }

    @Test fun allDayFirstThenByTimeWithPastRowsAndTheNowLine() {
        val events = listOf(event(1, "Standup", 9, 0, 15), event(2, "Lunch", 13, 0, location = "Café"), allDay(3, "Holiday"), event(4, "Gym", 18, 30))
        val view = CalendarDays.view(events, day, 1, at(day, 12, 0), zone)
        assertEquals(listOf("Holiday", "Standup", "Lunch", "Gym"), view.rows.map { it.event.title })
        assertEquals(listOf(null, "09:00", "13:00", "18:30"), view.rows.map { it.time })
        assertEquals(listOf(false, true, false, false), view.rows.map { it.past })
        // Before Lunch, the next row to start.
        assertEquals(2, view.nowLine)
        assertEquals(0, view.more)
    }

    @Test fun nowLineAfterTheLastRowOnceTheDayIsDone() {
        val view = CalendarDays.view(listOf(event(1, "Standup", 9, 0)), day, 1, at(day, 21, 0), zone)
        assertEquals(1, view.nowLine)
        assertTrue(view.rows.single().past)
    }

    @Test fun eventUnderWaySitsAboveTheLine() {
        val view = CalendarDays.view(listOf(event(1, "Call", 11, 30, 60), event(2, "Review", 14, 0)), day, 1, at(day, 12, 0), zone)
        assertEquals(1, view.nowLine)
        assertTrue(view.rows.none { it.past })
    }

    @Test fun capsAtFiveRowsWithMore() {
        val events = (0 until 8).map { event(it.toLong(), "E$it", 8 + it, 0, 30) }
        val early = CalendarDays.view(events, day, 1, at(day, 9, 45), zone)
        assertEquals(5, early.rows.size)
        assertEquals(3, early.more)
        assertEquals(2, early.nowLine)
        // The line would fall among the hidden rows: none.
        assertNull(CalendarDays.view(events, day, 1, at(day, 14, 10), zone).nowLine)
        // Right after the last shown row, before "3 more": that's where now is.
        assertEquals(5, CalendarDays.view(events, day, 1, at(day, 12, 45), zone).nowLine)
    }

    @Test fun otherDaysHaveNoNowLine() {
        val tomorrow = day.plusDays(1)
        val view = CalendarDays.view(listOf(event(1, "Dentist", 10, 0, d = tomorrow)), tomorrow, 1, at(day, 12, 0), zone)
        assertNull(view.nowLine)
        assertTrue(view.rows.none { it.past })
    }

    @Test fun aWeekGroupsByDayAndDropsOutOfRangeAllDayEvents() {
        val events = listOf(
            event(1, "Flight", 7, 0, d = day.plusDays(2)), allDay(2, "Trip", day.plusDays(2)), event(3, "Standup", 9, 0),
            allDay(4, "Last week", day.minusDays(1)),
        )
        val view = CalendarDays.view(events, day, 7, at(day, 8, 0), zone)
        assertTrue(view.multiDay)
        assertEquals(listOf("Standup", "Trip", "Flight"), view.rows.map { it.event.title })
        assertEquals(listOf(day, day.plusDays(2), day.plusDays(2)), view.rows.map { it.day })
        assertEquals(0, view.nowLine)
    }

    @Test fun titles() {
        assertEquals("Today", CalendarDays.title(day, 1, day))
        assertEquals("Tomorrow", CalendarDays.title(day.plusDays(1), 1, day))
        assertEquals("Today – Sun 18 Oct", CalendarDays.title(day, 7, day))
        assertEquals("Tue 20 Oct", CalendarDays.dayName(LocalDate.of(2026, 10, 20), day))
    }

    // --- Email ---

    /** A message open in Gmail (one message, sender row with Reply, body from the WebView, the reply bar). */
    private val message = screen(
        "com.google.android.gm",
        "[1] ImageButton 'Navigate up' @48,104 click",
        "[2] Button 'Archive' @444,104 click",
        "[3] Button 'Delete' @528,104 click",
        "[4] Button 'Mark unread' @606,104 click",
        "[5] ImageButton 'More options' @684,104 click",
        "[6] ScrollView 'conversation_pager' @360,880 scroll",
        "[7] TextView 'Your order has shipped Inbox' @300,232",
        "[8] Button 'Add star' (Add star) @672,232 click",
        "[9] ImageView 'Show contact information for Amazon.com' @64,348 click",
        "[10] TextView 'Amazon.com' @220,330",
        "[11] TextView '10:24 AM' @388,330",
        "[12] Button 'to me (Show details)' @190,374 click",
        "[13] ImageButton 'Reply' @604,348 click",
        "[14] ImageButton 'More options' @684,348 click",
        "[15] WebView 'Your order has shipped' @360,900",
        "[16] View 'Hello Akram,' @180,460",
        "[17] View 'Your package with Kindle Paperwhite (16 GB) is on its way and should arrive Thursday 15 October.' @360,520",
        "[18] Button 'Track package' @360,640 click",
        "[19] View 'Thanks for shopping with us.' @200,720",
        "[20] Button 'Reply' @150,1500 click",
        "[21] Button 'Reply all' @360,1500 click",
        "[22] Button 'Forward' @570,1500 click",
    )

    @Test fun readsAnOpenMessage() {
        val email = EmailReads.parse(message)!!
        assertEquals("Amazon.com", email.from)
        assertEquals("Your order has shipped", email.subject)
        assertTrue(email.quote, email.quote.startsWith("Hello Akram, Your package with Kindle Paperwhite (16 GB) is on its way"))
        assertTrue(email.quote.endsWith("Thanks for shopping with us."))
    }

    @Test fun readsTheExpandedMessageOfAThread() {
        val thread = screen(
            "com.google.android.gm",
            "[1] ImageButton 'Navigate up' @48,104 click",
            "[2] Button 'Archive' @444,104 click",
            "[3] Button 'Delete' @528,104 click",
            "[4] ImageButton 'More options' @684,104 click",
            "[5] TextView 'Lunch on Friday?' @260,232",
            "[6] Button 'Inbox' @560,232 click",
            "[7] TextView 'Sam Lee' @200,330",
            "[8] TextView 'Are we still on for Friday? I booked the usual place' @330,370",
            "[9] TextView 'Oct 10' @640,330",
            "[10] TextView 'Akram Ahmed' @200,450",
            "[11] TextView 'Sounds good, see you there' @330,490",
            "[12] TextView 'Yesterday' @640,450",
            "[13] TextView 'Sam Lee' @200,570",
            "[14] TextView '9:05 AM' @360,570",
            "[15] TextView 'to me, Priya' @190,612 click",
            "[16] ImageButton 'Reply' @604,590 click",
            "[17] ImageButton 'More options' @684,590 click",
            "[18] View 'Great — moving it to 1pm since Priya can only make it then. Table for three under my name.' @360,700",
            "[19] View 'Sam' @80,780",
            "[20] Button 'Reply' @150,1500 click",
            "[21] Button 'Forward' @570,1500 click",
        )
        val email = EmailReads.parse(thread)!!
        assertEquals("Sam Lee", email.from)
        assertEquals("Lunch on Friday?", email.subject)
        assertTrue(email.quote.startsWith("Great — moving it to 1pm"))
        assertEquals(1, ResultCards.of(turn(call(1, "screen", thread))).filterIsInstance<ResultCard.Email>().size)
    }

    @Test fun inboxAndComposeAreNotMessages() {
        val inbox = screen(
            "com.google.android.gm",
            "[1] ImageButton 'Open navigation drawer' @48,104 click",
            "[2] EditText 'Search in mail' @360,104 click edit",
            "[3] TextView 'Primary' @80,220",
            "[4] View 'Amazon.com, Your order has shipped, 10:24 AM, Your package is on its way' @360,320 click",
            "[5] Button 'Compose' @560,1400 click",
        )
        val compose = screen(
            "com.google.android.gm",
            "[1] ImageButton 'Navigate up' @48,104 click",
            "[2] Button 'Attach file' @520,104 click",
            "[3] Button 'Send' @604,104 click",
            "[4] EditText 'To' @360,250 click edit",
            "[5] EditText 'Subject' @360,350 click edit",
        )
        assertNull(EmailReads.parse(inbox))
        assertNull(EmailReads.parse(compose))
        assertNull(EmailReads.parse(message.replace("com.google.android.gm", "com.google.android.youtube")))
    }

    @Test fun scrolledPastTheHeaderIsNotSure() {
        // The sender row scrolled away: the only Reply is the bar at the bottom, and a body line starting "To" isn't a recipient.
        val scrolled = screen(
            "com.google.android.gm",
            "[1] ImageButton 'Navigate up' @48,104 click",
            "[2] Button 'Archive' @444,104 click",
            "[3] ImageButton 'More options' @684,104 click",
            "[4] View 'Welcome to the club' @360,300",
            "[5] View 'To get started, open the app' @360,400",
            "[6] Button 'Reply' @150,1500 click",
            "[7] Button 'Forward' @570,1500 click",
        )
        assertNull(EmailReads.parse(scrolled))
    }

    @Test fun theLastMessageReadWins() {
        val inbox = screen("com.google.android.gm", "[1] Button 'Compose' @560,1400 click")
        val t = turn(call(1, "open package=com.google.android.gm", inbox), call(2, "act action=tap element=4", message), call(3, "act action=key key=back", inbox))
        val card = ResultCards.of(t).single() as ResultCard.Email
        assertEquals(2L, card.callId)
        assertEquals("Your order has shipped", card.email.subject)
    }

    @Test fun longQuotesAreCut() {
        val long = message.replace("Thanks for shopping with us.", "x".repeat(70) + " " + "y".repeat(70) + " " + "z".repeat(70) + " " + "w".repeat(70))
        val quote = EmailReads.parse(long)!!.quote
        assertTrue(quote.length <= 321)
        assertTrue(quote.endsWith("…"))
    }
}
