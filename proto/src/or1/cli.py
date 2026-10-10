"""Terminal chat with the phone agent: the Phase 0 stand-in for the on-device composer."""

import argparse
import os
import sys
from pathlib import Path

import anthropic

from . import adb, screen
from .agent import Agent, Hooks

DIM, CYAN, YELLOW, RED, RESET = "\033[2m", "\033[36m", "\033[33m", "\033[31m", "\033[0m"
API_KEY_FILE = Path.home() / ".config" / "or1" / "anthropic-api-key"


def load_api_key() -> None:
    """Use ANTHROPIC_API_KEY if set, else the key file (kept outside the project)."""
    if os.environ.get("ANTHROPIC_API_KEY"):
        return
    key = API_KEY_FILE.read_text().strip() if API_KEY_FILE.exists() else ""
    if not key:
        sys.exit(f"No API key: paste it into {API_KEY_FILE} (or set ANTHROPIC_API_KEY).")
    os.environ["ANTHROPIC_API_KEY"] = key


def make_hooks(auto_approve: bool = False) -> Hooks:
    always: set[str] = set()

    def approve(name: str, args: dict) -> bool:
        key = f"{name}:{args.get('action', '')}"
        if auto_approve or key in always:
            return True
        from .tools import describe
        while True:
            answer = input(f"{YELLOW}  approve {describe(name, args)}? [y]es / [n]o / [a]lways this session: {RESET}").strip().lower()
            if answer in ("y", "yes"):
                return True
            if answer in ("n", "no"):
                return False
            if answer in ("a", "always"):
                always.add(key)
                return True

    def tool_result(name: str, result: str, is_error: bool) -> None:
        lines = result.splitlines()
        head = "\n    ".join(lines[:3]) + (f"\n    ... ({len(lines) - 3} more lines)" if len(lines) > 3 else "")
        print(f"{RED if is_error else DIM}    {head}{RESET}")

    return Hooks(
        approve=approve,
        thinking=lambda text: print(f"{DIM}  thinking: {text.strip()}{RESET}"),
        tool_call=lambda name, args: print(f"{CYAN}  → {name} {args}{RESET}"),
        tool_result=tool_result,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Chat with the phone agent over ADB.")
    parser.add_argument("--model", default="claude-haiku-5-5")
    parser.add_argument("--effort", default="medium", choices=["low", "medium", "high", "xhigh", "max"])
    parser.add_argument("--web", action="store_true", help="enable the web_search server tool")
    args = parser.parse_args()

    load_api_key()
    try:
        adb.check_device()
    except adb.AdbError as e:
        sys.exit(f"Phone not reachable: {e}")

    agent = Agent(hooks=make_hooks(), model=args.model, effort=args.effort, web=args.web)
    print(f"or1 agent · {args.model} · effort {args.effort} · log {agent.log_path}")
    print("Commands: /cost  /reset  /screen  /effort <level>  /quit\n")

    while True:
        try:
            text = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not text:
            continue
        if text == "/quit":
            break
        if text == "/cost":
            print(agent.usage.summary())
            continue
        if text == "/reset":
            agent.messages.clear()
            print("Conversation cleared.")
            continue
        if text == "/screen":
            print(screen.read())
            continue
        if text.startswith("/effort "):
            agent.effort = text.split(maxsplit=1)[1]
            print(f"Effort set to {agent.effort} (takes effect next request; invalidates the message cache).")
            continue

        before = agent.usage.cost
        try:
            reply = agent.send(text)
        except KeyboardInterrupt:
            print(f"\n{RED}[interrupted]{RESET}")
            continue
        except anthropic.AuthenticationError:
            sys.exit("Anthropic API key missing or invalid: set ANTHROPIC_API_KEY.")
        except anthropic.APIStatusError as e:
            print(f"{RED}[API error {e.status_code}: {e.message}]{RESET}")
            continue
        except anthropic.APIConnectionError:
            print(f"{RED}[Network error reaching the API]{RESET}")
            continue
        print(f"\nagent> {reply}\n{DIM}  (${agent.usage.cost - before:.4f} this turn · ${agent.usage.cost:.4f} session){RESET}\n")

    print(agent.usage.summary())
