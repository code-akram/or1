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
  cards, replies in serif. `WorkViewer.kt`: the work display full screen; touching it takes over from the
  agent, "Bring to front" moves the app to the real screen.
- Fonts: Inter and Source Serif 4 (OFL) in `res/font/`.

## Hidden work display (`tools/HiddenDisplay.kt`)
A 720x1600 @ 320 dpi virtual display, flags PUBLIC | OWN_CONTENT_ONLY | TRUSTED | SUPPORTS_TOUCH |
DESTROY_CONTENT_ON_REMOVAL | TOUCH_FEEDBACK_DISABLED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED. The hidden
flags need signature permissions, which the platform key grants (see the manifest).
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
- `shell` exports `OR1_DISPLAY`; the phone prompt (`proto/scripts/export_agent_json.py`) tells the model to
  use `am start --display $OR1_DISPLAY` and `input -d $OR1_DISPLAY`.
- Debug: `am broadcast -n com.or1.agent/.DebugReceiver --es display info|create|launch|tap|back|home|park|snap|front|move|tool …`
  (results in logcat tag `or1-display` and `files/display.txt`).

## Build and install

```sh
export JAVA_HOME=~/.local/share/jdk/temurin-17.0.20.1+1
./gradlew :agent:assembleDebug        # signed with the AOSP platform test key in ~/.config/or1/platform.p12
scripts/provision.sh                  # install, API key, accessibility, notification access, HOME role
```

`provision.sh` uses the Mac's ADB server tunnelled to port 5038 and is pinned to `RAV1022V1000265`.
The API key goes over stdin from `~/.config/or1/anthropic-api-key` and is relabelled for the platform
app's SELinux level (files written by `run-as` aren't readable by platform apps).

Prompt and tool schemas are shared with the host prototype: edit `proto/src/or1/{prompt,tools}.py`, then
run `uv run python scripts/export_agent_json.py` in `proto/`.

## Eval

```sh
cd ../proto && uv run or1-eval --target phone --auto-approve --no-judge
```

This drives the on-device agent through `DebugReceiver` (it requires `android.permission.DUMP`, which only
shell and system hold). Afterwards it clears the Clock app (no test timer or alarm keeps ringing), deletes
the "Test Person" contact and reinstalls Gallery. With `--auto-approve`, the last task (uninstall Gallery, a
judgement check meant to be declined) really runs; skip it with `--only`.

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

## Known limits
- Calendar events fail: there is no calendar account.
- FLAG_SECURE screens show black in the thumbnail, viewer and screenshots (the accessibility tree still works).
- Hidden apps can still play sound.
