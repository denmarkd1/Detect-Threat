from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .config import SUPPORT_DEVICE_UMBRELLA_LOG_PATH, SUPPORT_DEVICE_UMBRELLA_STATE_PATH


SCHEMA_VERSION = 1
DEFAULT_TTL_SECONDS = 15 * 60
MIN_TTL_SECONDS = 120
MAX_TTL_SECONDS = 60 * 60
MAX_MEMBERS_PER_SESSION = 5
MAX_STORED_SESSIONS = 400
MAX_ENVELOPE_B64_CHARS = 80_000
MAX_ACTIVE_SESSIONS_PER_OWNER_PROOF = 3
MAX_ACTIVE_MEMBER_LINKS_PER_OWNER_PROOF = 8
MAX_PENDING_JOIN_REQUESTS_PER_SESSION = 12
JOIN_REQUEST_TTL_MS = 15 * 60 * 1000


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def now_epoch_ms() -> int:
    return int(time.time() * 1000)


def sanitize_text(raw: Any, *, max_chars: int = 120, lowercase: bool = False) -> str:
    value = str(raw or "").replace("\n", " ").replace("\r", " ").strip()[:max_chars]
    if lowercase:
        return value.lower()
    return value


def append_jsonl(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False) + "\n")


def _load_json(path: Path, fallback: Any) -> Any:
    if not path.exists():
        return fallback
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return fallback


def _save_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _random_token(prefix: str, size: int = 12) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:size]}"


def _normalize_link_code(raw: str) -> str:
    value = sanitize_text(raw, max_chars=128).upper().replace(" ", "")
    value = value.replace("-", "")
    return "".join(ch for ch in value if ch.isalnum())[:32]


def _validate_code_hash(raw: str) -> str:
    value = sanitize_text(raw, max_chars=96, lowercase=True)
    if len(value) != 64:
        raise ValueError("code_hash must be a 64-character sha256 hex digest")
    if any(ch not in "0123456789abcdef" for ch in value):
        raise ValueError("code_hash must be lowercase hex")
    return value


def _validate_owner_proof(raw: str) -> str:
    value = sanitize_text(raw, max_chars=96, lowercase=True)
    if len(value) != 64:
        raise ValueError("owner_proof must be a 64-character sha256 hex digest")
    if any(ch not in "0123456789abcdef" for ch in value):
        raise ValueError("owner_proof must be lowercase hex")
    return value


def _validate_device_fingerprint(raw: str) -> str:
    value = sanitize_text(raw, max_chars=96, lowercase=True)
    if len(value) != 64:
        raise ValueError("device_fingerprint must be a 64-character sha256 hex digest")
    if any(ch not in "0123456789abcdef" for ch in value):
        raise ValueError("device_fingerprint must be lowercase hex")
    return value


def _ttl_seconds(raw: Any) -> int:
    try:
        parsed = int(raw)
    except (TypeError, ValueError):
        parsed = DEFAULT_TTL_SECONDS
    return max(MIN_TTL_SECONDS, min(MAX_TTL_SECONDS, parsed))


class DeviceUmbrellaStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()

    def create_session(
        self,
        *,
        owner: str,
        owner_proof: str,
        device_fingerprint: str,
        device_alias: str,
        device_model: str,
        ttl_seconds: int = DEFAULT_TTL_SECONDS,
    ) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            now_ms = now_epoch_ms()
            ttl = _ttl_seconds(ttl_seconds)
            session_id = _random_token("umbsess")
            umbrella_id = _random_token("umbrella", size=10)
            member_id = _random_token("member", size=10)
            salt = base64.urlsafe_b64encode(os.urandom(18)).decode("ascii").rstrip("=")
            created_at = now_iso()
            expires_at_ms = now_ms + ttl * 1000

            owner_id = sanitize_text(owner, max_chars=40, lowercase=True) or "parent"
            normalized_owner_proof = _validate_owner_proof(owner_proof)
            normalized_device_fingerprint = _validate_device_fingerprint(device_fingerprint)
            owner_active_sessions = self._count_active_sessions_for_owner_proof(
                state["sessions"],
                normalized_owner_proof,
            )
            if owner_active_sessions >= MAX_ACTIVE_SESSIONS_PER_OWNER_PROOF:
                raise ValueError("Too many active umbrella sessions for this owner")
            session = {
                "session_id": session_id,
                "umbrella_id": umbrella_id,
                "owner": owner_id,
                "owner_proof": normalized_owner_proof,
                "created_at": created_at,
                "created_at_epoch_ms": now_ms,
                "expires_at_epoch_ms": expires_at_ms,
                "status": "pending_code",
                "salt": salt,
                "code_hash": "",
                "members": [
                    {
                        "member_id": member_id,
                        "role": "host",
                        "device_fingerprint": normalized_device_fingerprint,
                        "device_alias": sanitize_text(device_alias, max_chars=80) or "Android device",
                        "device_model": sanitize_text(device_model, max_chars=120),
                        "joined_at": created_at,
                        "last_seen_at": created_at,
                        "envelope": {},
                    }
                ],
                "join_requests": [],
                "updated_at": created_at,
            }
            state["sessions"].append(session)
            self._save_state(state)

            append_jsonl(
                SUPPORT_DEVICE_UMBRELLA_LOG_PATH,
                {
                    "event": "umbrella_session_created",
                    "timestamp": created_at,
                    "session_id": session_id,
                    "umbrella_id": umbrella_id,
                    "owner": owner_id,
                    "owner_proof_prefix": normalized_owner_proof[:12],
                    "expires_at_epoch_ms": expires_at_ms,
                },
            )

            return {
                "session_id": session_id,
                "umbrella_id": umbrella_id,
                "member_id": member_id,
                "owner": owner_id,
                "salt": salt,
                "created_at": created_at,
                "expires_at_epoch_ms": expires_at_ms,
                "ttl_seconds": ttl,
            }

    def register_code(
        self,
        *,
        session_id: str,
        member_id: str,
        code_hash: str,
        owner_proof: str,
    ) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            normalized_session_id = sanitize_text(session_id, max_chars=60)
            normalized_member_id = sanitize_text(member_id, max_chars=60)
            normalized_hash = _validate_code_hash(code_hash)
            normalized_owner_proof = _validate_owner_proof(owner_proof)
            now = now_iso()
            now_ms = now_epoch_ms()

            session = self._find_session(state["sessions"], normalized_session_id)
            self._require_not_expired(session, now_ms)
            self._find_member(session, normalized_member_id)
            expected_owner_proof = sanitize_text(session.get("owner_proof", ""), max_chars=96, lowercase=True)
            if not expected_owner_proof or not hmac.compare_digest(expected_owner_proof, normalized_owner_proof):
                raise ValueError("owner proof mismatch for this pairing session")

            session["code_hash"] = normalized_hash
            session["status"] = "active"
            session["updated_at"] = now
            self._save_state(state)

            append_jsonl(
                SUPPORT_DEVICE_UMBRELLA_LOG_PATH,
                {
                    "event": "umbrella_code_registered",
                    "timestamp": now,
                    "session_id": normalized_session_id,
                    "member_id": normalized_member_id,
                },
            )
            return {
                "session_id": normalized_session_id,
                "member_id": normalized_member_id,
                "registered": True,
                "status": session.get("status", "active"),
            }

    def join_with_code(
        self,
        *,
        link_code: str,
        owner_proof: str,
        device_fingerprint: str,
        device_alias: str,
        device_model: str,
    ) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            normalized_code = _normalize_link_code(link_code)
            normalized_owner_proof = _validate_owner_proof(owner_proof)
            normalized_device_fingerprint = _validate_device_fingerprint(device_fingerprint)
            if len(normalized_code) < 8:
                raise ValueError("link_code is invalid")

            now = now_iso()
            now_ms = now_epoch_ms()
            matched_session: dict[str, Any] | None = None

            for session in state["sessions"]:
                self._require_not_expired(session, now_ms)
                code_hash = sanitize_text(session.get("code_hash", ""), max_chars=96, lowercase=True)
                if len(code_hash) != 64:
                    continue
                candidate = hashlib.sha256(
                    f"{session.get('session_id', '')}|{normalized_code}|{session.get('salt', '')}".encode("utf-8")
                ).hexdigest()
                if hmac.compare_digest(code_hash, candidate):
                    matched_session = session
                    break

            if matched_session is None:
                raise ValueError("No active pairing session matches this link code")

            expected_owner_proof = sanitize_text(
                matched_session.get("owner_proof", ""),
                max_chars=96,
                lowercase=True,
            )
            if not expected_owner_proof or not hmac.compare_digest(expected_owner_proof, normalized_owner_proof):
                raise ValueError("This umbrella is locked to a different owner identity")

            active_member_links = self._count_active_member_links_for_owner_proof(
                state["sessions"],
                normalized_owner_proof,
            )
            if active_member_links >= MAX_ACTIVE_MEMBER_LINKS_PER_OWNER_PROOF:
                raise ValueError("Too many linked devices for this owner")

            self._compact_join_requests_for_session(matched_session, now_ms)
            members = matched_session.setdefault("members", [])
            if not isinstance(members, list):
                members = []
                matched_session["members"] = members
            if len(members) >= MAX_MEMBERS_PER_SESSION:
                raise ValueError("Pairing session is full")

            existing_member = self._find_member_by_device_fingerprint(
                matched_session,
                normalized_device_fingerprint,
            )
            if existing_member is not None:
                existing_member["last_seen_at"] = now
                matched_session["updated_at"] = now
                self._save_state(state)
                return {
                    "session_id": matched_session.get("session_id", ""),
                    "umbrella_id": matched_session.get("umbrella_id", ""),
                    "owner": matched_session.get("owner", "parent"),
                    "salt": matched_session.get("salt", ""),
                    "expires_at_epoch_ms": int(matched_session.get("expires_at_epoch_ms", 0) or 0),
                    "request_id": "",
                    "request_status": "approved",
                    "member_id": sanitize_text(existing_member.get("member_id", ""), max_chars=60),
                    "member_role": sanitize_text(existing_member.get("role", "member"), max_chars=20, lowercase=True)
                    or "member",
                }

            pending_request = self._find_pending_join_request_for_fingerprint(
                matched_session,
                normalized_device_fingerprint,
            )
            if pending_request is not None:
                pending_request["last_requested_at"] = now
                matched_session["status"] = "awaiting_host_approval"
                matched_session["updated_at"] = now
                self._save_state(state)
                return {
                    "session_id": matched_session.get("session_id", ""),
                    "umbrella_id": matched_session.get("umbrella_id", ""),
                    "owner": matched_session.get("owner", "parent"),
                    "salt": matched_session.get("salt", ""),
                    "expires_at_epoch_ms": int(matched_session.get("expires_at_epoch_ms", 0) or 0),
                    "request_id": sanitize_text(pending_request.get("request_id", ""), max_chars=60),
                    "request_status": "pending",
                }

            join_requests = matched_session.setdefault("join_requests", [])
            if not isinstance(join_requests, list):
                join_requests = []
                matched_session["join_requests"] = join_requests
            pending_count = len(
                [
                    req
                    for req in join_requests
                    if isinstance(req, dict)
                    and sanitize_text(req.get("status", ""), max_chars=20, lowercase=True) == "pending"
                ]
            )
            if pending_count >= MAX_PENDING_JOIN_REQUESTS_PER_SESSION:
                raise ValueError("Too many pending join requests for this umbrella")

            request_id = _random_token("joinreq", size=10)
            request = {
                "request_id": request_id,
                "status": "pending",
                "device_fingerprint": normalized_device_fingerprint,
                "device_alias": sanitize_text(device_alias, max_chars=80) or "Android device",
                "device_model": sanitize_text(device_model, max_chars=120),
                "requested_at": now,
                "requested_at_epoch_ms": now_ms,
                "last_requested_at": now,
                "decided_at": "",
                "decided_by_member_id": "",
                "approved_member_id": "",
                "decision_reason": "",
            }
            join_requests.append(request)
            matched_session["status"] = "awaiting_host_approval"
            matched_session["updated_at"] = now
            self._save_state(state)

            append_jsonl(
                SUPPORT_DEVICE_UMBRELLA_LOG_PATH,
                {
                    "event": "umbrella_join_requested",
                    "timestamp": now,
                    "session_id": matched_session.get("session_id", ""),
                    "request_id": request_id,
                    "device_alias": sanitize_text(request.get("device_alias", ""), max_chars=80),
                },
            )

            return {
                "session_id": matched_session.get("session_id", ""),
                "umbrella_id": matched_session.get("umbrella_id", ""),
                "owner": matched_session.get("owner", "parent"),
                "salt": matched_session.get("salt", ""),
                "expires_at_epoch_ms": int(matched_session.get("expires_at_epoch_ms", 0) or 0),
                "request_id": request_id,
                "request_status": "pending",
            }

    def list_join_requests(self, *, session_id: str, member_id: str) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            normalized_session_id = sanitize_text(session_id, max_chars=60)
            normalized_member_id = sanitize_text(member_id, max_chars=60)
            now = now_iso()
            now_ms = now_epoch_ms()

            session = self._find_session(state["sessions"], normalized_session_id)
            self._require_not_expired(session, now_ms)
            requester = self._find_member(session, normalized_member_id)
            requester_role = sanitize_text(requester.get("role", ""), max_chars=20, lowercase=True)
            if requester_role != "host":
                raise ValueError("Only host device can review pending join requests")

            self._compact_join_requests_for_session(session, now_ms)
            pending_requests = []
            for item in session.get("join_requests", []):
                if not isinstance(item, dict):
                    continue
                if sanitize_text(item.get("status", ""), max_chars=20, lowercase=True) != "pending":
                    continue
                pending_requests.append(
                    {
                        "request_id": sanitize_text(item.get("request_id", ""), max_chars=60),
                        "device_alias": sanitize_text(item.get("device_alias", ""), max_chars=80),
                        "device_model": sanitize_text(item.get("device_model", ""), max_chars=120),
                        "requested_at": sanitize_text(item.get("requested_at", ""), max_chars=64),
                    }
                )

            requester["last_seen_at"] = now
            session["updated_at"] = now
            self._save_state(state)
            return {
                "session_id": normalized_session_id,
                "umbrella_id": sanitize_text(session.get("umbrella_id", ""), max_chars=60),
                "pending_count": len(pending_requests),
                "requests": pending_requests,
            }

    def decide_join_request(
        self,
        *,
        session_id: str,
        member_id: str,
        request_id: str,
        decision: str,
    ) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            normalized_session_id = sanitize_text(session_id, max_chars=60)
            normalized_member_id = sanitize_text(member_id, max_chars=60)
            normalized_request_id = sanitize_text(request_id, max_chars=60)
            normalized_decision = sanitize_text(decision, max_chars=20, lowercase=True)
            if normalized_decision not in {"approve", "reject"}:
                raise ValueError("decision must be approve or reject")
            if not normalized_request_id:
                raise ValueError("request_id is required")

            now = now_iso()
            now_ms = now_epoch_ms()

            session = self._find_session(state["sessions"], normalized_session_id)
            self._require_not_expired(session, now_ms)
            requester = self._find_member(session, normalized_member_id)
            requester_role = sanitize_text(requester.get("role", ""), max_chars=20, lowercase=True)
            if requester_role != "host":
                raise ValueError("Only host device can approve join requests")

            self._compact_join_requests_for_session(session, now_ms)
            join_requests = session.get("join_requests", [])
            if not isinstance(join_requests, list):
                raise ValueError("join requests state is invalid")
            request: dict[str, Any] | None = None
            for item in join_requests:
                if not isinstance(item, dict):
                    continue
                if sanitize_text(item.get("request_id", ""), max_chars=60) == normalized_request_id:
                    request = item
                    break
            if request is None:
                raise ValueError("request_id not found")

            status = sanitize_text(request.get("status", ""), max_chars=20, lowercase=True)
            if status != "pending":
                return {
                    "session_id": normalized_session_id,
                    "request_id": normalized_request_id,
                    "request_status": status or "unknown",
                    "member_id": sanitize_text(request.get("approved_member_id", ""), max_chars=60),
                }

            approved_member_id = ""
            if normalized_decision == "approve":
                members = session.setdefault("members", [])
                if not isinstance(members, list):
                    members = []
                    session["members"] = members
                if len(members) >= MAX_MEMBERS_PER_SESSION:
                    raise ValueError("Pairing session is full")
                fingerprint = _validate_device_fingerprint(request.get("device_fingerprint", ""))
                existing_member = self._find_member_by_device_fingerprint(session, fingerprint)
                if existing_member is not None:
                    approved_member_id = sanitize_text(existing_member.get("member_id", ""), max_chars=60)
                    existing_member["last_seen_at"] = now
                else:
                    session_owner_proof = sanitize_text(session.get("owner_proof", ""), max_chars=96, lowercase=True)
                    if session_owner_proof:
                        active_links = self._count_active_member_links_for_owner_proof(
                            state["sessions"],
                            session_owner_proof,
                        )
                        if active_links >= MAX_ACTIVE_MEMBER_LINKS_PER_OWNER_PROOF:
                            raise ValueError("Too many linked devices for this owner")
                    approved_member_id = _random_token("member", size=10)
                    members.append(
                        {
                            "member_id": approved_member_id,
                            "role": "member",
                            "device_fingerprint": fingerprint,
                            "device_alias": sanitize_text(request.get("device_alias", ""), max_chars=80)
                            or "Android device",
                            "device_model": sanitize_text(request.get("device_model", ""), max_chars=120),
                            "joined_at": now,
                            "last_seen_at": now,
                            "envelope": {},
                        }
                    )
                request["status"] = "approved"
                request["approved_member_id"] = approved_member_id
            else:
                request["status"] = "rejected"
                request["decision_reason"] = "rejected_by_host"

            request["decided_at"] = now
            request["decided_by_member_id"] = normalized_member_id
            session["status"] = "active"
            session["updated_at"] = now
            self._save_state(state)

            append_jsonl(
                SUPPORT_DEVICE_UMBRELLA_LOG_PATH,
                {
                    "event": "umbrella_join_decided",
                    "timestamp": now,
                    "session_id": normalized_session_id,
                    "request_id": normalized_request_id,
                    "decision": normalized_decision,
                    "approved_member_id": approved_member_id,
                },
            )

            return {
                "session_id": normalized_session_id,
                "umbrella_id": sanitize_text(session.get("umbrella_id", ""), max_chars=60),
                "request_id": normalized_request_id,
                "request_status": sanitize_text(request.get("status", ""), max_chars=20, lowercase=True),
                "member_id": approved_member_id,
            }

    def push_envelope(
        self,
        *,
        session_id: str,
        member_id: str,
        payload_version: int,
        iv_b64: str,
        ciphertext_b64: str,
    ) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            normalized_session_id = sanitize_text(session_id, max_chars=60)
            normalized_member_id = sanitize_text(member_id, max_chars=60)
            iv = sanitize_text(iv_b64, max_chars=MAX_ENVELOPE_B64_CHARS)
            ciphertext = sanitize_text(ciphertext_b64, max_chars=MAX_ENVELOPE_B64_CHARS)
            if not iv or not ciphertext:
                raise ValueError("iv_b64 and ciphertext_b64 are required")
            if len(iv) < 12 or len(ciphertext) < 24:
                raise ValueError("Envelope payload is too short")

            now = now_iso()
            now_ms = now_epoch_ms()
            session = self._find_session(state["sessions"], normalized_session_id)
            self._require_not_expired(session, now_ms)
            member = self._find_member(session, normalized_member_id)

            member["last_seen_at"] = now
            member["envelope"] = {
                "payload_version": max(1, min(int(payload_version), 9)),
                "iv_b64": iv,
                "ciphertext_b64": ciphertext,
                "sent_at": now,
            }
            session["updated_at"] = now
            self._save_state(state)

            append_jsonl(
                SUPPORT_DEVICE_UMBRELLA_LOG_PATH,
                {
                    "event": "umbrella_envelope_pushed",
                    "timestamp": now,
                    "session_id": normalized_session_id,
                    "member_id": normalized_member_id,
                },
            )
            return {
                "session_id": normalized_session_id,
                "member_id": normalized_member_id,
                "accepted": True,
                "synced_at": now,
            }

    def pull_envelopes(self, *, session_id: str, member_id: str) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            normalized_session_id = sanitize_text(session_id, max_chars=60)
            normalized_member_id = sanitize_text(member_id, max_chars=60)
            now = now_iso()
            now_ms = now_epoch_ms()

            session = self._find_session(state["sessions"], normalized_session_id)
            self._require_not_expired(session, now_ms)
            requester = self._find_member(session, normalized_member_id)
            requester["last_seen_at"] = now
            session["updated_at"] = now

            envelopes: list[dict[str, Any]] = []
            for item in session.get("members", []):
                if not isinstance(item, dict):
                    continue
                if item.get("member_id", "") == normalized_member_id:
                    continue
                envelope = item.get("envelope", {})
                if not isinstance(envelope, dict):
                    continue
                iv = sanitize_text(envelope.get("iv_b64", ""), max_chars=MAX_ENVELOPE_B64_CHARS)
                ciphertext = sanitize_text(
                    envelope.get("ciphertext_b64", ""),
                    max_chars=MAX_ENVELOPE_B64_CHARS,
                )
                sent_at = sanitize_text(envelope.get("sent_at", ""), max_chars=64)
                if not iv or not ciphertext or not sent_at:
                    continue
                envelopes.append(
                    {
                        "member_id": sanitize_text(item.get("member_id", ""), max_chars=60),
                        "device_alias": sanitize_text(item.get("device_alias", ""), max_chars=80),
                        "device_model": sanitize_text(item.get("device_model", ""), max_chars=120),
                        "payload_version": int(envelope.get("payload_version", 1) or 1),
                        "iv_b64": iv,
                        "ciphertext_b64": ciphertext,
                        "sent_at": sent_at,
                    }
                )

            self._save_state(state)
            append_jsonl(
                SUPPORT_DEVICE_UMBRELLA_LOG_PATH,
                {
                    "event": "umbrella_envelopes_pulled",
                    "timestamp": now,
                    "session_id": normalized_session_id,
                    "member_id": normalized_member_id,
                    "envelope_count": len(envelopes),
                },
            )

            return {
                "session_id": normalized_session_id,
                "umbrella_id": sanitize_text(session.get("umbrella_id", ""), max_chars=60),
                "owner": sanitize_text(session.get("owner", "parent"), max_chars=40),
                "envelopes": envelopes,
            }

    def session_status(self, *, session_id: str, member_id: str) -> dict[str, Any]:
        with self._lock:
            state = self._load_state()
            self._compact_sessions(state)

            normalized_session_id = sanitize_text(session_id, max_chars=60)
            normalized_member_id = sanitize_text(member_id, max_chars=60)
            now = now_iso()
            now_ms = now_epoch_ms()

            session = self._find_session(state["sessions"], normalized_session_id)
            self._require_not_expired(session, now_ms)
            requester = self._find_member(session, normalized_member_id)
            requester["last_seen_at"] = now
            session["updated_at"] = now
            self._save_state(state)

            members = [
                {
                    "member_id": sanitize_text(item.get("member_id", ""), max_chars=60),
                    "device_alias": sanitize_text(item.get("device_alias", ""), max_chars=80),
                    "device_model": sanitize_text(item.get("device_model", ""), max_chars=120),
                    "last_seen_at": sanitize_text(item.get("last_seen_at", ""), max_chars=64),
                    "has_envelope": bool((item.get("envelope", {}) or {}).get("ciphertext_b64")),
                }
                for item in session.get("members", [])
                if isinstance(item, dict)
            ]

            return {
                "session_id": normalized_session_id,
                "umbrella_id": sanitize_text(session.get("umbrella_id", ""), max_chars=60),
                "owner": sanitize_text(session.get("owner", "parent"), max_chars=40),
                "expires_at_epoch_ms": int(session.get("expires_at_epoch_ms", 0) or 0),
                "member_count": len(members),
                "members": members,
            }

    def _load_state(self) -> dict[str, Any]:
        fallback = {"schema_version": SCHEMA_VERSION, "sessions": []}
        state = _load_json(SUPPORT_DEVICE_UMBRELLA_STATE_PATH, fallback)
        if not isinstance(state, dict):
            return dict(fallback)
        sessions = state.get("sessions", [])
        if not isinstance(sessions, list):
            sessions = []
        return {
            "schema_version": int(state.get("schema_version", SCHEMA_VERSION) or SCHEMA_VERSION),
            "sessions": sessions,
        }

    def _save_state(self, state: dict[str, Any]) -> None:
        self._compact_sessions(state)
        _save_json(SUPPORT_DEVICE_UMBRELLA_STATE_PATH, state)

    def _compact_sessions(self, state: dict[str, Any]) -> None:
        sessions = state.get("sessions", [])
        if not isinstance(sessions, list):
            state["sessions"] = []
            return
        now_ms = now_epoch_ms()
        filtered = []
        for session in sessions:
            if not isinstance(session, dict):
                continue
            expires = int(session.get("expires_at_epoch_ms", 0) or 0)
            if expires > 0 and expires < now_ms:
                continue
            self._compact_join_requests_for_session(session, now_ms)
            filtered.append(session)
        if len(filtered) > MAX_STORED_SESSIONS:
            filtered = filtered[-MAX_STORED_SESSIONS:]
        state["sessions"] = filtered

    def _find_session(self, sessions: list[dict[str, Any]], session_id: str) -> dict[str, Any]:
        for session in sessions:
            if sanitize_text(session.get("session_id", ""), max_chars=60) == session_id:
                return session
        raise ValueError("session_id not found")

    def _find_member(self, session: dict[str, Any], member_id: str) -> dict[str, Any]:
        members = session.get("members", [])
        if not isinstance(members, list):
            raise ValueError("session members are invalid")
        for member in members:
            if not isinstance(member, dict):
                continue
            if sanitize_text(member.get("member_id", ""), max_chars=60) == member_id:
                return member
        raise ValueError("member_id not found for this session")

    def _require_not_expired(self, session: dict[str, Any], now_ms: int) -> None:
        expires = int(session.get("expires_at_epoch_ms", 0) or 0)
        if expires > 0 and expires < now_ms:
            session["status"] = "expired"
            raise ValueError("pairing session has expired")

    def _compact_join_requests_for_session(self, session: dict[str, Any], now_ms: int) -> None:
        join_requests = session.get("join_requests", [])
        if not isinstance(join_requests, list):
            session["join_requests"] = []
            return
        filtered = []
        for item in join_requests:
            if not isinstance(item, dict):
                continue
            status = sanitize_text(item.get("status", ""), max_chars=20, lowercase=True)
            requested_at_ms = int(item.get("requested_at_epoch_ms", 0) or 0)
            if status == "pending" and requested_at_ms > 0 and requested_at_ms + JOIN_REQUEST_TTL_MS < now_ms:
                continue
            filtered.append(item)
        if len(filtered) > MAX_PENDING_JOIN_REQUESTS_PER_SESSION * 3:
            filtered = filtered[-(MAX_PENDING_JOIN_REQUESTS_PER_SESSION * 3) :]
        session["join_requests"] = filtered

    def _find_member_by_device_fingerprint(
        self,
        session: dict[str, Any],
        device_fingerprint: str,
    ) -> dict[str, Any] | None:
        members = session.get("members", [])
        if not isinstance(members, list):
            return None
        for item in members:
            if not isinstance(item, dict):
                continue
            existing_fingerprint = sanitize_text(
                item.get("device_fingerprint", ""),
                max_chars=96,
                lowercase=True,
            )
            if existing_fingerprint and hmac.compare_digest(existing_fingerprint, device_fingerprint):
                return item
        return None

    def _find_pending_join_request_for_fingerprint(
        self,
        session: dict[str, Any],
        device_fingerprint: str,
    ) -> dict[str, Any] | None:
        join_requests = session.get("join_requests", [])
        if not isinstance(join_requests, list):
            return None
        for item in join_requests:
            if not isinstance(item, dict):
                continue
            status = sanitize_text(item.get("status", ""), max_chars=20, lowercase=True)
            request_fingerprint = sanitize_text(
                item.get("device_fingerprint", ""),
                max_chars=96,
                lowercase=True,
            )
            if status == "pending" and request_fingerprint and hmac.compare_digest(request_fingerprint, device_fingerprint):
                return item
        return None

    def _count_active_sessions_for_owner_proof(self, sessions: list[dict[str, Any]], owner_proof: str) -> int:
        count = 0
        for session in sessions:
            if not isinstance(session, dict):
                continue
            session_owner_proof = sanitize_text(session.get("owner_proof", ""), max_chars=96, lowercase=True)
            if not session_owner_proof:
                continue
            if hmac.compare_digest(session_owner_proof, owner_proof):
                count += 1
        return count

    def _count_active_member_links_for_owner_proof(self, sessions: list[dict[str, Any]], owner_proof: str) -> int:
        count = 0
        for session in sessions:
            if not isinstance(session, dict):
                continue
            session_owner_proof = sanitize_text(session.get("owner_proof", ""), max_chars=96, lowercase=True)
            if not session_owner_proof or not hmac.compare_digest(session_owner_proof, owner_proof):
                continue
            members = session.get("members", [])
            if not isinstance(members, list):
                continue
            count += len([item for item in members if isinstance(item, dict)])
        return count
