from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import requests

from .config import (
    SETTINGS_PATH,
    SUPPORT_CHAT_LOG_PATH,
    SUPPORT_FEEDBACK_LOG_PATH,
    SUPPORT_FEEDBACK_PATH,
    SUPPORT_SUMMARY_PATH,
    SUPPORT_TICKET_EVENTS_PATH,
    SUPPORT_TICKETS_PATH,
    WORKSPACE_ROOT,
    ensure_workspace_files,
)


SECRET_PATTERN = re.compile(
    r"(?i)\b(password|pass|pwd|token|api[_-]?key|secret)\b\s*[:=]\s*([^\s,;]+)"
)
DECLARED_PASSWORD_PATTERN = re.compile(r"(?i)\b(my password is|password is)\s+([^\s,;]+)")
LONG_TOKEN_PATTERN = re.compile(r"\b[A-Za-z0-9_\-]{28,}\b")


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sanitize_text(raw: str, max_chars: int = 4000) -> str:
    text = (raw or "").strip()[:max_chars]
    if not text:
        return ""
    text = SECRET_PATTERN.sub(lambda m: f"{m.group(1)}=[REDACTED]", text)
    text = DECLARED_PASSWORD_PATTERN.sub(lambda m: f"{m.group(1)} [REDACTED]", text)
    text = LONG_TOKEN_PATTERN.sub("[REDACTED_TOKEN]", text)
    return text


def parse_bool(value: Any, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"1", "true", "yes", "y", "on"}:
            return True
        if normalized in {"0", "false", "no", "n", "off"}:
            return False
    return default


def load_json(path: Path, fallback: Any) -> Any:
    if not path.exists():
        return fallback
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return fallback


def save_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def append_jsonl(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False) + "\n")


@dataclass
class SupportAiConfig:
    provider: str
    model: str
    api_key: str
    base_url: str


class SupportAiResponder:
    def __init__(self) -> None:
        provider = os.getenv("DT_SUPPORT_AI_PROVIDER", "openai").strip().lower()
        self.config = SupportAiConfig(
            provider=provider,
            model=os.getenv("DT_SUPPORT_OPENAI_MODEL", "gpt-4o-mini").strip(),
            api_key=os.getenv("DT_SUPPORT_OPENAI_API_KEY", "").strip(),
            base_url=os.getenv("DT_SUPPORT_OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/"),
        )

    def chat_reply(self, message: str, context: dict[str, Any] | None = None) -> str:
        prompt = sanitize_text(message, max_chars=2000)
        if not prompt:
            return "Please share a short question so I can help."

        if self._openai_ready():
            reply = self._openai_reply(
                system_prompt=(
                    "You are DT Guardian Support AI. "
                    "Provide concise, practical help for defensive security, app usage, subscriptions, and ticket triage. "
                    "Never ask for or store raw passwords. Ask users to redact sensitive data."
                ),
                user_prompt=self._build_chat_prompt(prompt, context or {}),
            )
            if reply:
                return reply

        return self._fallback_chat_reply(prompt)

    def ticket_triage_reply(self, ticket: dict[str, Any]) -> str:
        title = sanitize_text(ticket.get("title", ""), max_chars=300)
        details = sanitize_text(ticket.get("details", ""), max_chars=1500)
        if self._openai_ready():
            reply = self._openai_reply(
                system_prompt=(
                    "You are DT Guardian ticket triage AI. "
                    "Create one practical first-response message with immediate next steps. "
                    "Defensive-only security guidance."
                ),
                user_prompt=(
                    f"Ticket type: {ticket.get('type')}\n"
                    f"Title: {title}\n"
                    f"Details: {details}\n"
                    "Return a concise first response with 1-4 actionable steps."
                ),
            )
            if reply:
                return reply

        return self._fallback_ticket_reply(ticket)

    def _openai_ready(self) -> bool:
        return self.config.provider == "openai" and bool(self.config.api_key)

    def _openai_reply(self, system_prompt: str, user_prompt: str) -> str:
        try:
            url = f"{self.config.base_url}/chat/completions"
            headers = {
                "Authorization": f"Bearer {self.config.api_key}",
                "Content-Type": "application/json",
            }
            payload = {
                "model": self.config.model,
                "temperature": 0.2,
                "max_tokens": 350,
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            }
            response = requests.post(url, headers=headers, json=payload, timeout=30)
            response.raise_for_status()
            body = response.json()
            choices = body.get("choices") or []
            if not choices:
                return ""
            message = choices[0].get("message") or {}
            content = message.get("content", "")
            return sanitize_text(content, max_chars=2000)
        except Exception:
            return ""

    def _build_chat_prompt(self, prompt: str, context: dict[str, Any]) -> str:
        region = context.get("region", "unknown")
        pending = context.get("pending_tickets", 0)
        return (
            f"User region: {region}\n"
            f"Pending queued tickets: {pending}\n"
            f"User message:\n{prompt}"
        )

    def _fallback_chat_reply(self, prompt: str) -> str:
        lowered = prompt.lower()
        if "feature" in lowered or "add" in lowered:
            return (
                "Feature request noted. Please submit it through the request form so it enters the FIFO ticket queue. "
                "Include expected behavior and why it improves daily workflow."
            )
        if "bug" in lowered or "error" in lowered or "problem" in lowered or "issue" in lowered:
            return (
                "I can help triage this. Please include device model, Android version, app version, and exact steps to reproduce. "
                "Then submit through the problem report form so it is queued in order."
            )
        if "price" in lowered or "billing" in lowered or "trial" in lowered:
            return (
                "DT Guardian uses a 7-day trial and region-based subscription pricing. "
                "Use the app's 'Choose plan' flow to view your local weekly/monthly/yearly amounts."
            )
        return (
            "Support AI is available. Share the issue details and expected behavior. "
            "If this requires follow-up, submit a ticket so it is processed in FIFO order."
        )

    def _fallback_ticket_reply(self, ticket: dict[str, Any]) -> str:
        ticket_type = ticket.get("type", "general")
        if ticket_type == "feedback_rating":
            return (
                "Feedback recorded. Thank you for rating DT Guardian. "
                "We use this to prioritize roadmap and quality improvements."
            )
        if ticket_type == "feature_request":
            return (
                "Feature request queued. Next step: we will evaluate fit, complexity, and security impact, "
                "then respond with implementation status or backlog placement."
            )
        return (
            "Problem report queued. Next step: please keep the app installed, collect reproduction steps, "
            "and include screenshots/log details in follow-up if requested."
        )


class SupportStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()

    def create_ticket(
        self,
        ticket_type: str,
        title: str,
        details: str,
        email: str,
        source: str,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        with self._lock:
            tickets = self._load_tickets()
            created_at = now_iso()
            ticket_id = self._next_ticket_id(tickets)
            ticket = {
                "ticket_id": ticket_id,
                "type": ticket_type,
                "title": sanitize_text(title, max_chars=300),
                "details": sanitize_text(details, max_chars=2000),
                "email": sanitize_text(email, max_chars=120),
                "source": sanitize_text(source, max_chars=80),
                "status": "queued",
                "created_at": created_at,
                "updated_at": created_at,
                "triage_reply": "",
                "metadata": metadata or {},
            }
            tickets.append(ticket)
            self._save_tickets(tickets)

            append_jsonl(
                SUPPORT_TICKET_EVENTS_PATH,
                {
                    "event": "ticket_created",
                    "ticket_id": ticket_id,
                    "timestamp": created_at,
                    "type": ticket_type,
                    "status": "queued",
                },
            )
            self._write_summary(tickets)
            return ticket

    def reserve_next_queued(self) -> dict[str, Any] | None:
        with self._lock:
            tickets = self._load_tickets()
            queued = sorted(
                [item for item in tickets if item.get("status") == "queued"],
                key=lambda item: item.get("created_at", ""),
            )
            if not queued:
                return None
            target_id = queued[0]["ticket_id"]
            now = now_iso()
            for item in tickets:
                if item.get("ticket_id") == target_id:
                    item["status"] = "in_progress"
                    item["updated_at"] = now
                    self._save_tickets(tickets)
                    append_jsonl(
                        SUPPORT_TICKET_EVENTS_PATH,
                        {
                            "event": "ticket_in_progress",
                            "ticket_id": target_id,
                            "timestamp": now,
                        },
                    )
                    self._write_summary(tickets)
                    return dict(item)
            return None

    def complete_ticket_triage(self, ticket_id: str, triage_reply: str) -> dict[str, Any] | None:
        with self._lock:
            tickets = self._load_tickets()
            now = now_iso()
            for item in tickets:
                if item.get("ticket_id") != ticket_id:
                    continue
                item["status"] = "triaged"
                item["updated_at"] = now
                item["triage_reply"] = sanitize_text(triage_reply, max_chars=2000)
                self._save_tickets(tickets)
                append_jsonl(
                    SUPPORT_TICKET_EVENTS_PATH,
                    {
                        "event": "ticket_triaged",
                        "ticket_id": ticket_id,
                        "timestamp": now,
                    },
                )
                self._write_summary(tickets)
                return dict(item)
            return None

    def list_tickets(self, status: str | None = None, limit: int = 50) -> list[dict[str, Any]]:
        with self._lock:
            tickets = self._load_tickets()
            if status:
                tickets = [item for item in tickets if item.get("status") == status]
            tickets = sorted(tickets, key=lambda item: item.get("created_at", ""))
            return tickets[: max(1, min(limit, 250))]

    def pending_count(self) -> int:
        with self._lock:
            tickets = self._load_tickets()
            return sum(1 for item in tickets if item.get("status") == "queued")

    def append_chat(self, session_id: str, role: str, message: str, metadata: dict[str, Any] | None = None) -> None:
        payload = {
            "timestamp": now_iso(),
            "session_id": sanitize_text(session_id, max_chars=80),
            "role": role,
            "message": sanitize_text(message, max_chars=2000),
            "metadata": metadata or {},
        }
        append_jsonl(SUPPORT_CHAT_LOG_PATH, payload)

    def chat_history(self, session_id: str, limit: int = 20) -> list[dict[str, Any]]:
        session = sanitize_text(session_id, max_chars=80)
        if not SUPPORT_CHAT_LOG_PATH.exists():
            return []
        rows: list[dict[str, Any]] = []
        lines = SUPPORT_CHAT_LOG_PATH.read_text(encoding="utf-8").splitlines()
        for line in reversed(lines):
            if not line.strip():
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if payload.get("session_id") != session:
                continue
            rows.append(payload)
            if len(rows) >= max(1, min(limit, 100)):
                break
        rows.reverse()
        return rows

    def append_feedback(
        self,
        rating: int,
        recommend_to_friends: bool,
        source: str,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        with self._lock:
            feedback_rows = self._load_feedback()
            created_at = now_iso()
            feedback_id = self._next_feedback_id(feedback_rows)
            row = {
                "feedback_id": feedback_id,
                "rating": max(1, min(int(rating), 5)),
                "recommend_to_friends": bool(recommend_to_friends),
                "source": sanitize_text(source, max_chars=80),
                "created_at": created_at,
                "metadata": metadata or {},
            }
            feedback_rows.append(row)
            self._save_feedback(feedback_rows)
            append_jsonl(
                SUPPORT_FEEDBACK_LOG_PATH,
                {
                    "event": "feedback_received",
                    "timestamp": created_at,
                    "feedback_id": feedback_id,
                    "rating": row["rating"],
                    "recommend_to_friends": row["recommend_to_friends"],
                    "source": row["source"],
                },
            )
            append_jsonl(
                SUPPORT_TICKET_EVENTS_PATH,
                {
                    "event": "feedback_received",
                    "timestamp": created_at,
                    "feedback_id": feedback_id,
                    "rating": row["rating"],
                    "recommend_to_friends": row["recommend_to_friends"],
                },
            )
            self._write_summary(self._load_tickets())
            return row

    def list_feedback(self, limit: int = 100) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._load_feedback()
            rows = sorted(rows, key=lambda item: item.get("created_at", ""))
            return rows[: max(1, min(limit, 500))]

    def _load_tickets(self) -> list[dict[str, Any]]:
        data = load_json(SUPPORT_TICKETS_PATH, [])
        if isinstance(data, list):
            return data
        return []

    def _save_tickets(self, tickets: list[dict[str, Any]]) -> None:
        save_json(SUPPORT_TICKETS_PATH, tickets)

    def _load_feedback(self) -> list[dict[str, Any]]:
        data = load_json(SUPPORT_FEEDBACK_PATH, [])
        if isinstance(data, list):
            return data
        return []

    def _save_feedback(self, rows: list[dict[str, Any]]) -> None:
        save_json(SUPPORT_FEEDBACK_PATH, rows)

    def _next_ticket_id(self, tickets: list[dict[str, Any]]) -> str:
        max_sequence = 0
        for item in tickets:
            ticket_id = str(item.get("ticket_id", ""))
            if not ticket_id.startswith("TKT-"):
                continue
            tail = ticket_id.split("-")[-1]
            if tail.isdigit():
                max_sequence = max(max_sequence, int(tail))
        return f"TKT-{max_sequence + 1:06d}"

    def _next_feedback_id(self, rows: list[dict[str, Any]]) -> str:
        max_sequence = 0
        for item in rows:
            feedback_id = str(item.get("feedback_id", ""))
            if not feedback_id.startswith("FBK-"):
                continue
            tail = feedback_id.split("-")[-1]
            if tail.isdigit():
                max_sequence = max(max_sequence, int(tail))
        return f"FBK-{max_sequence + 1:06d}"

    def _write_summary(self, tickets: list[dict[str, Any]]) -> None:
        feedback_rows = self._load_feedback()
        summary = {
            "updated_at": now_iso(),
            "counts": {
                "queued": sum(1 for item in tickets if item.get("status") == "queued"),
                "in_progress": sum(1 for item in tickets if item.get("status") == "in_progress"),
                "triaged": sum(1 for item in tickets if item.get("status") == "triaged"),
                "feedback": len(feedback_rows),
            },
            "oldest_queued_ticket_id": next(
                (item.get("ticket_id") for item in sorted(tickets, key=lambda x: x.get("created_at", "")) if item.get("status") == "queued"),
                "",
            ),
        }
        save_json(SUPPORT_SUMMARY_PATH, summary)


class SupportHub:
    def __init__(self) -> None:
        self.store = SupportStore()
        self.ai = SupportAiResponder()
        self.stop_event = threading.Event()
        self._wake = threading.Event()
        self.worker = threading.Thread(target=self._worker_loop, name="support-ticket-worker", daemon=True)

    def start(self) -> None:
        self.worker.start()

    def stop(self) -> None:
        self.stop_event.set()
        self._wake.set()
        self.worker.join(timeout=2.0)

    def wake_worker(self) -> None:
        self._wake.set()

    def create_ticket(self, payload: dict[str, Any], ticket_type: str) -> dict[str, Any]:
        ticket = self.store.create_ticket(
            ticket_type=ticket_type,
            title=payload.get("title", ""),
            details=payload.get("details", ""),
            email=payload.get("email", ""),
            source=payload.get("source", "support_web"),
            metadata={"client": sanitize_text(payload.get("client", ""), max_chars=120)},
        )
        self.wake_worker()
        return ticket

    def handle_chat(self, payload: dict[str, Any]) -> dict[str, Any]:
        session_id = sanitize_text(payload.get("session_id", ""), max_chars=80) or f"sess-{uuid.uuid4().hex[:12]}"
        message = sanitize_text(payload.get("message", ""), max_chars=2000)
        if not message:
            return {"session_id": session_id, "reply": "Please enter a message.", "ticket_id": ""}

        pending = self.store.pending_count()
        region = sanitize_text(payload.get("region", ""), max_chars=16) or "unknown"
        self.store.append_chat(session_id, "user", message, metadata={"region": region})

        reply = self.ai.chat_reply(message, context={"pending_tickets": pending, "region": region})
        self.store.append_chat(session_id, "assistant", reply)

        auto_ticket_id = ""
        lowered = message.lower()
        should_create_ticket = any(token in lowered for token in ("feature request", "bug", "problem report", "open ticket"))
        if should_create_ticket:
            ticket_type = "feature_request" if "feature" in lowered else "problem_report"
            ticket = self.store.create_ticket(
                ticket_type=ticket_type,
                title=(message[:100] or f"chat_{ticket_type}"),
                details=message,
                email=sanitize_text(payload.get("email", ""), max_chars=120),
                source="support_chat",
                metadata={"session_id": session_id},
            )
            auto_ticket_id = ticket["ticket_id"]
            self.wake_worker()

        return {"session_id": session_id, "reply": reply, "ticket_id": auto_ticket_id}

    def health(self) -> dict[str, Any]:
        summary = load_json(SUPPORT_SUMMARY_PATH, {})
        return {
            "status": "ok",
            "timestamp": now_iso(),
            "pending_tickets": self.store.pending_count(),
            "summary": summary,
            "ai_provider": self.ai.config.provider,
            "ai_model": self.ai.config.model,
            "ai_enabled": bool(self.ai.config.api_key),
        }

    def handle_feedback(self, payload: dict[str, Any]) -> dict[str, Any]:
        raw_rating = payload.get("rating")
        try:
            rating = int(raw_rating)
        except (TypeError, ValueError):
            rating = 0
        if rating < 1 or rating > 5:
            return {"error": "rating must be an integer from 1 to 5"}

        recommend_to_friends = parse_bool(payload.get("recommend_to_friends"), default=False)
        source = sanitize_text(payload.get("source", "android_app"), max_chars=80) or "android_app"
        metadata = {
            "tier": sanitize_text(payload.get("tier", ""), max_chars=30),
            "plan": sanitize_text(payload.get("selected_plan", ""), max_chars=20),
            "app_version": sanitize_text(payload.get("app_version", ""), max_chars=40),
            "build_code": sanitize_text(str(payload.get("build_code", "")), max_chars=40),
            "platform": sanitize_text(payload.get("platform", ""), max_chars=20),
            "device_model": sanitize_text(payload.get("device_model", ""), max_chars=80),
            "region": sanitize_text(payload.get("region", ""), max_chars=40),
        }
        feedback = self.store.append_feedback(
            rating=rating,
            recommend_to_friends=recommend_to_friends,
            source=source,
            metadata=metadata,
        )

        settings = load_json(SETTINGS_PATH, {})
        default_create_ticket = parse_bool(
            settings.get("support", {}).get("feedback", {}).get("create_ticket_for_feedback"),
            default=True,
        )
        create_ticket = parse_bool(payload.get("create_ticket"), default=default_create_ticket)
        ticket_id = ""
        if create_ticket:
            title = f"App feedback {rating}/5 (recommend={'yes' if recommend_to_friends else 'no'})"
            details = sanitize_text(payload.get("details", ""), max_chars=1200)
            if not details:
                details = (
                    "Feedback submitted from Android app rating flow. "
                    f"Rating: {rating}/5. Recommend: {'yes' if recommend_to_friends else 'no'}."
                )
            ticket = self.store.create_ticket(
                ticket_type="feedback_rating",
                title=title,
                details=details,
                email=sanitize_text(payload.get("email", ""), max_chars=120),
                source=source,
                metadata={
                    **metadata,
                    "feedback_id": feedback["feedback_id"],
                },
            )
            ticket_id = ticket["ticket_id"]
            self.wake_worker()

        return {
            "feedback": feedback,
            "ticket_id": ticket_id,
            "logged": True,
        }

    def _worker_loop(self) -> None:
        # Backlog worker: process queued tickets in strict FIFO order.
        while not self.stop_event.is_set():
            ticket = self.store.reserve_next_queued()
            if ticket is None:
                self._wake.wait(timeout=2.0)
                self._wake.clear()
                continue

            triage = self.ai.ticket_triage_reply(ticket)
            self.store.complete_ticket_triage(ticket["ticket_id"], triage)


class SupportHttpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def hub(self) -> SupportHub:
        return self.server.support_hub  # type: ignore[attr-defined]

    @property
    def docs_root(self) -> Path:
        return self.server.docs_root  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: Any) -> None:
        append_jsonl(
            SUPPORT_TICKET_EVENTS_PATH,
            {
                "event": "http_access",
                "timestamp": now_iso(),
                "path": sanitize_text(self.path, max_chars=300),
                "message": sanitize_text(format % args, max_chars=300),
            },
        )

    def do_OPTIONS(self) -> None:  # noqa: N802
        self._send_json(HTTPStatus.NO_CONTENT, {})

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/api/support/health":
            self._send_json(HTTPStatus.OK, self.hub.health())
            return
        if parsed.path == "/api/support/tickets":
            params = parse_qs(parsed.query)
            status = sanitize_text(params.get("status", [""])[0], max_chars=40) or None
            try:
                limit = int(params.get("limit", ["50"])[0])
            except ValueError:
                limit = 50
            tickets = self.hub.store.list_tickets(status=status, limit=limit)
            self._send_json(HTTPStatus.OK, {"tickets": tickets})
            return
        if parsed.path == "/api/support/chat/history":
            params = parse_qs(parsed.query)
            session_id = sanitize_text(params.get("session_id", [""])[0], max_chars=80)
            if not session_id:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "session_id is required"})
                return
            history = self.hub.store.chat_history(session_id=session_id, limit=40)
            self._send_json(HTTPStatus.OK, {"session_id": session_id, "history": history})
            return
        if parsed.path == "/api/support/feedback":
            params = parse_qs(parsed.query)
            try:
                limit = int(params.get("limit", ["100"])[0])
            except ValueError:
                limit = 100
            rows = self.hub.store.list_feedback(limit=limit)
            self._send_json(HTTPStatus.OK, {"feedback": rows})
            return

        self._serve_static(parsed.path)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        payload = self._read_json_body()
        if payload is None:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid json payload"})
            return

        if parsed.path == "/api/support/chat":
            result = self.hub.handle_chat(payload)
            self._send_json(HTTPStatus.OK, result)
            return
        if parsed.path == "/api/support/feedback":
            result = self.hub.handle_feedback(payload)
            if result.get("error"):
                self._send_json(HTTPStatus.BAD_REQUEST, result)
                return
            self._send_json(HTTPStatus.CREATED, result)
            return

        if parsed.path in {"/api/support/tickets", "/api/support/feature-request", "/api/support/problem-report"}:
            ticket_type = payload.get("type", "")
            if parsed.path.endswith("/feature-request"):
                ticket_type = "feature_request"
            elif parsed.path.endswith("/problem-report"):
                ticket_type = "problem_report"
            ticket_type = sanitize_text(ticket_type or "general", max_chars=40) or "general"

            title = sanitize_text(payload.get("title", ""), max_chars=300)
            details = sanitize_text(payload.get("details", ""), max_chars=2000)
            if not title or not details:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "title and details are required"})
                return

            ticket = self.hub.create_ticket(payload, ticket_type=ticket_type)
            self._send_json(HTTPStatus.CREATED, {"ticket": ticket})
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": "endpoint not found"})

    def _serve_static(self, raw_path: str) -> None:
        if raw_path in {"", "/"}:
            relative = Path("index.html")
        else:
            relative = Path(raw_path.lstrip("/"))

        candidate = (self.docs_root / relative).resolve()
        docs_root_resolved = self.docs_root.resolve()
        if docs_root_resolved not in candidate.parents and candidate != docs_root_resolved:
            self._send_text(HTTPStatus.FORBIDDEN, "forbidden")
            return
        if not candidate.exists() or not candidate.is_file():
            self._send_text(HTTPStatus.NOT_FOUND, "not found")
            return

        content_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
        body = candidate.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self._send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self) -> dict[str, Any] | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if length <= 0:
            return {}
        body = self.rfile.read(length)
        try:
            payload = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            return None
        if not isinstance(payload, dict):
            return None
        return payload

    def _send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._send_cors_headers()
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _send_text(self, status: HTTPStatus, text: str) -> None:
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def _send_cors_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")


