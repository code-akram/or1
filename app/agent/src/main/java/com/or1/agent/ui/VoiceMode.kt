package com.or1.agent.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.or1.agent.MediaApps
import com.or1.agent.Stage
import com.or1.agent.NowState
import com.or1.agent.Sentences
import com.or1.agent.SpeechInput
import com.or1.agent.SpeechOutput
import com.or1.agent.UndoState
import com.or1.agent.UndoTier
import com.or1.agent.VoiceEngines
import com.or1.agent.VoiceError
import com.or1.agent.tools.HiddenDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    private const val WAKE = "wake_phrase"

    var speakReplies by mutableStateOf(false)
        private set

    /** The drawer's "Listen for “Hey or1”": the composer listens for [WakePhrase] while the home is idle. Off by default. */
    var wakePhrase by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        speakReplies = prefs.getBoolean(KEY, false)
        wakePhrase = prefs.getBoolean(WAKE, false)
    }

    fun set(context: Context, value: Boolean) {
        speakReplies = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    fun setWake(context: Context, value: Boolean) {
        wakePhrase = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WAKE, value).apply()
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
 * effects are left out. Without a reply, the turn's last note (why it stopped), and up to three effects with the rest
 * counted; with one, at most one unmentioned effect and the reply's first two sentences (the card and receipt carry
 * the rest). Everything goes through [Speakable.tidy].
 */
fun spokenSummary(turn: Turn): String {
    val reply = turn.reply?.text?.trim().orEmpty()
    val effects = turn.effects.map { it.effect }
        .filter { it.state != UndoState.UNDONE }
        .filter { it.tier == UndoTier.NONE || !Mentions.covered(it.label, reply) }
    val kept = if (reply.isEmpty()) effects else effects.filter { it.tier == UndoTier.NONE } + effects.filter { it.tier != UndoTier.NONE }.take(1)
    val phrases = kept.map { e -> e.label.trim().trimEnd('.') + if (e.tier == UndoTier.NONE) ", irreversible." else "." }
    val parts = mutableListOf<String>()
    if (phrases.isNotEmpty()) {
        parts += "Done."
        parts += phrases.take(3)
        if (phrases.size > 3) parts += if (phrases.size == 4) "And one more change." else "And ${phrases.size - 3} more changes."
    }
    when {
        reply.isNotEmpty() -> parts += Sentences.split(Speakable.tidy(reply)).take(2).joinToString(" ")
        parts.isEmpty() -> parts += turn.infos.lastOrNull()?.text?.trim()?.ifBlank { null } ?: "Sorry, I didn't get an answer."
    }
    return Speakable.tidy(parts.joinToString(" "))
}

/**
 * Text made for the ear: time zones, seconds on clock times, the current year, links and parenthesised asides out
 * (they're on the screen, and a voice reading "GMT plus four" is noise).
 */
internal object Speakable {
    private val zoneOffset = Regex("""\s*\b(?:GMT|UTC)\s*[+\-−]\s*\d{1,2}(?::?\d{2})?\b""")
    private val zoneName = Regex("""\s*\b(?:GMT|UTC)\b""")
    /** "07:30 GST", "7:30 PM +04": an abbreviation or offset right after a clock time. */
    private val zoneAfterTime = Regex("""(\b\d{1,2}:\d{2}(?:\s*[AaPp]\.?[Mm]\.?)?)\s+(?:[A-Z]{3,5}\b(?<!\b(?:AM|PM))|[+\-−]\d{2}(?::?\d{2})?\b)""")
    private val seconds = Regex("""\b(\d{1,2}:\d{2}):\d{2}\b""")
    private val link = Regex("""\bhttps?://\S*[^\s.,!?;:)]""")
    private val aside = Regex("""\s*\([^()]*\)""")

