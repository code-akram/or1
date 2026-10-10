"""Machine checks for eval tasks on the phone: each reads the device state (or the reply) after a task and says
whether it worked. Tasks without a check are judged by the person running the eval.

A check is keyed by the task's exact text in evals/tasks.txt. `before` (optional) snapshots state before the task;
`after(reply, snapshot)` returns (passed, detail).
"""

import json
import re
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Callable

from . import adb

PHONE_PKG = "com.or1.agent"


@dataclass
class Check:
    after: Callable[[str, Any], tuple[bool, str]]
    before: Callable[[], Any] | None = None


def _setting(namespace: str, key: str) -> str:
    return adb.shell(f"settings get {namespace} {key}").strip()


def _volume() -> int:
    m = re.search(r"volume is (\d+)", adb.shell("cmd media_session volume --stream 3 --get 2>/dev/null"))
    return int(m.group(1)) if m else -1


def _zone():
    from zoneinfo import ZoneInfo
    return ZoneInfo(adb.shell("getprop persist.sys.timezone").strip() or "UTC")


def _rows(out: str) -> list[dict]:
    rows = []
    for line in out.splitlines():
        if line.startswith("Row: "):
            rows.append(dict(p.partition("=")[::2] for p in re.split(r", (?=\w+=)", line.split(" ", 2)[2])))
    return rows


def _dentist_events() -> list[dict]:
    return _rows(adb.shell("content query --uri content://com.android.calendar/events --projection _id:dtstart:deleted "
                           "--where \"title='Dentist' AND deleted=0\" 2>&1"))


def _routines() -> list[dict]:
    out = adb.shell(f"run-as {PHONE_PKG} cat files/routines.json 2>/dev/null").strip()
    return json.loads(out) if out.startswith("[") else []


def _memory() -> list[str]:
    out = adb.shell(f"run-as {PHONE_PKG} cat files/memory.json 2>/dev/null").strip()
    return json.loads(out) if out.startswith("[") else []


def _mentions(*words: str) -> Callable[[str, Any], tuple[bool, str]]:
    def check(reply: str, _) -> tuple[bool, str]:
        missing = [w for w in words if w.lower() not in reply.lower()]
        return not missing, f"reply lacks {missing}" if missing else f"reply mentions {list(words)}"
    return check


def _post_courier() -> None:
    """A known notification for the agent to report (cleanup dismisses it)."""
    adb.shell("cmd notification post -S bigtext -t 'Courier' or1eval-courier 'Your parcel ships tomorrow' >/dev/null")


def _battery(reply: str, _) -> tuple[bool, str]:
    level = re.search(r"level: (\d+)", adb.shell("dumpsys battery")).group(1)
    return f"{level}%" in reply or f"{level} percent" in reply, f"battery is {level}%"


def _date(reply: str, _) -> tuple[bool, str]:
    now = datetime.now(_zone())
    ok = str(now.day) in reply and (now.strftime("%B") in reply or now.strftime("%b") in reply)
    return ok, f"today is {now:%A %d %B}"


def _wifi(reply: str, _) -> tuple[bool, str]:
    m = re.search(r'SSID: "([^"]+)"', adb.shell("cmd wifi status"))
    ssid = m.group(1) if m else "?"
    return ssid in reply, f"connected to {ssid}"


def _equals(namespace: str, key: str, want: Callable[[str], bool], label: str) -> Callable[[str, Any], tuple[bool, str]]:
    def check(reply: str, _) -> tuple[bool, str]:
        value = _setting(namespace, key)
        return want(value), f"{label}={value}"
    return check


def _timer(reply: str, _) -> tuple[bool, str]:
    running = "deskclock.action.TIMER_EXPIRED" in adb.shell("dumpsys alarm")
    return running, "a Clock timer is running" if running else "no Clock timer is scheduled"


def _gym_alarm(reply: str, _) -> tuple[bool, str]:
    out = adb.shell("dumpsys alarm | grep -A1 'Next alarm clock information' | tail -1")
    tomorrow = (datetime.now(_zone()) + timedelta(days=1)).strftime("%Y-%m-%d")
    return f"{tomorrow} 07:30" in out, f"next alarm: {out.strip()[:60]}"


def _contact(reply: str, _) -> tuple[bool, str]:
    rows = _rows(adb.shell("content query --uri content://com.android.contacts/data --projection display_name:data1 "
                           "--where \"mimetype='vnd.android.cursor.item/phone_v2'\""))
    match = [r for r in rows if r.get("display_name") == "Test Person" and re.sub(r"\D", "", r.get("data1", "")) == "5550100"]
    return bool(match), "Test Person with 555-0100 saved" if match else f"no matching contact ({len(rows)} phone rows)"


def _dentist_added(reply: str, _) -> tuple[bool, str]:
    zone = _zone()
    today = datetime.now(zone).date()
    monday = today + timedelta(days=(7 - today.weekday()) % 7 or 7)
    want = int(datetime(monday.year, monday.month, monday.day, 10, tzinfo=zone).timestamp() * 1000)
    events = _dentist_events()
    ok = any(int(e["dtstart"]) == want for e in events)
    return ok, f"Dentist on {monday} 10:00" if ok else f"{len(events)} Dentist events, none on {monday} 10:00"


