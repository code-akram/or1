package com.or1.agent

import com.or1.agent.ui.InboxReads
import com.or1.agent.ui.SpokenSoFar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeedTest {
    private val apps = mapOf("youtube" to "com.google.android.youtube", "gmail" to "com.google.android.gm", "clock" to "com.android.deskclock")
    private fun match(text: String) = LocalCommands.match(text) { name -> apps[name.lowercase().filter { it.isLetterOrDigit() }] }
    private fun call(text: String) = match(text) as? LocalCommands.Call

    @Test fun opensOneNamedApp() {
        val c = call("Open YouTube.")!!
        assertEquals("open", c.tool)
        assertEquals("com.google.android.youtube", c.args.getString("package"))
        assertTrue(c.args.getBoolean("show"))
        assertEquals("Opening You Tube.", call("please open you tube")!!.reply(""))
        // More than an app: the model.
        assertNull(match("Open YouTube and play John Wick"))
        assertNull(match("open my last email"))
        assertNull(match("open Spotify"))
    }

    @Test fun controlsMedia() {
        assertEquals("pause", call("Pause the music")!!.args.getString("action"))
        assertEquals("pause", call("stop the video")!!.args.getString("action"))
        assertEquals("play", call("Resume")!!.args.getString("action"))
        assertEquals("next", call("skip this song")!!.args.getString("action"))
        assertEquals("previous", call("previous track")!!.args.getString("action"))
        assertEquals("Playing “Lofi radio”.", call("next")!!.reply("YouTube (com.google.android.youtube): playing “Lofi radio”"))
        assertEquals("Paused.", call("pause")!!.reply("YouTube (com.google.android.youtube): paused “Lofi radio”"))
        // "Stop" alone could mean the timer, the music or the reply.
        assertNull(match("stop"))
        assertNull(match("play John Wick"))
    }

    @Test fun setsTheVolume() {
        assertEquals(LocalCommands.Volume(steps = 1), match("turn the volume up"))
        assertEquals(LocalCommands.Volume(steps = -1), match("Quieter please"))
        assertEquals(LocalCommands.Volume(steps = -1), match("Make it quieter."))
        assertEquals(LocalCommands.Volume(level = 5), match("set volume to 5"))
        assertEquals(LocalCommands.Volume(percent = 50), match("volume 50%"))
    }

    @Test fun timersAndAlarms() {
        assertEquals(300, call("Set a timer for 5 minutes")!!.args.getInt("seconds"))
        assertEquals(300, call("set a 5 minute timer")!!.args.getInt("seconds"))
        assertEquals(5400, call("timer for an hour and a half")!!.args.getInt("seconds"))
        assertEquals(5400, call("set a timer for 1 hour and 30 minutes")!!.args.getInt("seconds"))
        assertEquals(90, call("start a timer for ninety seconds")!!.args.getInt("seconds"))
        assertEquals(1800, call("set a timer for half an hour")!!.args.getInt("seconds"))
        assertEquals("Timer set for 1 hour and 30 minutes.", call("timer for 90 minutes")!!.reply("Timer set for 5400 seconds."))
        assertNull(match("set a timer"))
        assertNull(match("set a timer for the pasta"))
        assertEquals("stop_timer", call("Cancel the timer")!!.args.getString("action"))
        val alarm = call("Set an alarm for 6:30 a.m.")!!
        assertEquals(6, alarm.args.getInt("hour"))
        assertEquals(30, alarm.args.getInt("minute"))
        assertEquals(19, call("wake me up at 7 pm")!!.args.getInt("hour"))
        assertEquals(18, call("alarm for 18:00")!!.args.getInt("hour"))
        // Morning or evening? The model asks.
        assertNull(match("set an alarm for 7"))
    }

    @Test fun switchesAndQuestions() {
        val dnd = call("Turn on do not disturb")!!
        assertEquals("dnd", dnd.args.getString("setting"))
        assertEquals("on", dnd.args.getString("value"))
        assertEquals("wifi", call("wifi off")!!.args.getString("setting"))
        assertEquals("off", call("Turn Do Not Disturb off again.")!!.args.getString("value"))
        assertEquals("Wi-Fi is off.", call("turn off wi-fi")!!.reply("wifi set to off (was on)."))
        assertEquals(LocalCommands.Answer(LocalCommands.Answer.Kind.TIME), match("What time is it?"))
        assertEquals(LocalCommands.Answer(LocalCommands.Answer.Kind.DATE), match("what's the date today"))
        assertNull(match("what time is my next meeting"))
    }

    @Test fun readsGmailsInboxList() {
        val screen = """
            Foreground: com.google.android.gm. Screen 720x1600.
            [1] ScrollView '' @360,800 scroll
            [3] EditText 'Search in mail' @357,64 click edit
            [6] RecyclerView 'Thread list view' @360,872 scroll
            [7] TextView 'Primary' @83,192
            [8] FrameLayout 'Unread, Google, 2 messages, Security alert, A new sign-in on Mac OS' @360,327 click
            [9] ImageView 'Contact image' @88,306 click
            [10] TextView 'Google  2' @342,286
            [11] TextView '11:41 AM' @598,289
            [12] TextView 'Security alert' @384,329
            [13] TextView 'A new sign-in on Mac OS. We noticed a new sign-in to your' @384,370
            [14] ImageView 'Star' @656,356 click
            [15] FrameLayout 'Sam, Lunch on Friday?, Are you free, Oct 11' @360,517 click
            [16] ImageView 'Contact image' @88,496 click
            [17] TextView 'Sam' @375,476
            [18] TextView 'Oct 11' @631,479
            [19] TextView 'Lunch on Friday?' @384,519
            [20] TextView 'Are you free' @384,560
            [21] ImageView 'Star' @656,546 click
            [57] FrameLayout 'dev test, Fw: Board deck, Get Outlook' @360,1582 click
            [58] ImageView 'Contact image' @88,1582 click
            [59] TextView 'dev test' @378,1598
            [60] Button 'Compose' @536,1416 click
        """.trimIndent()
        val rows = InboxReads.parse(screen)
        assertEquals(2, rows.size)
        assertEquals(8, rows[0].id)
        assertEquals("unread · Google (2 messages) · 11:41 AM: Security alert — A new sign-in on Mac OS. We noticed a new sign-in to your", rows[0].line())
        assertFalse(rows[1].unread)
        assertEquals("Sam · Oct 11: Lunch on Friday? — Are you free", rows[1].line())
        assertTrue(InboxReads.parse(screen.replace("Thread list view", "Conversation")).isEmpty())
    }

    @Test fun saysOnlyWhatWasntStreamed() {
        val summary = listOf("Done.", "Timer set for 5 minutes.", "It rings at 17:08.")
        assertEquals(listOf("Done.", "It rings at 17:08."), SpokenSoFar.rest(summary, listOf("Timer set for 5 minutes.")))
        assertEquals(summary, SpokenSoFar.rest(summary, emptyList()))
        assertTrue(SpokenSoFar.same("Set a timer for 5 minutes.", "set a timer for 5 minutes"))
        assertFalse(SpokenSoFar.same("set a timer for 5", "set a timer for 50 minutes"))
    }
}
