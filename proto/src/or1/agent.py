"""The agent loop: one user message in, tool calls against the phone, one reply out."""

import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import anthropic

from . import adb, tools
from .prompt import SYSTEM

MAX_STEPS = 30
RUNS_DIR = Path(__file__).resolve().parents[2] / "runs"

# USD per million tokens: (input, output, cache read, 1-hour cache write).
# Haiku 5.5 doubles to the long-context card above 100K prompt tokens. Verify against the pricing page.
PRICES = {
    "claude-haiku-5-5": (0.10, 0.50, 0.01, 0.20),
    "claude-sonnet-5-5": (2.00, 10.00, 0.20, 4.00),
    "claude-opus-5-5": (4.00, 20.00, 0.20, 8.00),
}
HAIKU_LONG_PROMPT = 100_000

WEB_SEARCH_TOOL = {"type": "web_search_20250305", "name": "web_search", "max_uses": 3}


@dataclass
class Usage:
    requests: int = 0
    input: int = 0
    output: int = 0
    cache_read: int = 0
    cache_write: int = 0
    web_searches: int = 0
    cost: float = 0.0

    def add(self, model: str, u) -> float:
        cache_read = u.cache_read_input_tokens or 0
        cache_write = u.cache_creation_input_tokens or 0
        p_in, p_out, p_read, p_write = PRICES.get(model, PRICES["claude-opus-5-5"])
        if model == "claude-haiku-5-5" and u.input_tokens + cache_read + cache_write > HAIKU_LONG_PROMPT:
            p_in, p_out, p_read, p_write = (p * 5 for p in (p_in, p_out, p_read, p_write))
        cost = (u.input_tokens * p_in + u.output_tokens * p_out + cache_read * p_read + cache_write * p_write) / 1e6
        self.requests += 1
        self.input += u.input_tokens
        self.output += u.output_tokens
        self.cache_read += cache_read
        self.cache_write += cache_write
        if u.server_tool_use:
            self.web_searches += u.server_tool_use.web_search_requests or 0
        self.cost += cost
        return cost

    def summary(self) -> str:
        s = (f"{self.requests} requests, in {self.input} / cached {self.cache_read} / cache-write {self.cache_write} / "
             f"out {self.output} tokens, ${self.cost:.4f}")
        return s + (f", {self.web_searches} web searches" if self.web_searches else "")


@dataclass
class Hooks:
    """Callbacks the CLI uses to show progress and ask for approval."""
    approve: Callable[[str, dict], bool]
    thinking: Callable[[str], None] = lambda text: None
    text: Callable[[str], None] = lambda text: None
    tool_call: Callable[[str, dict], None] = lambda name, args: None
    tool_result: Callable[[str, str, bool], None] = lambda name, result, is_error: None


@dataclass
class Agent:
    hooks: Hooks
    model: str = "claude-haiku-5-5"
    effort: str = "medium"
    web: bool = False
    client: anthropic.Anthropic = field(default_factory=anthropic.Anthropic)
    messages: list = field(default_factory=list)
    usage: Usage = field(default_factory=Usage)
    log_path: Path = field(default_factory=lambda: RUNS_DIR / time.strftime("%Y%m%d-%H%M%S.jsonl"))

    def _tools(self) -> list:
        return tools.TOOLS + ([WEB_SEARCH_TOOL] if self.web else [])

    def _log(self, record: dict) -> None:
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        with self.log_path.open("a") as f:
            f.write(json.dumps(record, default=str) + "\n")

    def send(self, text: str) -> str:
        """Handle one user message to completion and return the final reply text."""
        start = len(self.messages)
        # The phone's local time rides on each message (not the system prompt, which must stay byte-stable for the
        # cache), so date-relative requests don't spend a call on `device get`.
        now = adb.shell("date '+%a %d %b %Y %H:%M %Z'").strip()
        self.messages.append({"role": "user", "content": f"[Now: {now}] {text}"})
        self._log({"t": time.time(), "user": text, "now": now})
        try:
            return self._loop(start)
        except KeyboardInterrupt:
            self._close_open_tool_calls("Interrupted by the user before this ran.")
            raise

    def _loop(self, start: int) -> str:
        for _ in range(MAX_STEPS):
            # Prompt caching, 1-hour TTL: an explicit breakpoint after the static tools + system prompt, plus
            # top-level automatic caching for the growing conversation. Both 1h: a 1-hour entry can't follow a
            # 5-minute one. Requests in a session are minutes apart at most, but a conversation picked up later
            # within the hour still hits.
            response = self.client.messages.create(
                model=self.model,
                max_tokens=16000,
                system=[{"type": "text", "text": SYSTEM, "cache_control": {"type": "ephemeral", "ttl": "1h"}}],
                cache_control={"type": "ephemeral", "ttl": "1h"},
                tools=self._tools(),
                messages=self.messages,
                thinking={"type": "adaptive", "display": "summarized"},
                output_config={"effort": self.effort},
            )
            cost = self.usage.add(self.model, response.usage)
            self._log({"t": time.time(), "stop": response.stop_reason, "usage": response.usage.model_dump(),
                       "cost": cost, "content": [b.model_dump() for b in response.content]})

            if response.stop_reason == "refusal":
                # Drop this whole exchange so the history stays valid for the next message.
                del self.messages[start:]
                category = response.stop_details.category if response.stop_details else None
                return f"[The model declined this request{f' ({category})' if category else ''}.]"

            self.messages.append({"role": "assistant", "content": response.content})
            reply = []
            for block in response.content:
                if block.type == "thinking" and block.thinking:
                    self.hooks.thinking(block.thinking)
                elif block.type == "text":
                    self.hooks.text(block.text)
                    reply.append(block.text)

            if response.stop_reason == "pause_turn":
                continue
            if response.stop_reason == "max_tokens":
                return "\n".join(reply) + "\n[Stopped: hit the output token limit.]"

            calls = [b for b in response.content if b.type == "tool_use"]
            if not calls:
                return "\n".join(reply)
            self.messages.append({"role": "user", "content": [self._run_tool(c) for c in calls]})
        return f"[Stopped after {MAX_STEPS} steps without finishing.]"

    def _run_tool(self, call) -> dict:
        name, args = call.name, call.input if isinstance(call.input, dict) else {}
        self.hooks.tool_call(name, args)
        result: dict = {"type": "tool_result", "tool_use_id": call.id}
        if tools.needs_approval(name, args) and not self.hooks.approve(name, args):
            result["content"] = "The user declined this action."
            self.hooks.tool_result(name, result["content"], False)
            self._log({"t": time.time(), "tool": name, "args": args, "declined": True})
            return result
        try:
            content = tools.execute(name, args)
            result["content"] = content
            summary = content if isinstance(content, str) else content[0]["text"] + " [+screenshot]"
            self.hooks.tool_result(name, summary, False)
        except Exception as e:  # tool failures go back to the model, not up the stack
            result["content"] = f"Error: {e}"
            result["is_error"] = True
            self.hooks.tool_result(name, result["content"], True)
        self._log({"t": time.time(), "tool": name, "args": args,
                   "result": result["content"] if isinstance(result["content"], str) else "[blocks]",
                   "is_error": result.get("is_error", False)})
        return result

    def _close_open_tool_calls(self, note: str) -> None:
        """After an interrupt, answer any unanswered tool calls so the next request is valid."""
        if not self.messages or self.messages[-1]["role"] != "assistant":
            return
        calls = [b for b in self.messages[-1]["content"] if getattr(b, "type", None) == "tool_use"]
        if calls:
            self.messages.append({"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": c.id, "content": note} for c in calls]})