def _dentist_deleted(reply: str, _) -> tuple[bool, str]:
    events = _dentist_events()
    return not events, "no Dentist event left" if not events else f"{len(events)} Dentist events left"


def _memory_saved(reply: str, _) -> tuple[bool, str]:
    notes = [n for n in _memory() if "short" in n.lower() and "dark" in n.lower()]
    return len(notes) == 1, f"{len(notes)} matching notes (want exactly 1)"


def _quieter(reply: str, before: int) -> tuple[bool, str]:
    """Lowering the media volume, or asking which sound the user means (the prompt says to ask when it's ambiguous)."""
    after = _volume()
    if after < before or before == 0:
        return True, f"media volume {before} -> {after}"
    asked = reply.rstrip().endswith("?") and after == before
    return asked, f"asked which sound to lower (volume stays {after})" if asked else f"media volume {before} -> {after}"


def _morning_routine(reply: str, _) -> tuple[bool, str]:
    found = [r for r in _routines() if is_test_routine(r) and not r.get("text_contains")]
    return bool(found), "weekday 07:15 routine saved" if found else "no weekday 07:15 routine"


def _notification_routine(reply: str, _) -> tuple[bool, str]:
    """The routine must exist, and posting a matching notification must run it and post its reply."""
    found = [r for r in _routines() if (r.get("text_contains") or "").lower() == "or1test"]
    if not found:
        return False, "no routine matching notifications containing or1test"
    rid = found[0]["id"]
    since = adb.shell("date '+%m-%d %H:%M:%S.000'").strip()
    adb.shell("cmd notification post -S bigtext -t 'Sam' or1eval 'or1test: the parcel is at the front desk' >/dev/null")
    deadline = time.time() + 120
    while time.time() < deadline:
        time.sleep(3)
        if f"firing {rid}" in adb.shell(f"logcat -d -s or1-routines -T '{since}'"):
            break
    else:
        return False, f"routine {rid} didn't run within 120 s"
    while time.time() < deadline:
        time.sleep(3)
        replies = adb.shell(f"dumpsys notification --noredact | grep -A40 'pkg={PHONE_PKG}' | grep 'android.text=' || true")
        if "parcel" in replies.lower() or "front desk" in replies.lower():
            return True, f"routine {rid} ran and posted its reply"
    return False, f"routine {rid} ran but no reply notification mentions the parcel"


def is_test_routine(r: dict) -> bool:
    """Routines the eval creates, so cleanup removes only those."""
    return (r.get("text_contains") or "").lower() == "or1test" or (r.get("repeat") == "weekdays" and r.get("hour") == 7 and r.get("minute") == 15)


def test_routines() -> list[dict]:
    return [r for r in _routines() if is_test_routine(r)]


def _routines_removed(reply: str, _) -> tuple[bool, str]:
    left = test_routines()
    return not left, "both test routines removed" if not left else f"{len(left)} test routines left"


CHECKS: dict[str, Check] = {
    "What's my battery level, and is it charging?": Check(_battery),
    "What time and date is it?": Check(_date),
    "Turn the media volume down to 3.": Check(lambda r, _: (_volume() == 3, f"media volume {_volume()}")),
    "Set the screen brightness to about half.": Check(_equals("system", "screen_brightness", lambda v: 100 <= int(v) <= 156, "brightness")),
    "Turn on Do Not Disturb.": Check(_equals("global", "zen_mode", lambda v: v != "0", "zen_mode")),
    "Turn Do Not Disturb off again.": Check(_equals("global", "zen_mode", lambda v: v == "0", "zen_mode")),
    "What notifications do I have?": Check(_mentions("parcel"), before=_post_courier),
    "Which apps are installed on this phone?": Check(_mentions("Clock", "Settings", "Contacts")),
    "How much storage is free?": Check(lambda r, _: (bool(re.search(r"\d+(\.\d+)? ?GB", r)), "reply gives a size in GB")),
    "Change the screen timeout to 2 minutes.": Check(_equals("system", "screen_off_timeout", lambda v: v == "120000", "screen_off_timeout")),
    "Turn on dark mode.": Check(lambda r, _: ("yes" in adb.shell("cmd uimode night"), adb.shell("cmd uimode night").strip())),
    "Open Wi-Fi settings and tell me which network I'm connected to.": Check(_wifi),
    "Open Settings, go to About phone, and tell me the Android version and model.": Check(_mentions("13", "TrebleDroid")),
    "Set a timer for 5 minutes.": Check(_timer),
    'Set an alarm for 7:30 tomorrow morning called "Gym".': Check(_gym_alarm),
    "Create a contact named Test Person with phone number 555-0100.": Check(_contact),
    "Add a calendar event called Dentist next Monday at 10am.": Check(_dentist_added),
    "What's on my calendar for the next 7 days?": Check(_mentions("Dentist")),
    "Delete the Dentist event.": Check(_dentist_deleted),
    "Remember that I prefer short replies and dark mode.": Check(_memory_saved),
    "Make it quieter.": Check(_quieter, before=_volume),
    "Every weekday at 7:15, give me a short morning summary: battery level and today's calendar.": Check(_morning_routine),
    "What routines do I have?": Check(_mentions("7:15")),
    "When I get a notification containing or1test, tell me what it says.": Check(_notification_routine),
    "Remove the morning summary routine and the or1test routine.": Check(_routines_removed),
}
