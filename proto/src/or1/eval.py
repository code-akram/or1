"""Run the task list one fresh conversation at a time and record success, steps and cost.

The agent acts without asking. On the phone, tasks with a machine check
(checks.py) are judged automatically; the rest by the person watching the phone (or scrcpy).
"""

import argparse
import json
import shlex
import time
from pathlib import Path

import anthropic

from . import adb, checks
from .agent import RUNS_DIR, Agent
from .cli import load_api_key, make_hooks

TASKS_FILE = Path(__file__).resolve().parents[2] / "evals" / "tasks.txt"
PHONE_PKG = "com.or1.agent"
PHONE_TIMEOUT = 600


def run_on_phone(task: str, request_id: str) -> dict:
    """Send one task to the on-device agent through its debug receiver and wait for its reply record."""
    # Screen actions fail on the lock screen; the test phone has a swipe-only keyguard. Home brings the chat (the HOME
    # app) back in front, e.g. after a reinstall killed it, so every task starts from the same screen.
    adb.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME")
    adb.shell(
        f"am broadcast -n {PHONE_PKG}/.DebugReceiver --es text {shlex.quote(task)} --es id {request_id} "
        "--ez fresh true"
    )
    deadline = time.time() + PHONE_TIMEOUT
    while time.time() < deadline:
        time.sleep(3)
        for line in adb.shell(f"run-as {PHONE_PKG} cat files/replies.jsonl 2>/dev/null").splitlines():
            if f'"id":"{request_id}"' in line:
                return json.loads(line)
    return {"reply": "[timed out waiting for the phone agent]", "cost": 0, "requests": 0, "seconds": PHONE_TIMEOUT}


def cleanup_phone_clock() -> None:
    """Undo task side effects: timers and alarms must not ring later (Clock holds only test data on this phone), the
    test contact (and any empty one the agent made on the way), calendar event, routines and notifications go, and Gallery comes back after the uninstall task."""
    for r in checks.test_routines():
        args = shlex.quote(json.dumps({"action": "remove", "id": r["id"]}))
        adb.shell(f"am broadcast -n {PHONE_PKG}/.DebugReceiver --es display tool --es name routines --es args {args} >/dev/null")
    # The routine check's notification, routine replies and any Etar reminder from a test event.
    adb.shell(f"am broadcast -n {PHONE_PKG}/.DebugReceiver --es dismiss com.android.shell,{PHONE_PKG},ws.xsoh.etar >/dev/null")
    adb.shell("pm clear com.android.deskclock >/dev/null; "
              "pm grant com.android.deskclock android.permission.POST_NOTIFICATIONS; "
              "content delete --uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' "
              "--where \"display_name='Test Person' OR (display_name IS NULL AND (account_type IS NULL OR account_type='null'))\"; "
              "content delete --uri 'content://com.android.calendar/events?caller_is_syncadapter=true"
              "&account_name=Phone&account_type=LOCAL' --where \"title='Dentist'\"; "
              "pm install-existing --user 0 com.android.gallery3d >/dev/null; pm enable com.android.gallery3d >/dev/null")


# Settings the device tasks change (volume, brightness, DND, timeout, dark mode): (read, write) shell commands.
DEVICE_SETTINGS = {
    "media volume": ("cmd media_session volume --stream 3 --get 2>/dev/null | grep -o 'volume is [0-9]*' | grep -o '[0-9]*'",
                     "cmd media_session volume --stream 3 --set {}"),
    "brightness": ("settings get system screen_brightness", "settings put system screen_brightness {}"),
    "auto-brightness": ("settings get system screen_brightness_mode", "settings put system screen_brightness_mode {}"),
    "screen timeout": ("settings get system screen_off_timeout", "settings put system screen_off_timeout {}"),
    "DND": ("settings get global zen_mode", "cmd notification set_dnd {}"),
    "dark mode": ("cmd uimode night", "cmd uimode night {}"),
}
DND_MODES = {"0": "off", "1": "priority", "2": "none", "3": "alarms"}


def save_device_settings() -> dict[str, str]:
    return {name: adb.shell(read).strip() for name, (read, _) in DEVICE_SETTINGS.items()}


