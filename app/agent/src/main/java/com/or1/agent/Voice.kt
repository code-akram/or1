package com.or1.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.EnumMap
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// --- Interfaces (docs/ui-ledger.md §6): the UI only sees these, so ElevenLabs can replace the Android engines ---------

enum class ListenState { IDLE, LISTENING, PROCESSING, ERROR }

data class VoiceError(val kind: Kind, val message: String) {
    enum class Kind {
        /** Heard nothing it could turn into words. */
        NO_MATCH,
        /** No speech before the recogniser gave up. */
        TIMEOUT,
        NETWORK,
        /** No recognition service on the phone: the level meter still runs. */
        UNAVAILABLE,
        PERMISSION,
        BUSY,
        OTHER,
    }
}

/**
 * Speech to text, one utterance per [start]. [state] goes LISTENING → PROCESSING (after [stop], or when the recogniser
 * hears the end of speech) → IDLE with [result] set, or ERROR with [error] set. [level] is the input level 0..1 at
 * about 10 Hz, for the listening columns. All methods may be called from any thread.
 */
interface SpeechInput {
    /** Whether words can come out at all; without it, [start] only runs the level meter and reports UNAVAILABLE. */
    val available: Boolean
    val state: StateFlow<ListenState>
    /** The words so far (cleared on [start]). */
    val partial: StateFlow<String>
    /** The utterance's final text once it's done; null until then (cleared on [start]). */
    val result: StateFlow<String?>
    val error: StateFlow<VoiceError?>
    val level: StateFlow<Float>
    /**
     * [focus]: duck other audio while listening (push-to-talk, voice mode). The wake-phrase listener passes false, so
     * media on the idle home isn't ducked whenever it listens.
     */
    fun start(focus: Boolean = true)
    /** Stop listening and finish: the words heard so far become [result]. */
    fun stop()
    /** Stop and drop the utterance. */
    fun cancel()
    fun release()
}

/**
 * What's being spoken: the reply [id], its [sentences], the one being spoken ([index], -1 before the first starts) and
 * whether speech is under way. One value, so the UI never sees a sentence index from another reply.
 */
data class SpeechProgress(val id: String? = null, val sentences: List<String> = emptyList(), val index: Int = -1, val speaking: Boolean = false)

/** Text to speech. [speak] replaces whatever is being said. [level] is an output envelope 0..1, for the speaking columns. */
interface SpeechOutput {
    /** False until the engine is ready, and if there's none. */
    val available: StateFlow<Boolean>
    val progress: StateFlow<SpeechProgress>
    val level: StateFlow<Float>
    fun speak(text: String, id: String)
    fun stop()
    fun release()
}

/** Where the UI gets its voice engines. Swap the factories (e.g. for ElevenLabs) before first use. */
object VoiceEngines {
    @Volatile
    var inputFactory: (Context) -> SpeechInput = { AndroidSpeechInput(it) }
    @Volatile
    var outputFactory: (Context) -> SpeechOutput = { AndroidSpeechOutput(it) }
    private var input: SpeechInput? = null
    private var output: SpeechOutput? = null

    @Synchronized
    fun input(context: Context): SpeechInput = input ?: inputFactory(context.applicationContext).also { input = it }

    @Synchronized
    fun output(context: Context): SpeechOutput = output ?: outputFactory(context.applicationContext).also { output = it }

    /** Frees both (they're created again on next use). */
    @Synchronized
    fun release() {
        input?.release()
        output?.release()
        input = null
        output = null
    }
}

// --- Android SpeechRecognizer -----------------------------------------------------------------------------------------

/** Which recogniser: the default `voice_recognition_service`, or the system's on-device one. */
enum class RecognizerRoute { DEFAULT, ON_DEVICE }

/** What a recogniser says about its languages (`checkRecognitionSupport`, API 33); [error] is set when it can't say. */
data class LanguageSupport(
    val installed: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val supported: List<String> = emptyList(),
    val online: List<String> = emptyList(),
    val error: Int? = null,
) {
    override fun toString() =
        if (error != null) "can't check (error $error)" else "installed=$installed pending=$pending supported=$supported online=$online"
}

