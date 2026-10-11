# or1 UI direction: the Ledger

Design brief for the M1–M6 UI/UX revamp (2026-10-12). Written with Fable as design advisor; adjustments from the
review are folded in. This is the spec every milestone builds against.

## 1. Thesis

A chat transcript is optimised for *reading*; an agent phone should be optimised for *glancing and trusting*. The
user never opens apps, never sees a notification shade and never approves anything. The home answers, in order:
what is the phone doing right now, what did it just change on my behalf, what is coming, and where do I say the
next thing. Prose is the exception, not the default.

Keep a single vertical stream (it is the only structure that survives voice: utterance, then outcome), but change
its unit from a message pair to an **act**: *intent → receipt → result*.
- The **receipt** lists the side effects the agent produced, each with **Undo** where an inverse exists.
- The **result** is a **live card** when the answer is a thing (timer, day, contact, notifications) and short serif
  prose only when the answer is words.
- Live things float up into a **Now strip** under the status rail while they are alive and sink back when they end.

There is no empty state: the Now strip (time-relevant chips) is always present, the stream shows today's acts dimmed,
and the greeting is a one-line serif caption above the composer.

Rejected: a tile dashboard (recomposition cost on 4×A53, configuration burden, no voice equivalent).

## 2. Surfaces and navigation

| State | What shows | Enter | Leave |
|---|---|---|---|
| Now (idle) | Rail, Now strip, today's acts (dimmed), caption + suggestions, pill composer | Default; tap `or1` in the rail from anywhere | Tap composer, speak, tap a card |
| Composing | Card composer, keyboard | Tap pill / hold mic | Send, or tap stream with empty text |
| Working | Act card live: step blocks, doing-line, evidence strip, "Watch or take over" | Send | Completes, Stop, takeover |
| Result | Receipt rows + live card or prose | Automatic | Scrolls into history |
| Routine result | An act in the stream tagged `routine · <trigger>`, chime, one accent tick in the rail | Background | Dismiss like any act |
| Triage | Bottom sheet: notifications grouped by app, per-row Handle / Dismiss | Tap the notifications chip in Now | Swipe down, Back, tap `or1` |
| Activity (Today) | Drawer → Today: every side effect across chats and routines, newest first, with undo state | Drawer | Back |
| What or1 knows | Drawer → Memory: memory notes, editable/deletable | Drawer | Back |
| Agent screen | `WorkViewer` | Thumbnail / chip / "Watch or take over" | Close, Ask or1, tap `or1`, Back |
| Voice mode | Full screen: Now strip, spoken reply large, live card, one stop target | Hold round button + slide up to lock | Tap, silence timeout |

Navigation rules (no nav bar, flaky digitiser):
1. **The wordmark is the home button.** `or1` in the rail (≥48 dp hit area) always returns to Now: closes sheets,
   viewer, drawer, keyboard; scrolls the stream to the bottom.
2. **Back works everywhere:** closes the topmost of sheet → viewer → drawer → keyboard/composer (today Back does not
   close the drawer; fix it).
3. Vertical gestures only on critical paths; the left-edge drawer stays but is also reachable from the `+` menu.
4. Every tappable is ≥48 dp tall.

## 3. Visual language

**Colour** (extend `Palette` in `ui/Theme.kt`). The LCD leaks light through black, so separation comes from
hairlines, never luminance alone; hairlines are never darker than `#3E3C38`.

| Token | Hex | Use |
|---|---|---|
| ink0 | `#000000` | window, rail (boot continuity) |
| ink1 | `#141413` | sunken: code, evidence mats |
| ink2 | `#1C1B19` | stream cards (acts, receipts, live cards) |
| ink3 | `#262624` | composer, sheets |
| ink4 | `#30302E` | chips, pressed, user bubbles |
| line | `#3E3C38` | hairlines |
| lineStrong | `#4A4844` | active card border |
| text | `#ECEAE4` | primary |
| text2 | `#9C9A92` | secondary |
| text3 | `#6E6C66` | tertiary, dimmed history |
| ember | `#D97757` | or1 is acting *right now* — never decoration |
| emberDim | `#6B3A28` | ember at rest |
| live | `#8FBF8A` | a thing that is yours and running (timer, your turn on the agent screen, listening) |
| warn | `#E5B65C` | irreversible effect |
| error | `#E5857A` | failure |

