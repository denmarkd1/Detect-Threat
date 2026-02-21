#!/usr/bin/env python3
"""Real-time dashboard for D_T System agent monitoring."""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict


WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
MONITORING_FILE = WORKSPACE_ROOT / "systems" / "D_T_System" / "data" / "monitoring" / "monitoring_state.json"
REFRESH_INTERVAL = float(os.environ.get("DT_MONITOR_REFRESH", "2"))


class Colors:
    """Terminal colour helper."""

    HEADER = "\033[95m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    GREEN = "\033[92m"
    YELLOW = "\033[93m"
    RED = "\033[91m"
    BOLD = "\033[1m"
    END = "\033[0m"


def clear_screen() -> None:
    os.system("clear" if os.name == "posix" else "cls")


def _parse_timestamp(value: str) -> tuple[str, str]:
    if not value:
        return ("Never", "")
    try:
        ts = value.rstrip("Z")
        dt = datetime.fromisoformat(ts)
        if value.endswith("Z"):
            dt = dt.replace(tzinfo=timezone.utc)
        now = datetime.now(dt.tzinfo or timezone.utc)
        delta = now - dt
        minutes, seconds = divmod(int(delta.total_seconds()), 60)
        hours, minutes = divmod(minutes, 60)
        human = f"{hours:02d}:{minutes:02d}:{seconds:02d} ago"
        return (value, human)
    except Exception:
        return (value, "")


def load_monitoring_state() -> Dict[str, Any]:
    try:
        if MONITORING_FILE.exists():
            with MONITORING_FILE.open("r", encoding="utf-8") as handle:
                return json.load(handle)
    except Exception as exc:  # pragma: no cover - display-only failure path
        return {"error": str(exc)}
    return {}


def format_progress_bar(progress: float, width: int = 20) -> str:
    try:
        progress = max(0.0, min(float(progress), 100.0))
    except (TypeError, ValueError):
        progress = 0.0
    filled = int(progress / 100.0 * width)
    bar = "█" * filled + "░" * (width - filled)
    return f"[{bar}] {progress:5.1f}%"


def colour_for_status(status: str) -> str:
    status = (status or "").lower()
    if status in {"active", "running", "ready"}:
        return Colors.GREEN + "🟢" + Colors.END
    if status in {"deploying", "pending", "initializing"}:
        return Colors.YELLOW + "🟡" + Colors.END
    if status in {"failed", "error"}:
        return Colors.RED + "🔴" + Colors.END
    if status in {"completed", "verified"}:
        return Colors.BLUE + "🔵" + Colors.END
    return Colors.CYAN + "⚪" + Colors.END


def display_dashboard() -> None:
    while True:
        try:
            clear_screen()
            print(f"{Colors.CYAN}╔{'═' * 78}╗{Colors.END}")
            print(f"{Colors.CYAN}║{Colors.BOLD} 🤖 D_T Agent Monitor - Real-time Dashboard{' ' * 23}║{Colors.END}")
            print(f"{Colors.CYAN}╚{'═' * 78}╝{Colors.END}\n")

            state = load_monitoring_state()
            if "error" in state:
                print(f"{Colors.RED}⚠️  Unable to read monitoring data: {state['error']}{Colors.END}")
                time.sleep(REFRESH_INTERVAL)
                continue

            agents: Dict[str, Dict[str, Any]] = state.get("monitored_agents", {}) or {}
            timestamp_raw, timestamp_human = _parse_timestamp(state.get("last_updated", ""))
            monitoring_active = state.get("monitoring_active", False)

            print(f"{Colors.BLUE}⏰ Last Update: {timestamp_raw or 'Never'} {('(' + timestamp_human + ')') if timestamp_human else ''}{Colors.END}")
            print(f"{Colors.BLUE}🛰️  Monitoring Active: {'Yes' if monitoring_active else 'No'}{Colors.END}\n")

            total_agents = len(agents)
            completed = sum(1 for a in agents.values() if str(a.get('status')).lower() in {"completed", "verified"})
            failed = sum(1 for a in agents.values() if str(a.get('status')).lower() in {"failed", "error"})
            active = sum(1 for a in agents.values() if str(a.get('status')).lower() in {"active", "running", "deploying", "pending", "initializing"})

            average_progress = 0.0
            if agents:
                average_progress = sum(float(a.get("progress_percentage", 0) or 0) for a in agents.values()) / len(agents)

            print(f"{Colors.HEADER}📊 DEPLOYMENT SUMMARY{Colors.END}")
            print("─" * 60)
            print(f"Overall Progress: {format_progress_bar(average_progress)}")
            print(f"Total Agents: {total_agents} | Active: {active} | Completed: {completed} | Failed: {failed}\n")

            print(f"{Colors.GREEN}🚀 AGENT STATUS{Colors.END}")
            print("─" * 60)
            if not agents:
                print("No agent activity recorded yet. Waiting for updates...\n")
            else:
                for agent_id, agent_data in sorted(agents.items()):
                    status = str(agent_data.get("status", "unknown"))
                    indicator = colour_for_status(status)
                    role = agent_data.get("role", "Zen Agent")
                    task = agent_data.get("current_phase", "N/A")
                    progress = agent_data.get("progress_percentage", 0)
                    last_updated = agent_data.get("last_updated", "")

                    print(f"{indicator} {agent_id} ({role})")
                    print(f"  Status     : {status.upper()}")
                    print(f"  Task       : {task}")
                    print(f"  Progress   : {format_progress_bar(progress, 15)}")
                    if last_updated:
                        _, human_delta = _parse_timestamp(last_updated)
                        suffix = f" ({human_delta})" if human_delta else ""
                        print(f"  Updated    : {last_updated}{suffix}")
                    print()

            print(f"{Colors.CYAN}🎮 CONTROLS{Colors.END}")
            print("─" * 60)
            print("Ctrl+C - Exit monitor")
            print(f"Refresh interval: {REFRESH_INTERVAL} seconds")

            time.sleep(REFRESH_INTERVAL)
        except KeyboardInterrupt:
            print(f"\n{Colors.YELLOW}Monitoring stopped by user{Colors.END}")
            break
        except Exception as exc:  # pragma: no cover - display-only failure path
            print(f"{Colors.RED}Unexpected error: {exc}{Colors.END}")
            time.sleep(5)


def main() -> None:
    title = "D_T Agent Monitor"
    sys.stdout.write(f"\033]0;{title}\007")
    sys.stdout.flush()

    if not MONITORING_FILE.exists():
        MONITORING_FILE.parent.mkdir(parents=True, exist_ok=True)
        MONITORING_FILE.write_text(
            json.dumps({"monitored_agents": {}, "last_updated": "", "monitoring_active": False}, indent=2),
            encoding="utf-8",
        )

    display_dashboard()


if __name__ == "__main__":
    main()
