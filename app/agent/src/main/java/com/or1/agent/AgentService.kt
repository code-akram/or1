package com.or1.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.or1.agent.tools.HiddenDisplay
import com.or1.agent.tools.Tasks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keeps the agent's process alive while a request runs in the background (another app in front). */
class AgentService : Service() {
    override fun onCreate() {
        super.onCreate()
        AgentHub.init(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("or1 agent")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "agent"
    }
}

/**
 * Dev-only remote driver for evals: `am broadcast -n com.or1.agent/.DebugReceiver --es text "..." --es id N
 * [--ez fresh true]`. Guarded by android.permission.DUMP, which only shell and system hold.
 * The reply is appended to files/replies.jsonl under the given id. `--es routine ID` runs that routine now;
 * `--el backdate_chat_ms N` makes the chat look idle N ms longer (tests the auto new chat after an hour);
 * `--es lockdown off|on` lifts or restores the system UI lockdown ([Lockdown]) for development;
 * `--ez stop true` stops the request or routine run in progress;
 * `--es dismiss pkg1,pkg2` clears those apps' notifications (eval cleanup: the shell can't clear other apps');
 * `--es audio test` runs the speaker/mic check, `--es audio listen` just measures what the mic hears ([AudioCheck];
 * result in logcat tag or1-audio and files/audio.txt);
 * `--es wallpaper black` sets a plain black wallpaper on home and lock (provisioning: nothing but or1 from boot on).
 *
 * Effects ([ActivityLog]): `--es effects list|today` writes the recent (or today's) effects, newest first and eval ones
 * included, one per line (`id tier state source label | call | detail`) to files/effects.txt and logcat tag or1-activity;
 * `--es undo ID|last` undoes one (`last`: the newest that can be), then writes the list again once it settles.
 *
 * Evidence ([Evidence]): `--es evidence list` writes the effects that have an evidence frame (`id tier label | path |
 * size`) to files/evidence.txt; `--es taps N` writes the last N (default 20) tap marks on the work display to
 * files/taps.txt (tag or1-evidence). `--es acts list` writes today's routine acts ([AgentHub.routineActs]) to
 * files/routine-acts.txt.
 *
 * Voice ([Voice.kt]): `--es voice speak:<text>` speaks it (`bargein:<text>` plays it 1.5 s into or1's next spoken reply; `wake:<phrase>` adds a wake phrase in memory, empty clears it) and `--es voice listen` records one utterance; the result (final text,
 * peak level, sentences, word boundaries, recogniser and network) goes to files/voice.txt and logcat tag or1-voice;
 * `--es voice stop` stops both. `--es voice support` writes what each recogniser (default and on-device) says about its
 * languages (installed, downloading, downloadable, online) and the plan it picks; `--es voice download` triggers the
 * language model download on each recogniser that hasn't got it (progress in logcat on API 34). `--es now dump` writes the Now state ([NowState]) to files/now.txt (tag or1-now).
 *
 * Hidden display checks: `--es display info|create|launch|tap|back|home|park|keep|restore|focus|snap|front|move|tool [--es name N --es args JSON] [--es pkg P] [--ei x X --ei y Y]
 * [--ei task T]`; results go to logcat (tag or1-display) and files/display.txt.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentHub.init(context.applicationContext)
        intent.getStringExtra("display")?.let { displayCommand(context, it, intent); return }
        intent.getStringExtra("voice")?.let { voiceCommand(context.applicationContext, it); return }
        intent.getStringExtra("now")?.let { writeNow(context); return }
        intent.getStringExtra("routine")?.let { Routines.fire(it); return }
        if (intent.getBooleanExtra("stop", false)) { AgentHub.stop(); return }
        intent.getStringExtra("lockdown")?.let { Lockdown.setEnabled(it != "off"); return }
        intent.getLongExtra("backdate_chat_ms", 0).takeIf { it > 0 }?.let { AgentHub.backdateChat(it); return }
        intent.getStringExtra("dismiss")?.let { NotifListener.dismiss(it.split(',').toSet()); return }
        intent.getStringExtra("effects")?.let { writeEffects(context, today = it == "today"); return }
        intent.getStringExtra("evidence")?.let { writeEvidence(context); return }
        intent.getStringExtra("taps")?.let { writeTaps(context, it.toIntOrNull() ?: 20); return }
        intent.getStringExtra("acts")?.let { writeRoutineActs(context); return }
        intent.getStringExtra("undo")?.let { id ->
            val target = if (id == "last") ActivityLog.effects.value.firstOrNull { it.canUndo() }?.id else id
            val started = target != null && AgentHub.undo(target)
            android.util.Log.i("or1-activity", "undo $id -> ${target ?: "nothing to undo"}: ${if (started) "started" else "not undoable"}")
            kotlin.concurrent.thread {
                // Tier 1 settles in a few seconds; a tier-2 undo is a whole agent request.
                val deadline = System.currentTimeMillis() + 120_000
                while (target != null && ActivityLog.get(target)?.state == UndoState.PENDING && System.currentTimeMillis() < deadline) Thread.sleep(500)
                writeEffects(context, today = false)
            }
            return
        }
        if (intent.getStringExtra("wallpaper") == "black") {
            val black = android.graphics.Bitmap.createBitmap(72, 160, android.graphics.Bitmap.Config.ARGB_8888)
            black.eraseColor(android.graphics.Color.BLACK)
            val flags = android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
            android.app.WallpaperManager.getInstance(context).setBitmap(black, null, true, flags)
            return
        }
        intent.getStringExtra("audio")?.let { mode ->
            kotlin.concurrent.thread {
                val check = AudioCheck(context.applicationContext)
                val result = try { if (mode == "listen") check.listen() else check.run() } catch (e: Exception) { "error: $e" }
                android.util.Log.i("or1-audio", result)
                java.io.File(context.filesDir, "audio.txt").writeText(result + "\n")
            }
            return
        }
        val text = intent.getStringExtra("text") ?: return
        AgentHub.submit(
            text,
            requestId = intent.getStringExtra("id"),
            fresh = intent.getBooleanExtra("fresh", false),
            // As if spoken in voice mode (low effort, short reply), for evals of voice requests.
            spoken = intent.getBooleanExtra("voice", false),
        )
    }
}

private fun writeEffects(context: Context, today: Boolean) {
    val all = ActivityLog.effects.value
    val text = (if (today) all.today() else all.take(50)).joinToString("\n") { e ->
        "${e.id} tier${e.tier.level} ${e.stateAt().name.lowercase()} ${e.source}${e.sourceId?.let { ":$it" }.orEmpty()} ${e.label} | ${e.call}" +
            (e.detail?.let { " | $it" }.orEmpty())
    }.ifEmpty { "no effects" }
    android.util.Log.i("or1-activity", text)
    java.io.File(context.filesDir, "effects.txt").writeText(text + "\n")
}

/** Effects with an evidence frame, newest first: `id tierN label | path | size or missing`. */
private fun writeEvidence(context: Context) {
    val text = ActivityLog.effects.value.filter { it.evidence != null }.joinToString("\n") { e ->
        val file = java.io.File(e.evidence!!)
        "${e.id} tier${e.tier.level} ${e.label} | ${e.evidence} | ${if (file.exists()) "${file.length()} bytes" else "missing"}"
    }.ifEmpty { "no evidence" }
    android.util.Log.i("or1-evidence", text)
    java.io.File(context.filesDir, "evidence.txt").writeText(text + "\n")
}

/** The last [n] tap marks on the work display, oldest first: `kind x,y on WxH, N ms ago`. */
private fun writeTaps(context: Context, n: Int) {
    val now = android.os.SystemClock.elapsedRealtime()
    val text = Evidence.recentTaps(n).joinToString("\n") { t ->
        "${t.kind} ${t.x.toInt()},${t.y.toInt()} on ${t.displayW}x${t.displayH}, ${now - t.atElapsed} ms ago"
    }.ifEmpty { "no taps" }
    android.util.Log.i("or1-evidence", text)
    java.io.File(context.filesDir, "taps.txt").writeText(text + "\n")
}

/** Today's routine acts ([AgentHub.routineActs]), oldest first. */
private fun writeRoutineActs(context: Context) {
    val text = AgentHub.routineActs.value.joinToString("\n") { a ->
        "${a.id} ${a.label} | ${java.util.Date(a.finished)} | failed=${a.failed} stopped=${a.stopped} steps=${a.steps} " +
            "effects=${a.effects.joinToString(",") { it.id }} | ${a.request.take(60)} -> ${a.reply.take(80).replace('\n', ' ')}"
    }.ifEmpty { "no routine acts" }
    android.util.Log.i("or1-routines", text)
    java.io.File(context.filesDir, "routine-acts.txt").writeText(text + "\n")
}

private fun writeNow(context: Context) {
    val now = System.currentTimeMillis()
    val elapsed = android.os.SystemClock.elapsedRealtime()
    val text = buildString {
        appendLine("chips: " + NowState.chips.value.joinToString(", ") { it.key }.ifEmpty { "none" })
        NowState.timers.value.forEach { t ->
            appendLine("timer ${t.id} ${t.state.name.lowercase()} label=${t.label} left=${t.endsAtElapsed?.let { (it - elapsed) / 1000 }}s paused_left=${t.remainingMs?.div(1000)}s or1=${t.fromOr1}")
        }
        NowState.alarm.value?.let { appendLine("alarm ${java.util.Date(it.at)} label=${it.label} effect=${it.effectId}") } ?: appendLine("alarm none")
        NowState.event.value?.let { appendLine("event ${it.eventId} “${it.title}” in ${(it.begin - now) / 60_000} min, ends ${java.util.Date(it.end)}") } ?: appendLine("event none")
        appendLine("routine ${NowState.routine.value}")
        NowState.notifications.value.forEach { g ->
            appendLine("notifications ${g.app} (${g.pkg}): ${g.items.size}")
            g.items.forEach { appendLine("  ${it.key} | ${it.title} | ${it.text.take(60)} | icon=${it.iconPackage}:${it.iconRes}") }
        }
    }.trimEnd()
    android.util.Log.i("or1-now", text)
    java.io.File(context.filesDir, "now.txt").writeText(text + "\n")
}

/** `speak:<text>`, `listen`, `stop`, `support` or `download`; see [DebugReceiver]. */
private fun voiceCommand(context: Context, command: String) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        val result = try {
            when {
                command.startsWith("speak:") -> debugSpeak(context, command.removePrefix("speak:"))
                command == "listen" -> debugListen(context)
                command.startsWith("selftest") -> debugSelfTest(context, command.substringAfter(':', "Set a timer for ten minutes."))
                command.startsWith("play:") -> debugSelfTest(context, command.removePrefix("play:"), listen = false)
                command.startsWith("wake:") -> {
                    val words = command.removePrefix("wake:").trim()
                    com.or1.agent.ui.WakePhrase.extra = words.ifEmpty { null }?.let { Regex("\\b${Regex.escape(it)}\\b[\\s,.!?]*", RegexOption.IGNORE_CASE) }
                    "wake: extra phrase ${words.ifEmpty { "cleared" }}"
                }
                // Barge-in: synthesised now, played 1.5 s into or1's next spoken reply (within 60 s).
                command.startsWith("bargein:") -> debugSelfTest(context, command.removePrefix("bargein:"), listen = false, overSpeech = true)
                command == "support" || command == "download" -> debugSupport(context, download = command == "download")
                command == "stop" -> {
                    VoiceEngines.output(context).stop()
                    VoiceEngines.input(context).cancel()
                    "stopped"
                }
                else -> "unknown voice command '$command' (speak:<text>, listen, selftest[:<text>], play:<text>, bargein:<text>, stop, support, download)"
            }
        } catch (e: Exception) {
            "error: $e"
        }
        android.util.Log.i("or1-voice", result)
        java.io.File(context.filesDir, "voice.txt").writeText(result + "\n")
    }
}