**Type** — four voices, each with one job:
- Serif (Source Serif 4): the agent's words. Reply 17/26, Spoken 24/32 (voice mode), Caption-serif 16/22 (greeting).
- Sans (Inter): the phone's facts. Body 16/23, Label 15/20 medium, Step 14/20, Caption 12/16, Numeral 44/48 medium
  tabular (`fontFeatureSettings = "tnum"`), Numeral-s 22/26 tabular.
- Mono: raw tool calls only, 12/17.
- Pixel (`PixelFont`): system state only — 2 dp blocks in the rail, 3 dp in cards, 5 dp in the drawer wordmark. Never
  words other than `or1`.

**Pixel motif, three uses only:** the rail; **step blocks** (one 3 dp block per tool call: `line` unlit, `ember`
running, `text2` done, `error` failed) as the app's only progress indicator, replacing spinners; **meters** (voice
level, timer remaining) as columns of blocks.

**Motion is quantised.** Only layout eases (`animateContentSize`, 220 ms, fast-out-slow-in). State changes step:
march 180 ms, meters 100 ms, timers 1 s. Three durations: 120 ms (colour/alpha), 220 ms (size/position), 360 ms
(surface enter: slide 24 dp + alpha). No blur, no shadows beyond a 1 dp hairline, no scale above 1.04.

**Developer details** (cost, seconds, raw tool calls) are hidden by default behind a "Developer details" switch in the
drawer (persisted in shared prefs). The eval does not depend on them being visible.

## 4. Agent work: the Act card

While working (replaces the working card in `ui/Cards.kt`):
- Step blocks + the current step label (Step style, `text`, not pulsing).
- Evidence strip: when the work display is used, a 96 dp full-width crop of the work display's live preview with a
  10 dp `ember` ring at the last tap for 600 ms.
- "Watch or take over" 48 dp row → opens the viewer (first touch stops the agent, as today).
- Routine work shows the same card tagged `routine`.

When done it becomes a **receipt**: rows for write actions only, each with glyph, past-tense label and **Undo** when an
inverse exists. Reads/navigation collapse into "N steps" (expand on tap). Effects persist in `files/activity.jsonl`.

Undo tiers:
1. **Local inverse, instant, no API call:** `device` settings (DND, Wi-Fi, dark mode, volume, brightness, timeout —
   the tool records the previous value), alarm (dismiss by label), timer (dismiss), calendar add (delete by id),
   contact add (delete by id), routine add (remove), memory add (remove).
2. **Agent inverse:** effects inside a driven app (archive, setting in an app). Undo sends `Undo: <label>` with the
   receipt attached, shown as a new act.
3. **Irreversible** (sent, posted, deleted elsewhere, shell): `warn` glyph, no Undo, plus the **evidence frame**
   captured at the committing tap (≤8 frames per act, ~120 px wide RGB565). Tap to view full size.

## 5. Live result cards

Rendered from tool-call arguments/results plus local sources — never by parsing prose (`ui/ResultCards.kt`).
- **Timer:** Numeral 44 countdown from `elapsedRealtime`, block meter, Cancel. Pinned to Now as `12:40`.
- **Alarm:** next alarm from `AlarmManager.nextAlarmClock` (+ `ACTION_NEXT_ALARM_CLOCK_CHANGED`), label, Off. Pinned.
- **Calendar day:** the day's rows, times in Numeral-s, now-line. Next event pinned with minutes-until.
- **Contact:** name, phone, email rows (copy); "Ask about".
- **Notifications:** grouped by app, two-line rows, Dismiss (listener `cancelNotification`), Handle (sends that one to
  the agent). Count pinned in Now; the same component is the triage sheet.
