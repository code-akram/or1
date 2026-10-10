"""Trends across eval runs (runs/eval-*.jsonl): pass rate, cost and time per run, and each task's history. Also the
phone's request gaps (or1-gaps), to check the prompt cache's 1-hour TTL against real use.

    uv run or1-eval-report                # last 10 phone runs
    uv run or1-eval-report --runs 20 --target all
    uv run or1-gaps

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


# --- Request gaps (files/request-gaps.jsonl on the phone): is the 1-hour cache TTL right for real use? -------------

BUCKETS = (("< 5 min", 300), ("5-60 min", 3600), ("> 60 min", float("inf")))


def bucket_gaps(records: list[dict], field: str, kinds: tuple[str, ...] = ("chat", "routine")) -> dict[str, int]:
    """Count gaps in `field` (since_any_s or since_chat_s) per bucket, for the given kinds (evals left out)."""
    counts = {name: 0 for name, _ in BUCKETS} | {"first": 0}
    for r in records:
        if r.get("kind") not in kinds:
            continue
        gap = r.get(field)
        if gap is None:
            counts["first"] += 1
        else:
            counts[next(name for name, limit in BUCKETS if gap < limit)] += 1
    return counts


def render_gaps(records: list[dict]) -> str:
    real = [r for r in records if r.get("kind") in ("chat", "routine")]
    if not real:
        return "No chat or routine requests logged yet (eval requests are left out)."
    lines = [f"{len(real)} requests ({sum(r['kind'] == 'chat' for r in real)} chat, "
             f"{sum(r['kind'] == 'routine' for r in real)} routine), "
             f"{sum(1 for r in real if r.get('auto_new_chat'))} started a new chat after an hour idle."]
    for field, what in (("since_any_s", "since the previous request (shared system-prompt cache)"),
                        ("since_chat_s", "since the previous message in the same chat (conversation cache)")):
        counts = bucket_gaps(records, field, ("chat",) if field == "since_chat_s" else ("chat", "routine"))
        total = sum(counts.values()) or 1
        lines.append(f"Gap {what}:")
        lines += [f"  {name:>9}  {n:>4}  {n / total:>4.0%}" for name, n in counts.items()]
    lines.append("1-hour TTL hits on < 5 min and 5-60 min; a 5-minute TTL would hit only on < 5 min.")
    return "\n".join(lines)


def gaps_main() -> None:
    from . import adb
    out = adb.shell("run-as com.or1.agent cat files/request-gaps.jsonl 2>/dev/null || true")
    print(render_gaps([json.loads(l) for l in out.splitlines() if l.strip()]))
