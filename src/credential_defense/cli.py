from __future__ import annotations

import argparse
import os
from getpass import getpass
from pathlib import Path

from .actions import execute_pending_actions, load_action_queue
from .browser_ingest import discover_installed_browsers, import_csv_exports
from .config import ensure_workspace_files, load_settings, load_site_profiles
from .platform_watchdog import build_runtime_status, watchdog_loop, write_runtime_status
from .support_hub import build_support_server_parser
from .vault import LocalEncryptedVault, VaultError
from .workflow import run_guided_session


def _prompt_master_password(confirm: bool = False, env_var: str | None = None) -> str:
    if env_var:
        value = os.environ.get(env_var)
        if value:
            return value
    first = getpass("Vault master password: ")
    if confirm:
        second = getpass("Confirm master password: ")
        if first != second:
            raise VaultError("Password confirmation mismatch.")
    return first


def _cmd_init(_: argparse.Namespace) -> int:
    ensure_workspace_files()
    vault = LocalEncryptedVault()
    if vault.exists():
        print("Vault already initialized.")
        return 0
    master = _prompt_master_password(confirm=True, env_var=_.master_password_env)
    vault.initialize(master)
    print("Initialized encrypted local vault.")
    return 0


def _cmd_detect_browsers(_: argparse.Namespace) -> int:
    ensure_workspace_files()
    settings = load_settings()
    status = build_runtime_status(settings)
    write_runtime_status(status)
    found = discover_installed_browsers(settings)
    print(f"Runtime OS: {status['os_family']} ({status['platform']})")
    print("Detected browser profile directories:")
    for name, present in found.items():
        state = "present" if present else "not found"
        print(f"- {name}: {state}")
    if _.show_paths:
        print("")
        print("Path details:")
        for item in status.get("browser_paths", []):
            state = "present" if item.get("present") else "not found"
            print(
                f"- {item.get('browser')}: {state} | source={item.get('source')} | "
                f"path={item.get('path')}"
            )
    return 0


def _print_watchdog_status(status: dict) -> None:
    print(f"Runtime OS: {status['os_family']}")
    print(f"Platform: {status['platform']}")
    print(f"Host: {status['hostname']}")
    print(f"User: {status['active_user']}")
    print(f"Service available: {status['service_available']}")
    print("Browser presence:")
    for browser, present in status.get("browser_presence", {}).items():
        state = "present" if present else "not found"
        print(f"- {browser}: {state}")


def _cmd_watchdog_status(args: argparse.Namespace) -> int:
    ensure_workspace_files()
    settings = load_settings()
    status = build_runtime_status(settings)
    status_path = write_runtime_status(status)
    _print_watchdog_status(status)
    if args.show_paths:
        print("")
        print("Path details:")
        for item in status.get("browser_paths", []):
            state = "present" if item.get("present") else "not found"
            print(
                f"- {item.get('browser')}: {state} | source={item.get('source')} | "
                f"profile={item.get('user_profile')} | path={item.get('path')}"
            )
    print(f"Watchdog status file: {status_path}")
    return 0


def _cmd_watchdog_daemon(args: argparse.Namespace) -> int:
    ensure_workspace_files()
    settings = load_settings()
    configured_interval = settings.get("watchdog", {}).get("heartbeat_interval_seconds", 60)
    interval = int(args.interval or configured_interval)
    max_cycles = int(args.max_cycles or 0)
    print(
        f"Starting watchdog daemon: interval={interval}s max_cycles={max_cycles or 'infinite'} "
        "(Ctrl+C to stop)"
    )
    try:
        return watchdog_loop(settings, interval_seconds=interval, max_cycles=max_cycles, print_updates=True)
    except KeyboardInterrupt:
        print("Watchdog stopped by user.")
        return 0


def _cmd_import_exports(args: argparse.Namespace) -> int:
    ensure_workspace_files()
    vault = LocalEncryptedVault()
    if not vault.exists():
        raise VaultError("Vault missing. Run `credential-defense init` first.")
    settings = load_settings()
    imports_dir = Path(args.imports_dir).expanduser().resolve()
    if not imports_dir.exists():
        raise VaultError(f"Imports directory not found: {imports_dir}")
    records, summary = import_csv_exports(imports_dir, settings)
    if not records:
        print("No records parsed from CSV exports.")
        return 0
    master = _prompt_master_password(env_var=args.master_password_env)
    added = vault.upsert_records(master, records)
    print(
        "Import complete: "
        f"files={summary.total_files}, rows={summary.parsed_rows}, "
        f"imported={summary.imported_records}, skipped={summary.skipped_rows}, new={added}"
    )
    return 0


def _cmd_list_records(args: argparse.Namespace) -> int:
    ensure_workspace_files()
    vault = LocalEncryptedVault()
    if not vault.exists():
        raise VaultError("Vault missing. Run `credential-defense init` first.")
    master = _prompt_master_password(env_var=args.master_password_env)
    records = vault.list_records(master)
    print(f"Vault records: {len(records)}")
    for record in sorted(records, key=lambda item: (item.owner, item.category, item.service.lower())):
        print(
            f"- owner={record.owner} category={record.category} service={record.service} "
            f"username={record.username} state={record.lifecycle_state} compromised={record.compromised} "
            f"pending_rotation={bool(record.pending_password)}"
        )
    return 0


