from __future__ import annotations

import hashlib
from typing import Any

from .actions import load_action_queue, queue_task, save_action_queue
from .breach import assess_record_risk
from .models import ActionTask, CredentialRecord
from .passwords import generate_strong_password
from .prompts import prompt_choice, prompt_yes_no
from .utils import DEFAULT_CATEGORY_ORDER, utc_now_iso


def _priority_index(category: str, priority_order: list[str]) -> int:
    try:
        return priority_order.index(category)
    except ValueError:
        return len(priority_order)


def _task_id(record_id: str, action_type: str) -> str:
    return hashlib.sha256(f"{record_id}|{action_type}".encode("utf-8")).hexdigest()[:20]


def _sort_records(records: list[CredentialRecord], settings: dict[str, Any]) -> list[CredentialRecord]:
    priority_order = settings.get("priority_categories", DEFAULT_CATEGORY_ORDER)
    return sorted(records, key=lambda item: (_priority_index(item.category, priority_order), item.service.lower(), item.username.lower()))


def run_guided_session(
    records: list[CredentialRecord],
    settings: dict[str, Any],
    *,
    online_password_check: bool,
    online_email_check: bool,
    hibp_api_key: str | None,
) -> tuple[list[CredentialRecord], list[ActionTask]]:
    ordered_records = _sort_records(records, settings)
    tasks = load_action_queue()
    print(f"Loaded {len(ordered_records)} records. Starting top-to-bottom review.")

    for index, record in enumerate(ordered_records, start=1):
        print("")
        print("=" * 80)
        print(f"[{index}/{len(ordered_records)}] {record.service} | {record.username} | owner={record.owner} | category={record.category}")
        print(f"URL: {record.url}")
        risk = assess_record_risk(
            record,
            online_password_check=online_password_check,
            online_email_check=online_email_check,
            hibp_api_key=hibp_api_key,
        )
        print(f"Risk: {risk['risk_level']} | reasons: {', '.join(risk['reasons']) if risk['reasons'] else 'none'}")

        still_using = prompt_choice(
            "Are you still using this account?",
            ["yes", "no", "not sure"],
            default_index=0,
        )

        if still_using == "no":
            record.lifecycle_state = "inactive"
            remove_presence = prompt_yes_no("Queue account deletion/removal from this site?", default=True)
            if remove_presence:
                queue_task(
                    tasks,
                    ActionTask(
                        task_id=_task_id(record.record_id, "delete_account"),
                        record_id=record.record_id,
                        owner=record.owner,
                        service=record.service,
                        url=record.url,
                        action_type="delete_account",
                        detail="User marked account as no longer needed",
                    ),
                )
                record.lifecycle_state = "retire_pending"
            record.updated_at = utc_now_iso()
            continue

        if still_using == "not sure":
            record.lifecycle_state = "review_later"
            record.updated_at = utc_now_iso()
            continue

        record.lifecycle_state = "active"
        suggested_rotate = risk["risk_level"] in {"high", "medium"}
        rotate_now = prompt_yes_no("Rotate password now?", default=suggested_rotate)
        if rotate_now:
            record.pending_password = generate_strong_password(24)
            record.last_rotated_at = utc_now_iso()
            queue_task(
                tasks,
                ActionTask(
                    task_id=_task_id(record.record_id, "rotate_password"),
                    record_id=record.record_id,
                    owner=record.owner,
                    service=record.service,
                    url=record.url,
                    action_type="rotate_password",
                    detail="User approved password rotation",
                ),
            )
            print("Generated new password and queued rotate task.")
        record.updated_at = utc_now_iso()

    save_action_queue(tasks)
    return ordered_records, tasks
