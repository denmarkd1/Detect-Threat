#!/usr/bin/env python3
"""Lyra device-backed QA trainer for DT Guardian release readiness."""

from __future__ import annotations

import argparse
import json
import re
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
MEMORY_STORE_PATH = ROOT_DIR / "systems" / "D_T_System" / "data" / "mcp_memory.jsonl"
ZEN_COUNCIL_EVENTS_PATH = ROOT_DIR / "systems" / "D_T_System" / "logs" / "zen_council_events.jsonl"
MEMORY_RELOCATION_DIR = ROOT_DIR / "logs" / "memory"
ROADMAP_PATH = ROOT_DIR / "docs" / "integration" / "top5_competitor_smart_integration_roadmap_2026-03-03.md"
PHASE1_5_AUDIT_PATH = ROOT_DIR / "docs" / "integration" / "phase1_5_retail_readiness_audit_2026-03-04.md"
PHASE6_ARTIFACT_PATHS = (
    ROOT_DIR / "scripts" / "ops" / "phase6_masvs_sweep.sh",
    ROOT_DIR / "docs" / "integration" / "phase6_masvs_verification_sweep_2026-03-04.md",
    ROOT_DIR / "docs" / "integration" / "phase6_policy_play_disclosure_review_2026-03-04.md",
    ROOT_DIR / "docs" / "integration" / "phase6_staged_rollout_rollback_playbook_2026-03-04.md",
    ROOT_DIR / "docs" / "integration" / "phase6_pricing_packaging_update_2026-03-04.md",
    ROOT_DIR / "docs" / "integration" / "phase6_play_store_first_submission_guide_2026-03-04.md",
    ROOT_DIR / "docs" / "integration" / "phase6_tutorial_overlay_2026-03-04.md",
    ROOT_DIR / "docs" / "integration" / "phase6_lyra_qa_trainer_2026-03-04.md",
)
REQUIRED_PHASE6_MEMORY_ENTITIES = (
    "security_phase6_launch_readiness_2026_03_04",
    "security_phase6_masvs_report_20260304T102443Z",
    "security_phase6_lyra_report_20260304T102544Z",
    "security_phase6_play_submission_guide_20260304",
    "security_phase6_tutorial_overlay_20260304",
)
REQUIRED_ROADMAP_PHASE_HEADERS = (
    "## Phase 0 - Product and legal framing (1 week)",
    "## Phase 1 - Architecture and data model (1 to 2 weeks)",
    "## Phase 2 - Smart-home connector MVP (2 to 3 weeks)",
    "## Phase 3 - VPN broker and service linking (1 to 2 weeks)",
    "## Phase 4 - Digital key risk guardrails (2 weeks)",
    "## Phase 5 - Competitive parity+ enhancements (2 weeks)",
    "## Phase 6 - Hardening and launch readiness (1 to 2 weeks)",
)
REQUIRED_PHASE1_5_PASS_ROWS = (
    "| Phase 1 - Architecture and data model |",
    "| Phase 2 - Smart-home connector MVP |",
    "| Phase 3 - VPN broker and service linking |",
    "| Phase 4 - Digital key risk guardrails |",
    "| Phase 5 - Competitive parity+ |",
)


def _safe_read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def _extract_workspace_paths(text: str) -> List[Path]:
    candidates: List[Path] = []
    absolute_matches = re.findall(r"/home/[A-Za-z0-9._/\-]+", text)
    relative_matches = re.findall(
        r"(?:docs|scripts|android-watchdog|watchdog|config|logs|src)/[A-Za-z0-9._/\-]+",
        text,
    )

    for raw in absolute_matches:
        cleaned = raw.rstrip(".,;:)'\"`")
        if cleaned:
            candidates.append(Path(cleaned))

    for raw in relative_matches:
        cleaned = raw.rstrip(".,;:)'\"`")
        if cleaned:
            candidates.append(ROOT_DIR / cleaned)

    unique: List[Path] = []
    seen = set()
    for path in candidates:
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        unique.append(path)
    return unique


