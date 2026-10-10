"""Trends across eval runs (runs/eval-*.jsonl): pass rate, cost and time per run, and each task's history.

    uv run or1-eval-report                # last 10 phone runs
    uv run or1-eval-report --runs 20 --target all

Tasks are matched by their text, since numbers shift when tasks are added. A task is a regression when its latest
judged result failed after it passed in the run before.
"""

import argparse
import json
import re
import statistics
from dataclasses import dataclass, field
from pathlib import Path

from .agent import RUNS_DIR

NAME = re.compile(r"eval-(\d{8})-(\d{6})-(.+)-(low|medium|high|max)\.jsonl$")
MARK = {True: "✓", False: "✗", None: "·"}


@dataclass
class Run:
    path: Path
    stamp: str  # "2026-10-10 14:27"
    model: str
    effort: str
    records: list[dict] = field(default_factory=list)

    @property
    def target(self) -> str:
        return self.records[0].get("target", "host") if self.records else "?"

    @property
    def judged(self) -> list[dict]:
        return [r for r in self.records if r.get("success") is not None]

    @property
    def passed(self) -> int:
        return sum(1 for r in self.judged if r["success"])

    @property
    def cost(self) -> float:
        return sum(r.get("cost") or 0 for r in self.records)

    @property
    def median_seconds(self) -> float:
        return statistics.median(r["seconds"] for r in self.records) if self.records else 0.0

    def result(self, text: str) -> dict | None:
        return next((r for r in self.records if r.get("text") == text), None)


def load_runs(runs_dir: Path = RUNS_DIR) -> list[Run]:
    """All eval runs, oldest first. Runs with no records (aborted at once) are skipped."""
    runs = []
    for path in sorted(runs_dir.glob("eval-*.jsonl")):
        m = NAME.search(path.name)
        if not m:
            continue
        d, t, model, effort = m.groups()
        records = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        if records:
            runs.append(Run(path, f"{d[:4]}-{d[4:6]}-{d[6:]} {t[:2]}:{t[2:4]}", model, effort, records))
    return runs


def regressions(runs: list[Run]) -> list[str]:
    """Tasks whose latest judged result failed while the judged result before it passed."""
    found = []
    for text in task_texts(runs):
        history = [r["success"] for run in runs if (r := run.result(text)) and r.get("success") is not None]
        if len(history) >= 2 and history[-1] is False and history[-2] is True:
            found.append(text)
    return found


def task_texts(runs: list[Run]) -> list[str]:
    """Every task seen: the latest run's tasks in its order, then tasks only older runs had."""
    texts: list[str] = []
    for run in reversed(runs):
        texts += [r["text"] for r in run.records if r.get("text") and r["text"] not in texts]
    return texts


def render(runs: list[Run]) -> str:
    if not runs:
        return "No eval runs."
    lines = ["Runs (oldest first):", f"  {'#':>2}  {'when':16}  {'model / effort':28}  {'pass':>7}  {'cost':>8}  {'median':>7}"]
    for i, run in enumerate(runs, 1):
        judged = len(run.judged)
        rate = f"{run.passed}/{judged}" if judged else "-"
        lines.append(f"  {i:>2}  {run.stamp:16}  {run.model + ' / ' + run.effort:28}  {rate:>7}  "
                     f"${run.cost:>7.4f}  {run.median_seconds:>6.1f}s")
    lines += ["", f"Tasks ({MARK[True]} pass, {MARK[False]} fail, {MARK[None]} unjudged, blank = not run), runs 1-{len(runs)}:"]
    for text in task_texts(runs):
        marks = "".join(MARK[r.get("success")] if (r := run.result(text)) else " " for run in runs)
        seconds = [r["seconds"] for run in runs if (r := run.result(text))]
        lines.append(f"  {marks}  {statistics.median(seconds):>5.1f}s  {text[:70]}")
    found = regressions(runs)
    lines += ["", "Regressions: " + ("; ".join(t[:60] for t in found) if found else "none")]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Trends across eval runs.")
    parser.add_argument("--runs", type=int, default=10, help="how many recent runs to show")
    parser.add_argument("--target", choices=["phone", "host", "all"], default="phone")
    parser.add_argument("--dir", type=Path, default=RUNS_DIR)
    args = parser.parse_args()
    runs = [r for r in load_runs(args.dir) if args.target == "all" or r.target == args.target]
    print(render(runs[-args.runs:]))
