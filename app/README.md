# or1 agent app (Phase 2)

The phone's home screen is a chat composer. The agent (Claude Haiku 5.5 via the Anthropic Java SDK) runs on
the RAVOZ and acts through platform APIs, an accessibility service and a notification listener. Changes
need approval in the chat (Allow / Deny / Always).

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
shell and system hold) and clears the Clock app afterwards, so no test timer or alarm keeps ringing.

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