def _load_json_stream(path: Path) -> List[dict]:
    if not path.exists():
        return []
    text = _safe_read_text(path)
    decoder = json.JSONDecoder()
    position = 0
    objects: List[dict] = []

    while position < len(text):
        while position < len(text) and text[position].isspace():
            position += 1
        if position >= len(text):
            break
        try:
            parsed, end = decoder.raw_decode(text, position)
        except json.JSONDecodeError:
            next_newline = text.find("\n", position)
            if next_newline == -1:
                break
            position = next_newline + 1
            continue
        position = end
        if isinstance(parsed, dict):
            objects.append(parsed)
    return objects


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

    def _extract_issue_summaries(self) -> List[str]:
        summaries: List[str] = []
        if not ZEN_COUNCIL_EVENTS_PATH.exists():
            return summaries
        for line in _safe_read_text(ZEN_COUNCIL_EVENTS_PATH).splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            prompt = str(payload.get("prompt", ""))
            match = re.search(r"Issue summary:\s*(.+?)(?:\n|$)", prompt, re.IGNORECASE | re.DOTALL)
            if match:
                summaries.append(match.group(1).strip())
        return summaries

    def _apply_marker_rules(
        self,
        label: str,
        rules: List[Dict[str, object]],
        errors: List[str],
        notes: List[str],
    ) -> None:
        for rule in rules:
            path = rule["path"]
            if not isinstance(path, Path):
                errors.append(f"{label}: invalid path rule ({path})")
                continue
            if not path.exists():
                errors.append(f"{label}: missing file {path}")
                continue

            payload = _safe_read_text(path)
            required = rule.get("must_contain", [])
            forbidden = rule.get("must_not_contain", [])

            if not isinstance(required, list):
                required = []
            if not isinstance(forbidden, list):
                forbidden = []

            for token in required:
                if isinstance(token, str) and token not in payload:
                    errors.append(f"{label}: `{token}` not found in {path}")
            for token in forbidden:
                if isinstance(token, str) and token in payload:
                    errors.append(f"{label}: `{token}` unexpectedly present in {path}")

        notes.append(f"{label}: validated {len(rules)} file marker rule(s).")

    def _run_memory_phase_fix_coverage(self) -> None:
        check_name = "memory_phase_fix_coverage"
        notes: List[str] = []
        errors: List[str] = []

        if not MEMORY_STORE_PATH.exists():
            errors.append(f"Missing local memory store: {MEMORY_STORE_PATH}")
            self._record_supplemental_result(
                name=check_name,
                command="memory sweep + phase/fix artifact validation",
                status="FAIL",
                return_code=4,
                output_excerpt="\n".join(errors),
            )
            return

        memory_objects = _load_json_stream(MEMORY_STORE_PATH)
        memory_entities = [item for item in memory_objects if item.get("type") == "entity"]
        memory_by_name = {
            str(item.get("name", "")): item
            for item in memory_entities
            if isinstance(item, dict) and item.get("name")
        }
        notes.append(f"Loaded {len(memory_entities)} memory entities from {MEMORY_STORE_PATH}.")

        missing_entities = [name for name in REQUIRED_PHASE6_MEMORY_ENTITIES if name not in memory_by_name]
        if missing_entities:
            errors.append(f"Missing required Phase 6 memory entities: {', '.join(missing_entities)}")
        else:
            notes.append("Phase 6 memory entities present in local D_T store.")

        for entity_name in REQUIRED_PHASE6_MEMORY_ENTITIES:
            entry = memory_by_name.get(entity_name)
            if not entry:
                continue
            observations = entry.get("observations", [])
            if not isinstance(observations, list):
                observations = []
            for observation in observations:
                for candidate in _extract_workspace_paths(str(observation)):
                    if not candidate.exists():
                        errors.append(f"Memory path missing for {entity_name}: {candidate}")

        if not ROADMAP_PATH.exists():
            errors.append(f"Missing roadmap file: {ROADMAP_PATH}")
        else:
            roadmap_payload = _safe_read_text(ROADMAP_PATH)
            for heading in REQUIRED_ROADMAP_PHASE_HEADERS:
                if heading not in roadmap_payload:
                    errors.append(f"Roadmap phase heading missing: {heading}")
            notes.append("Roadmap phase headers (Phase 0-6) verified.")

        if not PHASE1_5_AUDIT_PATH.exists():
            errors.append(f"Missing Phase 1-5 audit file: {PHASE1_5_AUDIT_PATH}")
        else:
            audit_payload = _safe_read_text(PHASE1_5_AUDIT_PATH)
            for marker in REQUIRED_PHASE1_5_PASS_ROWS:
                matching_lines = [line for line in audit_payload.splitlines() if marker in line]
                if not matching_lines:
                    errors.append(f"Phase 1-5 audit row missing: {marker}")
                    continue
                if not any("| PASS |" in line for line in matching_lines):
                    errors.append(f"Phase 1-5 audit row is not PASS: {marker}")
            notes.append("Phase 1-5 audit markers verified.")

        for required_path in PHASE6_ARTIFACT_PATHS:
            if not required_path.exists():
                errors.append(f"Missing Phase 6 artifact: {required_path}")
        notes.append(f"Phase 6 artifact bundle validated ({len(PHASE6_ARTIFACT_PATHS)} paths).")

        relocation_reports = sorted(MEMORY_RELOCATION_DIR.glob("mcp_relocation_plan_*.json"))
        if not relocation_reports:
            errors.append(f"No relocation report found under {MEMORY_RELOCATION_DIR}")
        else:
            notes.append(f"Relocation report present: {relocation_reports[-1]}")

        issue_summaries = self._extract_issue_summaries()
        lowered_summaries = [entry.lower() for entry in issue_summaries]
        for phase_number in (2, 3, 4, 5, 6):
            token = f"phase {phase_number}"
            if not any(token in entry for entry in lowered_summaries):
                errors.append(f"No memory issue summary found for {token}.")
        notes.append(f"Loaded {len(issue_summaries)} issue summaries from Zen council memory log.")

        fix_marker_rules: Dict[str, List[Dict[str, object]]] = {
            "fix_false_positive_tuning": [
                {
                    "path": ROOT_DIR / "watchdog" / "watchdog.py",
                    "must_contain": [
                        "sensitive_permissions",
                        "Critical permissions newly observed",
                        "Sensitive permissions newly observed",
                    ],
                },
                {
                    "path": ROOT_DIR / "config" / "workspace_settings.json",
                    "must_contain": [
                        "\"trusted_packages\"",
                        "com.azure.authenticator",
                        "com.microsoft.office.outlook",
                        "ph.com.globe.globeonesuperapp",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "assets" / "workspace_settings.json",
                    "must_contain": [
                        "\"trusted_packages\"",
                        "com.azure.authenticator",
                        "com.microsoft.office.outlook",
                        "ph.com.globe.globeonesuperapp",
                    ],
                },
            ],
            "fix_home_scan_routing": [
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "MainActivity.kt",
                    "must_contain": [
                        "private fun runOneTimeScan()",
                        "runQuickGuardianSweep()",
                        "private fun shouldShowSweepNavAction()",
                    ],
                    "must_not_contain": ["showScanModeDialog("],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "res" / "values" / "strings.xml",
                    "must_contain": [
                        "home_tutorial_step_nav_scan_body",
                        "home_tutorial_step_nav_page2_hint",
                    ],
                    "must_not_contain": [
                        "scan_mode_dialog_title",
                        "scan_mode_dialog_message",
                    ],
                },
            ],
            "fix_incident_assistant_flow": [
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "IncidentStore.kt",
                    "must_contain": [
                        "syncFromDeepScan",
                        "syncFromWifiPosture",
                        "nextUnresolvedForWork",
                        "markInProgress",
                        "markResolved",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "ScanResultsActivity.kt",
                    "must_contain": [
                        "skipIncidentAndContinue",
                        "EXTRA_SCREEN_MODE",
                        "SCREEN_MODE_INCIDENT_ASSISTANT",
                        "configureIncidentAssistantOnlyScreen",
                        "incident_guidance_why_template",
                        "incident_guidance_signal_map_title",
                        "incident_assistant_section_recommended",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "res" / "layout" / "activity_scan_results.xml",
                    "must_contain": [
                        "scanResultsPrimaryActionsRow",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "res" / "values" / "strings.xml",
                    "must_contain": [
                        "incident_assistant_skip_choice",
                        "incident_assistant_section_recommended",
                        "incident_assistant_screen_subtitle",
                        "incident_assistant_back_to_scan_results",
                        "home_tutorial_step_incident_assistant_body",
                    ],
                },
            ],
            "fix_vault_hardening": [
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "MediaVaultSecureViewActivity.kt",
                    "must_contain": [
                        "class MediaVaultSecureViewActivity",
                        "FLAG_SECURE",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "PinFallbackStore.kt",
                    "must_contain": [
                        "KEY_PIN_LOCKOUT_UNTIL",
                        "lockoutSecondsForLevel",
                        "currentLockoutState",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "AppAccessGate.kt",
                    "must_contain": [
                        "PinFallbackStore.currentLockoutState",
                        "verifyPinWithPolicy",
                    ],
                },
            ],
            "fix_startup_intro_regression": [
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "MainActivity.kt",
                    "must_contain": [
                        "HOME_SURFACE_CACHE_PAYLOAD_KEY",
                        "markStartupTrace(\"home_surface_cache_applied\")",
                        "markStartupTrace(\"home_surface_hydration_ready\")",
                        "Keep previously rendered snapshot when hydration fails.",
                    ],
                },
            ],
        }
        for fix_name, rules in fix_marker_rules.items():
            self._apply_marker_rules(fix_name, rules, errors, notes)

        if not any("false positives" in entry for entry in lowered_summaries):
            errors.append("Memory summaries did not include the false-positive tuning issue.")
        if not any("home scan ux split" in entry for entry in lowered_summaries):
            errors.append("Memory summaries did not include the home scan UX split issue.")
        if not any("incident assistant ux fixes" in entry for entry in lowered_summaries):
            errors.append("Memory summaries did not include the incident assistant UX fix issue.")
        if not any("startup ux regression" in entry for entry in lowered_summaries):
            errors.append("Memory summaries did not include the startup UX regression issue.")

        summary_lines = notes
        if errors:
            summary_lines += ["", "Validation errors:"] + [f"- {line}" for line in errors]
        output = "\n".join(summary_lines).strip()
        if len(output) > OUTPUT_EXCERPT_LIMIT:
            output = output[:OUTPUT_EXCERPT_LIMIT] + "\n...[truncated]"

        self._record_supplemental_result(
            name=check_name,
            command="memory sweep + phase/fix artifact validation",
            status="PASS" if not errors else "FAIL",
            return_code=0 if not errors else 4,
            output_excerpt=output or "No output.",
        )

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
        self._run_memory_phase_fix_coverage()
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