private suspend fun debugSpeak(context: Context, text: String): String = kotlinx.coroutines.coroutineScope {
    val out = VoiceEngines.output(context)
    val started = System.currentTimeMillis()
    val ready = kotlinx.coroutines.withTimeoutOrNull(10_000) { out.available.first { it } } != null
    var peak = 0f
    val spoken = sortedSetOf<Int>()
    val level = launch { out.level.collect { peak = maxOf(peak, it) } }
    val progress = launch {
        out.progress.collect { p ->
            if (p.id == "debug" && p.index >= 0 && spoken.add(p.index)) android.util.Log.i("or1-voice", "sentence ${p.index}: ${p.sentences[p.index]}")
        }
    }
    out.speak(text, "debug")
    val finished = kotlinx.coroutines.withTimeoutOrNull(120_000) { out.progress.first { it.id == "debug" && !it.speaking } } != null
    level.cancel()
    progress.cancel()
    val p = out.progress.value
    "speak: ready=$ready finished=$finished engine=${(out as? AndroidSpeechOutput)?.engine ?: "default"} " +
        "sentences=${p.sentences.size} spoken=$spoken words=${(out as? AndroidSpeechOutput)?.words} peak=${"%.2f".format(peak)} " +
        "took=${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)}s\n" + p.sentences.withIndex().joinToString("\n") { "${it.index}: ${it.value}" }
}

