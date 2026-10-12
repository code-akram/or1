package com.or1.agent

import android.media.session.PlaybackState
import com.or1.agent.tools.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
