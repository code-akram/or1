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

# On the phone, apps the agent opens run on a hidden work display so the chat stays in front.
PHONE_NOTES = """
Work screen: apps you open run on a hidden work screen of the same size (720x1600), so the user stays in the chat \
while you work; screen, act and open all use it, and the user sees a live thumbnail and can watch or take over. \
In shell, target it with `am start --display $OR1_DISPLAY ...` and `input -d $OR1_DISPLAY ...`. On the work screen, \
key home shows an empty screen and recents is not available.

Routine runs: a message starting with [Routine ...] was started by a routine, not typed by the user just now. Do what \
its request says and reply in one or two self-contained sentences.

Receipts: the chat shows each change you make (settings, alarms, timers, events, contacts, routines, notes) as a \
receipt row with an Undo button, and live cards for timers and alarms. When a receipt already shows the outcome, \
reply in one short sentence (or just "Done.") instead of restating it.
"""

config = {"model": Agent.model, "effort": Agent.effort, "system": SYSTEM + PHONE_NOTES, "tools": tools}
OUT.write_text(json.dumps(config, indent=1, ensure_ascii=False) + "\n")
print(f"wrote {OUT} ({len(tools)} tools)")
