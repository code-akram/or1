package com.or1.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hardware check for voice, before it depends on it (`DebugReceiver --es audio test`): records ambient sound, then
 * plays a 1 kHz tone through the speaker while recording, and looks for the tone in the recording. A clear 1 kHz peak
 * means both the speaker and the mic work. The recordings are saved as files/mic-ambient.wav and files/mic-loopback.wav.
 */
class AudioCheck(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun run(): String {
        val ambient = record(AMBIENT_SECONDS)
        writeWav(File(context.filesDir, "mic-ambient.wav"), ambient)
        val music = AudioManager.STREAM_MUSIC
        val volume = audio.getStreamVolume(music)
        // Loud enough for the mic to hear across the phone body, briefly.
        audio.setStreamVolume(music, (audio.getStreamMaxVolume(music) * 0.6).toInt(), 0)
        val loopback = try {
            var recorded = ShortArray(0)
            val recorder = thread { recorded = record(LOOPBACK_SECONDS) }
            Thread.sleep(250)
            playTone(TONE_HZ, TONE_SECONDS)
            recorder.join()
            recorded
        } finally {
            audio.setStreamVolume(music, volume, 0)
        }
        writeWav(File(context.filesDir, "mic-loopback.wav"), loopback)

        val ambientDb = dbfs(rms(ambient))
        val loopbackDb = dbfs(rms(loopback))
        val tone = goertzel(loopback, TONE_HZ)
        val neighbours = (goertzel(loopback, 700.0) + goertzel(loopback, 1300.0)) / 2
        val toneOverNeighbours = 10 * log10(tone / neighbours.coerceAtLeast(1e-9))
        val micWorks = ambient.any { it.toInt() != 0 }
        val speakerHeard = toneOverNeighbours > 15 && loopbackDb > ambientDb + 6
        return listOf(
            "mic: ${if (micWorks) "records" else "silent (all zeros)"}, ambient ${"%.1f".format(ambientDb)} dBFS",
            "loopback: ${"%.1f".format(loopbackDb)} dBFS, 1 kHz ${"%.1f".format(toneOverNeighbours)} dB over 700/1300 Hz",
            "verdict: " + when {
                !micWorks -> "the mic returns silence"
                speakerHeard -> "the speaker plays and the mic hears it"
                else -> "the tone wasn't heard: speaker silent, or mic too insensitive"
            },
        ).joinToString("\n")
    }

    /** Records [seconds] and reports the level and how strong 1 kHz is (`--es audio listen`: is something playing it?). */
    fun listen(seconds: Double = 2.0): String {
        val pcm = record(seconds)
        val tone = goertzel(pcm, TONE_HZ)
        val neighbours = (goertzel(pcm, 700.0) + goertzel(pcm, 1300.0)) / 2
        return "level ${"%.1f".format(dbfs(rms(pcm)))} dBFS, 1 kHz ${"%.1f".format(10 * log10(tone / neighbours.coerceAtLeast(1e-9)))} dB over 700/1300 Hz"
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted by provision.sh.
    private fun record(seconds: Double): ShortArray {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord didn't initialise (RECORD_AUDIO granted?)" }
        val samples = ShortArray((RATE * seconds).toInt())
        recorder.startRecording()
        var read = 0
        while (read < samples.size) {
            val n = recorder.read(samples, read, minOf(4096, samples.size - read))
            if (n <= 0) break
            read += n
        }
        recorder.stop()
        recorder.release()
        return samples
    }

    private fun playTone(hz: Double, seconds: Double) {
        val n = (RATE * seconds).toInt()
        val pcm = ShortArray(n) { i ->
            // 20 ms fades, so the tone starts and stops without clicks.
            val fade = minOf(1.0, i / (RATE * 0.02), (n - i) / (RATE * 0.02))
            (sin(2 * PI * hz * i / RATE) * 0.5 * fade * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
        track.write(pcm, 0, n)
        track.play()
        Thread.sleep((seconds * 1000).toLong() + 100)
        track.stop()
        track.release()
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        file.writeBytes(header + data)
    }

    companion object {
        private const val RATE = 16_000
        private const val AMBIENT_SECONDS = 1.0
        private const val LOOPBACK_SECONDS = 2.0
        private const val TONE_SECONDS = 1.5
        private const val TONE_HZ = 1000.0

        fun rms(pcm: ShortArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size.coerceAtLeast(1))

        fun dbfs(rms: Double): Double = 20 * log10((rms / Short.MAX_VALUE).coerceAtLeast(1e-9))

        /** Power of one frequency in [pcm] (Goertzel). */
        fun goertzel(pcm: ShortArray, hz: Double, rate: Int = RATE): Double {
            val coeff = 2 * cos(2 * PI * hz / rate)
            var s1 = 0.0
            var s2 = 0.0
            for (x in pcm) {
                val s = x + coeff * s1 - s2
                s2 = s1
                s1 = s
            }
            return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / pcm.size
        }
    }
}
