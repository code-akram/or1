"""Pure helpers: provider-output parsing, note de-duplication, test-routine matching. No phone needed."""

from or1 import checks, tools

QUERY = "Row: 0 _id=3, title=Lunch, with Bob, dtstart=1791784800000\nRow: 1 _id=4, title=NULL, dtstart=5\n"


def test_calendar_rows_keep_commas_in_values():
    rows = tools._calendar_rows(QUERY)
    assert rows[0] == {"_id": "3", "title": "Lunch, with Bob", "dtstart": "1791784800000"}
    assert rows[1]["title"] is None


def test_check_rows_match_tool_rows():
    rows = checks._rows(QUERY)
    assert rows[0]["title"] == "Lunch, with Bob"
    assert [r["_id"] for r in rows] == ["3", "4"]


def test_rows_ignore_other_lines():
    assert tools._calendar_rows("No result found.\n") == []
    assert checks._rows("Error while accessing provider\n") == []


def test_normalize_note():
    assert tools._normalize_note("The user prefers short replies.") == tools._normalize_note("User prefers short replies")
    assert tools._normalize_note("User prefers tea") != tools._normalize_note("User prefers coffee")


def test_memory_add_skips_duplicates(tmp_path, monkeypatch):
    monkeypatch.setattr(tools, "MEMORY_FILE", tmp_path / "memory.json")
    tools._memory({"action": "add", "text": "User prefers dark mode."})
    out = tools._memory({"action": "add", "text": "the user prefers dark mode"})
    assert out.startswith("Already noted as 0.")
    assert tools._memory({"action": "view"}) == "0. User prefers dark mode."


def test_is_test_routine():
    assert checks.is_test_routine({"text_contains": "OR1TEST"})
    assert checks.is_test_routine({"repeat": "weekdays", "hour": 7, "minute": 15})
    assert not checks.is_test_routine({"repeat": "daily", "hour": 7, "minute": 15})
    assert not checks.is_test_routine({"text_contains": "Sam"})


def test_every_check_matches_a_task():
    from pathlib import Path
    tasks = {l.strip() for l in (Path(__file__).parents[1] / "evals" / "tasks.txt").read_text().splitlines()
             if l.strip() and not l.startswith("#")}
    assert set(checks.CHECKS) <= tasks