/** How to listen for [language] on [route]. */
data class LanguagePlan(val kind: Kind, val language: String, val route: RecognizerRoute = RecognizerRoute.DEFAULT) {
    /** In order of preference. */
    enum class Kind {
        /** Installed on the device, or recognised online. */
        READY,
        /** The recogniser can't say: just try. */
        UNKNOWN,
        /** Its model is downloading. */
        PENDING,
        /** It could be recognised on the device once its model is downloaded. */
        DOWNLOAD,
        /** Not offered at all. */
        NONE,
    }
}

/** Picking the recognition language from what the recognisers report (JVM-tested). */
object VoiceLanguages {
    /** The languages to ask for, best first: the device's if it's English, and en-US (or/1 speaks English). */
    fun candidates(locale: Locale): List<String> {
        val device = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" }
        return if (device != null && locale.language == "en") listOf(device, "en-US").distinct() else listOfNotNull("en-US", device)
    }

    /** The first of [candidates] in [languages]: the same tag, else the same language in another region. */
    fun match(candidates: List<String>, languages: List<String>): String? {
        val norm = languages.associateBy { it.replace('_', '-').lowercase() }
        candidates.forEach { c -> norm[c.lowercase()]?.let { return it } }
        candidates.forEach { c ->
            val lang = c.substringBefore('-').lowercase()
            norm.entries.firstOrNull { it.key.substringBefore('-') == lang }?.let { return it.value }
        }
        return null
    }

    fun plan(candidates: List<String>, support: LanguageSupport, route: RecognizerRoute): LanguagePlan {
        val first = candidates.first()
        if (support.error != null) return LanguagePlan(LanguagePlan.Kind.UNKNOWN, first, route)
        match(candidates, support.installed)?.let { return LanguagePlan(LanguagePlan.Kind.READY, it, route) }
        match(candidates, support.online)?.let { return LanguagePlan(LanguagePlan.Kind.READY, it, route) }
        match(candidates, support.pending)?.let { return LanguagePlan(LanguagePlan.Kind.PENDING, it, route) }
        match(candidates, support.supported)?.let { return LanguagePlan(LanguagePlan.Kind.DOWNLOAD, it, route) }
        return LanguagePlan(LanguagePlan.Kind.NONE, first, route)
    }

    /** The most usable plan; on a tie, the earlier one (the default recogniser comes first). */
    fun best(plans: List<LanguagePlan>): LanguagePlan? = plans.minByOrNull { it.kind.ordinal }

    /** "English", for the "downloading English" note. */
    fun name(tag: String): String = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    fun downloading(tag: String) = "Voice input isn't ready yet — downloading ${name(tag)}"
    fun missing(tag: String) = "Voice input isn't ready yet — ${name(tag)} isn't installed for speech recognition"
}