- **Email summary:** from/subject chips, 2–3 serif lines, Open (viewer on Gmail), Archive (agent).
- **Video/audio:** 16:9 live crop of the work display (500 ms; 300 ms while a media app is on top) with a play badge;
  tap opens the viewer, which is the player.
- **Setting toggled:** one row with state + Undo (a promoted receipt row).
- **Routine created:** trigger sentence, next run, Edit.

Each card owns one `LaunchedEffect` ticking one `Text`. At most four live cards; older ones freeze. Live text must not
churn the accessibility semantics tree (the agent's accessibility service is always on — see the typing fix in
`app/README.md`): use `clearAndSetSemantics` with a coarse description on ticking text.

## 6. Voice-ready

- The round primary button is push-to-talk: hold to listen, release to send, slide up 56 dp to lock (voice mode).
- **Listening:** 9 columns of 3 dp blocks at 100 ms in `live`, from `AudioRecord` RMS; text arrives in the field.
- **Working:** the march. **Speaking:** the same columns in `ember` from the TTS amplitude; the spoken sentence in
  `text`, the rest in `text2`.
- **Voice mode:** full screen on ink0: rail, Now strip, last two sentences in Spoken 24/32, live card, a 72 dp stop.
- Every card exposes `spoken()` (= its content description): "Done. Timer, ten minutes." Irreversible receipts say
  "irreversible".
- Until ElevenLabs is approved: speech-to-text through Android's `SpeechRecognizer` if a recognition service exists on
  the device (else recording + level meter only, with a clear "voice input isn't available yet" note), and spoken replies
  through Android `TextToSpeech` if an engine exists. The voice layer sits behind an interface so ElevenLabs drops in.

## 7. Milestones

| # | Slice | Main files |
|---|---|---|
| M1 | Tokens, type, quantised motion, step blocks replace spinners, `or1` home button, greeting → caption, Back everywhere, hairlines, developer-details switch | `Theme.kt`, `StatusRail.kt`, `Cards.kt`, `ChatScreen.kt`, `Composer.kt` |
| M2 | Effects + Undo tier 1 + Today tab + What or1 knows | new `Activity.kt`, `Undo.kt`, `tools/*`, `AgentHub.kt` (`ChatItem.Effect`), `Cards.kt`, drawer |
| M3 | Live cards v1 (timer, alarm, setting, notifications, contact, routine) + Now strip | new `ResultCards.kt`, `NowStrip.kt`, `NowState.kt`, `ChatScreen.kt`, `NotifListener.kt` |
| M4 | Evidence frames + tap ring + takeover strip + routine results in the stream + triage sheet | `tools/HiddenDisplay.kt`, `Cards.kt`, `ChatScreen.kt`, new `Triage.kt` |
| M5 | Voice-ready composer: PTT, lock, meter, voice mode, `spoken()`, Android STT/TTS stopgap | `Composer.kt`, new `Voice.kt`, `VoiceMode.kt` |
| M6 | Live media card, Undo tier 2, calendar-day and email cards, viewer polish | `Cards.kt`, `WorkViewer.kt`, `ResultCards.kt` |

## 8. Anti-goals

- Blur, glass, moving gradients, particles, a glowing voice orb (jank on MT6761, smear on LCD).
- Floating text on large black voids; hairlines darker than `#3E3C38`.
- Continuous 60 fps ambient animation. Step everything.
- Edge swipes, multi-finger or long-press as the only path to anything.
- More than four live cards; more than one `SurfaceView` on screen.
- Prose walls and Markdown; Material 3 tonal surfaces, FABs, snackbars.
- Countdown "confirm by waiting" delays — that is an approval with worse ergonomics. Undo and evidence instead.
- Typing-effect replies, emoji, haptics on every tap.
