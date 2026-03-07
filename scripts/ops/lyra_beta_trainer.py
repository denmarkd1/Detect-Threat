#!/usr/bin/env python3
"""Lyra device-backed QA trainer for DT Guardian release readiness."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import textwrap
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
REQUIRED_ROADMAP_REVALIDATION_MARKERS = (
    "## 2A) 2026-03-07 competitor and implementation revalidation",
    "| Phase 2 | PARTIAL |",
    "| Phase 5 | PARTIAL |",
    "| Phase 6 | BLOCKED |",
    "SmartThings-first local readiness",
)
REQUIRED_PHASE1_5_AUDIT_ROWS = {
    "| Phase 1 - Architecture and data model |": "PASS",
    "| Phase 2 - Smart-home connector MVP |": "PARTIAL",
    "| Phase 3 - VPN broker and service linking |": "PASS",
    "| Phase 4 - Digital key risk guardrails |": "PASS",
    "| Phase 5 - Competitive parity+ |": "PARTIAL",
}
SMART_HOME_CONFIG_PATHS = (
    ROOT_DIR / "config" / "workspace_settings.json",
    ANDROID_DIR / "app" / "src" / "main" / "assets" / "workspace_settings.json",
)
AGENTS_PATH = ROOT_DIR / "AGENTS.md"
SATELLITE_CONFIG_PATH = ROOT_DIR / "D_T_System" / "satellite_config.json"
COMPETITOR_GAP_DOC_PATH = ROOT_DIR / "docs" / "integration" / "competitor_gap_analysis_2026-02-21.md"
PYTHON_DEFAULT_CONFIG_PATH = ROOT_DIR / "src" / "credential_defense" / "config.py"
INTEGRATION_MESH_CONFIG_PATH = ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "IntegrationMeshConfig.kt"
SMARTTHINGS_CONNECTOR_PATH = ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "SmartThingsConnector.kt"
MAIN_ACTIVITY_PATH = ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "MainActivity.kt"
STRINGS_PATH = ANDROID_DIR / "app" / "src" / "main" / "res" / "values" / "strings.xml"
TUTORIAL_DOC_PATH = ROOT_DIR / "docs" / "integration" / "phase6_tutorial_overlay_2026-03-04.md"
LYRA_TRAINER_DOC_PATH = ROOT_DIR / "docs" / "integration" / "phase6_lyra_qa_trainer_2026-03-04.md"
POLICY_DISCLOSURE_DOC_PATH = ROOT_DIR / "docs" / "integration" / "phase6_policy_play_disclosure_review_2026-03-04.md"
PRICING_PACKAGING_DOC_PATH = ROOT_DIR / "docs" / "integration" / "phase6_pricing_packaging_update_2026-03-04.md"
EXPECTED_WALLET_SETUP_URI = "https://support.google.com/wallet/answer/12060041?hl=en"
HUB_WORKSPACE_ROOT = ROOT_DIR.parent / "Danicous_Troubleshooter"
DARK_CODER_ROOT = ROOT_DIR.parent / "Dark_Coder"
DARK_CODER_BACKEND_ROOT = DARK_CODER_ROOT / "local-ai-coding-assistant" / "backend"
SECURITY_SATELLITE_ROUTER_PATH = ROOT_DIR / "D_T_System" / "dt_satellite_router.py"
DARK_CODER_SATELLITE_ROUTER_PATH = DARK_CODER_ROOT / "D_T_System" / "dt_satellite_router.py"


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

    def _run_python_probe(
        self,
        name: str,
        cwd: Path,
        code: str,
        timeout: int = 300,
    ) -> CheckResult:
        return self._run(name, ["python3", "-c", code], cwd=cwd, timeout=timeout)

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
            for marker in REQUIRED_ROADMAP_REVALIDATION_MARKERS:
                if marker not in roadmap_payload:
                    errors.append(f"Roadmap revalidation marker missing: {marker}")
            notes.append("Roadmap phase headers (Phase 0-6) verified.")

        if not PHASE1_5_AUDIT_PATH.exists():
            errors.append(f"Missing Phase 1-5 audit file: {PHASE1_5_AUDIT_PATH}")
        else:
            audit_payload = _safe_read_text(PHASE1_5_AUDIT_PATH)
            for marker, expected_status in REQUIRED_PHASE1_5_AUDIT_ROWS.items():
                matching_lines = [line for line in audit_payload.splitlines() if marker in line]
                if not matching_lines:
                    errors.append(f"Phase 1-5 audit row missing: {marker}")
                    continue
                expected_token = f"| {expected_status} |"
                if not any(expected_token in line for line in matching_lines):
                    errors.append(
                        f"Phase 1-5 audit row has unexpected status (expected {expected_status}): {marker}"
                    )
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
                        "showRecommendedSettingsDecisionDialog",
                        "startAutoRecommendedSettingsFlow",
                        "showManualRecommendedSettingsGuideDialog",
                        "showRecommendedSettingsPermissionDialog",
                        "continueWithContainmentOrGuidance",
                        "OemStepPack",
                        "resolveOemStepPack",
                        "startupTapTargets",
                        "incident_assistant_recommended_open_with_overlay",
                        "incident_assistant_recommended_tap_pack_template",
                        "WatchdogConfig.EXTRA_INCIDENT_OVERLAY_COMPACT_MODE",
                        "incident_guidance_why_template",
                        "incident_guidance_signal_map_title",
                        "incident_assistant_section_recommended",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "IncidentGuideOverlayService.kt",
                    "must_contain": [
                        "class IncidentGuideOverlayService",
                        "WatchdogConfig.EXTRA_INCIDENT_OVERLAY_COMPACT_MODE",
                        "renderCompactStep",
                        "incident_overlay_focus_label",
                        "incident_overlay_complete_step",
                        "incident_overlay_finish",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "realyn" / "watchdog" / "WatchdogConfig.kt",
                    "must_contain": [
                        "ACTION_SHOW_INCIDENT_OVERLAY",
                        "ACTION_HIDE_INCIDENT_OVERLAY",
                        "EXTRA_INCIDENT_OVERLAY_COMPACT_MODE",
                    ],
                },
                {
                    "path": ANDROID_DIR / "app" / "src" / "main" / "AndroidManifest.xml",
                    "must_contain": [
                        ".IncidentGuideOverlayService",
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
                        "incident_assistant_recommended_decision_title",
                        "incident_assistant_recommended_decision_apply_message",
                        "incident_assistant_recommended_permission_title",
                        "incident_assistant_recommended_manual_title",
                        "incident_assistant_recommended_open_with_overlay",
                        "incident_assistant_recommended_tap_pack_template",
                        "incident_overlay_focus_label",
                        "incident_overlay_complete_step",
                        "incident_overlay_finish",
                        "incident_assistant_screen_subtitle",
                        "incident_assistant_back_to_scan_results",
                        "home_tutorial_step_timeline_window_body",
                        "home_tutorial_step_incident_assistant_body",
                        "home_tutorial_step_incident_assistant_hint",
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
        if not any(
            ("incident assistant ux fixes" in entry) or ("recommended best settings" in entry)
            for entry in lowered_summaries
        ):
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

    def _run_integration_mesh_retail_readiness(self) -> None:
        check_name = "integration_mesh_retail_readiness"
        notes: List[str] = []
        errors: List[str] = []

        for path in SMART_HOME_CONFIG_PATHS:
            if not path.exists():
                errors.append(f"Missing smart-home config path: {path}")
                continue
            payload = _safe_read_text(path)
            if '"google_home"' in payload:
                errors.append(f"Active smart-home rollout still advertises google_home in {path}")
            if EXPECTED_WALLET_SETUP_URI not in payload:
                errors.append(f"Current Google Wallet digital key setup URI missing in {path}")
        notes.append(f"Validated {len(SMART_HOME_CONFIG_PATHS)} smart-home rollout config path(s).")

        if not INTEGRATION_MESH_CONFIG_PATH.exists():
            errors.append(f"Missing integration mesh config source: {INTEGRATION_MESH_CONFIG_PATH}")
        else:
            integration_mesh_payload = _safe_read_text(INTEGRATION_MESH_CONFIG_PATH)
            if EXPECTED_WALLET_SETUP_URI not in integration_mesh_payload:
                errors.append(
                    "IntegrationMeshConfig.kt is not using the current Google Wallet digital key setup URI."
                )

        if not PYTHON_DEFAULT_CONFIG_PATH.exists():
            errors.append(f"Missing Python default config source: {PYTHON_DEFAULT_CONFIG_PATH}")
        else:
            python_default_payload = _safe_read_text(PYTHON_DEFAULT_CONFIG_PATH)
            if '"google_home"' in python_default_payload:
                errors.append(
                    "credential_defense.config still advertises google_home in the default smart-home connector list."
                )
            if EXPECTED_WALLET_SETUP_URI not in python_default_payload:
                errors.append(
                    "credential_defense.config is not using the current Google Wallet digital key setup URI."
                )

        if not SMARTTHINGS_CONNECTOR_PATH.exists():
            errors.append(f"Missing SmartThings connector source: {SMARTTHINGS_CONNECTOR_PATH}")
        else:
            smartthings_payload = _safe_read_text(SMARTTHINGS_CONNECTOR_PATH)
            simulation_markers = (
                'proofHash = createHash("$connectorId|$ownerId|smart_home|$now|smartthings")',
                'val status = if (isClientInstalled) "connected" else "unknown"',
                "val deviceCount = estimateConnectedDevices(",
            )
            if all(marker in smartthings_payload for marker in simulation_markers):
                errors.append(
                    "SmartThingsConnector remains simulation-backed: local consent artifact, installed-app health, "
                    "and synthetic device counts are still present."
                )
            else:
                notes.append("SmartThingsConnector simulation markers are no longer fully present.")

        wording_rules = {
            STRINGS_PATH: [
                "SmartThings-first local readiness snapshot",
                "read-only local snapshot",
            ],
            MAIN_ACTIVITY_PATH: [
                "local SmartThings-first assessment",
                "current local snapshot",
            ],
            TUTORIAL_DOC_PATH: [
                "SmartThings-first local readiness only",
            ],
            POLICY_DISCLOSURE_DOC_PATH: [
                "Home Risk is limited to SmartThings-first local readiness",
                "| Smart-home connectors |",
                "| PARTIAL |",
            ],
            PRICING_PACKAGING_DOC_PATH: [
                "SmartThings-first Home Risk readiness layer with local audit timeline.",
            ],
        }
        for path, required_tokens in wording_rules.items():
            if not path.exists():
                errors.append(f"Missing wording validation file: {path}")
                continue
            payload = _safe_read_text(path)
            for token in required_tokens:
                if token not in payload:
                    errors.append(f"Required wording token missing from {path}: {token}")
        notes.append(f"Validated wording alignment across {len(wording_rules)} file(s).")

        output_lines = notes
        if errors:
            output_lines += ["", "Validation errors:"] + [f"- {line}" for line in errors]
        output = "\n".join(output_lines).strip()
        if len(output) > OUTPUT_EXCERPT_LIMIT:
            output = output[:OUTPUT_EXCERPT_LIMIT] + "\n...[truncated]"

        self._record_supplemental_result(
            name=check_name,
            command="integration mesh retail readiness validation",
            status="PASS" if not errors else "FAIL",
            return_code=0 if not errors else 5,
            output_excerpt=output or "No output.",
        )

    def _run_family_role_canonicalization(self) -> None:
        check_name = "family_role_canonicalization"
        notes: List[str] = []
        errors: List[str] = []

        for path in SMART_HOME_CONFIG_PATHS:
            if not path.exists():
                errors.append(f"Missing family-role config path: {path}")
                continue
            try:
                payload = json.loads(_safe_read_text(path))
            except json.JSONDecodeError as exc:
                errors.append(f"Unable to parse {path}: {exc}")
                continue

            owners = payload.get("owners", [])
            if not isinstance(owners, list):
                owners = []
            owner_by_id = {
                str(item.get("id", "")).strip().lower(): item
                for item in owners
                if isinstance(item, dict)
            }
            for required_owner in ("parent", "child"):
                if required_owner not in owner_by_id:
                    errors.append(f"{path}: missing canonical owner `{required_owner}`")

            child_owner = owner_by_id.get("child", {})
            aliases = child_owner.get("legacy_aliases", []) if isinstance(child_owner, dict) else []
            if not isinstance(aliases, list) or "son" not in {
                str(alias).strip().lower() for alias in aliases
            }:
                errors.append(f"{path}: child owner must retain `son` as a legacy alias")

            integration_mesh = payload.get("integration_mesh", {})
            if not isinstance(integration_mesh, dict):
                integration_mesh = {}
            feature_flags = integration_mesh.get("feature_flags", {})
            if not isinstance(feature_flags, dict):
                feature_flags = {}
            for flag_name in ("smart_home_connector", "vpn_provider_connector", "digital_key_risk_adapter"):
                flag = feature_flags.get(flag_name, {})
                allowlist = flag.get("owner_allowlist", []) if isinstance(flag, dict) else []
                normalized_allowlist = {
                    str(role).strip().lower() for role in allowlist if str(role).strip()
                }
                if "son" in normalized_allowlist:
                    errors.append(f"{path}: `{flag_name}` still treats `son` as a first-class owner_allowlist value")

            rollout = integration_mesh.get("rollout", {})
            stages = rollout.get("stages", []) if isinstance(rollout, dict) else []
            if not isinstance(stages, list):
                stages = []
            for stage in stages:
                if not isinstance(stage, dict):
                    continue
                owner_roles = stage.get("owner_roles", [])
                normalized_roles = {
                    str(role).strip().lower() for role in owner_roles if str(role).strip()
                }
                if "son" in normalized_roles:
                    errors.append(
                        f"{path}: rollout stage `{stage.get('name', 'unknown')}` still treats `son` as canonical"
                    )
        notes.append(f"Validated canonical owner config across {len(SMART_HOME_CONFIG_PATHS)} runtime config path(s).")

        source_rules = {
            AGENTS_PATH: {
                "must_contain": [
                    "`parent` and `child`",
                    "`son` only as a legacy alias",
                ],
                "must_not_contain": ["`parent` and `son`"],
            },
            SATELLITE_CONFIG_PATH: {
                "must_contain": ['"child"'],
                "must_not_contain": ['"son"'],
            },
            COMPETITOR_GAP_DOC_PATH: {
                "must_contain": [
                    "(`parent`, `child`)",
                    "Legacy `son` aliases remain accepted",
                ],
                "must_not_contain": ["(`parent`, `son`)"],
            },
            TUTORIAL_DOC_PATH: {
                "must_contain": [
                    "Canonical family-role language",
                    "`parent/child`",
                    "legacy alias `son`",
                ],
            },
            LYRA_TRAINER_DOC_PATH: {
                "must_contain": [
                    "family-role canonicalization",
                    "`parent`/`child`",
                    "`son` preserved only as a legacy alias",
                ],
            },
        }
        for path, rules in source_rules.items():
            if not path.exists():
                errors.append(f"Missing family-role documentation path: {path}")
                continue
            payload = _safe_read_text(path)
            for token in rules.get("must_contain", []):
                if token not in payload:
                    errors.append(f"Required family-role token missing from {path}: {token}")
            for token in rules.get("must_not_contain", []):
                if token in payload:
                    errors.append(f"Deprecated family-role token still present in {path}: {token}")

        if not PYTHON_DEFAULT_CONFIG_PATH.exists():
            errors.append(f"Missing Python default config source: {PYTHON_DEFAULT_CONFIG_PATH}")
        else:
            python_payload = _safe_read_text(PYTHON_DEFAULT_CONFIG_PATH)
            if '"legacy_aliases": ["son"]' not in python_payload:
                errors.append("credential_defense.config must preserve `son` as a legacy alias in default owners.")
            if '"owner_allowlist": ["parent", "child", "son"]' in python_payload:
                errors.append("credential_defense.config still treats `son` as a first-class owner_allowlist value.")

        if not INTEGRATION_MESH_CONFIG_PATH.exists():
            errors.append(f"Missing integration mesh config source: {INTEGRATION_MESH_CONFIG_PATH}")
        else:
            integration_payload = _safe_read_text(INTEGRATION_MESH_CONFIG_PATH)
            for token in (
                'ownerAllowlist = listOf("parent", "child")',
                'ownerRoles = listOf("parent", "child")',
                "CredentialPolicy.canonicalOwnerId(ownerRole)",
            ):
                if token not in integration_payload:
                    errors.append(f"IntegrationMeshConfig.kt missing canonical family-role token: {token}")
            for token in (
                'ownerAllowlist = listOf("parent", "child", "son")',
                'ownerRoles = listOf("parent", "child", "son")',
            ):
                if token in integration_payload:
                    errors.append(f"IntegrationMeshConfig.kt still contains deprecated family-role token: {token}")

        output_lines = notes
        if errors:
            output_lines += ["", "Validation errors:"] + [f"- {line}" for line in errors]
        output = "\n".join(output_lines).strip()
        if len(output) > OUTPUT_EXCERPT_LIMIT:
            output = output[:OUTPUT_EXCERPT_LIMIT] + "\n...[truncated]"

        self._record_supplemental_result(
            name=check_name,
            command="family role canonicalization validation",
            status="PASS" if not errors else "FAIL",
            return_code=0 if not errors else 6,
            output_excerpt=output or "No output.",
        )

    def _run_dt_scope_confidence_ladder(self) -> None:
        scoped_review_query = (
            "Perform read-only review of modules (systems, D_T_System, scripts, analysis, tests, gui) "
            "and report findings; constraints: no code changes."
        )
        family_normalization_query = (
            "Normalize family role handling to parent/child across docs, config, Android/Python defaults, "
            "and Lyra trainer guidance while preserving legacy aliases for compatibility."
        )
        cross_system_alignment_query = (
            "Normalize and align the D_T hub, security satellite, Dark_Coder backend, "
            "bootstrap_router.py, dt_satellite_router.py, and the Lyra trainer ladder "
            "from basic through expert coverage."
        )

        clarification_payload_json = json.dumps(
            {
                "resolution": {
                    "completion_status": {
                        "status": "clarification_needed",
                        "clarifying_questions": ["What scope should D_T operate on?"],
                    },
                    "certainty_assessment": {
                        "level": "low",
                        "confidence_percentage": 45.0,
                        "assessment_reasoning": "Initial low-confidence routing result.",
                    },
                    "code_review_handoff": "# Existing handoff",
                }
            }
        )
        local_core_route_payload_json = json.dumps(
            {
                "completion_status": {
                    "status": "clarification_needed",
                    "clarifying_questions": ["What scope should D_T operate on?"],
                },
                "certainty_level": "low",
                "confidence_percentage": 45.0,
                "code_review_handoff": "# Existing handoff",
            }
        )

        self._run_python_probe(
            "dt_scope_ladder_basic_clarification",
            cwd=DARK_CODER_BACKEND_ROOT,
            code=textwrap.dedent(
                """
                import json
                from src.dt_integration.backend_dt_system_core import build_clarification_policy

                policy = build_clarification_policy("Help with this issue.")
                assert policy["mode"] == "strict_clarification_first", policy
                assert policy["scope_signal_count"] == 0, policy
                print(json.dumps(policy, indent=2))
                """
            ).strip(),
        )

        self._run_python_probe(
            "dt_scope_ladder_hub_scoped_review",
            cwd=HUB_WORKSPACE_ROOT,
            code=textwrap.dedent(
                f"""
                import json
                import sys
                import tempfile
                from pathlib import Path

                sys.path.insert(0, str(Path.cwd()))
                from D_T_System.src.dt_system_core import DTSystemCore

                with tempfile.TemporaryDirectory() as tmp_dir:
                    core = DTSystemCore(Path(tmp_dir))
                    resolution = core.process_issue({scoped_review_query!r}, auto_execute=False)

                payload = {{
                    "status": resolution.completion_status.get("status"),
                    "confidence": resolution.certainty_assessment.confidence_percentage,
                }}
                assert payload["status"] == "ready_for_execution", payload
                assert payload["confidence"] >= 60.0, payload
                print(json.dumps(payload, indent=2))
                """
            ).strip(),
        )

        self._run_python_probe(
            "dt_scope_ladder_hub_normalization_alignment",
            cwd=HUB_WORKSPACE_ROOT,
            code=textwrap.dedent(
                f"""
                import json
                import sys
                import tempfile
                from pathlib import Path

                sys.path.insert(0, str(Path.cwd()))
                from D_T_System.src.dt_system_core import DTSystemCore

                with tempfile.TemporaryDirectory() as tmp_dir:
                    core = DTSystemCore(Path(tmp_dir))
                    resolution = core.process_issue({family_normalization_query!r}, auto_execute=False)

                payload = {{
                    "status": resolution.completion_status.get("status"),
                    "confidence": resolution.certainty_assessment.confidence_percentage,
                }}
                assert payload["status"] == "ready_for_execution", payload
                assert payload["confidence"] >= 60.0, payload
                print(json.dumps(payload, indent=2))
                """
            ).strip(),
        )

        self._run_python_probe(
            "dt_scope_ladder_security_local_core_upgrade",
            cwd=ROOT_DIR,
            code=textwrap.dedent(
                f"""
                import importlib
                import json
                import sys
                from pathlib import Path

                sys.path.insert(0, str(Path.cwd()))
                core = importlib.import_module("systems.D_T_System.src.dt_system_core")
                payload = json.loads({local_core_route_payload_json!r})
                resolution = core._resolution_from_route_payload(
                    {cross_system_alignment_query!r},
                    None,
                    payload,
                    automation_requested=False,
                    workspace_root=Path.cwd(),
                )

                result = {{
                    "status": resolution.completion_status.get("status"),
                    "assumption_mode": resolution.completion_status.get("assumption_mode"),
                    "todo_list": resolution.todo_list,
                }}
                assert result["status"] == "ready_for_execution_assumptions", result
                assert result["assumption_mode"] is True, result
                assert result["todo_list"], result
                print(json.dumps(result, indent=2))
                """
            ).strip(),
        )

        self._run_python_probe(
            "dt_scope_ladder_security_satellite_upgrade",
            cwd=ROOT_DIR,
            code=textwrap.dedent(
                f"""
                import importlib.util
                import json
                from pathlib import Path

                module_path = Path({str(SECURITY_SATELLITE_ROUTER_PATH)!r})
                spec = importlib.util.spec_from_file_location("security_dt_satellite_router_probe", module_path)
                assert spec and spec.loader, module_path
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)

                payload = json.loads({clarification_payload_json!r})
                upgraded = module.upgrade_actionable_clarification_payload(
                    payload,
                    {cross_system_alignment_query!r},
                )
                completion = upgraded["resolution"]["completion_status"]
                certainty = upgraded["resolution"]["certainty_assessment"]
                handoff = upgraded["resolution"]["code_review_handoff"]
                result = {{
                    "status": completion.get("status"),
                    "assumption_mode": completion.get("assumption_mode"),
                    "confidence": certainty.get("confidence_percentage"),
                    "questions": completion.get("clarifying_questions"),
                }}
                assert result["status"] == "ready_for_execution_assumptions", result
                assert result["assumption_mode"] is True, result
                assert float(result["confidence"]) >= 68.0, result
                assert "Assumption Mode" in handoff, handoff
                print(json.dumps(result, indent=2))
                """
            ).strip(),
        )

        self._run_python_probe(
            "dt_scope_ladder_dark_coder_backend_alignment",
            cwd=DARK_CODER_BACKEND_ROOT,
            code=textwrap.dedent(
                f"""
                import json
                from src.dt_integration.backend_dt_system_core import build_backend_codereview_handoff

                handoff = build_backend_codereview_handoff(
                    query_text={cross_system_alignment_query!r},
                    session_id="lyra-scope-ladder",
                    dt_available=True,
                    workspace_root={str(DARK_CODER_ROOT)!r},
                )
                policy = handoff["clarification_policy"]
                result = {{
                    "mode": policy.get("mode"),
                    "actionable_signal_count": policy.get("actionable_signal_count"),
                    "scope_signal_count": policy.get("scope_signal_count"),
                }}
                assert result["mode"] == "actionable_assumption_first", result
                assert int(result["actionable_signal_count"]) > 0, result
                assert int(result["scope_signal_count"]) > 0, result
                print(json.dumps(result, indent=2))
                """
            ).strip(),
        )

        self._run_python_probe(
            "dt_scope_ladder_dark_coder_satellite_upgrade",
            cwd=DARK_CODER_ROOT,
            code=textwrap.dedent(
                f"""
                import importlib.util
                import json
                from pathlib import Path

                module_path = Path({str(DARK_CODER_SATELLITE_ROUTER_PATH)!r})
                spec = importlib.util.spec_from_file_location("dark_coder_dt_satellite_router_probe", module_path)
                assert spec and spec.loader, module_path
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)

                payload = json.loads({clarification_payload_json!r})
                upgraded = module.upgrade_actionable_clarification_payload(
                    payload,
                    {cross_system_alignment_query!r},
                )
                completion = upgraded["resolution"]["completion_status"]
                certainty = upgraded["resolution"]["certainty_assessment"]
                result = {{
                    "status": completion.get("status"),
                    "assumption_mode": completion.get("assumption_mode"),
                    "confidence": certainty.get("confidence_percentage"),
                    "source_mode": completion.get("source_mode"),
                }}
                assert result["status"] == "ready_for_execution_assumptions", result
                assert result["assumption_mode"] is True, result
                assert float(result["confidence"]) >= 68.0, result
                assert result["source_mode"] == "satellite_actionable_scope_upgrade", result
                print(json.dumps(result, indent=2))
                """
            ).strip(),
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
        self._run_integration_mesh_retail_readiness()
        self._run_family_role_canonicalization()
        self._run_dt_scope_confidence_ladder()
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