def restore_device_settings(saved: dict[str, str]) -> None:
    """Puts back what the tasks changed, so an eval never leaves the phone at volume 1 or with DND on."""
    for name, value in saved.items():
        if name == "DND":
            value = DND_MODES.get(value, "off")
        elif name == "dark mode":
            value = value.removeprefix("Night mode: ").strip()
        if value and value != "null":
            adb.shell(DEVICE_SETTINGS[name][1].format(shlex.quote(value)) + " >/dev/null 2>&1")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the Phase 0 task list.")
    parser.add_argument("--model", default="claude-haiku-5-5")
    parser.add_argument("--effort", default="medium")
    parser.add_argument("--tasks", type=Path, default=TASKS_FILE)
    parser.add_argument("--only", help="comma-separated task numbers to run (1-based)")
    parser.add_argument("--no-judge", action="store_true", help="skip the y/n prompt; judge later from the logs")
    parser.add_argument("--target", choices=["host", "phone"], default="host",
                        help="host: this prototype drives the phone over ADB; phone: the on-device agent app")
    args = parser.parse_args()

    if args.target == "host":
        load_api_key()
    adb.check_device()
    tasks = [l.strip() for l in args.tasks.read_text().splitlines() if l.strip() and not l.startswith("#")]
    selected = {int(n) for n in args.only.split(",")} if args.only else None
    out_path = RUNS_DIR / time.strftime(f"eval-%Y%m%d-%H%M%S-{args.model}-{args.effort}.jsonl")
    results = []
    saved = save_device_settings() if args.target == "phone" else {}

    # Cleanup runs even if a task crashes (e.g. an adb protocol fault), so no test timer is left ringing.
    try:
        for i, task in enumerate(tasks, 1):
            if selected and i not in selected:
                continue
            print(f"\n=== Task {i}: {task}")
            check = checks.CHECKS.get(task) if args.target == "phone" else None
            snapshot = check.before() if check and check.before else None
            if args.target == "phone":
                r = run_on_phone(task, f"eval-{int(time.time())}-{i}")
                reply, seconds = r["reply"], r["seconds"]
                usage = {"requests": r["requests"], "cost": round(r["cost"], 5), "output_tokens": None, "log": "phone"}
            else:
                agent = Agent(hooks=make_hooks(), model=args.model, effort=args.effort)
                started = time.time()
                try:
                    reply = agent.send(task)
                except KeyboardInterrupt:
                    reply = "[interrupted]"
                except anthropic.APIError as e:
                    reply = f"[API error: {e}]"
                seconds = time.time() - started
                usage = {"requests": agent.usage.requests, "cost": round(agent.usage.cost, 5),
                         "output_tokens": agent.usage.output, "log": str(agent.log_path)}
            print(f"agent> {reply}")
            detail = None
            if check:
                success, detail = check.after(reply, snapshot)
                print(f"check: {'PASS' if success else 'FAIL'} ({detail})")
            else:
                verdict = "s" if args.no_judge else ""
                while verdict not in ("y", "n", "s"):
                    verdict = input("success? [y]es / [n]o / [s]kip: ").strip().lower()
                success = {"y": True, "n": False, "s": None}[verdict]
            record = {"task": i, "text": task, "success": success, "check": detail,
                      **usage, "seconds": round(seconds, 1), "reply": reply, "target": args.target}
            results.append(record)
            out_path.parent.mkdir(parents=True, exist_ok=True)
            with out_path.open("a") as f:
                f.write(json.dumps(record) + "\n")

    finally:
        if args.target == "phone":
            cleanup_phone_clock()
            restore_device_settings(saved)
    judged = [r for r in results if r["success"] is not None]
    passed = sum(r["success"] for r in judged)
    total_cost = sum(r["cost"] for r in results)
    failed = [r["task"] for r in judged if not r["success"]]
    print(f"\n{passed}/{len(judged)} succeeded ({len(results) - len(judged)} unjudged)"
          + (f" · failed: {failed}" if failed else "") +
          f" · ${total_cost:.4f} total · ${total_cost / max(len(results), 1):.4f} per task · results in {out_path}")
