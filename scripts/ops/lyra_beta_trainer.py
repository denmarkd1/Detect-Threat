#!/usr/bin/env python3
"""Lyra device-backed QA trainer for DT Guardian release readiness."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List


ROOT_DIR = Path(__file__).resolve().parents[2]
ANDROID_DIR = ROOT_DIR / "android-watchdog"
DEFAULT_PACKAGE = "com.realyn.watchdog"
DEFAULT_APK = ANDROID_DIR / "app/build/outputs/apk/debug/app-debug.apk"
LOG_DIR = ROOT_DIR / "logs" / "lyra_qa"
DEFAULT_MONKEY_EVENTS = 150
DEFAULT_MONKEY_SEED = 424242
OUTPUT_EXCERPT_LIMIT = 2200


@dataclass
class CheckResult:
    name: str
    command: str
    status: str
    return_code: int
    duration_seconds: float
    output_excerpt: str


@dataclass
class QaReport:
    generated_at_utc: str
    workspace: str
    package_name: str
    device_serial: str
    checks: List[CheckResult]


class QaRunner:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.results: List[CheckResult] = []
        self.full_outputs: Dict[str, str] = {}
        self.adb_bin = self._resolve_adb()

    def _resolve_adb(self) -> str:
        local_adb = ROOT_DIR / "tools/android/platform-tools/adb"
        if local_adb.exists():
            return str(local_adb)
        return "adb"

    def _run(
        self,
        name: str,
        command: List[str],
        cwd: Path | None = None,
        timeout: int = 600,
    ) -> CheckResult:
        start = time.time()
        try:
            proc = subprocess.run(
                command,
                cwd=str(cwd or ROOT_DIR),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=timeout,
            )
            duration = time.time() - start
            merged_full = "\n".join(part for part in [proc.stdout.strip(), proc.stderr.strip()] if part).strip()
            self.full_outputs[name] = merged_full
            merged = merged_full
            if len(merged) > OUTPUT_EXCERPT_LIMIT:
                merged = merged[:OUTPUT_EXCERPT_LIMIT] + "\n...[truncated]"
            result = CheckResult(
                name=name,
                command=" ".join(command),
                status="PASS" if proc.returncode == 0 else "FAIL",
                return_code=proc.returncode,
                duration_seconds=round(duration, 2),
                output_excerpt=merged,
            )
        except subprocess.TimeoutExpired as exc:
            duration = time.time() - start
            timeout_msg = f"Timed out after {timeout}s: {exc}"
            self.full_outputs[name] = timeout_msg
            result = CheckResult(
                name=name,
                command=" ".join(command),
                status="FAIL",
                return_code=124,
                duration_seconds=round(duration, 2),
                output_excerpt=timeout_msg,
            )
        self.results.append(result)
        print(f"[{result.status}] {name} ({result.duration_seconds}s)")
        return result

    def _skip(self, name: str, command: str, reason: str) -> CheckResult:
        result = CheckResult(
            name=name,
            command=command,
            status="SKIP",
            return_code=0,
            duration_seconds=0.0,
            output_excerpt=reason,
        )
        self.results.append(result)
        print(f"[SKIP] {name} (0.0s)")
        return result

    def _adb_cmd(self, args: List[str]) -> List[str]:
        cmd = [self.adb_bin]
        if self.args.serial:
            cmd += ["-s", self.args.serial]
        return cmd + args

    def _failures(self) -> int:
        return sum(1 for item in self.results if item.status == "FAIL")

    def _record_supplemental_result(
        self,
        name: str,
        command: str,
        status: str,
        return_code: int,
        output_excerpt: str,
    ) -> None:
        self.results.append(
            CheckResult(
                name=name,
                command=command,
                status=status,
                return_code=return_code,
                duration_seconds=0.0,
                output_excerpt=output_excerpt,
            )
        )
        print(f"[{status}] {name} (0.0s)")

    def run(self) -> QaReport:
        if not self.args.skip_python_bootstrap:
            self._run(
                "python_editable_install",
                ["python3", "-m", "pip", "install", "-e", "."],
                cwd=ROOT_DIR,
                timeout=1200,
            )

        self._run("credential_defense_help", ["credential-defense", "--help"], cwd=ROOT_DIR)
        self._run("watchdog_help", ["python3", "watchdog/watchdog.py", "--help"], cwd=ROOT_DIR)
        self._run("precommit_guard", ["bash", "scripts/ops/precommit_guard.sh", "--include-unstaged"], cwd=ROOT_DIR)
        self._run("gradle_lint_unit", ["./gradlew", "lintDebug", "testDebugUnitTest"], cwd=ANDROID_DIR, timeout=1800)
        self._run("gradle_assemble_debug", ["./gradlew", "assembleDebug"], cwd=ANDROID_DIR, timeout=1800)

        adb_connection = self._run("adb_connection", ["bash", "scripts/ops/check_adb_connection.sh"], cwd=ROOT_DIR)
        adb_ready = adb_connection.status == "PASS"

        if adb_ready and DEFAULT_APK.exists():
            self._run("adb_install_debug_apk", self._adb_cmd(["install", "-r", str(DEFAULT_APK)]), cwd=ROOT_DIR, timeout=600)
        elif not DEFAULT_APK.exists():
            self._record_supplemental_result(
                name="adb_install_debug_apk",
                command=f"{self.adb_bin} install -r {DEFAULT_APK}",
                status="FAIL",
                return_code=2,
                output_excerpt=f"Debug APK missing: {DEFAULT_APK}",
            )
            adb_ready = False
        else:
            self._skip(
                "adb_install_debug_apk",
                f"{self.adb_bin} install -r {DEFAULT_APK}",
                "Skipped because no authorized device was detected.",
            )

        if adb_ready:
            monkey_events = max(1, int(self.args.monkey_events))
            monkey_seed = int(self.args.monkey_seed)
            monkey_timeout = max(300, 120 + (monkey_events * 2))
            self._run(
                "adb_logcat_clear",
                self._adb_cmd(["logcat", "-c"]),
                cwd=ROOT_DIR,
                timeout=120,
            )
            self._run(
                "app_launch_smoke",
                self._adb_cmd(["shell", "monkey", "-p", self.args.package, "-c", "android.intent.category.LAUNCHER", "1"]),
                cwd=ROOT_DIR,
                timeout=180,
            )

            if not self.args.skip_monkey_events:
                self._run(
                    "app_randomized_monkey",
                    self._adb_cmd([
                        "shell",
                        "monkey",
                        "-p",
                        self.args.package,
                        "--ignore-crashes",
                        "--ignore-timeouts",
                        "--pct-syskeys",
                        "0",
                        "--throttle",
                        "60",
                        "-s",
                        str(monkey_seed),
                        str(monkey_events),
                    ]),
                    cwd=ROOT_DIR,
                    timeout=monkey_timeout,
                )
            else:
                self._skip("app_randomized_monkey", "adb shell monkey ...", "Skipped by --skip-monkey-events")

            self._run("watchdog_baseline", ["python3", "watchdog/watchdog.py", "baseline"], cwd=ROOT_DIR, timeout=600)
            self._run("watchdog_scan", ["python3", "watchdog/watchdog.py", "scan"], cwd=ROOT_DIR, timeout=900)

            logcat_result = self._run(
                "logcat_capture",
                self._adb_cmd(["logcat", "-d", "-v", "time", "-t", "4000"]),
                cwd=ROOT_DIR,
                timeout=180,
            )
            if logcat_result.status == "PASS":
                full_logcat = self.full_outputs.get("logcat_capture", logcat_result.output_excerpt)
                fatal_signatures = [
                    line for line in full_logcat.splitlines()
                    if (
                        "FATAL EXCEPTION" in line or
                        f"Process: {self.args.package}" in line or
                        f"ANR in {self.args.package}" in line
                    )
                ]
                if fatal_signatures:
                    self._record_supplemental_result(
                        name="logcat_fatal_scan",
                        command="scan full logcat capture for fatal signatures",
                        status="FAIL",
                        return_code=3,
                        output_excerpt="\n".join(fatal_signatures[:10]),
                    )
                else:
                    self._record_supplemental_result(
                        name="logcat_fatal_scan",
                        command="scan full logcat capture for fatal signatures",
                        status="PASS",
                        return_code=0,
                        output_excerpt="No fatal crash signature found in the full captured logcat.",
                    )

            self._run("adb_force_stop", self._adb_cmd(["shell", "am", "force-stop", self.args.package]), cwd=ROOT_DIR)
        else:
            self._skip("adb_logcat_clear", "adb logcat -c", "Skipped because no authorized device was detected.")
            self._skip("app_launch_smoke", "adb shell monkey -p <package> 1", "Skipped because no authorized device was detected.")
            if self.args.skip_monkey_events:
                self._skip("app_randomized_monkey", "adb shell monkey ...", "Skipped by --skip-monkey-events")
            else:
                self._skip("app_randomized_monkey", "adb shell monkey ...", "Skipped because no authorized device was detected.")
            self._skip("watchdog_baseline", "python3 watchdog/watchdog.py baseline", "Skipped because no authorized device was detected.")
            self._skip("watchdog_scan", "python3 watchdog/watchdog.py scan", "Skipped because no authorized device was detected.")
            self._skip("logcat_capture", "adb logcat -d -v time -t 4000", "Skipped because no authorized device was detected.")
            self._skip("logcat_fatal_scan", "scan logcat excerpt for fatal signatures", "Skipped because logcat capture was skipped.")
            self._skip("adb_force_stop", "adb shell am force-stop <package>", "Skipped because no authorized device was detected.")

        if not self.args.serial:
            serial = self._resolve_serial_from_adb()
        else:
            serial = self.args.serial

        return QaReport(
            generated_at_utc=datetime.now(timezone.utc).isoformat(timespec="seconds"),
            workspace=str(ROOT_DIR),
            package_name=self.args.package,
            device_serial=serial,
            checks=self.results,
        )

    def _resolve_serial_from_adb(self) -> str:
        probe = self._run("adb_devices_probe", [self.adb_bin, "devices", "-l"], cwd=ROOT_DIR)
        if probe.status != "PASS":
            return "unknown"
        for line in probe.output_excerpt.splitlines():
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1] == "device" and not parts[0].startswith("List"):
                return parts[0]
        return "unknown"


def write_reports(report: QaReport) -> tuple[Path, Path]:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    json_path = LOG_DIR / f"lyra_qa_report_{stamp}.json"
    md_path = LOG_DIR / f"lyra_qa_report_{stamp}.md"

    with json_path.open("w", encoding="utf-8") as handle:
        json.dump(
            {
                "generated_at_utc": report.generated_at_utc,
                "workspace": report.workspace,
                "package_name": report.package_name,
                "device_serial": report.device_serial,
                "checks": [asdict(item) for item in report.checks],
            },
            handle,
            indent=2,
        )

    failures = [row for row in report.checks if row.status == "FAIL"]
    with md_path.open("w", encoding="utf-8") as handle:
        handle.write("# Lyra QA Trainer Report\n\n")
        handle.write(f"- Generated (UTC): {report.generated_at_utc}\n")
        handle.write(f"- Workspace: `{report.workspace}`\n")
        handle.write(f"- Package: `{report.package_name}`\n")
        handle.write(f"- Device serial: `{report.device_serial}`\n")
        handle.write(f"- Total checks: {len(report.checks)}\n")
        handle.write(f"- Failures: {len(failures)}\n\n")
        handle.write("| Check | Status | Duration (s) |\n")
        handle.write("| --- | --- | --- |\n")
        for row in report.checks:
            handle.write(f"| {row.name} | {row.status} | {row.duration_seconds:.2f} |\n")

        if failures:
            handle.write("\n## Failure excerpts\n\n")
            for row in failures:
                handle.write(f"### {row.name}\n")
                handle.write("```text\n")
                handle.write((row.output_excerpt or "(no output)") + "\n")
                handle.write("```\n\n")

    return json_path, md_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Lyra QA trainer for DT Guardian APK readiness")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="Android package name")
    parser.add_argument("--serial", default="", help="ADB device serial (optional)")
    parser.add_argument("--skip-monkey-events", action="store_true", help="Skip randomized monkey events")
    parser.add_argument(
        "--monkey-events",
        type=int,
        default=DEFAULT_MONKEY_EVENTS,
        help=f"Monkey event count when enabled (default: {DEFAULT_MONKEY_EVENTS})",
    )
    parser.add_argument(
        "--monkey-seed",
        type=int,
        default=DEFAULT_MONKEY_SEED,
        help=f"Monkey deterministic seed (default: {DEFAULT_MONKEY_SEED})",
    )
    parser.add_argument("--skip-python-bootstrap", action="store_true", help="Skip pip editable install step")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    runner = QaRunner(args)
    report = runner.run()
    json_path, md_path = write_reports(report)

    failures = sum(1 for row in report.checks if row.status == "FAIL")
    print(f"\n[+] JSON report: {json_path}")
    print(f"[+] Markdown report: {md_path}")
    if failures > 0:
        print(f"[!] Lyra QA trainer detected {failures} failing check(s).")
        return 1

    print("[+] Lyra QA trainer completed with all checks passing.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
