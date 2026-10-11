package com.or1.agent.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.ChatItem
import com.or1.agent.ListenState
import com.or1.agent.SpeechInput
import com.or1.agent.SpeechOutput
import com.or1.agent.UndoState
import com.or1.agent.UndoTier
import com.or1.agent.VoiceEngines
import com.or1.agent.VoiceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The drawer's "Speak replies" switch: chat replies to a request made by voice (push-to-talk, or dictated and sent)
 * are spoken too. Off by default: then only voice mode speaks.
 */
object VoicePrefs {
    private const val PREFS = "ui"
    private const val KEY = "speak_replies"

    var speakReplies by mutableStateOf(false)
        private set

    fun load(context: Context) {
        speakReplies = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        speakReplies = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}

/** The chat request that came from voice, whose reply is spoken when [VoicePrefs.speakReplies] is on. */
object VoiceReplies {
    data class Pending(val text: String, val after: Long)

    @Volatile
    var pending: Pending? = null

    /** Called just before [text] is sent from voice input. */
    fun mark(text: String) {
        if (!VoicePrefs.speakReplies || text.isBlank()) return
        pending = Pending(text.trim(), AgentHub.items.value.maxOfOrNull { it.id } ?: 0L)
    }
}

/**
 * What or1 says for a finished turn: the reply's prose, after a short phrase per effect the reply doesn't already
 * mention ("Done. Turned on Do Not Disturb."). Irreversible effects are always said, marked "irreversible". Undone
 * effects are left out; past three, the rest are counted. Without a reply, the turn's last note (why it stopped).
 */
fun spokenSummary(turn: Turn): String {
    val reply = turn.reply?.text?.trim().orEmpty()
    val phrases = turn.effects.map { it.effect }
        .filter { it.state != UndoState.UNDONE }
        .filter { it.tier == UndoTier.NONE || !Mentions.covered(it.label, reply) }
        .map { e -> e.label.trim().trimEnd('.') + if (e.tier == UndoTier.NONE) ", irreversible." else "." }
    val parts = mutableListOf<String>()
    if (phrases.isNotEmpty()) {
        parts += "Done."
        parts += phrases.take(3)
        if (phrases.size > 3) parts += if (phrases.size == 4) "And one more change." else "And ${phrases.size - 3} more changes."
    }
    when {
        reply.isNotEmpty() -> parts += reply
        parts.isEmpty() -> parts += turn.infos.lastOrNull()?.text?.trim()?.ifBlank { null } ?: "Sorry, I didn't get an answer."
    }
    return parts.joinToString(" ")
}

/** Does a reply already say what an effect label says? Most of the label's telling words appear in it. */
internal object Mentions {
    private val plain = setOf(
        "the", "and", "for", "with", "from", "into", "your", "you", "this", "that", "turned", "set", "added", "created",
        "removed", "saved", "deleted", "ran", "opened", "tapped", "toggled", "note", "on", "off", "to", "of", "in", "at", "a", "an",
    )

    fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { m ->
        m.value.let { w -> if (w.all(Char::isDigit)) w.trimStart('0').ifEmpty { "0" } else w }
    }.filter { it !in plain }.toSet()

    fun covered(label: String, reply: String): Boolean {
        val telling = words(label)
        if (telling.isEmpty() || reply.isBlank()) return false
        val said = words(reply)
        return telling.count { it in said } >= (telling.size * 0.6).coerceAtLeast(1.0)
    }
}

/**
 * Voice mode over the chat while [open] (the rail stays above it), and, whenever it's closed, the chat's spoken
 * replies (see [VoicePrefs]).
 */
@Composable
fun VoiceModeHost(open: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    remember { VoicePrefs.load(context.applicationContext) }
    LaunchedEffect(open) {
        if (open) return@LaunchedEffect
        val output = VoiceEngines.output(context)
        AgentHub.items.collect { items ->
            val pending = VoiceReplies.pending ?: return@collect
            if (items.none { it is ChatItem.Reply && it.id > pending.after }) return@collect
            val turn = groupTurns(items).lastOrNull { (it.user?.id ?: -1) > pending.after && it.user?.text == pending.text } ?: return@collect
            if (turn.reply == null) return@collect
            VoiceReplies.pending = null
            if (VoicePrefs.speakReplies) output.speak(spokenSummary(turn), "chat-${turn.key}")
        }
    }
    val slide = with(LocalDensity.current) { Motion.ENTER_SLIDE.roundToPx() }
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { slide },
        exit = ExitTransition.None,
    ) {
        VoiceMode(onClose)
    }
}

private enum class Phase { LISTENING, WORKING, SPEAKING }