/**
 * [SpeechInput] on Android's [SpeechRecognizer] (here Google's GoogleTTSRecognitionService, the default
 * voice_recognition_service), preferring offline recognition. The recogniser lives on the main thread, as the API
 * requires; every call is posted there. Callbacks of an utterance that was cancelled or replaced are ignored.
 *
 * The language is explicit ([VoiceLanguages.candidates]). On API 33 each recogniser (the default one and, if there is
 * one, the on-device one) is asked which languages it has installed, is downloading or could download
 * (`checkRecognitionSupport`); the best is used, and a model download is triggered when the language is supported
 * but not installed. If listening still fails with "language unavailable", the other recogniser is tried, then a
 * download is triggered. Whenever words can't come out (no service, the language missing), [start] runs an
 * [AudioRecord] level meter instead: [state] is LISTENING with [error] set (UNAVAILABLE, with the reason) until [stop].
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    private val defaultAvailable: Boolean by lazy { SpeechRecognizer.isRecognitionAvailable(context) }
    private val onDeviceAvailable: Boolean by lazy { runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false) }
    override val available: Boolean by lazy { defaultAvailable || onDeviceAvailable }
    private val _state = MutableStateFlow(ListenState.IDLE)
    override val state: StateFlow<ListenState> = _state
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial
    private val _final = MutableStateFlow<String?>(null)
    override val result: StateFlow<String?> = _final
    private val _error = MutableStateFlow<VoiceError?>(null)
    override val error: StateFlow<VoiceError?> = _error
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val recognizers = EnumMap<RecognizerRoute, SpeechRecognizer>(RecognizerRoute::class.java)
    /** The utterance in progress; callbacks carry theirs and are dropped once it moves on. */
    private var session = 0
    private val meter = LevelMeter()
    @Volatile
    private var metering = false
    /** A plan that worked; until then each [start] asks the recognisers again (a download may have finished). */
    private var plan: LanguagePlan? = null
    /** The route and language of the utterance in progress, and the routes it has tried. */
    private var route = RecognizerRoute.DEFAULT
    private var language = "en-US"
    private val tried = mutableSetOf<RecognizerRoute>()
    /** Between [start] and the plan: a [stop] then ends the utterance before it begins. */
    private var resolving = false
    /** When a model download was last triggered, per route: not again within [DOWNLOAD_AGAIN_MS]. */
    private val downloadAt = EnumMap<RecognizerRoute, Long>(RecognizerRoute::class.java)

    private val routes: List<RecognizerRoute>
        get() = listOfNotNull(RecognizerRoute.DEFAULT.takeIf { defaultAvailable }, RecognizerRoute.ON_DEVICE.takeIf { onDeviceAvailable })

    private fun candidates() = VoiceLanguages.candidates(Locale.getDefault())

    /** Whether this utterance ducks other audio; see [SpeechInput.start]. */
    @Volatile
    private var takesFocus = true

    override fun start(focus: Boolean) = onMain {
        takesFocus = focus
        session++
        // An utterance still being processed is dropped; the recogniser would otherwise be busy.
        metering = false
        resolving = false
        recognizers.values.forEach { runCatching { it.cancel() } }
        _partial.value = ""
        _final.value = null
        _error.value = null
        meter.reset()
        _level.value = 0f
        if (!available) {
            meterOnly(VoiceError(VoiceError.Kind.UNAVAILABLE, "Voice input isn't available yet: there's no speech recognition service."))
            return@onMain
        }
        setState(ListenState.LISTENING)
        val id = session
        resolving = true
        resolvePlan { p ->
            if (id != session) return@resolvePlan
            resolving = false
            begin(p)
        }
    }

    private fun begin(p: LanguagePlan) {
        tried.clear()
        when (p.kind) {
            LanguagePlan.Kind.PENDING, LanguagePlan.Kind.DOWNLOAD -> {
                if (p.kind == LanguagePlan.Kind.DOWNLOAD) triggerDownload(p.route, p.language)
                meterOnly(VoiceError(VoiceError.Kind.UNAVAILABLE, VoiceLanguages.downloading(p.language)))
            }
            // NONE: the recogniser may under-report; a "language unavailable" error is handled when it comes.
            else -> listen(p.route, p.language)
        }
    }

    private fun listen(r: RecognizerRoute, lang: String) {
        tried += r
        route = r
        language = lang
        val recognizer = recognizer(r)
        if (recognizer == null) {
            val next = routes.firstOrNull { it !in tried }
            if (next != null) listen(next, lang) else meterOnly(VoiceError(VoiceError.Kind.UNAVAILABLE, "Voice input couldn't start."))
            return
        }
        recognizer.setRecognitionListener(Listener(session))
        Log.i(TAG, "listen: route=$r language=$lang")
        setState(ListenState.LISTENING)
        recognizer.startListening(intent(lang))
    }

    private fun intent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // or1's name and wake phrase, which the recogniser otherwise drops as noise (API 33).
        .apply { if (Build.VERSION.SDK_INT >= 33) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, arrayListOf("Hey or one", "or one", "or1")) }

    private fun recognizer(r: RecognizerRoute): SpeechRecognizer? = recognizers[r] ?: runCatching {
        when (r) {
            RecognizerRoute.DEFAULT -> SpeechRecognizer.createSpeechRecognizer(context)
            RecognizerRoute.ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
    }.onFailure { Log.w(TAG, "no $r recogniser", it) }.getOrNull()?.also { recognizers[r] = it }

    private fun drop(r: RecognizerRoute) {
        recognizers.remove(r)?.let { runCatching { it.destroy() } }
    }

    override fun stop() = onMain {
        if (resolving) {
            resolving = false
            session++
            setState(ListenState.IDLE)
            return@onMain
        }
        if (metering) {
            metering = false
            setState(ListenState.IDLE)
            return@onMain
        }
        if (_state.value != ListenState.LISTENING) return@onMain
        setState(ListenState.PROCESSING)
        recognizers[route]?.stopListening()
    }

    override fun cancel() = onMain {
        session++
        resolving = false
        metering = false
        recognizers.values.forEach { it.cancel() }
        setState(ListenState.IDLE)
        _level.value = 0f
    }

    override fun release() = onMain {
        cancel()
        RecognizerRoute.entries.forEach(::drop)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Holds audio focus while listening (so media ducks), unless [takesFocus] is off; releases it after. */
    private fun setState(s: ListenState) {
        _state.value = s
        val listening = s == ListenState.LISTENING || s == ListenState.PROCESSING
        if (listening && takesFocus) AudioFocus.hold(context, "listen") else AudioFocus.drop(context, "listen")
    }

    // --- Languages ---

    /** The plan for the next utterance: the one that last worked, or the best the recognisers report now. */
    private fun resolvePlan(onPlan: (LanguagePlan) -> Unit) {
        plan?.let { return onPlan(it) }
        val candidates = candidates()
        if (Build.VERSION.SDK_INT < 33) return onPlan(LanguagePlan(LanguagePlan.Kind.UNKNOWN, candidates.first(), routes.first()))
        probeAll { results ->
            val plans = results.map { (r, support) -> VoiceLanguages.plan(candidates, support, r) }
            val best = VoiceLanguages.best(plans) ?: LanguagePlan(LanguagePlan.Kind.UNKNOWN, candidates.first(), routes.first())
            Log.i(TAG, "plan: $best of $plans")
            if (best.kind == LanguagePlan.Kind.READY) plan = best
            onPlan(best)
        }
    }

    /** Each route's [LanguageSupport], in route order (API 33; before that, "can't check"). */
    private fun probeAll(onDone: (List<Pair<RecognizerRoute, LanguageSupport>>) -> Unit) {
        val out = mutableListOf<Pair<RecognizerRoute, LanguageSupport>>()
        fun next(i: Int) {
            if (i >= routes.size) return onDone(out)
            probe(routes[i]) { support ->
                Log.i(TAG, "support ${routes[i]}: $support")
                out += routes[i] to support
                next(i + 1)
            }
        }
        next(0)
    }

    private fun probe(r: RecognizerRoute, onSupport: (LanguageSupport) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return onSupport(LanguageSupport(error = -1))
        val recognizer = recognizer(r) ?: return onSupport(LanguageSupport(error = -1))
        var done = false
        lateinit var timeout: Runnable
        val finish = { s: LanguageSupport ->
            if (!done) {
                done = true
                main.removeCallbacks(timeout)
                onSupport(s)
            }
        }
        // A service that never answers.
        timeout = Runnable { finish(LanguageSupport(error = -2)) }
        main.postDelayed(timeout, PROBE_TIMEOUT_MS)
        try {
            // Some versions want a listener before they connect.
            recognizer.setRecognitionListener(Listener(-1))
            recognizer.checkRecognitionSupport(intent(candidates().first()), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) = finish(
                    LanguageSupport(s.installedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.supportedOnDeviceLanguages, s.onlineLanguages),
                )

                override fun onError(error: Int) = finish(LanguageSupport(error = error))
            })
        } catch (e: Exception) {
            Log.w(TAG, "checkRecognitionSupport on $r failed", e)
            finish(LanguageSupport(error = -3))
        }
    }

    /** Asks [r] to download [lang]'s model (API 33); false if it can't be asked. */
    private fun triggerDownload(r: RecognizerRoute, lang: String, force: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val now = SystemClock.elapsedRealtime()
        val last = downloadAt[r]
        if (!force && last != null && now - last < DOWNLOAD_AGAIN_MS) return true
        val recognizer = recognizer(r) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                recognizer.triggerModelDownload(intent(lang), context.mainExecutor, object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {
                        Log.i(TAG, "download $r $lang: $completedPercent%")
                    }

                    override fun onSuccess() {
                        Log.i(TAG, "download $r $lang: done")
                        plan = null
                    }

                    override fun onScheduled() {
                        Log.i(TAG, "download $r $lang: scheduled")
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "download $r $lang: error $error")
                    }
                })
            } else {
                recognizer.triggerModelDownload(intent(lang))
            }
            downloadAt[r] = now
            Log.i(TAG, "download $r $lang: triggered")
            true
        } catch (e: Exception) {
            Log.w(TAG, "triggerModelDownload on $r failed", e)
            false
        }
    }

    /** Debug (`--es voice support`): what each recogniser reports, and the plan. Calls back on the main thread. */
    fun support(onReport: (String) -> Unit) = onMain {
        val candidates = candidates()
        val head = "api=${Build.VERSION.SDK_INT} locale=${Locale.getDefault().toLanguageTag()} candidates=$candidates " +
            "default=$defaultAvailable on_device=$onDeviceAvailable service=" +
            android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        probeAll { results ->
            val plans = results.map { (r, s) -> VoiceLanguages.plan(candidates, s, r) }
            onReport(
                head + "\n" + results.joinToString("\n") { (r, s) -> "$r: $s" } +
                    "\nplans: $plans\nchosen: ${VoiceLanguages.best(plans)}\nworking plan: $plan",
            )
        }
    }

    /**
     * Debug (`--es voice download`): triggers the model download on every route whose plan isn't READY (on all of them
     * if they can't say), and reports what was asked. Progress (API 34) goes to logcat.
     */
    fun download(onReport: (String) -> Unit) = onMain {
        val candidates = candidates()
        probeAll { results ->
            val lines = results.map { (r, s) ->
                val p = VoiceLanguages.plan(candidates, s, r)
                val asked = if (p.kind == LanguagePlan.Kind.READY) "already ready" else "triggered=${triggerDownload(r, p.language, force = true)}"
                "$r ${p.kind} ${p.language}: $asked ($s)"
            }
            plan = null
            onReport(lines.joinToString("\n").ifEmpty { "no recogniser" })
        }
    }

    private inner class Listener(private val id: Int) : RecognitionListener {
        private val live get() = id == session

        override fun onReadyForSpeech(params: Bundle?) {
            if (live && _state.value != ListenState.PROCESSING) setState(ListenState.LISTENING)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!live) return
            meter.push(VoiceLevels.fromRecognizerDb(rmsdB), SystemClock.uptimeMillis())?.let { _level.value = it }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!live) return
            setState(ListenState.PROCESSING)
            _level.value = 0f
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live) return
            first(partialResults)?.let { _partial.value = it }
        }

        override fun onResults(results: Bundle?) {
            if (!live) return
            session++ // Done: a late error from this utterance is ignored.
            _level.value = 0f
            // This route and language work: no need to ask again.
            if (plan == null) plan = LanguagePlan(LanguagePlan.Kind.READY, language, route).also { Log.i(TAG, "working plan: $it") }
            val text = first(results) ?: _partial.value.ifBlank { null }
            if (text == null) {
                fail(VoiceError(VoiceError.Kind.NO_MATCH, "Didn't catch that."))
            } else {
                _final.value = text
                setState(ListenState.IDLE)
            }
        }

        override fun onError(error: Int) {
            if (!live) return
            session++
            _level.value = 0f
            Log.i(TAG, "recogniser $route ($language) error $error")
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                languageMissing()
                return
            }
            val e = VoiceLevels.error(error)
            // Some recognisers end with no-match after partial results: keep what was heard.
            val heard = _partial.value.ifBlank { null }
            if (heard != null && (e.kind == VoiceError.Kind.NO_MATCH || e.kind == VoiceError.Kind.TIMEOUT)) {
                _final.value = heard
                setState(ListenState.IDLE)
                return
            }
            // A busy or broken recogniser is made afresh next time.
            if (e.kind == VoiceError.Kind.BUSY || e.kind == VoiceError.Kind.OTHER) drop(route)
            fail(e)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun first(bundle: Bundle?) =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifBlank { null }
    }

    /** "Language unavailable": the other recogniser, else a download and the level meter with the reason. */
    private fun languageMissing() {
        plan = null
        val next = routes.firstOrNull { it !in tried }
        if (next != null) {
            Log.i(TAG, "$route has no $language: trying $next")
            listen(next, language)
            return
        }
        val downloading = tried.toList().map { triggerDownload(it, language) }.any { it }
        meterOnly(VoiceError(VoiceError.Kind.UNAVAILABLE, if (downloading) VoiceLanguages.downloading(language) else VoiceLanguages.missing(language)))
    }

    private fun fail(e: VoiceError) {
        Log.i(TAG, "listen: ${e.kind} ${e.message}")
        _error.value = e
        setState(ListenState.ERROR)
    }

    /** Words can't come out: say why ([error]) and still show that the mic hears, until [stop]. */
    private fun meterOnly(e: VoiceError) {
        Log.i(TAG, "level meter only: ${e.message}")
        _error.value = e
        setState(ListenState.LISTENING)
        startMeter()
    }

    /** No recogniser: still show that the mic hears, from the microphone's RMS every 100 ms. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted by provision.sh.
    private fun startMeter() {
        metering = true
        val id = session
        thread(name = "or1-voice-meter") {
            val rate = 16_000
            val chunk = ShortArray(rate / 10)
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk.size * 4))
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            if (recorder == null) {
                metering = false
                main.post { if (id == session) fail(VoiceError(VoiceError.Kind.PERMISSION, "The microphone couldn't be opened.")) }
                return@thread
            }
            try {
                recorder.startRecording()
                val started = SystemClock.uptimeMillis()
                // A minute at most: nobody is going to get words out of it.
                while (metering && id == session && SystemClock.uptimeMillis() - started < 60_000) {
                    val n = recorder.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    val level = VoiceLevels.fromDbfs(AudioCheck.dbfs(AudioCheck.rms(if (n == chunk.size) chunk else chunk.copyOf(n))).toFloat())
                    meter.push(level, SystemClock.uptimeMillis())?.let { _level.value = it }
                }
            } finally {
                recorder.stop()
                recorder.release()
                _level.value = 0f
                main.post { if (id == session && _state.value == ListenState.LISTENING) setState(ListenState.IDLE) }
                metering = false
            }
        }
    }

    private companion object {
        const val TAG = "or1-voice"
        const val PROBE_TIMEOUT_MS = 3_000L
        const val DOWNLOAD_AGAIN_MS = 10 * 60_000L
    }
}

/**
 * Transient audio focus while or1 listens or speaks, released when neither is: one request shared by both, held while
 * anyone holds it. MAY_DUCK, so media the user asked for (a video or1 started) gets quieter under or1's voice instead
 * of pausing (YouTube pauses on a plain transient loss and voice mode takes focus again every few seconds).
 */
