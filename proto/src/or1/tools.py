"""The agent's tools: schemas sent to Claude, their executors, and which calls need approval.

Phase 0 runs everything through `adb shell` (uid shell). On the phone the same
commands will run from the privileged agent with root.
"""

import json
import re
import shlex
import time
from pathlib import Path

from . import adb, screen

MEMORY_FILE = Path(__file__).resolve().parents[2] / "state" / "memory.json"
SHELL_OUTPUT_LIMIT = 4000
SETTLE_SECONDS = 1.0

KEYS = {
    "back": "KEYCODE_BACK", "home": "KEYCODE_HOME", "recents": "KEYCODE_APP_SWITCH",
    "enter": "KEYCODE_ENTER", "delete": "KEYCODE_DEL", "wake": "KEYCODE_WAKEUP", "sleep": "KEYCODE_SLEEP",
}
NAVIGATION_KEYS = {"back", "home", "recents", "wake"}

TOOLS = [
    {
        "name": "screen",
        "description": (
            "Read what is currently on the phone's screen: the foreground app and a numbered list of elements "
            "(type, label, centre coordinates, flags such as click/edit/scroll/checked). Element numbers are valid "
            "until the next screen read or action. Set screenshot=true only when the element list is not enough "
            "(images, canvas content, unlabeled icons)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"screenshot": {"type": "boolean", "description": "Also return a small screenshot."}},
            "additionalProperties": False,
        },
    },
    {
        "name": "act",
        "description": (
            "Interact with the screen like a finger or hardware key. Returns the updated screen afterwards, so you "
            "do not need a separate screen read. "
            "tap/long_press: give `element` (number from the last screen read) or `x`,`y`. "
            "type: enters `text` into the focused field (tap the field first). "
            "scroll: swipes the content in `direction` (down = reveal content further down). "
            "key: press `key` (back, home, recents, enter, delete, wake, sleep)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["tap", "long_press", "type", "scroll", "key"]},
                "element": {"type": "integer"},
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "text": {"type": "string"},
                "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
                "key": {"type": "string", "enum": sorted(KEYS)},
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
    {
        "name": "open",
        "description": (
            "Open an app by package name (see `apps`), or open a URI (https://, tel:, geo:, mailto:, settings "
            "actions like android.settings.WIFI_SETTINGS). Returns the screen after opening."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "e.g. com.android.settings"},
                "uri": {"type": "string"},
                "settings_action": {"type": "string", "description": "e.g. android.settings.WIFI_SETTINGS"},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "device",
        "description": (
            "Read or change device state directly, without touching the UI. action=get returns battery, Wi-Fi, "
            "Bluetooth, media volume, brightness, Do Not Disturb, airplane mode, screen timeout, storage and the "
            "current date/time. action=set changes one `setting` to `value`: wifi/bluetooth/dnd/airplane/"
            "auto_brightness/dark_mode (on|off), volume (0 to the maximum shown by get), brightness (0-255), screen_timeout (seconds)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["get", "set"]},
                "setting": {
                    "type": "string",
                    "enum": ["wifi", "bluetooth", "dnd", "airplane", "auto_brightness", "dark_mode", "volume", "brightness",
                             "screen_timeout"],
                },
                "value": {"type": "string"},
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
    {
        "name": "clock",
        "description": (
            "Alarms and timers without touching the UI. set_alarm: `hour` (0-23), `minute`, optional `label`. "
            "set_timer: `seconds`, optional `label`. dismiss_alarm: turns off alarms matching `label`, or all "
            "alarms if no label is given. next_alarm: when the next alarm rings. To view or cancel timers, open the "
            "Clock app (open package com.android.deskclock) and use the screen."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["set_alarm", "set_timer", "dismiss_alarm", "next_alarm"]},
                "hour": {"type": "integer"},
                "minute": {"type": "integer"},
                "seconds": {"type": "integer"},
                "label": {"type": "string"},
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
    {
        "name": "calendar",
        "description": (
            "Calendar events without touching the UI. list: events from `date` (YYYY-MM-DD, default today) for "
            "`days` days (default 7), with event ids. add: `title` and `start` (YYYY-MM-DDTHH:MM local time, or "
            "YYYY-MM-DD for an all-day event); optional `end` (same format) or `duration_minutes` (default 60), "
            "`location`, `notes`, `reminder_minutes` (an alert that many minutes before). delete: `event_id` from list. "
            "Use device get first to resolve relative dates like \"next Monday\"."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["list", "add", "delete"]},
                "date": {"type": "string"},
                "days": {"type": "integer"},
                "title": {"type": "string"},
                "start": {"type": "string"},
                "end": {"type": "string"},
                "duration_minutes": {"type": "integer"},
                "all_day": {"type": "boolean"},
                "location": {"type": "string"},
                "notes": {"type": "string"},
                "reminder_minutes": {"type": "integer"},
                "event_id": {"type": "integer"},
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
    {
        "name": "notifications",
        "description": "List current notifications (app package, title, text, posted time).",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "apps",
        "description": (
            "action=list: launchable apps (package/activity). action=info: version, install time and enabled "
            "state of `package`."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["list", "info"]},
                "package": {"type": "string"},
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
    {
        "name": "shell",
        "description": (
            "Run a shell command on the phone (Android toybox shell: am, pm, cmd, settings, dumpsys, input, ...). "
            "Use when no other tool covers the task. Output is truncated to 4000 characters."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"command": {"type": "string"}},
            "required": ["command"],
            "additionalProperties": False,
        },
    },
    {
        "name": "memory",
        "description": (
            "Long-term notes about the user and their preferences that persist across conversations. "
            "action=view lists notes with indexes; add stores `text`; remove deletes the note at `index`."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["view", "add", "remove"]},
                "text": {"type": "string"},
                "index": {"type": "integer"},
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
]


def needs_approval(name: str, args: dict) -> bool:
    """Reads and navigation run freely; anything that changes state needs the user's OK. `open` with a URI is gated:
    a URI can carry data out (https://...?q=<notes>) if untrusted text steers the agent; an app or settings page can't."""
    if name in ("screen", "notifications", "apps", "memory"):
        return False
    if name == "open":
        return bool(args.get("uri"))
    if name == "device":
        return args.get("action") == "set"
    if name == "clock":
        return args.get("action") != "next_alarm"
    if name in ("calendar", "routines"):
        return args.get("action") != "list"
    if name == "act":
        action = args.get("action")
        return not (action == "scroll" or (action == "key" and args.get("key") in NAVIGATION_KEYS))
    return True


def describe(name: str, args: dict) -> str:
    """One-line human summary of a call, shown in the approval prompt."""
    if name == "act" and args.get("element") in screen.last_elements:
        el = screen.last_elements[args["element"]]
        args = {**args, "element": f"{args['element']} ({el.label[:40]})"}
    return f"{name} " + " ".join(f"{k}={v!r}" for k, v in args.items())


def _after_action() -> str | list:
    time.sleep(SETTLE_SECONDS)
    return screen.read_or_screenshot()


def _act(args: dict) -> str | list:
    action = args["action"]
    if action in ("tap", "long_press"):
        if "element" in args:
            el = screen.last_elements.get(args["element"])
            if el is None:
                raise ValueError(f"Element {args['element']} is not on the last screen read; read the screen again.")
            x, y = el.x, el.y
        elif "x" in args and "y" in args:
            x, y = args["x"], args["y"]
        else:
            raise ValueError("tap/long_press needs `element` or `x` and `y`.")
        if action == "tap":
            adb.shell(f"input tap {x} {y}")
        else:
            adb.shell(f"input swipe {x} {y} {x} {y} 700")
    elif action == "type":
        text = args.get("text")
        if not text:
            raise ValueError("type needs `text`.")
        if not text.isascii():
            raise ValueError("Only ASCII text can be typed in Phase 0 (adb `input text` limitation).")
        adb.shell("input text " + shlex.quote(text.replace(" ", "%s")))
    elif action == "scroll":
        # Finger moves opposite to the direction the content should reveal.
        swipes = {"down": (360, 1150, 360, 450), "up": (360, 450, 360, 1150),
                  "right": (600, 800, 120, 800), "left": (120, 800, 600, 800)}
        x1, y1, x2, y2 = swipes[args.get("direction", "down")]
        adb.shell(f"input swipe {x1} {y1} {x2} {y2} 300")
    elif action == "key":
        key = args.get("key")
        if key not in KEYS:
            raise ValueError(f"Unknown key {key!r}.")
        adb.shell(f"input keyevent {KEYS[key]}")
    else:
        raise ValueError(f"Unknown action {action!r}.")
    return _after_action()


def _open(args: dict) -> str | list:
    adb.shell("cmd statusbar collapse")
    if pkg := args.get("package"):
        component = adb.shell(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {shlex.quote(pkg)}").strip().splitlines()[-1]
        if "/" not in component:
            raise ValueError(f"{pkg} has no launchable activity (is the package name right? see apps list).")
        out = adb.shell(f"am start -n {shlex.quote(component)}")
    elif uri := args.get("uri"):
        out = adb.shell(f"am start -a android.intent.action.VIEW -d {shlex.quote(uri)}")
    elif action := args.get("settings_action"):
        out = adb.shell(f"am start -a {shlex.quote(action)}")
    else:
        raise ValueError("open needs `package`, `uri` or `settings_action`.")
    if "Error" in out:
        raise ValueError(out.strip()[:500])
    return _after_action()


def _device_get() -> str:
    out = adb.shell(
        "echo time=$(date '+%a %d %b %Y %H:%M %Z');"
        "dumpsys battery | grep -E '^  (level|status|AC powered|USB powered):' | tr -s ' ' | tr '\\n' ';'; echo;"
        "echo wifi=$(cmd wifi status | head -1); cmd wifi status | grep -o 'SSID: [^,]*' | head -1;"
        "echo bluetooth_on=$(settings get global bluetooth_on);"
        "echo media_volume=$(cmd media_session volume --stream 3 --get 2>/dev/null | grep -o 'volume is [0-9]* in range.*');"
        "echo brightness=$(settings get system screen_brightness) auto_brightness=$(settings get system screen_brightness_mode);"
        "echo dnd_zen_mode=$(settings get global zen_mode) '(0=off)';"
        "echo airplane=$(settings get global airplane_mode_on);"
        "echo dark_mode=$(cmd uimode night | awk '{print $3}');"
        "echo screen_timeout_ms=$(settings get system screen_off_timeout);"
        "df -h /data | tail -1 | awk '{print \"storage_free=\"$4\" of \"$2}'"
    )
    return out.replace("status: 2", "status: charging").strip()


def _max_volume() -> int:
    out = adb.shell("cmd media_session volume --stream 3 --get 2>/dev/null")
    m = re.search(r"range \[0\.\.(\d+)\]", out)
    return int(m.group(1)) if m else 15


def _device_set(setting: str | None, value: str | None) -> str:
    if not setting or value is None:
        raise ValueError("set needs `setting` and `value`.")
    v = value.strip().lower()
    onoff = {"on": True, "off": False, "true": True, "false": False, "1": True, "0": False}
    if setting in ("wifi", "bluetooth", "dnd", "airplane", "auto_brightness", "dark_mode"):
        if v not in onoff:
            raise ValueError(f"{setting} takes on|off.")
        on = onoff[v]
        cmd = {
            "wifi": f"cmd wifi set-wifi-enabled {'enabled' if on else 'disabled'}",
            "bluetooth": f"cmd bluetooth_manager {'enable' if on else 'disable'}",
            "dnd": f"cmd notification set_dnd {'on' if on else 'off'}",
            "airplane": f"cmd connectivity airplane-mode {'enable' if on else 'disable'}",
            "auto_brightness": f"settings put system screen_brightness_mode {1 if on else 0}",
            "dark_mode": f"cmd uimode night {'yes' if on else 'no'}",
        }[setting]
    elif setting in ("volume", "brightness", "screen_timeout"):
        if not v.isdigit():
            raise ValueError(f"{setting} takes a number.")
        n = int(v)
        cmd = {
            "volume": f"cmd media_session volume --stream 3 --set {min(n, _max_volume())}",
            "brightness": f"settings put system screen_brightness_mode 0; settings put system screen_brightness {min(n, 255)}",
            "screen_timeout": f"settings put system screen_off_timeout {n * 1000}",
        }[setting]
    else:
        raise ValueError(f"Unknown setting {setting!r}.")
    out = adb.shell(cmd + " 2>&1").strip()
    return f"{setting} set to {value}." + (f" Output: {out[:300]}" if out and "Connecting to" not in out else "")


ALARM_EXTRA = "android.intent.extra.alarm."


def _next_alarm() -> str:
    out = adb.shell("dumpsys alarm | grep -A1 'Next alarm clock information' | tail -1")
    m = re.search(r"= (\d{4}-\d\d-\d\d \d\d:\d\d)", out)
    return f"Next alarm: {m.group(1)}." if m else "No alarm is set."


def _clock(args: dict) -> str:
    action, label = args["action"], args.get("label")
    msg = f" --es {ALARM_EXTRA}MESSAGE {shlex.quote(label)}" if label else ""
    if action == "next_alarm":
        return _next_alarm()
    if action == "set_alarm":
        h, m = args.get("hour"), args.get("minute", 0)
        if h is None or not (0 <= h <= 23 and 0 <= m <= 59):
            raise ValueError("set_alarm needs `hour` 0-23 and `minute` 0-59.")
        cmd = (f"am start -a android.intent.action.SET_ALARM --ei {ALARM_EXTRA}HOUR {h} "
               f"--ei {ALARM_EXTRA}MINUTES {m}{msg} --ez {ALARM_EXTRA}SKIP_UI true")
    elif action == "set_timer":
        secs = args.get("seconds")
        if not secs or not 1 <= secs <= 86400:
            raise ValueError("set_timer needs `seconds` between 1 and 86400.")
        cmd = (f"am start -a android.intent.action.SET_TIMER --ei {ALARM_EXTRA}LENGTH {secs}{msg} "
               f"--ez {ALARM_EXTRA}SKIP_UI true")
    elif action == "dismiss_alarm":
        mode = f"--es {ALARM_EXTRA}SEARCH_MODE android.label{msg}" if label else f"--es {ALARM_EXTRA}SEARCH_MODE android.all"
        cmd = f"am start -a android.intent.action.DISMISS_ALARM {mode}"
    else:
        raise ValueError(f"Unknown action {action!r}.")
    out = adb.shell(cmd + " 2>&1")
    if "Error" in out:
        raise ValueError(out.strip()[:300])
    time.sleep(1.5)
    adb.shell("input keyevent KEYCODE_HOME")
    if action == "set_timer":
        return f"Timer set for {args['seconds']} seconds."
    return _next_alarm()


CAL = "content://com.android.calendar"
LOCAL_CALENDAR = "Phone"


def _phone_zone():
    from zoneinfo import ZoneInfo
    return ZoneInfo(adb.shell("getprop persist.sys.timezone").strip() or "UTC")


def _calendar_rows(out: str) -> list[dict]:
    """Parse `content query` output ("Row: 0 a=1, b=x") into dicts. Values may contain ", ", so split on known keys."""
    rows = []
    for line in out.splitlines():
        if not line.startswith("Row: "):
            continue
        body = line.split(" ", 2)[2]
        row = {}
        for part in re.split(r", (?=\w+=)", body):
            k, _, v = part.partition("=")
            row[k] = None if v == "NULL" else v
        rows.append(row)
    return rows


def _calendar_id() -> int:
    """The first visible calendar we can write to, or a new local "Phone" calendar (same as the phone app)."""
    out = adb.shell(f"content query --uri {CAL}/calendars --projection _id --where 'visible=1 AND calendar_access_level>=500' --sort _id")
    if rows := _calendar_rows(out):
        return int(rows[0]["_id"])
    uri = f"'{CAL}/calendars?caller_is_syncadapter=true&account_name={LOCAL_CALENDAR}&account_type=LOCAL'"
    binds = {"account_name": f"s:{LOCAL_CALENDAR}", "account_type": "s:LOCAL", "name": f"s:{LOCAL_CALENDAR}",
             "calendar_displayName": f"s:{LOCAL_CALENDAR}", "calendar_color": "i:-2525353", "calendar_access_level": "i:700",
             "ownerAccount": f"s:{LOCAL_CALENDAR}", "visible": "i:1", "sync_events": "i:1",
             "calendar_timezone": f"s:{_phone_zone().key}"}
    adb.shell(f"content insert --uri {uri} " + " ".join(f"--bind {k}:{v}" for k, v in binds.items()))
    return _calendar_id()


def _calendar(args: dict) -> str:
    from datetime import date, datetime, timedelta, timezone
    zone, action = _phone_zone(), args["action"]
    if action == "list":
        start = date.fromisoformat(args["date"]) if args.get("date") else datetime.now(zone).date()
        days = min(max(args.get("days", 7), 1), 366)
        begin = int(datetime.combine(start, datetime.min.time(), zone).timestamp() * 1000)
        end = int(datetime.combine(start + timedelta(days=days), datetime.min.time(), zone).timestamp() * 1000)
        out = adb.shell(f"content query --uri {CAL}/instances/when/{begin}/{end} "
                        "--projection event_id:title:begin:end:allDay:eventLocation --sort begin")
        lines = []
        for r in _calendar_rows(out):
            tz = timezone.utc if r["allDay"] == "1" else zone
            b = datetime.fromtimestamp(int(r["begin"]) / 1000, tz)
            e = datetime.fromtimestamp(int(r["end"]) / 1000, tz)
            when = b.strftime("%a %d %b") + " (all day)" if r["allDay"] == "1" else b.strftime("%a %d %b %H:%M") + e.strftime("-%H:%M")
            loc = f" @ {r['eventLocation']}" if r.get("eventLocation") else ""
            lines.append(f"- [{r['event_id']}] {when} {r['title'] or ''}{loc}")
        return "Events (event ids in brackets):\n" + "\n".join(lines) if lines else "No events in that range."
    if action == "add":
        title, start = args.get("title"), args.get("start")
        if not title or not start:
            raise ValueError("add needs `title` and `start`.")
        all_day = args.get("all_day") or "T" not in start
        binds = {"calendar_id": f"i:{_calendar_id()}", "title": f"s:{title}"}
        if all_day:
            d = date.fromisoformat(start[:10])
            until = date.fromisoformat(args["end"][:10]) if args.get("end") else d
            binds |= {"allDay": "i:1", "eventTimezone": "s:UTC",
                      "dtstart": f"l:{int(datetime.combine(d, datetime.min.time(), timezone.utc).timestamp() * 1000)}",
                      "dtend": f"l:{int(datetime.combine(until + timedelta(days=1), datetime.min.time(), timezone.utc).timestamp() * 1000)}"}
        else:
            b = datetime.fromisoformat(start).replace(tzinfo=zone)
            e = datetime.fromisoformat(args["end"]).replace(tzinfo=zone) if args.get("end") else \
                b + timedelta(minutes=args.get("duration_minutes", 60))
            if e <= b:
                raise ValueError("`end` must be after `start`.")
            binds |= {"eventTimezone": f"s:{zone.key}", "dtstart": f"l:{int(b.timestamp() * 1000)}", "dtend": f"l:{int(e.timestamp() * 1000)}"}
        for key in ("location", "notes"):
            if args.get(key):
                binds[{"location": "eventLocation", "notes": "description"}[key]] = f"s:{args[key]}"
        adb.shell(f"content insert --uri {CAL}/events " + " ".join(f"--bind {k}:{shlex.quote(v)}" for k, v in binds.items()))
        where = shlex.quote(f"calendar_id={binds['calendar_id'][2:]} AND dtstart={binds['dtstart'][2:]}")
        rows = _calendar_rows(adb.shell(f"content query --uri {CAL}/events --projection _id --where {where} --sort '_id DESC'"))
        if not rows:
            raise ValueError("The calendar provider did not save the event.")
        event_id = rows[0]["_id"]
        if (minutes := args.get("reminder_minutes")) is not None:
            adb.shell(f"content insert --uri {CAL}/reminders --bind event_id:l:{event_id} --bind minutes:i:{minutes} --bind method:i:1")
        return f"Added “{title}” (event id {event_id})."
    if action == "delete":
        if args.get("event_id") is None:
            raise ValueError("delete needs `event_id` (see list).")
        adb.shell(f"content delete --uri {CAL}/events/{int(args['event_id'])}")
        return f"Deleted event {args['event_id']}."
    raise ValueError(f"Unknown action {action!r}.")


def _notifications() -> str:
    out = adb.shell("dumpsys notification --noredact")
    items, cur = [], None
    for line in out.splitlines():
        if "mArchive" in line or "Snoozed" in line:
            break
        if m := re.search(r"NotificationRecord\(0x\w+: pkg=(\S+).*?key=(\S+?):", line):
            cur = {"pkg": m.group(1), "key": m.group(2)}
            items.append(cur)
        elif cur is not None:
            if m := re.search(r"android\.(title|text|bigText)=\w+ \((.*)\)\s*$", line):
                cur.setdefault(m.group(1), m.group(2))
            elif m := re.match(r"\s+when=(\d+)", line):
                cur["when"] = int(m.group(1))
    seen, lines = set(), []
    for n in items:
        if n["key"] in seen or not (n.get("title") or n.get("text")):
            continue
        seen.add(n["key"])
        when = time.strftime("%H:%M %d %b", time.localtime(n["when"] / 1000)) if n.get("when") else "-"
        body = n.get("bigText") or n.get("text", "")
        lines.append(f"- {n['pkg']} [{when}] {n.get('title', '')}: {body}")
    return "\n".join(lines) or "No notifications."


def _apps(args: dict) -> str:
    if args["action"] == "list":
        out = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        comps = sorted({l.strip() for l in out.splitlines() if "/" in l})
        return "Launchable apps:\n" + "\n".join(comps)
    pkg = args.get("package")
    if not pkg:
        raise ValueError("info needs `package`.")
    out = adb.shell(
        f"dumpsys package {shlex.quote(pkg)} | grep -E 'versionName|firstInstallTime|lastUpdateTime|enabled=|installerPackageName' | head -8"
    )
    return out.strip() or f"Package {pkg} not found."


def _shell(args: dict) -> str:
    out = adb.shell(args["command"] + " 2>&1", timeout=60)
    if len(out) > SHELL_OUTPUT_LIMIT:
        out = out[:SHELL_OUTPUT_LIMIT] + f"\n... [truncated, {len(out)} chars total]"
    return out or "(no output)"


def _normalize_note(note: str) -> str:
    """"The user prefers X." and "User prefers x" are the same note."""
    text = re.sub(r"[^a-z0-9 ]", "", note.lower()).strip().removeprefix("the ").removeprefix("user ")
    return re.sub(r" +", " ", text)


def _memory(args: dict) -> str:
    notes = json.loads(MEMORY_FILE.read_text()) if MEMORY_FILE.exists() else []
    action = args["action"]
    if action == "add":
        if not args.get("text"):
            raise ValueError("add needs `text`.")
        for i, note in enumerate(notes):
            if _normalize_note(note) == _normalize_note(args["text"]):
                return f"Already noted as {i}. {note}"
        notes.append(args["text"])
    elif action == "remove":
        i = args.get("index")
        if i is None or not 0 <= i < len(notes):
            raise ValueError("remove needs a valid `index` (see view).")
        notes.pop(i)
    if action != "view":
        MEMORY_FILE.parent.mkdir(parents=True, exist_ok=True)
        MEMORY_FILE.write_text(json.dumps(notes, indent=1))
    return "\n".join(f"{i}. {n}" for i, n in enumerate(notes)) or "No notes yet."


def execute(name: str, args: dict) -> str | list:
    """Run a tool. Returns text, or a list of content blocks (for screenshots)."""
    if name == "screen":
        result = screen.read_or_screenshot()
        if args.get("screenshot") and isinstance(result, str):
            return [{"type": "text", "text": result}, screen.screenshot()]
        return result
    if name == "act":
        return _act(args)
    if name == "open":
        return _open(args)
    if name == "device":
        return _device_get() if args.get("action") == "get" else _device_set(args.get("setting"), args.get("value"))
    if name == "clock":
        return _clock(args)
    if name == "calendar":
        return _calendar(args)
    if name == "notifications":
        return _notifications()
    if name == "apps":
        return _apps(args)
    if name == "shell":
        return _shell(args)
    if name == "memory":
        return _memory(args)
    raise ValueError(f"Unknown tool {name!r}.")