def run_support_server(host: str, port: int, docs_root: Path) -> int:
    ensure_workspace_files()
    docs_root.mkdir(parents=True, exist_ok=True)
    hub = SupportHub()
    hub.start()
    httpd = ThreadingHTTPServer((host, port), SupportHttpHandler)
    httpd.support_hub = hub  # type: ignore[attr-defined]
    httpd.docs_root = docs_root  # type: ignore[attr-defined]
    append_jsonl(
        SUPPORT_TICKET_EVENTS_PATH,
        {
            "event": "support_server_started",
            "timestamp": now_iso(),
            "host": host,
            "port": port,
            "docs_root": str(docs_root),
        },
    )
    try:
        httpd.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        hub.stop()
        httpd.server_close()
        append_jsonl(
            SUPPORT_TICKET_EVENTS_PATH,
            {"event": "support_server_stopped", "timestamp": now_iso()},
        )
    return 0


def build_support_server_parser(subparsers: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    support = subparsers.add_parser(
        "support-server",
        help="Run local support hub (AI chat hotline + feature/problem ticket queue)"
    )
    support.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    support.add_argument("--port", type=int, default=8787, help="Bind port (default: 8787)")
    support.add_argument(
        "--docs-root",
        default=str(WORKSPACE_ROOT / "docs"),
        help="Path to support docs root served by the support hub",
    )
    support.set_defaults(
        func=lambda args: run_support_server(
            host=args.host,
            port=int(args.port),
            docs_root=Path(args.docs_root).expanduser().resolve(),
        )
    )
