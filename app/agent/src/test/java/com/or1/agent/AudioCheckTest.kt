package com.or1.agent

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCheckTest {
    private fun tone(hz: Double, amplitude: Double = 0.5) =
        ShortArray(16_000) { (sin(2 * PI * hz * it / 16_000) * amplitude * Short.MAX_VALUE).toInt().toShort() }

    @Test fun goertzelFindsTheTone() {
        val pcm = tone(1000.0)
        assertTrue(AudioCheck.goertzel(pcm, 1000.0) > 1000 * AudioCheck.goertzel(pcm, 700.0))
    }

    @Test fun dbfsOfAHalfScaleSine() = assertEquals(-9.03, AudioCheck.dbfs(AudioCheck.rms(tone(1000.0))), 0.1)

    @Test fun silenceIsVeryQuiet() = assertTrue(AudioCheck.dbfs(AudioCheck.rms(ShortArray(100))) < -150)
}