object AudioFocus {
    private val holders = mutableSetOf<String>()
    private var request: AudioFocusRequest? = null

    @Synchronized
    fun hold(context: Context, who: String) {
        if (!holders.add(who) || holders.size > 1) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        request = r
        runCatching { am.requestAudioFocus(r) }.onFailure { Log.w("or1-voice", "audio focus", it) }
    }

    @Synchronized
    fun drop(context: Context, who: String) {
        if (!holders.remove(who) || holders.isNotEmpty()) return
        val r = request ?: return
        request = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(r) }
    }
}

// --- Android TextToSpeech -----------------------------------------------------------------------------------------

/**
 * [SpeechOutput] on Android's [TextToSpeech] (Google's engine when installed). A reply is split into sentences
 * ([Sentences.split]) and queued one utterance each, so [SpeechProgress.index] follows the voice. The level is a
 * synthetic envelope ([WordEnvelope]) pulsed on word boundaries (`onRangeStart`), sampled every 100 ms while speaking
 * only. Holds transient audio focus ([AudioFocus]) while speaking.
 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val appContext = context.applicationContext
    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available
    private val _progress = MutableStateFlow(SpeechProgress())
    override val progress: StateFlow<SpeechProgress> = _progress
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val envelope = WordEnvelope()
    private var ticker: Job? = null
    /** Each [speak] is a session; utterance ids are "session#index", and callbacks of older sessions are ignored. */
    @Volatile
    private var session = 0
    @Volatile
    private var ready = false
    private var pending: Pair<String, String>? = null
    /** Word boundaries reported so far (debug: does the engine send them?). */
    @Volatile
    var words = 0
        private set
    val engine: String?

    private val tts: TextToSpeech

    init {
        val google = "com.google.android.tts"
        val installed = runCatching { context.packageManager.getPackageInfo(google, 0) }.isSuccess
        engine = if (installed) google else null
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed: $status")
                return@TextToSpeech
            }
            tts.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            tts.setOnUtteranceProgressListener(progressListener)
            synchronized(this) {
                ready = true
                _available.value = true
                pending?.let { (text, id) -> pending = null; enqueue(text, id) }
            }
        }, engine)
    }

    override fun speak(text: String, id: String) {
        synchronized(this) {
            if (!ready) {
                pending = text to id
                _progress.value = SpeechProgress(id, Sentences.split(text), -1, speaking = true)
                AudioFocus.hold(appContext, "speak")
                return
            }
            enqueue(text, id)
        }
    }

    private fun enqueue(text: String, id: String) {
        val sentences = Sentences.split(text)
        val s = ++session
        envelope.reset()
        if (sentences.isEmpty()) {
            tts.stop()
            _progress.value = SpeechProgress(id, emptyList(), -1, speaking = false)
            AudioFocus.drop(appContext, "speak")
            return
        }
        _progress.value = SpeechProgress(id, sentences, -1, speaking = true)
        AudioFocus.hold(appContext, "speak")
        sentences.forEachIndexed { i, sentence ->
            tts.speak(sentence, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$s#$i")
        }
    }

    override fun stop() {
        synchronized(this) {
            pending = null
            session++
            if (ready) tts.stop()
            finish()
        }
    }

    override fun release() {
        stop()
        runCatching { tts.shutdown() }
    }

    @Synchronized
    private fun finish() {
        ticker?.cancel()
        ticker = null
        _progress.value = _progress.value.copy(speaking = false)
        _level.value = 0f
        AudioFocus.drop(appContext, "speak")
    }

    /** (session, sentence) of an utterance id, or null if it belongs to an older [speak]. */
    private fun parse(utteranceId: String?): Int? {
        val (s, i) = utteranceId?.split('#')?.takeIf { it.size == 2 } ?: return null
        return if (s.toIntOrNull() == session) i.toIntOrNull() else null
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val i = parse(utteranceId) ?: return
            synchronized(this@AndroidSpeechOutput) {
                if (parse(utteranceId) == null) return
                _progress.value = _progress.value.copy(index = i, speaking = true)
                if (ticker?.isActive != true) ticker = scope.launch {
                    while (isActive) {
                        _level.value = envelope.level(SystemClock.uptimeMillis())
                        delay(100)
                    }
                }
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            parse(utteranceId) ?: return
            words++
            envelope.onWord(SystemClock.uptimeMillis(), end - start)
        }

        override fun onDone(utteranceId: String?) {
            val i = parse(utteranceId) ?: return
            if (i >= _progress.value.sentences.lastIndex) finish()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) {
            val i = parse(utteranceId) ?: return
            Log.w(TAG, "speak error $errorCode on sentence $i")
            if (i >= _progress.value.sentences.lastIndex) finish()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            parse(utteranceId) ?: return
            finish()
        }
    }

    private companion object {
        const val TAG = "or1-voice"
    }
}

