# or1 agent app (Phase 2)

The phone's home screen is a chat composer. The agent (Claude Haiku 5.5 via the Anthropic Java SDK) runs on
the RAVOZ and acts through platform APIs, an accessibility service and a notification listener. Changes
need approval in the chat (Allow / Deny / Always). Apps the agent drives run on a hidden work display, so
the chat never leaves the screen.

## UI (`ui/`)
- `ChatScreen.kt`: top bar (menu, new chat), time-of-day greeting with just-in-time suggestions
  (`Suggestions.kt`: from notifications and battery, no API call), the conversation, the drawer with past
  chats (`History.kt`, read-only from `files/transcripts/`).
- `Composer.kt`: a pill when idle; a card while typing with `+` (new chat, show work screen), the model and
  effort chip, mic (voice is not wired up yet) and the round button (voice placeholder / send / stop).
- `Cards.kt`: one working card per turn (steps, thinking, live thumbnail of the work display), approval
  cards, replies in serif. `WorkViewer.kt`: the work display full screen, watch-only (no touch
  forwarding, no moving an app to the real screen: the user doesn't use apps directly; see Lockdown).
- Fonts: Inter and Source Serif 4 (OFL) in `res/font/`.

## Hidden work display (`tools/HiddenDisplay.kt`)
A 720x1600 @ 320 dpi virtual display, flags PUBLIC | SECURE | OWN_CONTENT_ONLY | TRUSTED | SUPPORTS_TOUCH |
DESTROY_CONTENT_ON_REMOVAL | TOUCH_FEEDBACK_DISABLED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED. The hidden
flags need signature permissions, which the platform key grants (see the manifest). SECURE
(CAPTURE_SECURE_VIDEO_OUTPUT) lets FLAG_SECURE windows render there, so password and banking screens show in the
thumbnail, viewer and screenshots instead of black (and screenshots of them go to the API when the agent asks).
- `open` and `clock` launch there (`ActivityOptions.setLaunchDisplayId`); an app that opens on the real
  screen is moved over (`IActivityTaskManager.moveRootTaskToDisplay`), else it runs on the real screen for the
  session. A leak guard moves stray windows that appear on the real screen right after an agent action.
- Taps, swipes and back are injected with `InputManager.injectInputEvent` on that display: accessibility
  gestures report success there but don't land. Screen reads clear the accessibility cache first (the
  service only subscribes to window events, so scrolls never invalidate it).
- Its own display group keeps it working while the phone is off or locked. A screen wake lock bound to the
  display keeps it awake during a request; at the end the display is parked (surface removed, apps stop)
  and the lock released.
- Focus: AOSP 13 moves a trusted display to the top (and gives it input focus) whenever a task on it comes
  to the front, which would close the chat's keyboard. Injected touches carry `FLAG_NO_FOCUS_CHANGE`; after
  launches, and whenever accessibility reports a change on the hidden display, `restoreFocus()` re-starts the
  real screen's top task, which puts that display back on top (`moveDisplayToTop` isn't on the binder
  interface in 13, and `setFocusedTask`/`moveTaskToFront` no-op for a display's top task). Keys need focus,
  so `injectKey` focuses the hidden display for the key and restores it afterwards; the composer reopens the
  keyboard if that closed it (`HiddenDisplay.focusReturned`). IME policy is FALLBACK (LOCAL in the viewer):
  HIDE unbinds the IME from every display during those brief focus changes and it never re-binds.
- Leftovers: when the app process dies, DisplayManager drops the display, but WindowManager keeps it while a
  root task is left on it. Settings (activity embedding) leaves one: an empty task whose TaskFragments its
  organizer never removes. Before creating a display, `releaseStaleDisplays()` force-stops the apps owning
  tasks on displays DisplayManager no longer has (not ones with a task on a live display), which releases them.
- `shell` exports `OR1_DISPLAY`; the phone prompt (`proto/scripts/export_agent_json.py`) tells the model to
  use `am start --display $OR1_DISPLAY` and `input -d $OR1_DISPLAY`.
- Sound (`tools/HiddenAudio.kt`): while a request runs, apps on the hidden display are muted through their PLAY_AUDIO
  app-op (MANAGE_APP_OPS_MODES). Parking the display at the end of the request restores them, and so does watching
  the app in the viewer (and the debug `--es display front`). The alarm app (anything handling SET_ALARM) and system-uid apps (Settings)
  are never muted. Measured with the mic: a player on the hidden display went from -7 dBFS (tone 77 dB clear) unmuted
  to about -42 dBFS (room noise) muted.