/** What one listen produced. */
private sealed interface Heard {
    data class Words(val text: String) : Heard
    /** No match, a timeout, or stopped with nothing heard. */
    data object NoWords : Heard
    data class Problem(val message: String) : Heard
    /** [QUIET_MS] without a word. */
    data object Quiet : Heard
}

/** Voice mode ends after this long without words (silence, no match, or no recogniser). */
private const val QUIET_MS = 8_000L

/**
 * Hands-free: listen → ask or1 → speak the reply ([spokenSummary]) → listen again, until Stop or [QUIET_MS] without
 * words, then back to the chat. Full screen on ink0: the last thing the user said (small, text2), or1's reply in
 * Spoken type, only the sentence being spoken (text) and the one before (text2), the meter (`live` listening, `ember`
 * speaking, the march while or1 works) and one 72 dp Stop. A tap anywhere interrupts speech and listens (or, while
 * listening, finishes the utterance).
 */
@Composable
private fun VoiceMode(onClose: () -> Unit) {
    val context = LocalContext.current
    val input = remember { VoiceEngines.input(context) }
    val output = remember { VoiceEngines.output(context) }
    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var heard by remember { mutableStateOf("") }
    var replyId by remember { mutableStateOf<String?>(null) }
    // A reply shown without a voice (no TTS engine).
    var silentReply by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val close by rememberUpdatedState(onClose)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    KeepScreenOn()
    DisposableEffect(Unit) {
        onDispose {
            input.cancel()
            output.stop()
        }
    }
    LaunchedEffect(Unit) {
        keyboard?.hide()
        focus.clearFocus(force = true)
        var quietSince = SystemClock.uptimeMillis()
        while (true) {
            phase = Phase.LISTENING
            when (val h = listen(input, output, quietSince)) {
                is Heard.Words -> {
                    problem = null
                    heard = h.text
                    phase = Phase.WORKING
                    val turn = ask(h.text)
                    val id = "voice-${turn?.key ?: SystemClock.uptimeMillis()}"
                    replyId = id
                    phase = Phase.SPEAKING
                    say(output, turn?.let(::spokenSummary) ?: "Sorry, that didn't work.", id) { silentReply = it }
                    quietSince = SystemClock.uptimeMillis()
                }
                Heard.NoWords -> if (SystemClock.uptimeMillis() - quietSince >= QUIET_MS) break
                is Heard.Problem -> {
                    problem = h.message
                    if (SystemClock.uptimeMillis() - quietSince >= QUIET_MS) break
                    delay(1_000)
                }
                Heard.Quiet -> break
            }
        }
        close()
    }

    val onTap = {
        when (phase) {
            Phase.SPEAKING -> output.stop()
            Phase.LISTENING -> if (input.state.value == ListenState.LISTENING) input.stop()
            Phase.WORKING -> Unit
        }
    }
    val onStop = {
        if (phase == Phase.WORKING) AgentHub.stop()
        input.cancel()
        output.stop()
        close()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink0)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Interrupt",
                onClick = onTap,
            ),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(24.dp))
            UserLine(input, phase, heard)
            Spacer(Modifier.weight(1f))
            ReplyLines(output, replyId, silentReply)
            Spacer(Modifier.height(32.dp))
            Box(Modifier.fillMaxWidth().height(VOICE_BLOCK * 9), contentAlignment = Alignment.Center) {
                when (phase) {
                    Phase.LISTENING -> LevelMeter({ input.level.value }, Palette.Live, block = VOICE_BLOCK)
                    Phase.WORKING -> March(block = VOICE_BLOCK)
                    Phase.SPEAKING -> LevelMeter({ output.level.value }, Palette.Ember, block = VOICE_BLOCK)
                }
            }
            Spacer(Modifier.height(14.dp))
            StatusLine(input, phase, problem)
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Palette.Ink3)
                    .border(1.dp, Palette.Line, CircleShape)
                    .clickable(onClickLabel = "End voice mode", role = Role.Button, onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Stop, "End voice mode", tint = Palette.Text, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Voice mode's blocks: bigger than a card's, it's the whole screen. */
private val VOICE_BLOCK = 6.dp

/** The words so far while listening (text), else what the user last said (text2). */
@Composable
private fun UserLine(input: SpeechInput, phase: Phase, heard: String) {
    val partial by input.partial.collectAsState()
    val live = phase == Phase.LISTENING && partial.isNotBlank()
    Text(
        if (live) partial else heard,
        style = Type.Step.copy(color = if (live) Palette.Text else Palette.Text2),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp).clearAndSetSemantics { contentDescription = "You said" },
    )
}

/**
 * The sentence being spoken (text) and the one before (text2), one step per sentence from
 * [SpeechOutput.progress]; both text2 once speech stops. Coarse semantics: the whole reply, once.
 */