    fun tidy(text: String, year: Int = java.time.Year.now().value): String = text
        .replace(link, "the link")
        .replace(aside, "")
        .replace(seconds, "$1")
        .replace(zoneOffset, "")
        .replace(zoneName, "")
        .replace(zoneAfterTime, "$1")
        .replace(Regex("""\b((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?(?:\s+\d{1,2}(?:st|nd|rd|th)?)?),?\s+$year\b"""), "$1")
        .replace(Regex("""\s+([,.!?;:])"""), "$1")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}

/** Does a reply already say what an effect label says? Most of the label's telling words appear in it. */
internal object Mentions {
    private val plain = setOf(
        "the", "and", "for", "with", "from", "into", "your", "you", "this", "that", "turned", "set", "added", "created",
        "removed", "saved", "deleted", "ran", "opened", "tapped", "toggled", "note", "on", "off", "to", "of", "in", "at", "a", "an",
    )

    private val numbers = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    ).withIndex().associate { (i, w) -> w to i.toString() } + mapOf("thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60")

    /** Telling words, as digits ("three" = "3", "07" = "7") and singular ("minutes" = "minute"). */
    fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { m ->
        m.value.let { w ->
            when {
                w.all(Char::isDigit) -> w.trimStart('0').ifEmpty { "0" }
                w in numbers -> numbers.getValue(w)
                w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
                else -> w
            }
        }
    }.filter { it !in plain }.toSet()

    fun covered(label: String, reply: String): Boolean {
        val telling = words(label)
        if (telling.isEmpty() || reply.isBlank()) return false
        val said = words(reply)
        return telling.count { it in said } >= (telling.size * 0.6).coerceAtLeast(1.0)
    }
}

/** "Hey or1", as a recogniser writes it ("hey or one", "Hey, OR1", "okay or 1"), or "or1" as one word. */
internal object WakePhrase {
    private val phrase = Regex(
        """\b(?:(?:hey|hi|hay|okay|ok)[,.!]?\s+(?:or|oar|ore|our|all|awe|aw)[\s-]*(?:one|1|won|wan)|or1|orone)\b[\s,.!?]*""",
        RegexOption.IGNORE_CASE,
    )

    /** Debug (`--es voice wake:<phrase>`, in memory only): another phrase that also wakes, to test with synthesised speech. */
    @Volatile
    var extra: Regex? = null

    private fun find(text: String) = phrase.find(text) ?: extra?.find(text)

    fun heard(text: String): Boolean = find(text) != null

    /** What was said after the phrase ("Hey or one, set a timer" → "set a timer"); [text] itself without it. */
    fun strip(text: String): String = find(text)?.let { text.substring(it.range.last + 1) }?.trim() ?: text.trim()
}

/**
 * Barge-in: while or1 speaks, the recogniser also hears or1's own voice. What it hears is the user only when it has
 * words the reply doesn't (two or more), or a stop word.
 */
internal object Barge {
    private val stops = setOf("stop", "wait", "cancel", "pause", "quiet", "enough", "hold", "shut", "nevermind", "no")

    fun isUser(heard: String, spoken: String): Boolean {
        val novel = Mentions.words(heard) - Mentions.words(spoken)
        return novel.size >= 2 || novel.any { it in stops }
    }

    /**
     * [heard] without an echo of [spoken] at its start: the leading words that are all in the reply, when they hold two
     * or more of its telling words ("Timer set for three minutes stop" → "stop"). Otherwise [heard] as it is.
     */
    fun userPart(heard: String, spoken: String): String {
        val said = Mentions.words(spoken)
        val tokens = heard.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val echo = tokens.takeWhile { t -> Mentions.words(t).all { it in said } }
        if (echo.size == tokens.size || Mentions.words(echo.joinToString(" ")).size < 2) return heard.trim()
        return tokens.drop(echo.size).joinToString(" ")
    }
}

/**
 * Voice mode over the chat while [open] (the rail stays above it), and, whenever it's closed, the chat's spoken
 * replies (see [VoicePrefs]). [actions] are the stream's card actions (routines, triage, the viewer); voice mode closes
 * before each. [first]: a request already heard (the wake phrase and what followed it, when the recogniser finished
 * before voice mode opened).
 */
