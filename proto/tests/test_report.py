"""Eval trend report over synthetic runs."""

import json

from or1 import report


def write_run(d, stamp, records, model="claude-haiku-5-5", effort="medium"):
    (d / f"eval-{stamp}-{model}-{effort}.jsonl").write_text("".join(json.dumps(r) + "\n" for r in records))


def rec(text, success, seconds=2.0, cost=0.001, target="phone"):
    return {"text": text, "success": success, "seconds": seconds, "cost": cost, "target": target}


def test_loads_runs_oldest_first_and_skips_empty(tmp_path):
    write_run(tmp_path, "20261010-120000", [rec("a", True)])
    write_run(tmp_path, "20261009-090000", [rec("a", False)])
    (tmp_path / "eval-20261011-080000-claude-haiku-5-5-medium.jsonl").write_text("")
    runs = report.load_runs(tmp_path)
    assert [r.stamp for r in runs] == ["2026-10-09 09:00", "2026-10-10 12:00"]
    assert runs[1].passed == 1 and runs[0].passed == 0


def test_regression_is_pass_then_fail(tmp_path):
    write_run(tmp_path, "20261010-100000", [rec("a", True), rec("b", False)])
    write_run(tmp_path, "20261010-110000", [rec("a", False), rec("b", True)])
    assert report.regressions(report.load_runs(tmp_path)) == ["a"]


def test_unjudged_results_do_not_break_history(tmp_path):
    write_run(tmp_path, "20261010-100000", [rec("a", True)])
    write_run(tmp_path, "20261010-110000", [rec("a", None)])
    write_run(tmp_path, "20261010-120000", [rec("a", False)])
    assert report.regressions(report.load_runs(tmp_path)) == ["a"]


def test_render_marks_and_new_tasks(tmp_path):
    write_run(tmp_path, "20261010-100000", [rec("old task", True)])
    write_run(tmp_path, "20261010-110000", [rec("old task", True), rec("new task", None, seconds=4.0)])
    out = report.render(report.load_runs(tmp_path))
    assert "✓✓  " in out and " ·  " in out
    assert out.index("old task") < out.index("new task")
    assert "Regressions: none" in out


def test_gap_buckets_leave_out_evals():
    records = [
        {"kind": "chat", "since_any_s": None, "since_chat_s": None},
        {"kind": "chat", "since_any_s": 30, "since_chat_s": 30},
        {"kind": "routine", "since_any_s": 900, "since_chat_s": None},
        {"kind": "chat", "since_any_s": 5000, "since_chat_s": 7200, "auto_new_chat": True},
        {"kind": "eval", "since_any_s": 2, "since_chat_s": 2},
    ]
    assert report.bucket_gaps(records, "since_any_s") == {"< 5 min": 1, "5-60 min": 1, "> 60 min": 1, "first": 1}
    assert report.bucket_gaps(records, "since_chat_s", ("chat",)) == {"< 5 min": 1, "5-60 min": 0, "> 60 min": 1, "first": 1}
    assert "1 started a new chat" in report.render_gaps(records)
