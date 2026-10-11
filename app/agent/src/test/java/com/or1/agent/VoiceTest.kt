package com.or1.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    // --- Sentences ---

    @Test fun splitsOnSentenceEnds() {
        assertEquals(listOf("Done.", "Timer, ten minutes!", "Anything else?"), Sentences.split("Done. Timer, ten minutes! Anything else?"))
    }

    @Test fun keepsAbbreviationsInitialsDecimalsAndTimes() {
        assertEquals(listOf("Dr. Smith is at 3.5 km, e.g. near St. Mary's.", "J. R. Tolkien wrote it."),
            Sentences.split("Dr. Smith is at 3.5 km, e.g. near St. Mary's. J. R. Tolkien wrote it."))
        assertEquals(listOf("It's 10.30 now."), Sentences.split("It's 10.30 now."))
    }

    @Test fun lowercaseAfterADotIsNotANewSentence() {
        assertEquals(listOf("Version 2. still works fine."), Sentences.split("Version 2. still works fine."))
    }

    @Test fun ellipsesAndQuotesStayWithTheirSentence() {
        assertEquals(listOf("Well… maybe.", "He said “stop.”", "Then left?!"), Sentences.split("Well… maybe. He said “stop.” Then left?!"))
    }

    @Test fun linesAndBulletsAreBoundaries() {
        val text = "## Today\n- **Dentist** at 10:00\n- Call `Sam`\n\nSee [the map](https://x.y/z) for more"
        assertEquals(listOf("Today", "Dentist at 10:00", "Call Sam", "See the map for more"), Sentences.split(text))
    }

    @Test fun codeAndPunctuationOnlyAreDropped() {
        assertEquals(listOf("Run this:", "Done."), Sentences.split("Run this:\n```\nls -la\n```\n---\nDone."))
    }

    @Test fun longSentencesAreCutAtACommaOrSpace() {
        val long = (1..60).joinToString(", ") { "item $it" } + "."
        val parts = Sentences.split(long, maxLength = 100)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 100 })
        assertTrue(parts.dropLast(1).all { it.endsWith(",") })
        assertEquals(long.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        val noCommas = (1..80).joinToString(" ") { "word" }
        assertTrue(Sentences.split(noCommas, maxLength = 50).all { it.length <= 50 && !it.startsWith(" ") })
    }

    @Test fun emptyTextHasNoSentences() {
        assertTrue(Sentences.split("").isEmpty())
        assertTrue(Sentences.split("  \n\n ").isEmpty())
    }

    // --- Levels ---

    @Test fun recognizerAndMicLevelsAreScaled() {
        assertEquals(0f, VoiceLevels.fromRecognizerDb(-5f))
        assertEquals(0.5f, VoiceLevels.fromRecognizerDb(4f), 1e-6f)
        assertEquals(1f, VoiceLevels.fromRecognizerDb(20f))
        assertEquals(0f, VoiceLevels.fromDbfs(-90f))
        assertEquals(0.5f, VoiceLevels.fromDbfs(-35f), 1e-6f)
        assertEquals(1f, VoiceLevels.fromDbfs(0f))
    }

    @Test fun smoothingAttacksFastAndReleasesSlowly() {
        val m = LevelMeter(attack = 0.7f, release = 0.3f)
        assertEquals(0.7f, m.smooth(1f), 1e-6f)
        assertEquals(0.91f, m.smooth(1f), 1e-6f)
        assertEquals(0.637f, m.smooth(0f), 1e-6f)
        assertEquals(1f, LevelMeter(attack = 1f).smooth(5f))
    }

    @Test fun meterEmitsAtMostEvery100ms() {
        val m = LevelMeter()
        val emitted = (0L..1000L step 20).mapNotNull { t -> m.push(0.5f, t)?.let { t } }
        assertEquals((0L..1000L step 100).toList(), emitted)
        m.reset()
        assertEquals(0.35f, m.push(0.5f, 1010)!!, 1e-6f)
    }

    @Test fun wordEnvelopePulsesAndDecays() {
        val e = WordEnvelope(decayMs = 200f, quietMs = 700)
        e.onWord(1000, 5)
        val atWord = e.level(1000)
        assertEquals(0.85f, atWord, 1e-6f)
        assertTrue(e.level(1200) < atWord)
        assertEquals(0.12f, e.level(1690), 1e-6f)
        // Long words cap at 1.
        e.onWord(2000, 30)
        assertEquals(1f, e.level(2000))
    }

    @Test fun wordEnvelopeWobblesWithoutWords() {
        val e = WordEnvelope()
        val levels = (0L..2000L step 100).map { e.level(it) }
        assertTrue(levels.all { it in 0f..1f })
        assertTrue(levels.toSet().size > 5)
        // Words stop for a while: back to the wobble.
        e.onWord(3000, 4)
        assertTrue(e.level(3000) > 0.7f)
        assertEquals(WordEnvelope().level(4000), e.level(4000))
    }
}