/** The recognisers' language support, or (with [download]) a model download triggered where it's missing. */
private suspend fun debugSupport(context: Context, download: Boolean): String {
    val input = VoiceEngines.input(context) as? AndroidSpeechInput ?: return "not the Android recogniser"
    val report = kotlinx.coroutines.withTimeoutOrNull(15_000) {
        kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
            val done = { r: String -> if (cont.isActive) cont.resumeWith(Result.success(r)) }
            if (download) input.download(done) else input.support(done)
        }
    } ?: "no answer from the recognisers in 15 s"
    return (if (download) "download:\n" else "support:\n") + report
}

/**
 * Speech in, without a person: the phrase is synthesised to a file, then played through a MediaPlayer while listening.
 * The player takes no audio focus, so the recogniser's focus request doesn't pause it the way it pauses TTS.
 */
private suspend fun debugSelfTest(context: Context, phrase: String, listen: Boolean = true, overSpeech: Boolean = false): String = kotlinx.coroutines.coroutineScope {
    val wav = java.io.File(context.cacheDir, "voice-selftest.wav")
    val synthesised = kotlinx.coroutines.withTimeoutOrNull(20_000) {
        kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            var tts: android.speech.tts.TextToSpeech? = null
            tts = android.speech.tts.TextToSpeech(context) { status ->
                val engine = tts ?: return@TextToSpeech
                if (status != android.speech.tts.TextToSpeech.SUCCESS) { engine.shutdown(); if (cont.isActive) cont.resumeWith(Result.success(false)); return@TextToSpeech }
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { engine.shutdown(); if (cont.isActive) cont.resumeWith(Result.success(true)) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { engine.shutdown(); if (cont.isActive) cont.resumeWith(Result.success(false)) }
                })
                engine.synthesizeToFile(phrase, null, wav, "selftest")
            }
        }
    } == true
    if (!synthesised || !wav.exists()) return@coroutineScope "selftest: synthesis failed"
    val player = android.media.MediaPlayer().apply {
        setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        setDataSource(wav.path)
        prepare()
    }
    // play: only the sound, for driving the UI's own listening (push-to-talk, voice mode) from adb.
    if (!listen) {
        if (overSpeech) {
            val speaking = kotlinx.coroutines.withTimeoutOrNull(60_000) { VoiceEngines.output(context).progress.first { it.speaking && it.index >= 0 } }
            if (speaking == null) {
                player.release()
                return@coroutineScope "bargein: or1 didn't speak within 60 s"
            }
            kotlinx.coroutines.delay(1_500)
        }
        player.start()
        kotlinx.coroutines.delay(player.duration.toLong() + 200)
        player.release()
        return@coroutineScope "played: \"$phrase\""
    }
    val play = launch {
        kotlinx.coroutines.delay(800)
        player.start()
    }
    val heard = debugListen(context)
    play.cancel()
    player.release()
    "selftest: phrase=\"$phrase\"\n$heard"
}