def _cmd_session(args: argparse.Namespace) -> int:
    ensure_workspace_files()
    vault = LocalEncryptedVault()
    if not vault.exists():
        raise VaultError("Vault missing. Run `credential-defense init` first.")
    settings = load_settings()
    master = _prompt_master_password(env_var=args.master_password_env)
    records = vault.list_records(master)
    if not records:
        print("Vault is empty. Import browser exports first.")
        return 0

    hibp_api_key = None
    if args.online_email_check:
        hibp_api_key = os.environ.get(args.hibp_api_key_env)
        if not hibp_api_key:
            print(
                f"{args.hibp_api_key_env} is not set; email breach checks will be skipped "
                "for this run."
            )

    updated_records, tasks = run_guided_session(
        records,
        settings,
        online_password_check=args.online_password_check,
        online_email_check=args.online_email_check and bool(hibp_api_key),
        hibp_api_key=hibp_api_key,
    )
    vault.upsert_records(master, updated_records)
    pending_count = sum(1 for task in tasks if task.status == "pending")
    print(f"Session complete. Pending actions: {pending_count}")
    return 0


def _cmd_run_actions(args: argparse.Namespace) -> int:
    ensure_workspace_files()
    vault = LocalEncryptedVault()
    if not vault.exists():
        raise VaultError("Vault missing. Run `credential-defense init` first.")
    master = _prompt_master_password(env_var=args.master_password_env)
    records = {item.record_id: item for item in vault.list_records(master)}
    tasks = load_action_queue()
    if not tasks:
        print("Action queue is empty.")
        return 0
    site_profiles = load_site_profiles()
    execute_pending_actions(tasks, records, site_profiles)
    vault.upsert_records(master, list(records.values()))
    print("Action runner finished.")
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Credential Defense - local-first breach response workflow")
    sub = parser.add_subparsers(dest="command", required=True)

    cmd_init = sub.add_parser("init", help="Initialize config files and encrypted vault")
    cmd_init.add_argument("--master-password-env", default=None, help="Read vault password from environment variable name")
    cmd_init.set_defaults(func=_cmd_init)

    cmd_detect = sub.add_parser("detect-browsers", help="Detect installed browser profile directories")
    cmd_detect.add_argument("--show-paths", action="store_true", help="Show detailed per-path detection data")
    cmd_detect.set_defaults(func=_cmd_detect_browsers)

    cmd_watchdog_status = sub.add_parser("watchdog-status", help="Write and print runtime OS/watchdog status")
    cmd_watchdog_status.add_argument("--show-paths", action="store_true", help="Show detailed per-path detection data")
    cmd_watchdog_status.set_defaults(func=_cmd_watchdog_status)

    cmd_watchdog_daemon = sub.add_parser("watchdog-daemon", help="Run watchdog heartbeat loop for Linux/Windows runtime detection")
    cmd_watchdog_daemon.add_argument("--interval", type=int, default=0, help="Heartbeat interval in seconds")
    cmd_watchdog_daemon.add_argument("--max-cycles", type=int, default=0, help="Stop after N cycles (0 = run forever)")
    cmd_watchdog_daemon.set_defaults(func=_cmd_watchdog_daemon)

    cmd_import = sub.add_parser("import-exports", help="Import browser CSV exports into encrypted vault")
    cmd_import.add_argument("--imports-dir", default="imports", help="Directory containing exported CSV files")
    cmd_import.add_argument("--master-password-env", default=None, help="Read vault password from environment variable name")
    cmd_import.set_defaults(func=_cmd_import_exports)

    cmd_list = sub.add_parser("list-records", help="List vault records (without printing passwords)")
    cmd_list.add_argument("--master-password-env", default=None, help="Read vault password from environment variable name")
    cmd_list.set_defaults(func=_cmd_list_records)

    cmd_session = sub.add_parser("session", help="Run guided top-to-bottom triage session")
    cmd_session.add_argument("--online-password-check", action="store_true", help="Check passwords with Pwned Passwords")
    cmd_session.add_argument("--online-email-check", action="store_true", help="Check email breaches with HIBP API")
    cmd_session.add_argument("--hibp-api-key-env", default="HIBP_API_KEY", help="Environment variable containing HIBP API key")
    cmd_session.add_argument("--master-password-env", default=None, help="Read vault password from environment variable name")
    cmd_session.set_defaults(func=_cmd_session)

    cmd_actions = sub.add_parser("run-actions", help="Run queued rotate/delete actions with confirmations")
    cmd_actions.add_argument("--master-password-env", default=None, help="Read vault password from environment variable name")
    cmd_actions.set_defaults(func=_cmd_run_actions)

    build_support_server_parser(sub)

    return parser


def main() -> int:
    parser = _build_parser()
    args = parser.parse_args()
    try:
        return int(args.func(args))
    except VaultError as exc:
        print(f"Error: {exc}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
