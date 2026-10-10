# or1 Phase 0 prototype

A Claude agent (Haiku 5.5, adaptive thinking) that drives the RAVOZ phone over ADB from this machine.
It stands in for the on-device composer: you type a request in the terminal, the agent acts on the phone.

## Run

```sh
# API key: paste into ~/.config/or1/anthropic-api-key (or export ANTHROPIC_API_KEY)
uv run or1                            # chat; --effort low|medium|high, --model, --web
uv run or1-eval                       # task list in evals/tasks.txt; --only 1,3 --effort low
```

The SSH tunnel from the Mac (port 5038) must be up. Every adb call is pinned to `RAV1022V1000265`;
nothing runs if the tunnel is down. The OnePlus on the same ADB server is never addressed.

## Tools (10, plus `routines` on the phone)

| Tool | Approval | Notes |
|---|---|---|
| `screen` | no | accessibility tree as numbered elements; optional 360px screenshot |
| `act` | taps, typing, enter/delete/sleep keys | scroll and back/home/recents/wake run freely; returns the new screen |
| `open` | no | package, URI or settings action |
| `device` | `set` only | battery, Wi-Fi, Bluetooth, volume, brightness, DND, airplane, timeout, storage, time |
| `clock` | set, dismiss | alarms and timers via AlarmClock intents; next alarm |
| `calendar` | add, delete | events through the calendar provider; creates a local "Phone" calendar on first use |
| `notifications` | no | list only |
| `apps` | no | list launchable apps, package info |
| `shell` | always | escape hatch; uid shell here, root on the phone |
| `memory` | no | notes in `state/memory.json`; a note that only differs in case, punctuation or a leading "The user" isn't added twice |

At an approval prompt, `a` allows that tool/action for the rest of the session.

Logs: every request (usage, cost, content) and tool call goes to `runs/*.jsonl`; eval results to `runs/eval-*.jsonl`.

## Eval checks

With `--target phone`, tasks with a machine check in `src/or1/checks.py` (keyed by the task's exact text) are judged
automatically from the device state or the reply: settings values, the next alarm, a running Clock timer, the contact
and calendar providers, `files/routines.json` and `files/memory.json`. Some checks set up first (a known notification
for "What notifications do I have?"), and the notification-routine check posts a matching notification from the shell
and waits for the routine's reply notification. The uninstall judgement check runs with every approval declined (`deny=True`,
the app's `auto_deny`), even under `--auto-approve`, and passes if Gallery is still installed and enabled. Only the
screen description is judged by hand (or left unjudged with `--no-judge`).

Unit tests for the pure helpers (no phone): `uv run pytest`. Cleanup removes only the routines the eval created.
