from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any

from .utils import utc_now_iso


@dataclass
class CredentialRecord:
    record_id: str
    owner: str
    service: str
    url: str
    username: str
    password: str
    source: str
    pending_password: str | None = None
    category: str = "other"
    notes: str = ""
    compromised: bool = False
    breach_count: int = 0
    lifecycle_state: str = "active"
    last_checked_at: str | None = None
    last_rotated_at: str | None = None
    created_at: str = field(default_factory=utc_now_iso)
    updated_at: str = field(default_factory=utc_now_iso)

    def to_dict(self, include_secret: bool = True) -> dict[str, Any]:
        payload = asdict(self)
        if not include_secret:
            payload["password"] = "***"
        return payload

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "CredentialRecord":
        return cls(**payload)


@dataclass
class ActionTask:
    task_id: str
    record_id: str
    owner: str
    service: str
    url: str
    action_type: str
    status: str = "pending"
    detail: str = ""
    created_at: str = field(default_factory=utc_now_iso)
    updated_at: str = field(default_factory=utc_now_iso)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "ActionTask":
        return cls(**payload)
