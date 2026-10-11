package com.or1.agent.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.or1.agent.ListenState
import com.or1.agent.SpeechInput
import com.or1.agent.VoiceEngines
import com.or1.agent.tools.HiddenDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The ways the round primary button can act. */
private enum class PrimaryAction { VOICE, SEND, STOP }

/** What the composer's voice is doing: nothing, push-to-talk on the round button, or dictating into the field. */
private enum class VoiceUse { NONE, HOLD, DICTATE }

/** A quiet line in the composer ("Hold to talk", why voice input isn't ready) for [ms]. */
private data class Note(val text: String, val ms: Long, val at: Long = SystemClock.uptimeMillis())

/**
 * Composer, after both references: a single pill while idle (keyboard closed, nothing typed), and a card with the
 * field on top and a row of controls underneath while composing.
 *
 * Voice (docs/ui-ledger.md §6): with nothing typed, the round button is push-to-talk: hold to listen (the level meter
 * replaces the placeholder), release to send, slide up 56 dp for hands-free voice mode ([onVoiceMode]), slide left to
 * cancel. A press under 400 ms is a tap and just says "Hold to talk". While or1 works it's Stop, and holding it stops
 * or1 and listens. The mic dictates into the field (tap again to finish) and never sends.
 */
@Composable
fun Composer(
    input: ComposerInput,
    busy: Boolean,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPlusAction: (PlusAction) -> Unit,
    modifier: Modifier = Modifier,
    onVoiceMode: () -> Unit = {},
) {
    val context = LocalContext.current
    val speech = remember { VoiceEngines.input(context) }
    val hasText = input.hasText
    val action = when {
        busy -> PrimaryAction.STOP
        hasText -> PrimaryAction.SEND
        else -> PrimaryAction.VOICE
    }
    var use by remember { mutableStateOf(VoiceUse.NONE) }
    var note by remember { mutableStateOf<Note?>(null) }
    // Push-to-talk: the finger is still down; the utterance was dropped (slide left, or a tap).
    var held by remember { mutableStateOf(false) }
    var dropped by remember { mutableStateOf(false) }
    // The field's text came from dictation, so its reply may be spoken (Speak replies).
    var dictated by remember { mutableStateOf(false) }
    // Dictation opened the card from the pill: it closes again if nothing was heard.
    var openedForDictation by remember { mutableStateOf(false) }
    val busyNow by rememberUpdatedState(busy)
    val send by rememberUpdatedState(onSend)

    LaunchedEffect(note) {
        val n = note ?: return@LaunchedEffect
        delay(n.ms)
        if (note == n) note = null
    }
    LaunchedEffect(hasText) { if (!hasText) dictated = false }
    // Leaving with the mic open (a new screen over the chat): drop the utterance.
    DisposableEffect(Unit) { onDispose { if (use != VoiceUse.NONE) speech.cancel() } }

    fun startHold() {
        VoiceEngines.output(context).stop()
        note = null
        dropped = false
        held = true
        speech.start()
        use = VoiceUse.HOLD
    }

    // Push-to-talk: the utterance ends (release, or the recogniser heard the end); it's sent once the finger is up.
    LaunchedEffect(use) {
        if (use != VoiceUse.HOLD) return@LaunchedEffect
        val end = speech.state.first { it == ListenState.IDLE || it == ListenState.ERROR }
        val text = speech.result.value?.trim()?.ifBlank { null }
        if (!dropped) speech.error.value?.let { if (end == ListenState.ERROR || text == null) note = Note(it.message, 4000) }
        snapshotFlow { held }.first { !it }
        if (text != null && !dropped) {
            // Held while or1 was working: it was stopped, give it a moment to wind down.
            withTimeoutOrNull(5000) { snapshotFlow { busyNow }.first { !it } }
            input.setText(text)
            VoiceReplies.mark(text)
            send()
        }
        use = VoiceUse.NONE
    }

    // Dictation: the words stream into the field after what was there; nothing is sent.
    LaunchedEffect(use) {
        if (use != VoiceUse.DICTATE) return@LaunchedEffect
        val base = input.text.trimEnd().let { if (it.isEmpty()) "" else "$it " }
        coroutineScope {
            val stream = launch { speech.partial.collect { if (it.isNotBlank()) input.setText(base + it) } }
            val end = speech.state.first { it == ListenState.IDLE || it == ListenState.ERROR }
            stream.cancel()
            val text = speech.result.value?.trim()?.ifBlank { null }
            if (text != null) {
                input.setText(base + text)
                dictated = true
            } else {
                input.setText(base.trimEnd())
                speech.error.value?.let { note = Note(it.message, 4000) } ?: run { if (end == ListenState.IDLE) note = Note("Didn't catch that.", 2500) }
                if (openedForDictation && !input.hasText) onExpand(false)
            }
        }
        use = VoiceUse.NONE
    }

    // From the + menu or an accessibility click: voice mode does its own listening.
    val openVoiceMode: () -> Unit = {
        if (use == VoiceUse.DICTATE) {
            speech.cancel()
            use = VoiceUse.NONE
        }
        onVoiceMode()
    }
    val onMic: () -> Unit = {
        when (use) {
            VoiceUse.DICTATE -> speech.stop()
            VoiceUse.HOLD -> Unit
            VoiceUse.NONE -> {
                VoiceEngines.output(context).stop()
                note = null
                openedForDictation = !expanded && !hasText
                speech.start()
                use = VoiceUse.DICTATE
                onExpand(true)
            }
        }
    }
    val onPrimary: () -> Unit = {
        when (action) {
            PrimaryAction.STOP -> onStop()
            PrimaryAction.SEND -> {
                if (dictated) VoiceReplies.mark(input.text.trim())
                onSend()
            }
            PrimaryAction.VOICE -> openVoiceMode()
        }
    }
    val talk = PushToTalk(
        onDown = { if (!busyNow) startHold() },
        onHold = {
            if (use != VoiceUse.HOLD) {
                if (busyNow) onStop()
                startHold()
            }
        },
        onTap = {
            if (use == VoiceUse.HOLD) {
                dropped = true
                held = false
                speech.cancel()
                note = Note("Hold to talk", 1200)
            } else if (busyNow) {
                onStop()
            }
        },
        onRelease = {
            held = false
            speech.stop()
        },
        onLock = {
            // Voice mode carries on with this utterance.
            held = false
            dropped = true
            use = VoiceUse.NONE
            onVoiceMode()
        },
        onCancel = {
            dropped = true
            held = false
            speech.cancel()
        },
    )
    val primary: @Composable () -> Unit = {
        if (action == PrimaryAction.SEND) PrimaryButton(action, onPrimary)
        else PushToTalkButton(action, listening = use == VoiceUse.HOLD, talk, onPrimary)
    }
    val mic: @Composable (Color) -> Unit = { background ->
        val on = use == VoiceUse.DICTATE
        IconCircle(
            Icons.Mic, if (on) "Stop dictating" else "Dictate", onMic,
            tint = if (on) Palette.OnPrimary else Palette.Text2, background = if (on) Palette.Live else background,
        )
    }
    val voiceShown = use != VoiceUse.NONE || note != null

    // A plain swap, not AnimatedContent: the card's native field (an AndroidView) isn't redrawn when it comes back
    // inside a fading layer. The keyboard sliding in is the transition.
    Box(modifier) {
        val isExpanded = expanded || hasText
        if (!isExpanded) {
            // Pill: + · placeholder (or the level meter) · mic · round button.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(CircleShape)
                    .background(Palette.Ink3)
                    .border(1.dp, Palette.Line, CircleShape)
                    .clickable { onExpand(true) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlusButton(onPlusAction, openVoiceMode, background = Color.Transparent)
                if (voiceShown) {
                    VoiceSlot(use, speech, note, Modifier.weight(1f).padding(horizontal = 6.dp))
                } else {
                    Text(
                        "Ask or1…",
                        style = Type.Body.copy(color = Palette.Text2),
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                }
                if (use != VoiceUse.HOLD) mic(Color.Transparent)
                Spacer(Modifier.width(4.dp))
                primary()
            }
        } else {
            ComposerCard(
                input, busy, showKeyboard = use != VoiceUse.DICTATE, onExpand, onSend, onPlusAction, openVoiceMode,
                voice = if (voiceShown) ({ VoiceSlot(use, speech, note, Modifier.weight(1f).padding(horizontal = 4.dp)) }) else null,
                mic = { if (use != VoiceUse.HOLD) mic(Palette.Ink4) },
                primary = primary,
            )
        }
    }
}

/**
 * The meter and one quiet line, where the placeholder (pill) or the agent-screen chip (card) was: while listening,
 * the words so far (push-to-talk) or why words can't come out yet; otherwise a [Note].
 */
@Composable
private fun VoiceSlot(use: VoiceUse, speech: SpeechInput, note: Note?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (use != VoiceUse.NONE) {
            val partial by speech.partial.collectAsState()
            val error by speech.error.collectAsState()
            LevelMeter({ speech.level.value }, Palette.Live)
            Spacer(Modifier.width(10.dp))
            // Coarse semantics: the words change several times a second (the agent's a11y service is always on).
            val line = Modifier.weight(1f).clearAndSetSemantics { contentDescription = "Listening" }
            when {
                error != null -> Text(error!!.message, style = Type.Caption.copy(color = Palette.Text2), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = line)
                use == VoiceUse.HOLD && partial.isNotBlank() ->
                    Text(partial, style = Type.Step.copy(color = Palette.Text), maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = line)
                use == VoiceUse.HOLD -> Text("↑ hands-free   ← cancel", style = Type.Caption.copy(color = Palette.Text2), maxLines = 1, modifier = line)
                else -> Text("Listening", style = Type.Caption.copy(color = Palette.Text2), maxLines = 1, modifier = line)
            }
        } else if (note != null) {
            Text(note.text, style = Type.Caption.copy(color = Palette.Text2), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerCard(
    input: ComposerInput,
    busy: Boolean,
    showKeyboard: Boolean,
    onExpand: (Boolean) -> Unit,
    onSend: () -> Unit,
    onPlusAction: (PlusAction) -> Unit,
    onVoiceMode: () -> Unit,
    /** The meter or a note, in place of the agent-screen chip. */
    voice: (@Composable RowScope.() -> Unit)?,
    mic: @Composable () -> Unit,
    primary: @Composable () -> Unit,
) {
    // Not while dictating: the words arrive without it.
    LaunchedEffect(Unit) { if (showKeyboard) input.focusAndShowKeyboard() }
    DisposableEffect(input) {
        // Collapse back to the pill when focus leaves an empty composer.
        input.onFocusChange = { focused -> if (!focused && !input.hasText) onExpand(false) }
        input.onEnter = onSend
        onDispose { input.onFocusChange = {}; input.onEnter = {} }
    }
    // The agent's work display briefly takes focus for some actions (a back key, an app opening a new screen),
    // and Android closes the keyboard when that happens. When focus is handed back, reopen it if it was up a
    // moment ago. Focus can leave and return within a frame, so this listens to the hand-back rather than to
    // window focus.
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleNow by rememberUpdatedState(imeVisible)
    val imeHiddenAt = remember { longArrayOf(0L) }
    LaunchedEffect(imeVisible) { if (!imeVisible) imeHiddenAt[0] = SystemClock.uptimeMillis() }
    LaunchedEffect(Unit) {
        HiddenDisplay.focusReturned.drop(1).collect {
            // Was the keyboard up just before? (Insets lag, so it may still read as visible here.)
            val wasUp = imeVisibleNow || SystemClock.uptimeMillis() - imeHiddenAt[0] < 2500
            if (!input.focused || !wasUp) return@collect
            // A request made before the IME has re-bound to this window is dropped, so keep asking for a moment.
            repeat(8) {
                delay(200)
                if (!imeVisibleNow) input.focusAndShowKeyboard()
            }
        }
    }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Ink3)
            .border(1.dp, Palette.Line, shape)
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
    ) {
        AndroidView(
            factory = input::createView,
            onRelease = input::release,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlusButton(onPlusAction, onVoiceMode, background = Palette.Ink4)
            if (voice != null) {
                voice()
            } else {
                AgentScreenChip(busy) { onPlusAction(PlusAction.WORK_SCREEN) }
                Spacer(Modifier.weight(1f))
            }
            mic()
            primary()
        }
    }
}

enum class PlusAction { NEW_CHAT, WORK_SCREEN, DRAWER }

@Composable
private fun PlusButton(onAction: (PlusAction) -> Unit, onVoiceMode: () -> Unit, background: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconCircle(Icons.Plus, "More", { open = true }, tint = Palette.Text, background = background)
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp), containerColor = Palette.Ink4,
            tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(1.dp, Palette.Line),
        ) {
            DropdownMenuItem(
                text = { Text("New chat", style = Type.Body) },
                leadingIcon = { Icon(Icons.NewChat, null, tint = Palette.Text2, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.NEW_CHAT) },
            )
            DropdownMenuItem(
                text = { Text("Show agent screen", style = Type.Body) },
                leadingIcon = { Icon(Icons.Screen, null, tint = Palette.Text2, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.WORK_SCREEN) },
            )
            // Hands-free without the hold-and-slide: no gesture is the only way in.
            DropdownMenuItem(
                text = { Text("Voice mode", style = Type.Body) },
                leadingIcon = { Icon(Icons.Waveform, null, tint = Palette.Text2, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onVoiceMode() },
            )
            // The drawer's edge swipe is unreliable on this digitiser; this is the other way in.
            DropdownMenuItem(
                text = { Text("Chats and routines", style = Type.Body) },
                leadingIcon = { Icon(Icons.Menu, null, tint = Palette.Text2, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onAction(PlusAction.DRAWER) },
            )
        }
    }
}

/** Opens the agent's work screen; its icon becomes the march while or1 is working there. */
@Composable
private fun AgentScreenChip(busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(Palette.Ink4)
            .clickable(onClickLabel = "Show agent screen", onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same slot either way, so the row doesn't shift when or1 starts.
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (busy) March() else Icon(Icons.Screen, null, tint = Palette.Text2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("Agent screen", style = Type.Step.copy(color = Palette.Text), maxLines = 1)
    }
}

@Composable
private fun PrimaryButton(action: PrimaryAction, onClick: () -> Unit) {
    val (icon, label) = primaryIcon(action)
    IconCircle(icon, label, onClick, tint = Palette.OnPrimary, background = Palette.Primary)
}

private fun primaryIcon(action: PrimaryAction): Pair<ImageVector, String> = when (action) {
    PrimaryAction.STOP -> Icons.Stop to "Stop"
    PrimaryAction.SEND -> Icons.ArrowUp to "Send"
    PrimaryAction.VOICE -> Icons.Waveform to "Hold to talk"
}

/** What a press on the round button does; see [PushToTalkButton]. */
private class PushToTalk(
    /** The finger went down (listening starts at once, so the first word isn't lost). */
    val onDown: () -> Unit,
    /** Still down after [HOLD_MS]: a hold. */
    val onHold: () -> Unit,
    /** Up before [HOLD_MS]. */
    val onTap: () -> Unit,
    /** Up after a hold: send. */
    val onRelease: () -> Unit,
    /** Slid up [SLIDE]: hands-free voice mode. */
    val onLock: () -> Unit,
    /** Slid left [SLIDE]. */
    val onCancel: () -> Unit,
)

private const val HOLD_MS = 400L
/** This digitiser sometimes cancels a touch that is still down and then reports it again: wait this long for it. */
private const val REGRAB_MS = 600L
private val SLIDE = 56.dp

/**
 * The round button as push-to-talk, on raw MotionEvents: Compose reports a touch the system cancelled as a lift, and
 * this phone's digitiser cancels touches that are still down. A cancel doesn't end the press; a new down within
 * [REGRAB_MS] picks it up again (from where the finger is now), and otherwise it counts as a lift at the cancel.
 * Accessibility services get a plain click ([onClick]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PushToTalkButton(action: PrimaryAction, listening: Boolean, talk: PushToTalk, onClick: () -> Unit) {
    val (icon, label) = primaryIcon(action)
    val slide = with(LocalDensity.current) { SLIDE.toPx() }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(talk)
    val tracker = remember { PressTracker(scope) }
    tracker.talk = { current }
    tracker.slide = slide
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (listening) Palette.Live else Palette.Primary)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { click(); true }
            }
            .pointerInteropFilter { tracker.handle(it); true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Palette.OnPrimary, modifier = Modifier.size(22.dp))
    }
}

/** The press state machine behind [PushToTalkButton]; on the main thread. */
private class PressTracker(private val scope: CoroutineScope) {
    private enum class Phase { IDLE, PRESSED, HELD, CANCELLED, ENDED }

    var talk: () -> PushToTalk = { error("unset") }
    var slide = 0f
    private var phase = Phase.IDLE
    /** The phase a touch-cancel interrupted. */
    private var interrupted = Phase.IDLE
    private var downAt = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var timer: Job? = null

    fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x
                y0 = e.y
                if (phase == Phase.CANCELLED) {
                    // The same press, reported again.
                    timer?.cancel()
                    phase = interrupted
                    if (phase == Phase.PRESSED) holdAfter(HOLD_MS - (SystemClock.uptimeMillis() - downAt))
                    return
                }
                timer?.cancel()
                phase = Phase.PRESSED
                downAt = SystemClock.uptimeMillis()
                talk().onDown()
                holdAfter(HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> if (phase == Phase.PRESSED || phase == Phase.HELD) {
                when {
                    e.y - y0 < -slide -> end { talk().onLock() }
                    e.x - x0 < -slide -> end { talk().onCancel() }
                }
            }
            MotionEvent.ACTION_UP -> lift(phase)
            MotionEvent.ACTION_CANCEL -> if (phase == Phase.PRESSED || phase == Phase.HELD) {
                timer?.cancel()
                interrupted = phase
                phase = Phase.CANCELLED
                timer = scope.launch {
                    delay(REGRAB_MS)
                    if (phase == Phase.CANCELLED) lift(interrupted)
                }
            } else if (phase == Phase.ENDED) {
                phase = Phase.IDLE
            }
        }
    }

    private fun holdAfter(ms: Long) {
        timer = scope.launch {
            delay(ms.coerceAtLeast(0))
            if (phase == Phase.PRESSED) {
                phase = Phase.HELD
                talk().onHold()
            }
        }
    }

    private fun lift(at: Phase) {
        when (at) {
            Phase.PRESSED -> end { talk().onTap() }
            Phase.HELD -> end { talk().onRelease() }
            else -> Unit
        }
        phase = Phase.IDLE
    }

    private fun end(action: () -> Unit) {
        timer?.cancel()
        phase = Phase.ENDED
        action()
    }
}

@Composable
fun IconCircle(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color, background: Color, size: Int = 48) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}
