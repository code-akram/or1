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

# On the phone, apps the agent opens run on a hidden work display so the chat stays in front.
PHONE_NOTES = """
Work screen: apps you open run on a hidden work screen of the same size (720x1600), so the user stays in the chat \
while you work; screen, act and open all use it, and the user sees a live thumbnail and can bring an app to the front. \
In shell, target it with `am start --display $OR1_DISPLAY ...` and `input -d $OR1_DISPLAY ...`. On the work screen, \
key home shows an empty screen and recents is not available.
"""

config = {"model": Agent.model, "effort": Agent.effort, "system": SYSTEM + PHONE_NOTES, "tools": tools}
OUT.write_text(json.dumps(config, indent=1, ensure_ascii=False) + "\n")
print(f"wrote {OUT} ({len(tools)} tools)")