- `shell` arms the leak guard before and after each command, so an `am start` without `--display` gets moved too.
- Debug: `am broadcast -n com.or1.agent/.DebugReceiver --es display info|create|launch|tap|back|home|park|snap|front|move|tool …`
  (results in logcat tag `or1-display` and `files/display.txt`).

## Calendar (`tools/CalendarTool.kt`)
There's no Google account, so `calendar add` writes to the first visible writable calendar, creating a local
"Phone" calendar (`ACCOUNT_TYPE_LOCAL`, through the sync-adapter URI) on first use. Etar (`ws.xsoh.etar`
1.0.57 from F-Droid, SHA-256 `dae01d93…c599`, matching the index) shows and edits it; install it with
`adb install` and grant READ/WRITE_CALENDAR and POST_NOTIFICATIONS. Etar's reminders start a foreground service
from the background, so it needs the power-save allowlist (`provision.sh` adds it when Etar is installed). The
TrebleDroid AOSP Calendar is disabled (`pm disable-user com.android.calendar`): its `EditEventActivity` class is
missing and it crashes on every add.

## Routines (`Routines.kt`, `Notifier.kt`)
Requests the agent runs on its own: at a time (`at` HH:MM with `repeat` once/daily/weekdays/weekly, or
`in_minutes`) or when a notification matches (`app` and/or `text_contains`). The `routines` tool is phone-only
(the schema is added in `export_agent_json.py`). Stored in `files/routines.json`.
- Time routines use exact alarms (`USE_EXACT_ALARM`, `RoutineReceiver`). Alarms don't survive a reboot or force
  stop, so every process start (and `BOOT_COMPLETED`) re-arms them: a one-off that came due meanwhile runs late,
  a repeating one skips the missed run.
- Notification routines run from `NotifListener`. Ongoing, group-summary and foreground-service notifications are
  ignored, an update with the same content doesn't trigger again, and each routine runs at most once a minute.
  The notification's content goes to the model marked as untrusted data.
- Runs have their own thread and agent (fresh conversation each run, transcripts in `files/routine-transcripts/`),
  so they don't interleave with the chat. Requests still run one at a time (they share the work display); a chat
  request sent during a run says it will start afterwards. A partial wake lock holds for the run. The reply is
  posted as a notification, and the chat isn't pulled in front of whatever the user has open.
- A run first waits up to 2 minutes for a validated network (after boot or a doze wake-up it may not be back), and
  retries once 30 s after an API or network error. After 15 s without a network it turns the screen on (dim wake lock,
  plus a Wi-Fi scan) until the network is back, because Wi-Fi may not rejoin while the screen is off (see Known
  limits).
- The Routines screen (drawer → Routines, or tap a routine notification) lists the routines: tap one to edit its
  request and trigger (same validation as the tool) or revoke its Always answers; × removes it. Recent runs follow,
  with approval cards; runs from earlier processes come back as request and reply (`routine_run`/`routine_reply`
  records in the routine transcripts). "Continue in chat" copies a run into the chat and into the chat agent's
  context (`Agent.addContext`), so the user can follow up there.