private suspend fun debugListen(context: Context): String = kotlinx.coroutines.coroutineScope {
    val input = VoiceEngines.input(context)
    val service = android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
    val onDevice = runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    val started = System.currentTimeMillis()
    var peak = 0f
    var partials = 0
    val level = launch { input.level.collect { peak = maxOf(peak, it) } }
    val partial = launch { input.partial.collect { if (it.isNotBlank()) { partials++; android.util.Log.i("or1-voice", "partial: $it") } } }
    input.start()
    kotlinx.coroutines.withTimeoutOrNull(3_000) { input.state.first { it == ListenState.LISTENING || it == ListenState.PROCESSING } }
    if (!input.available) {
        // Level meter only: give it a few seconds of sound, then stop.
        kotlinx.coroutines.delay(5_000)
        input.stop()
    }
    var done = kotlinx.coroutines.withTimeoutOrNull(20_000) { input.state.first { it == ListenState.IDLE || it == ListenState.ERROR } } != null
    if (!done) {
        input.stop()
        done = kotlinx.coroutines.withTimeoutOrNull(8_000) { input.state.first { it == ListenState.IDLE || it == ListenState.ERROR } } != null
        if (!done) input.cancel()
    }
    level.cancel()
    partial.cancel()
    "listen: final=${input.result.value?.let { "\"$it\"" }} peak=${"%.2f".format(peak)} partials=$partials state=${input.state.value} " +
        "error=${input.error.value?.let { "${it.kind}: ${it.message}" }} finished=$done took=${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)}s\n" +
        "available=${input.available} service=$service on_device=$onDevice network_validated=$online"
}

