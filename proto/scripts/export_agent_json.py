"""Export the system prompt and tool schemas to the Android app, so host and phone agents share one definition.

    uv run python scripts/export_agent_json.py
"""

import json
from pathlib import Path

from or1.agent import Agent
from or1.prompt import SYSTEM
from or1.tools import TOOLS

OUT = Path(__file__).resolve().parents[2] / "app" / "agent" / "src" / "main" / "assets" / "agent.json"

# The phone agent drives the screen through an accessibility service rather than adb, so typing takes Unicode
# and can target an element directly. Adjust the act description accordingly.
tools = json.loads(json.dumps(TOOLS))
for tool in tools:
    if tool["name"] == "act":
        tool["description"] = tool["description"].replace(
            "type: enters `text` into the focused field (tap the field first).",
            "type: replaces the text of `element` (an editable field) or of the focused field with `text`.",
        )

# Routines need the agent running on the phone (alarms, the notification listener), so only the phone gets them.
tools.append({
    "name": "routines",
    "description": (
        "Requests you run later on your own, even when the user isn't looking: at a time, or when a matching "
        "notification arrives. Use for \"every morning...\", \"remind me in an hour to...\", \"when I get a message "
        "from...\". For a plain alarm or timer use clock. add: `prompt` (what to do when it runs, written as an "
        "instruction to yourself, e.g. \"Tell the user their battery level and today's calendar\") and exactly one "
        "trigger: `at` (HH:MM, 24-hour local time) with optional `repeat` (once, daily, weekdays, weekly; default once) "
        "and `date` (YYYY-MM-DD: the day for once, the weekday for weekly); or `in_minutes`; or a notification match: "
        "`app` (package or app name) and/or `text_contains` (case-insensitive); optional `name` (a short label the user sees, "
        "e.g. \"Morning summary\"). list: routines with ids. remove: `id`. "
        "When a routine runs, its request appears in the chat and your reply is shown as a notification."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ["add", "list", "remove"]},
            "prompt": {"type": "string"},
            "name": {"type": "string"},
            "at": {"type": "string"},
            "repeat": {"type": "string", "enum": ["once", "daily", "weekdays", "weekly"]},
            "date": {"type": "string"},
            "in_minutes": {"type": "integer"},
            "app": {"type": "string"},
            "text_contains": {"type": "string"},
            "id": {"type": "string"},
        },
        "required": ["action"],
        "additionalProperties": False,
    },
})

# Media keeps playing on the phone's stage display after a turn; the phone controls it through media sessions.
tools.append({
    "name": "media",
    "description": (
        "Songs and videos through the apps' media sessions, without their screens. status: what each media app is "
        "playing (title, playing or paused, and whether it's on its own screen). play with `query`: searches an app "
        "(`package`, default YouTube) and starts the top result in one step; use it whenever the user names something "
        "to play, instead of opening the app and searching. play, pause, next, previous without `query`: control the "
        "media that's playing (or `package`). Check the result: some apps (YouTube outside a playlist) ignore next "
        "and previous; then open the app."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ["status", "play", "pause", "next", "previous"]},
            "package": {"type": "string"},
            "query": {"type": "string", "description": "With play: what to search for and play, e.g. a song or video title."},
        },
        "required": ["action"],
        "additionalProperties": False,
    },
})

# On the phone, apps the agent opens run on a hidden work display so the chat stays in front.
PHONE_NOTES = """
Work screen: apps you open run on a hidden work screen of the same size (720x1600), so the user stays in the chat \
while you work; screen, act and open all use it, and the user sees a live thumbnail and can watch or take over. \
In shell, target it with `am start --display $OR1_DISPLAY ...` and `input -d $OR1_DISPLAY ...`. On the work screen, \
key home shows an empty screen and recents is not available.

Media: a song or video the user asks for keeps playing after your turn on its own screen (the screen tool notes it), \
so your work screen is free and other requests (a timer, a message) don't interrupt it; the user hears it. To play something the user names, use media play with a \
query. Control what plays with the media tool (status, pause, play, next); open the app only when you need its \
screen (to pick something else), which brings it back to your work screen. To stop a ringing or running timer use clock stop_timer, \
not the Clock app.

Routine runs: a message starting with [Routine ...] was started by a routine, not typed by the user just now. Do what \
its request says and reply in one or two self-contained sentences.

Receipts: the chat shows each change you make (settings, alarms, timers, events, contacts, routines, notes) as a \
receipt row with an Undo button, and live cards for timers, alarms, calendar lists, notifications, contacts, media \
and emails. When a receipt or card already shows the outcome, reply in one short sentence (or just "Done.") instead \
of restating it; for a calendar list, say what matters (for example the next event) rather than reading out every row.
"""

config = {"model": Agent.model, "effort": Agent.effort, "system": SYSTEM + PHONE_NOTES, "tools": tools}
OUT.write_text(json.dumps(config, indent=1, ensure_ascii=False) + "\n")
print(f"wrote {OUT} ({len(tools)} tools)")
