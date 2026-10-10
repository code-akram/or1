"""Read what is on the phone's screen as compact text, or as a small screenshot."""

import base64
import io
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass

from PIL import Image

from . import adb

DUMP_PATH = "/data/local/tmp/or1-ui.xml"
MAX_ELEMENTS = 150
SCREENSHOT_WIDTH = 360


class NotIdle(adb.AdbError):
    """uiautomator can't dump screens that keep changing (animations, live counters)."""


@dataclass
class Element:
    id: int
    x: int
    y: int
    label: str


# Element ids from the most recent screen read; `act` taps resolve against this.
last_elements: dict[int, Element] = {}

_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def foreground() -> str:
    out = adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2")
    m = re.search(r"u0 ([\w.]+)/([\w.$]+)", out)
    return f"{m.group(1)}/{m.group(2).split('.')[-1]}" if m else "unknown"


def _dump() -> ET.Element:
    for attempt in range(3):
        out = adb.shell(f"uiautomator dump --compressed {DUMP_PATH} 2>&1; cat {DUMP_PATH} 2>/dev/null; rm -f {DUMP_PATH}")
        start = out.find("<?xml")
        if start != -1:
            return ET.fromstring(out[start:])
        if "idle state" in out:
            raise NotIdle("the screen never went idle, so the element list is unavailable")
        # The dump fails while the screen is off or mid-animation.
        adb.shell("input keyevent KEYCODE_WAKEUP; sleep 0.7")
    raise adb.AdbError("uiautomator could not dump the screen: " + out.strip()[:200])


def read() -> str:
    """Return the foreground app and a numbered list of meaningful on-screen elements."""
    root = _dump()
    last_elements.clear()
    lines = []
    for node in root.iter("node"):
        a = node.attrib
        m = _BOUNDS.match(a.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        cls = a.get("class", "").split(".")[-1]
        text, desc = a.get("text", "").strip(), a.get("content-desc", "").strip()
        clickable = a.get("clickable") == "true" or a.get("long-clickable") == "true"
        editable = cls in ("EditText", "AutoCompleteTextView")
        scrollable = a.get("scrollable") == "true"
        checkable = a.get("checkable") == "true"
        if not (text or desc or clickable or editable or scrollable or checkable):
            continue
        rid = a.get("resource-id", "").split("/")[-1]
        label = text or desc or rid
        if text and desc and desc != text:
            label = f"{text} ({desc})"
        flags = [f for f, on in (
            ("click", clickable), ("edit", editable), ("scroll", scrollable),
            ("checked", a.get("checked") == "true"), ("unchecked", checkable and a.get("checked") != "true"),
            ("selected", a.get("selected") == "true"), ("focused", a.get("focused") == "true"),
            ("disabled", a.get("enabled") == "false"), ("password", a.get("password") == "true"),
        ) if on]
        eid = len(last_elements) + 1
        el = Element(eid, (x1 + x2) // 2, (y1 + y2) // 2, label)
        last_elements[eid] = el
        lines.append(f"[{eid}] {cls} {label[:80]!r} @{el.x},{el.y} {' '.join(flags)}".rstrip())
        if eid >= MAX_ELEMENTS:
            lines.append(f"... truncated at {MAX_ELEMENTS} elements")
            break
    header = f"Foreground: {foreground()}. Screen 720x1600."
    return header + "\n" + ("\n".join(lines) if lines else "(no readable elements)")


def read_or_screenshot() -> str | list:
    """Element list if possible; otherwise a screenshot, so the agent can still see the screen."""
    try:
        return read()
    except NotIdle as e:
        last_elements.clear()
        note = (f"Foreground: {foreground()}. Element list unavailable ({e}); screenshot attached. "
                "Tap with x,y coordinates on the 720x1600 screen (the image is scaled down by 2).")
        return [{"type": "text", "text": note}, screenshot()]


def screenshot() -> dict:
    """Return a downscaled screenshot as an image content block."""
    png = adb.exec_out("screencap -p")
    img = Image.open(io.BytesIO(png)).convert("RGB")
    img.thumbnail((SCREENSHOT_WIDTH, SCREENSHOT_WIDTH * 4))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=70)
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.standard_b64encode(buf.getvalue()).decode()},
    }