// --- Pure parts (JVM-tested) -----------------------------------------------------------------------------------------

/** Level scales and the recogniser's error codes. */
object VoiceLevels {
    /** SpeechRecognizer.onRmsChanged gives roughly -2 dB (quiet) to 10 dB (loud speech). */
    fun fromRecognizerDb(db: Float): Float = ((db + 2f) / 12f).coerceIn(0f, 1f)

    /** Mic level in dBFS: -60 (room) to -10 (shouting at the phone). */
    fun fromDbfs(dbfs: Float): Float = ((dbfs + 60f) / 50f).coerceIn(0f, 1f)

    fun error(code: Int): VoiceError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> VoiceError(VoiceError.Kind.NO_MATCH, "Didn't catch that.")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError(VoiceError.Kind.TIMEOUT, "Didn't hear anything.")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceError(VoiceError.Kind.NETWORK, "Voice input needs the network (error $code).")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError(VoiceError.Kind.PERMISSION, "No permission to use the microphone.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceError(VoiceError.Kind.BUSY, "Voice input is busy (error $code).")
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            VoiceError(VoiceError.Kind.UNAVAILABLE, "Voice input doesn't have this language (error $code).")
        else -> VoiceError(VoiceError.Kind.OTHER, "Voice input failed (error $code).")
    }
}