private fun displayCommand(context: Context, command: String, intent: Intent) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        val result = try {
            when (command) {
                "create" -> "id=${HiddenDisplay.ensure()} a11y=${HiddenDisplay.accessibilitySees()}"
                "launch" -> {
                    val pkg = intent.getStringExtra("pkg")!!
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)!!
                    "landed on display ${HiddenDisplay.launch(launch, pkg)}"
                }
                "tap" -> {
                    val id = HiddenDisplay.ensure()!!
                    intent.getIntExtra("x", 0).toFloat().let { x -> intent.getIntExtra("y", 0).toFloat().let { y ->
                        "injected=${HiddenDisplay.injectStroke(x, y, x, y, 50, id)}" } }
                }
                "back" -> "injected=${HiddenDisplay.injectKey(android.view.KeyEvent.KEYCODE_BACK, HiddenDisplay.ensure()!!)}"
                "home" -> { HiddenDisplay.goHome(); "home" }
                "park" -> { HiddenDisplay.park(); "parked" }
                // What a request's end does: park, unless media plays in front (Stage).
                "keep" -> { Stage.park(); "keep: front=${HiddenDisplay.id?.let { HiddenDisplay.topAppPackage(it) }} media=${NowState.media.value}" }
                "restore" -> { HiddenDisplay.restoreFocus(); "restored" }
                "focus" -> "focused=${HiddenDisplay.focus()}"
                "tool" -> debugTools(context).run(intent.getStringExtra("name")!!, org.json.JSONObject(intent.getStringExtra("args") ?: "{}")).text
                "snap" -> {
                    java.io.File(context.filesDir, "preview.png").outputStream().use {
                        HiddenDisplay.latestFrame(500)!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    "snap saved"
                }
                "front" -> "moved=${HiddenDisplay.bringToFront()}"
                "move" -> "moved=${Tasks.move(intent.getIntExtra("task", -1), HiddenDisplay.ensure()!!)}"
                else -> ""
            } + "\n" + info()
        } catch (e: Exception) {
            "error: $e"
        }
        android.util.Log.i("or1-display", result)
        java.io.File(context.filesDir, "display.txt").writeText(result + "\n")
    }
}

private var tools: com.or1.agent.tools.Tools? = null
private fun debugTools(context: Context) = tools ?: com.or1.agent.tools.Tools(context.applicationContext).also { tools = it }

private fun info(): String {
    val id = HiddenDisplay.id ?: return "no hidden display (unavailable=${HiddenDisplay.unavailable})"
    return "display=$id a11y=${HiddenDisplay.accessibilitySees()} top0=${HiddenDisplay.topAppPackage(0)} topN=${HiddenDisplay.topAppPackage(id)} " +
        "tasksN=${Tasks.on(id)} tasks0=${Tasks.on(0)} preview=${HiddenDisplay.preview.value?.let { "${it.width}x${it.height}" }}"
}