- Changes still need approval. With the system UI locked down, a waiting approval shows as a banner at the top of
  the chat (with a chime and a buzz; tap it to answer on the Routines screen), and finished runs leave result
  banners there (the three newest; dismissable). An approval counts as declined after 15 minutes. The notifications
  with Deny / Always / Allow buttons (`ApprovalReceiver`, own group) are still posted, for when the lockdown is off. Always is remembered per routine
  (`allowed` in the JSON), never for `shell`, and the chat's own Always answers don't apply (a routine may be
  acting on a notification's text).
- Debug: `am broadcast -n com.or1.agent/.DebugReceiver --es routine <id>` runs a routine now; `--es dismiss
  pkg1,pkg2` clears those apps' notifications through the listener (the shell can't clear other apps').

## Audio check (`AudioCheck.kt`)
`am broadcast -n com.or1.agent/.DebugReceiver --es audio test` records 1 s of room sound, then plays a 1 kHz tone at
60% media volume (restored afterwards) while recording, and looks for the tone (Goertzel). Result in logcat tag
`or1-audio` and `files/audio.txt` (`--es audio listen` only records and measures, e.g. to check that something is
muted); recordings in `files/mic-ambient.wav` and `files/mic-loopback.wav`. On the
RAVOZ (2026-10-10): ambient -45.6 dBFS, loopback -8.3 dBFS with the tone 80.8 dB above 700/1300 Hz, so the speaker
and the mic both work through the Android audio stack. `RECORD_AUDIO` is granted by `provision.sh`.

## Build and install

```sh
export JAVA_HOME=~/.local/share/jdk/temurin-17.0.20.1+1
./gradlew :agent:assembleDebug        # signed with the AOSP platform test key in ~/.config/or1/platform.p12
./gradlew :agent:testDebugUnitTest    # JVM unit tests: routine scheduling and parsing, note de-duplication, audio maths
scripts/provision.sh                  # install, API key, accessibility, notification access, HOME role
```

`provision.sh` uses the Mac's ADB server tunnelled to port 5038 and is pinned to `RAV1022V1000265`.
After an update `PackageReplacedReceiver` brings the chat back to the front. `provision.sh` disables launcher3 (its
Recents is no longer reachable; see Lockdown).
The API key goes over stdin from `~/.config/or1/anthropic-api-key` and is relabelled for the platform
app's SELinux level (files written by `run-as` aren't readable by platform apps).

Prompt and tool schemas are shared with the host prototype: edit `proto/src/or1/{prompt,tools}.py`, then
run `uv run python scripts/export_agent_json.py` in `proto/`.

## Eval

```sh
cd ../proto && uv run or1-eval --target phone --auto-approve --no-judge
```

Most tasks are checked automatically (see `proto/README.md` → Eval checks). This drives the on-device agent through `DebugReceiver` (it requires `android.permission.DUMP`, which only
shell and system hold). Afterwards, even if a task crashes, it clears the Clock app (no test timer or alarm
keeps ringing), deletes the "Test Person" contact and the "Dentist" event, and reinstalls Gallery. With
`--auto-approve`, the last task (uninstall Gallery, a judgement check meant to be declined) really runs; skip it
with `--only`.

