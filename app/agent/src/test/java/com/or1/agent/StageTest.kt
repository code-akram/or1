package com.or1.agent

import android.media.session.PlaybackState
import com.or1.agent.tools.AudioNow
import com.or1.agent.tools.MediaSearch
import com.or1.agent.tools.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTest {
    @Test fun keepsWhatPlaysOrMayStart() {
        val media = setOf("com.google.android.youtube")
        assertTrue(StagePolicy.mayPlay("com.google.android.youtube", playing = false, mediaApps = media))
        assertTrue(StagePolicy.mayPlay("com.example.radio", playing = true, mediaApps = media))
        assertFalse(StagePolicy.mayPlay("com.android.deskclock", playing = false, mediaApps = media))
    }

    @Test fun findsAddressesInShellCommands() {
        assertEquals(
            listOf("https://www.youtube.com/results?search_query=lofi", "vnd.youtube:abc"),
            StagePolicy.addresses("am start --display 3 -d \"https://www.youtube.com/results?search_query=lofi\"; am start -d vnd.youtube:abc"),
        )
        assertEquals(emptyList<String>(), StagePolicy.addresses("am start -n com.android.deskclock/.DeskClock"))
    }

    private val yt = MediaNow("com.google.android.youtube", "Lofi radio", null, PlaybackState.STATE_PLAYING)
    private val music = MediaNow("com.spotify.music", "Song", "Band", PlaybackState.STATE_PAUSED)

    @Test fun controlsGoToTheKeptThenThePlayingSession() {
        assertEquals("com.spotify.music", MediaStatus.target(listOf(yt, music), kept = "com.spotify.music"))
        assertEquals("com.google.android.youtube", MediaStatus.target(listOf(music, yt), kept = null))
        assertEquals("com.spotify.music", MediaStatus.target(listOf(music), kept = "com.gone"))
        assertEquals(null, MediaStatus.target(emptyList(), kept = null))
    }

    @Test fun statusSaysWhatPlaysAndWhere() {
        val label = { pkg: String -> if (pkg == yt.pkg) "YouTube" else "Spotify" }
        assertEquals(
            "YouTube (com.google.android.youtube): playing “Lofi radio” (on its own screen; keeps playing while you work)\n" +
                "Spotify (com.spotify.music): paused “Song” by Band",
            MediaStatus.describe(listOf(yt, music), kept = yt.pkg, label = label),
        )
        assertEquals("Nothing is playing: no app has a media session.", MediaStatus.describe(emptyList(), null, label))
    }

    @Test fun statusSaysWhetherSoundComesOutAndHowLoud() {
        val label = { pkg: String -> if (pkg == yt.pkg) "YouTube" else "Spotify" }
        // 2/25 was the volume of the "no sound at all" report: barely audible on this speaker.
        val quiet = AudioNow(volume = 2, max = 25, muted = false, sounding = setOf(yt.pkg))
        assertEquals(
            "YouTube (com.google.android.youtube): playing “Lofi radio” (on its own screen; keeps playing while you work); sound is coming out\n" +
                "Spotify (com.spotify.music): paused “Song” by Band\n" +
                "Media volume: 2 of 25 (very quiet: the user may not hear it).",
            MediaStatus.describe(listOf(yt, music), kept = yt.pkg, label = label, audio = quiet),
        )
        assertEquals("Media volume: 2 of 25 (very quiet: the user may not hear it).", quiet.quietNote())
        val loud = AudioNow(volume = 12, max = 25, muted = false, sounding = emptySet())
        assertNull(loud.quietNote())
        assertEquals("no sound coming out yet (loading or buffering)", loud.soundOf(yt.pkg, playing = true))
        assertNull(loud.soundOf(music.pkg, playing = false))
        assertEquals("Media volume: 12 of 25 (muted).", AudioNow(12, 25, muted = true, sounding = null).volumeLine())
        val hushed = AudioNow(12, 25, muted = false, sounding = null, mutedByOr1 = { it == yt.pkg })
        assertEquals("muted by or1 while it's on the work screen", hushed.soundOf(yt.pkg, playing = true))
        assertEquals(
            "Nothing is playing: no app has a media session.\nMedia volume: 12 of 25.",
            MediaStatus.describe(emptyList(), null, label, loud),
        )
    }

    @Test fun searchPicksTheFirstVideoNotAnAdOrShort() {
        val rows = listOf(
            "Navigate up",
            "0 minutes 7 seconds of 1 minute 6 seconds",
            "Sponsored - Fauchées, the series - 20 seconds - Visit site",
            "Shorts - John Wick edit - 12 seconds - 3M views - play Short",
            "John Wick (2014) Official Trailer - 2 minutes, 31 seconds - Go to channel Lionsgate - 40M views - play video",
            "John Wick 4 Trailer - 1 minute, 2 seconds - play video",
        )
        assertEquals(4, MediaSearch.pick(rows))
        assertNull(MediaSearch.pick(listOf("Results", "Lofi radio - LIVE - play video")))
    }

    @Test fun searchWaitsForItsOwnResultsAndVideo() {
        assertTrue(MediaSearch.showsQuery(listOf("Home", "Dune part two trailer "), "dune Part Two trailer"))
        assertFalse(MediaSearch.showsQuery(listOf("John Wick trailer", "Up next"), "dune part two trailer"))
        // The old video, still playing minimized, isn't the new one; the clicked one is, even if it's the same.
        assertFalse(MediaSearch.started("John Wick", before = "John Wick", clicked = null))
        assertTrue(MediaSearch.started("Dune: Part Two", before = "John Wick", clicked = null))
        assertTrue(MediaSearch.started("John Wick", before = "John Wick", clicked = "John Wick - 2 minutes - play video"))
        assertFalse(MediaSearch.started(null, before = null, clicked = null))
    }
}