@Composable
fun VoiceModeHost(open: Boolean, onClose: () -> Unit, actions: CardActions = CardActions(), first: String? = null) {
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
        val closing = remember(actions) {
            fun wrap(a: (() -> Unit)?) = a?.let { { onClose(); it() } }
            CardActions(wrap(actions.openRoutines), wrap(actions.openTriage), wrap(actions.openViewer), actions.ask?.let { ask -> { text: String -> onClose(); ask(text) } })
        }
        CompositionLocalProvider(LocalCardActions provides closing) { VoiceMode(onClose, first) }
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

private const val VOICE_TAG = "or1-voice"

/** A level (0..1, [com.or1.agent.VoiceLevels]) that counts as someone talking. */
private const val SPEECH_LEVEL = 0.45f

/** Voice mode ends after this long without words (silence, no match, or no recogniser). */
private const val QUIET_MS = 8_000L

/**
 * Hands-free: listen → ask or1 → speak the reply ([spokenSummary]) → listen again, until Stop or [QUIET_MS] without
 * words, then back to the chat. Full screen on ink0: the Now strip, the last thing the user said (small, text2), or1's
 * reply in Spoken type, only the sentence being spoken (text) and the one before (text2), the turn's newest result
 * card (live: a timer counts down, Undo works), the meter (`live` listening, `ember` speaking, the march while or1
 * works) and one 72 dp Stop. A tap anywhere else interrupts speech and listens (or, while listening, finishes the
 * utterance). Barge-in: while or1 speaks it also listens, and talking over it (more than an echo of the reply, see
 * [Barge]) stops the voice and carries on with what the user says. The wake phrase is dropped from what's heard.
 */
@Composable
private fun VoiceMode(onClose: () -> Unit, first: String?) {
    val context = LocalContext.current
    val input = remember { VoiceEngines.input(context) }
    val output = remember { VoiceEngines.output(context) }
    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var heard by remember { mutableStateOf("") }
    var replyId by remember { mutableStateOf<String?>(null) }
    // The turn whose result card shows (cleared when the next request is heard).
    var turnKey by remember { mutableStateOf<Any?>(null) }
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
        // A request heard before listening (the wake phrase's, or a barge-in's finished utterance).
        var pending = first?.let(WakePhrase::strip)?.ifBlank { null }
        // What or1 was saying when the user talked over it: its echo is taken off the front of what's heard.
        var echo: String? = null
        while (true) {
            phase = Phase.LISTENING
            val h = pending?.let { Heard.Words(it) } ?: listen(input, output, quietSince, echo.orEmpty())
            pending = null
            echo = null
            when (h) {
                is Heard.Words -> {
                    Log.i(VOICE_TAG, "voice mode heard: ${h.text}")
                    problem = null
                    heard = h.text
                    turnKey = null
                    phase = Phase.WORKING
                    val turn = ask(h.text)
                    turnKey = turn?.key
                    val id = "voice-${turn?.key ?: SystemClock.uptimeMillis()}"
                    replyId = id
                    phase = Phase.SPEAKING
                    val said = turn?.let(::spokenSummary) ?: "Sorry, that didn't work."
                    Log.i(VOICE_TAG, "voice mode says: $said")
                    // The turn started a song or video: the user wants to hear it, not a microphone open over it (the
                    // recogniser would hear the media too). Say the reply without barge-in, then close.
                    val media = workMediaPlaying()
                    say(output, input, said, id, bargeIn = !media) { silentReply = it }?.let { barged ->
                        echo = said
                        pending = barged.text?.let { WakePhrase.strip(Barge.userPart(it, said)) }?.ifBlank { null }
                    }
                    if (media && pending == null) {
                        Log.i(VOICE_TAG, "voice mode closes: media plays")
                        break
                    }
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
            val actions = LocalCardActions.current
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                NowStrip(onTriage = { actions.openTriage?.invoke() }, onRoutine = { actions.openRoutines?.invoke() })
            }
            Spacer(Modifier.height(8.dp))
            UserLine(input, phase, heard)
            Spacer(Modifier.weight(1f))
            val card = rememberVoiceCard(turnKey)
            ReplyLines(output, replyId, silentReply, compact = card != null)
            card?.let {
                Spacer(Modifier.height(16.dp))
                key(it.key) { ResultCardView(it, live = true) }
            }
            Spacer(Modifier.height(if (card != null) 20.dp else 32.dp))
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

/**
 * The newest result card of the voice turn [turnKey] (one: the screen is the reply's), following the chat's items so
 * its Undo and state stay current.
 */
@Composable
private fun rememberVoiceCard(turnKey: Any?): ResultCard? {
    if (turnKey == null) return null
    val items by AgentHub.items.collectAsState()
    val turn = remember(items, turnKey) { groupTurns(items).lastOrNull { it.key == turnKey } } ?: return null
    return remember(turn) { ResultCards.of(turn).lastOrNull() }
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
private fun ReplyLines(output: SpeechOutput, replyId: String?, silentReply: String?, compact: Boolean) {
    val progress by output.progress.collectAsState()
    val p = progress.takeIf { replyId != null && it.id == replyId }
    val previous = if (silentReply == null) p?.sentences?.getOrNull(p.index - 1) else null
    val current = silentReply ?: p?.let { it.sentences.getOrNull(it.index) }
    val speaking = silentReply != null || p?.speaking == true
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = silentReply ?: p?.sentences?.joinToString(" ").orEmpty() },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // With a card below, fewer lines: the card says the rest.
        previous?.let { Text(it, style = Type.Spoken.copy(color = Palette.Text2), maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis) }
        current?.let {
            Text(it, style = Type.Spoken.copy(color = if (speaking) Palette.Text else Palette.Text2), maxLines = if (compact) 4 else 8, overflow = TextOverflow.Ellipsis)
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
        Phase.SPEAKING -> "Talk or tap to interrupt"
    }
    Text(
        text,
        style = Type.Caption.copy(color = Palette.Text2),
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Listens once (or carries on with an utterance already under way: push-to-talk handed over by the composer, the wake
 * phrase, a barge-in). The words lose an [echo] of what or1 was saying and the wake phrase.
 */
private suspend fun listen(input: SpeechInput, output: SpeechOutput, quietSince: Long, echo: String): Heard {
    if (input.state.value == ListenState.IDLE || input.state.value == ListenState.ERROR) {
        output.stop()
        input.start()
    }
    val left = QUIET_MS - (SystemClock.uptimeMillis() - quietSince)
    // Words, or a voice-level sound (the recogniser's partials come a second or so after speech starts): someone
    // talking just before the quiet time is up isn't cut off.
    val began = withTimeoutOrNull(left.coerceAtLeast(1)) {
        combine(input.state, input.partial, input.level) { s, p, l -> s != ListenState.LISTENING || p.isNotBlank() || l >= SPEECH_LEVEL }.first { it }
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
    val text = input.result.value?.let { WakePhrase.strip(Barge.userPart(it, echo)) }?.ifBlank { null }
    if (text != null && input.state.value == ListenState.IDLE) return Heard.Words(text)
    val e = input.error.value
    return if (e == null || e.kind == VoiceError.Kind.NO_MATCH || e.kind == VoiceError.Kind.TIMEOUT) Heard.NoWords else Heard.Problem(e.message)
}

/** Sends [text] to or1 and waits for the turn to finish; the turn, or null if it can't be found. */
private suspend fun ask(text: String): Turn? {
    val mark = AgentHub.items.value.maxOfOrNull { it.id } ?: 0L
    val done = CompletableDeferred<Unit>()
    AgentHub.submit(text, spoken = true, done = { done.complete(Unit) })
    done.await()
    return groupTurns(AgentHub.items.value).lastOrNull { (it.user?.id ?: -1) > mark && it.user?.text == text }
}

/** The user talked over or1: [text] is their finished utterance, or null while the recogniser is still hearing it. */
private class Barged(val text: String?)

/** or1's voice starts before barge-in listening does, so the recogniser's start doesn't clip it. */
private const val BARGE_AFTER_MS = 600L

/**
 * Speaks [text] to the end, a tap, or a barge-in (returned). Without a TTS engine, shows it for a while instead. While
 * speaking it listens ([bargeIn]); when it ends without one, the listening is dropped.
 */
/** Media plays on the work display: kept after a turn, or the app in front there playing (Stage may not have decided yet). */
private suspend fun workMediaPlaying(): Boolean {
    // Stage gives a just-tapped player a few seconds to start; so does this.
    repeat(8) {
        if (Stage.kept.value != null) return true
        val front = HiddenDisplay.foregroundPackage.value
        if (front != null && NowState.media.value.any { it.pkg == front && it.playing }) return true
        if (front == null || front !in MediaApps.KNOWN && front !in MediaApps.seen) return false
        delay(500)
    }
    return false
}

private suspend fun say(
    output: SpeechOutput, input: SpeechInput, text: String, id: String, bargeIn: Boolean = true, showSilently: (String?) -> Unit,
): Barged? {
    showSilently(null)
    val ready = output.available.value || withTimeoutOrNull(5_000) { output.available.first { it } } != null
    if (!ready) {
        showSilently(text)
        delay((text.length * 60L).coerceIn(2_000, 8_000))
        return null
    }
    output.speak(text, id)
    var barged: Barged? = null
    coroutineScope {
        val listening = if (bargeIn) launch { bargeIn(input, output, text) { barged = it } } else null
        if (withTimeoutOrNull(180_000) { output.progress.first { it.id == id && !it.speaking } } == null) output.stop()
        listening?.cancel()
    }
    if (barged == null && bargeIn) {
        input.cancel()
        // A recogniser started again straight after a cancel fails with "client error" (5).
        delay(300)
    }
    return barged
}

/**
 * Listens while or1 speaks [spoken], again after each utterance that was only its echo (or nothing), until the user
 * talks over it ([Barge.isUser]): then [onBarge] and the voice stops. A recogniser that can't give words (the level
 * meter only) gives no barge-in.
 */
private suspend fun bargeIn(input: SpeechInput, output: SpeechOutput, spoken: String, onBarge: (Barged) -> Unit) {
    delay(BARGE_AFTER_MS)
    while (true) {
        input.start()
        val state = combine(input.state, input.partial) { s, p -> s to p }
            .first { (s, p) -> Barge.isUser(p, spoken) || s == ListenState.IDLE || s == ListenState.ERROR }.first
        val result = input.result.value
        when {
            state == ListenState.LISTENING || state == ListenState.PROCESSING -> onBarge(Barged(null))
            state == ListenState.IDLE && result != null && Barge.isUser(result, spoken) -> onBarge(Barged(result))
            input.error.value?.kind == VoiceError.Kind.UNAVAILABLE -> return
            else -> {
                result?.let { Log.i(VOICE_TAG, "barge-in: only the echo: $it") }
                delay(300)
                continue
            }
        }
        Log.i(VOICE_TAG, "barge-in: ${result ?: input.partial.value}")
        output.stop()
        return
    }
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
    VoiceSwitch("Speak replies", VoicePrefs.speakReplies) { VoicePrefs.set(context, it) }
}

/** The drawer's "Listen for “Hey or1”" switch. */
@Composable
fun WakePhraseSwitch() {
    val context = LocalContext.current
    VoiceSwitch("Listen for “Hey or1”", VoicePrefs.wakePhrase) { VoicePrefs.setWake(context, it) }
}

@Composable
private fun VoiceSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Body, modifier = Modifier.weight(1f))
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