/**
 * Smooths a level (fast attack, slower release, so columns jump up and fall back) and paces it to one value per
 * [intervalMs]: [push] returns the value to show, or null when it's too soon since the last one.
 */
class LevelMeter(private val attack: Float = 0.7f, private val release: Float = 0.3f, private val intervalMs: Long = 100) {
    private var value = 0f
    private var lastEmit = Long.MIN_VALUE

    fun reset() {
        value = 0f
        lastEmit = Long.MIN_VALUE
    }

    fun smooth(raw: Float): Float {
        val x = raw.coerceIn(0f, 1f)
        value += (x - value) * if (x > value) attack else release
        return value
    }

    fun push(raw: Float, now: Long): Float? {
        val v = smooth(raw)
        if (lastEmit != Long.MIN_VALUE && now - lastEmit < intervalMs) return null
        lastEmit = now
        return v
    }
}

/**
 * A speaking envelope without access to the audio: each word boundary is a pulse (longer words a little stronger)
 * that decays over [decayMs]. If the engine reports no word boundaries, a gentle synthetic wobble keeps the columns
 * alive while speaking.
 */
class WordEnvelope(private val decayMs: Float = 220f, private val quietMs: Long = 700) {
    private var lastWord = Long.MIN_VALUE
    private var peak = 0f

