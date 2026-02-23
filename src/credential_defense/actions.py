from __future__ import annotations

import json
import webbrowser
from dataclasses import asdict
from getpass import getpass
from typing import Any

from .config import ACTION_QUEUE_PATH, SESSION_JOURNAL_PATH, load_json, save_json
from .models import ActionTask, CredentialRecord
from .passwords import is_weak_password
from .prompts import prompt_yes_no
from .utils import domain_from_url, utc_now_iso


def load_action_queue() -> list[ActionTask]:
    payload = load_json(ACTION_QUEUE_PATH, [])
    return [ActionTask.from_dict(item) for item in payload]


def save_action_queue(tasks: list[ActionTask]) -> None:
    save_json(ACTION_QUEUE_PATH, [asdict(task) for task in tasks])


def queue_task(tasks: list[ActionTask], task: ActionTask) -> None:
    if any(existing.task_id == task.task_id for existing in tasks):
        return
    tasks.append(task)


def _site_profile_for(record: CredentialRecord, site_profiles: dict[str, Any]) -> dict[str, Any]:
    domain = domain_from_url(record.url)
    profiles = site_profiles.get("profiles", {})
    return profiles.get(domain, {})


def _task_target_url(task: ActionTask, record: CredentialRecord, site_profiles: dict[str, Any]) -> str:
    profile = _site_profile_for(record, site_profiles)
    if task.action_type == "rotate_password":
        return profile.get("change_password_url") or record.url
    if task.action_type == "delete_account":
        return profile.get("delete_account_url") or record.url
    return record.url


def _write_journal(entry: dict[str, Any]) -> None:
    SESSION_JOURNAL_PATH.parent.mkdir(parents=True, exist_ok=True)
    with SESSION_JOURNAL_PATH.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")


def _playwright_available() -> bool:
    try:
        import playwright  # noqa: F401

        return True
    except Exception:
        return False


def _prompt_manual_rotation_password() -> str:
    while True:
        first = getpass("Enter the new password currently set on the account: ").strip()
        if not first:
            print("Password entry is required to safely complete rotation.")
            continue
        second = getpass("Confirm the new password: ").strip()
        if first != second:
            print("Password confirmation mismatch. Try again.")
            continue
        if is_weak_password(first):
            if not prompt_yes_no(
                "Entered password appears weak. Continue anyway?",
                default=False,
            ):
                continue
        return first


def _run_playwright_rotation(
    record: CredentialRecord,
    *,
    current_password: str,
    new_password: str,
    target_url: str,
    profile: dict[str, Any],
) -> bool:
    try:
        from playwright.sync_api import sync_playwright
    except Exception:
        return False

    automation = profile.get("automation", {})
    selectors = automation.get("selectors", {})
    new_password_selector = selectors.get("new_password")
    confirm_selector = selectors.get("confirm_password")
    current_selector = selectors.get("current_password")
    submit_selector = selectors.get("submit_button")
    if not (new_password_selector and confirm_selector):
        return False

    with sync_playwright() as api:
        browser = api.chromium.launch(headless=False)
        page = browser.new_page()
        page.goto(target_url, wait_until="domcontentloaded")
        input(
            "Browser automation is ready. Log in and navigate to the change-password form, "
            "then press Enter to continue..."
        )
        if current_selector:
            page.fill(current_selector, current_password)
        page.fill(new_password_selector, new_password)
        page.fill(confirm_selector, new_password)
        if submit_selector and prompt_yes_no("Click submit automatically?", default=False):
            page.click(submit_selector)
        input("Review browser state. Press Enter to close automated browser...")
        browser.close()
    return True


def _run_playwright_deletion(target_url: str, profile: dict[str, Any]) -> bool:
    try:
        from playwright.sync_api import sync_playwright
    except Exception:
        return False

    automation = profile.get("automation", {})
    selectors = automation.get("selectors", {})
    delete_selector = selectors.get("delete_confirm_button")
    if not delete_selector:
        return False

    with sync_playwright() as api:
        browser = api.chromium.launch(headless=False)
        page = browser.new_page()
        page.goto(target_url, wait_until="domcontentloaded")
        input(
            "Browser opened for deletion flow. Log in/MFA and navigate to the deletion confirmation step, "
            "then press Enter to continue..."
        )
        if prompt_yes_no("Click delete confirmation automatically?", default=False):
            page.click(delete_selector)
        input("Review browser state. Press Enter to close automated browser...")
        browser.close()
    return True


def execute_pending_actions(
    tasks: list[ActionTask],
    records: dict[str, CredentialRecord],
    site_profiles: dict[str, Any],
) -> list[ActionTask]:
    for task in tasks:
        if task.status != "pending":
            continue
        record = records.get(task.record_id)
        if not record:
            task.status = "failed"
            task.detail = "Record missing in vault"
            task.updated_at = utc_now_iso()
            continue

        print("")
        print(f"Task: {task.action_type} | {record.service} | {record.username} | owner={record.owner}")
        target_url = _task_target_url(task, record, site_profiles)
        print(f"Target URL: {target_url}")
        profile = _site_profile_for(record, site_profiles)

        if not prompt_yes_no("Open this URL and execute now?", default=True):
            task.status = "skipped"
            task.detail = "User deferred"
            task.updated_at = utc_now_iso()
            continue

        automation_attempted = False
        automation_succeeded = False
        new_password = record.pending_password or record.password
        applied_password = new_password
        if _playwright_available() and profile.get("automation", {}).get("enabled", False):
            if prompt_yes_no("Try Playwright automation for this task?", default=True):
                automation_attempted = True
                if task.action_type == "rotate_password":
                    automation_succeeded = _run_playwright_rotation(
                        record,
                        current_password=record.password,
                        new_password=new_password,
                        target_url=target_url,
                        profile=profile,
                    )
                elif task.action_type == "delete_account":
                    automation_succeeded = _run_playwright_deletion(target_url, profile)

        if not automation_succeeded:
            webbrowser.open(target_url)
            print("Browser opened.")

        if task.action_type == "rotate_password":
            if not automation_succeeded:
                print(
                    "Raw password display is disabled by security policy. "
                    "Confirm the password you applied using hidden input."
                )
                applied_password = _prompt_manual_rotation_password()

        completed = prompt_yes_no("Mark this task as completed?", default=True)
        task.status = "completed" if completed else "failed"
        if completed:
            if automation_succeeded:
                task.detail = "Completed with Playwright automation + user confirmation"
            elif automation_attempted:
                task.detail = "Automation attempted; completed manually by user confirmation"
            else:
                task.detail = "Completed manually by user confirmation"
        else:
            task.detail = "Not completed"
        if task.action_type == "rotate_password" and completed:
            record.password = applied_password
            record.pending_password = None
            record.updated_at = utc_now_iso()
        task.updated_at = utc_now_iso()
        _write_journal(
            {
                "timestamp": task.updated_at,
                "task_id": task.task_id,
                "record_id": task.record_id,
                "service": record.service,
                "username": record.username,
                "action_type": task.action_type,
                "status": task.status,
            }
        )

    save_action_queue(tasks)
    return tasks