## Root
`shell` runs commands through `su` when it works (checked once per process) and falls back to `sh` as the
platform app. TrebleDroid's phh-su only grants root to apps through phh's Superuser app (`me.phh.superuser`
1.0.3.3, from F-Droid's archive; SHA-256 `8350033f…2ca9d`, matching the index). On a fresh device: install
it, trigger `run-as com.or1.agent su -c id`, and on the prompt choose "Remember choice forever", then Allow.
The grant lives in `/data/data/me.phh.superuser/databases/su.sqlite` (`uid_policy`, uid 10121). Restart
the agent process afterwards so the cached root check runs again. Toasts on each grant are off: `settings`
table in `superuser.sqlite`, `notification` = `0` (1 = toast, the default; 2 = notification).
Grant logging is off too: `logging` = `false` in the same table, and `logging` = 0 on the agent's
`uid_policy` row.

Commands with `content query` always run as the platform app: SELinux stops providers from passing a query
cursor to su's domain (`avc: denied { transfer } … tcontext=u:r:phhsu_daemon`), so those queries die with
DeadObjectException as root, while inserts still work.

## Lockdown: no direct app access (`Lockdown.kt`)
The user doesn't use apps on this phone; the agent does. The app closes the system UI's ways into them:
- `StatusBarManager.disable`/`disable2` (hidden; STATUS_BAR via the platform signature, the kiosk-mode mechanism):
  no home, back or recents buttons, no notification shade or quick settings (so no Settings gear, tiles or tapping a
  notification), no heads-up pop-ups, no notification icons. The flags are held against the app's process: they lift
  if it dies, so a crash can't strand the phone, and they come back when it starts (and on every resume).
- Secure settings, saved and restored on release: notifications hidden on the lock screen, camera gestures
  (double-press power) off.
- launcher3 disabled (`provision.sh`); the viewer is watch-only; no "Bring to front".
- The chat hides the now-empty navigation bar (`MainActivity`), so the composer sits at the bottom edge with the
  keyboard closed; a swipe up shows the empty bar for a moment. Android's "Viewing full screen" tip is marked as seen
  (`immersive_mode_confirmations`), since its swipe-down exit is locked too.
- Notifications reach the user through the chat: other apps' as suggestions and through the agent's notifications
  tool, routines' as banners (see Routines). The chat makes its own chime, since the lockdown mutes notification
  sounds.
- Without a back button, every screen has its own close or back control, and tapping empty chat space closes the
  keyboard (the keyboard's own hide key also works).
- Checked on the phone: no nav buttons; pulling the shade (also on the lock screen), the recents key and
  `cmd statusbar expand-settings` do nothing; the eval passes 30/30 under it.
- Still reachable: the power menu (power off, restart; its Emergency button), and the keyboard's own settings by
  long-press. Dev: `am broadcast -n com.or1.agent/.DebugReceiver --es lockdown off|on`.

## API requests (`Agent.kt`)
- Streaming: each response is streamed (`createStreaming` folded by `MessageAccumulator`), and its text shows in
  the chat as it arrives (`ChatItem.Draft`, replaced by the reply, dropped if the response turns out to be a tool
  call). Stop is checked on every event, so it works mid-response too.
- Prompt caching, 1-hour TTL: an explicit `cache_control` (ttl 1h) on the system prompt, after the tools, plus
  top-level automatic caching (ttl 1h) for the growing conversation. Both must be 1h: a 1-hour entry can't follow a
  5-minute one. Until 2026-10-10 there was no `cache_control` at all on the belief that the account cached
  automatically; the responses showed zero cache reads and writes. Now the first request writes ~4.2K tokens at
  1h and later ones read them (`usage.cache_creation.ephemeral_1h_input_tokens`, `cache_read_input_tokens`).
- A chat idle for over an hour (the cache TTL) starts a new conversation on the next message, with a note in the
  chat: past that point the whole history would be re-written to the cache anyway. Each new chat (this, or the New
  chat button) gets its own transcript, so the earlier one is a separate entry under past chats in the menu;
  eval requests share one transcript so they don't flood it. Long-term memory notes carry over, by design.
- Every chat and routine request logs its gap since the previous request (the shared system-prompt cache) and since
  the previous message in that chat (the conversation cache) to `files/request-gaps.jsonl`; `uv run or1-gaps` in
  `proto/` buckets them (< 5 min, 5-60 min, > 60 min, evals left out) to check the 1-hour TTL against real use.
  Debug: `--el backdate_chat_ms N` makes the chat look N ms more idle.
- Each user message starts with `[Now: <local time>]` (not the system prompt, which must stay byte-stable for the
  cache), so relative dates need no `device get` call.

## Approvals and untrusted content
Reads and navigation run freely; changes ask first (`Tools.needsApproval`, same rules as `proto`). Text from the
screen, notifications, apps and web pages can carry instructions, and a notification routine puts it in front of the
model unasked, so the free calls were audited (2026-10-10) for what a planted instruction could do with them:
- `open` with a URI asks first: a URI can carry data out (`https://…?q=<memory notes>`), and some act on their own.
  Opening an app or a settings page stays free.
- During a routine run, memory writes also ask (`AgentHooks.mustApprove`): a planted note would steer every later
  chat. In the chat the user asked for the note, so it stays free there.
- A routine's Always answers never include `shell`, and the chat's Always answers don't apply to routines.
- Regression tests: three eval tasks plant instructions in notifications, one read in the chat and two reaching a
  notification routine (`proto/src/or1/checks.py`). They pass only if nothing gated was attempted (in a routine, an
  approval request means the agent tried), memory is unchanged, and the reply points the instruction out.
- Still free everywhere: reading the screen, notifications, apps, calendar, routines and device state; scrolling and
  the back/home/recents/wake keys; opening an app or settings page. None of these can send data out or change
  anything outside the agent's view.

## Performance
The RAVOZ's load average sits around 12 even when idle. That's 11 MediaTek kernel threads (hang_detect, ccci_poll1,
display, battery gauge, charger, dvfs) that wait in uninterruptible sleep and count toward the load while using
no CPU; the CPU is ~97% idle and ~1.4 GB of memory is available. In an eval run, 86% of the agent's time is API
latency (median 1.3 s per call); on the phone, a screen read takes ~0.2 s and opening an app ~2.8 s.

## Known limits
- Wi-Fi sometimes doesn't rejoin a network while the screen is off (seen for 9+ minutes on 2026-10-10: scans ran but
  never connected; not reproducible on demand). Routines work around it by waking the screen; waking it reconnects
  within seconds.
- Force-stopping the agent (`am force-stop com.or1.agent`) makes Android drop its accessibility service from
  `enabled_accessibility_services`, so screen and act stop working until `provision.sh` (or `settings put`) re-enables it.