    fun reset() {
        lastWord = Long.MIN_VALUE
        peak = 0f
    }

    fun onWord(now: Long, length: Int) {
        lastWord = now
        peak = (0.55f + 0.06f * length).coerceAtMost(1f)
    }

    fun level(now: Long): Float {
        if (lastWord == Long.MIN_VALUE || now - lastWord > quietMs) {
            // Two slow sines out of step: irregular enough to read as speech.
            val t = now / 1000.0
            return (0.4 + 0.18 * sin(t * 9.0) + 0.12 * sin(t * 23.0)).toFloat().coerceIn(0f, 1f)
        }
        return (peak * exp(-(now - lastWord) / decayMs)).coerceAtLeast(0.12f)
    }
}

/** Replies into sentences to speak one at a time. */
object Sentences {
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "e.g", "i.e", "approx", "no", "jr", "sr", "inc", "ltd", "co",
        "mt", "ft", "min", "max", "fig", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )
    private const val CLOSERS = "\"'”’)]"

    /** Sentences of [text] with markdown taken out; ones longer than [maxLength] are split at a comma or a space. */
    fun split(text: String, maxLength: Int = 300): List<String> =
        plain(text).lines().map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { splitLine(it) }
            .flatMap { chop(it, maxLength) }
            .map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }

    /** Markdown out: code fences, emphasis, inline code, headings, bullets, links (their text stays). */
    fun plain(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*(?:[-*+•]|\\d+[.)])\\s+"), "")
        .replace(Regex("(\\*\\*|__|\\*|`)"), "")
        .replace(Regex("(?<![\\w])_(\\S[^_]*?)_(?![\\w])"), "$1")

    private fun splitLine(line: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                while (end < line.length && (line[end] in ".!?…" || line[end] in CLOSERS)) end++
                val atEnd = end >= line.length
                if (atEnd || line[end].isWhitespace()) {
                    val next = line.substring(end).trimStart().firstOrNull()
                    if (atEnd || (c != '.' || !isAbbreviation(line, start, i)) && next != null && !next.isLowerCase()) {
                        out += line.substring(start, end)
                        start = end
                    }
                }
                i = end
            } else {
                i++
            }
        }
        if (start < line.length) out += line.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The word before the '.' at [dot] is an abbreviation or an initial ("J. Smith"). */
    private fun isAbbreviation(line: String, from: Int, dot: Int): Boolean {
        var s = dot
        while (s > from && !line[s - 1].isWhitespace()) s--
        val word = line.substring(s, dot).trimStart(*"\"'“‘([".toCharArray()).lowercase()
        return word.length == 1 && word[0].isLetter() || word in abbreviations
    }

    private fun chop(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(": "))
                .maxOrNull()?.takeIf { it > max / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