@Composable
private fun ReplyLines(output: SpeechOutput, replyId: String?, silentReply: String?) {
    val progress by output.progress.collectAsState()
    val p = progress.takeIf { replyId != null && it.id == replyId }
    val previous = if (silentReply == null) p?.sentences?.getOrNull(p.index - 1) else null
    val current = silentReply ?: p?.let { it.sentences.getOrNull(it.index) }
    val speaking = silentReply != null || p?.speaking == true
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = silentReply ?: p?.sentences?.joinToString(" ").orEmpty() },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        previous?.let { Text(it, style = Type.Spoken.copy(color = Palette.Text2), maxLines = 3, overflow = TextOverflow.Ellipsis) }
        current?.let {
            Text(it, style = Type.Spoken.copy(color = if (speaking) Palette.Text else Palette.Text2), maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusLine(input: SpeechInput, phase: Phase, problem: String?) {
    val state by input.state.collectAsState()
    val error by input.error.collectAsState()
    val text = when (phase) {
        // Listening with an error set: the level meter only, and why.
        Phase.LISTENING -> error?.takeIf { state == ListenState.LISTENING && it.kind == VoiceError.Kind.UNAVAILABLE }?.message
            ?: problem ?: "Listening"
        Phase.WORKING -> "Working"
        Phase.SPEAKING -> "Tap to interrupt"
    }
    Text(
        text,
        style = Type.Caption.copy(color = Palette.Text2),
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Listens once (or carries on with a push-to-talk utterance handed over by the composer). */
private suspend fun listen(input: SpeechInput, output: SpeechOutput, quietSince: Long): Heard {
    if (input.state.value == ListenState.IDLE || input.state.value == ListenState.ERROR) {
        output.stop()
        input.start()
    }
    val left = QUIET_MS - (SystemClock.uptimeMillis() - quietSince)
    val began = withTimeoutOrNull(left.coerceAtLeast(1)) {
        combine(input.state, input.partial) { s, p -> s != ListenState.LISTENING || p.isNotBlank() }.first { it }
    }
    if (began == null) {
        input.cancel()
        return Heard.Quiet
    }
    val done: suspend () -> Boolean = { input.state.first { it == ListenState.IDLE || it == ListenState.ERROR }; true }
    if (withTimeoutOrNull(30_000) { done() } == null) {
        input.stop()
        if (withTimeoutOrNull(5_000) { done() } == null) input.cancel()
    }
    val text = input.result.value?.trim()?.ifBlank { null }
    if (text != null && input.state.value == ListenState.IDLE) return Heard.Words(text)
    val e = input.error.value
    return if (e == null || e.kind == VoiceError.Kind.NO_MATCH || e.kind == VoiceError.Kind.TIMEOUT) Heard.NoWords else Heard.Problem(e.message)
}

/** Sends [text] to or1 and waits for the turn to finish; the turn, or null if it can't be found. */
private suspend fun ask(text: String): Turn? {
    val mark = AgentHub.items.value.maxOfOrNull { it.id } ?: 0L
    val done = CompletableDeferred<Unit>()
    AgentHub.submit(text, done = { done.complete(Unit) })
    done.await()
    return groupTurns(AgentHub.items.value).lastOrNull { (it.user?.id ?: -1) > mark && it.user?.text == text }
}

/** Speaks [text] to the end (or an interruption). Without a TTS engine, shows it for a while instead. */
private suspend fun say(output: SpeechOutput, text: String, id: String, showSilently: (String?) -> Unit) {
    showSilently(null)
    val ready = output.available.value || withTimeoutOrNull(5_000) { output.available.first { it } } != null
    if (!ready) {
        showSilently(text)
        delay((text.length * 60L).coerceIn(2_000, 8_000))
        return
    }
    output.speak(text, id)
    if (withTimeoutOrNull(180_000) { output.progress.first { it.id == id && !it.speaking } } == null) output.stop()
}

/** The screen stays on while voice mode is open (the chat's own keep-on follows only or1's work). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.activity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The drawer's "Speak replies" switch (next to Developer details). */
@Composable
fun SpeakRepliesSwitch() {
    val context = LocalContext.current
    val on = VoicePrefs.speakReplies
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .toggleable(value = on, role = Role.Switch, onValueChange = { VoicePrefs.set(context, it) })
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Speak replies", style = Type.Body, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink0, checkedTrackColor = Palette.Text, checkedBorderColor = Palette.Text,
                uncheckedThumbColor = Palette.Text3, uncheckedTrackColor = Palette.Ink2, uncheckedBorderColor = Palette.Line,
            ),
        )
    }
}
