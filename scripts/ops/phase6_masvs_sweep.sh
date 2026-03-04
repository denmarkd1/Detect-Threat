#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ANDROID_DIR="${ROOT_DIR}/android-watchdog"
LOG_DIR="${ROOT_DIR}/logs/phase6"
TIMESTAMP="$(date -u +"%Y%m%dT%H%M%SZ")"
REPORT_PATH="${LOG_DIR}/masvs_sweep_${TIMESTAMP}.md"
RUN_GRADLE=1

usage() {
  cat <<'USAGE'
Usage: phase6_masvs_sweep.sh [options]

Run Phase 6 MASVS-aligned hardening checks and emit a timestamped markdown report.

Options:
  --skip-gradle     Skip lint/unit-test verification check.
  -h, --help        Show this help text.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-gradle)
      RUN_GRADLE=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[!] Unknown option: $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

mkdir -p "${LOG_DIR}"

echo "# DT Guardian Phase 6 MASVS Verification Sweep" > "${REPORT_PATH}"
echo >> "${REPORT_PATH}"
echo "- Generated (UTC): ${TIMESTAMP}" >> "${REPORT_PATH}"
echo "- Workspace: ${ROOT_DIR}" >> "${REPORT_PATH}"
echo "- Android module: ${ANDROID_DIR}" >> "${REPORT_PATH}"
echo >> "${REPORT_PATH}"
echo "| Control | Description | Status | Evidence |" >> "${REPORT_PATH}"
echo "| --- | --- | --- | --- |" >> "${REPORT_PATH}"

failures=0

append_result() {
  local control="$1"
  local description="$2"
  local status="$3"
  local evidence="$4"
  echo "| ${control} | ${description} | ${status} | ${evidence} |" >> "${REPORT_PATH}"
}

run_rg_presence() {
  local control="$1"
  local description="$2"
  local pattern="$3"
  local target="$4"
  local output
  if output="$(rg -n --max-count 1 --no-heading -S "${pattern}" ${target} 2>/dev/null)"; then
    append_result "${control}" "${description}" "PASS" "\`${output}\`"
  else
    append_result "${control}" "${description}" "FAIL" "pattern not found: \`${pattern}\`"
    failures=$((failures + 1))
  fi
}

run_rg_absence() {
  local control="$1"
  local description="$2"
  local pattern="$3"
  local target="$4"
  local output
  if output="$(rg -n --max-count 1 --no-heading -S "${pattern}" ${target} 2>/dev/null)"; then
    append_result "${control}" "${description}" "FAIL" "unexpected match: \`${output}\`"
    failures=$((failures + 1))
  else
    append_result "${control}" "${description}" "PASS" "no matches for \`${pattern}\`"
  fi
}

run_cmd_check() {
  local control="$1"
  local description="$2"
  shift 2
  local tmp
  tmp="$(mktemp)"
  if "$@" >"${tmp}" 2>&1; then
    local first_line
    first_line="$(head -n 1 "${tmp}" | sed 's/|/\\|/g')"
    append_result "${control}" "${description}" "PASS" "\`${first_line:-command succeeded}\`"
  else
    local first_line
    first_line="$(head -n 1 "${tmp}" | sed 's/|/\\|/g')"
    append_result "${control}" "${description}" "FAIL" "\`${first_line:-command failed}\`"
    failures=$((failures + 1))
  fi
  rm -f "${tmp}"
}

run_rg_presence "MASVS-STORAGE-1" "Credential data uses local encrypted storage path" "CredentialVaultStore|MediaVaultCrypto" "${ROOT_DIR}/android-watchdog/app/src/main/java"
run_rg_presence "MASVS-NETWORK-1" "Breach checks use k-anonymity range endpoint" "api\\.pwnedpasswords\\.com/range" "${ROOT_DIR}/android-watchdog/app/src/main/java/com/realyn/watchdog/CredentialBreachChecker.kt"
run_rg_absence "MASVS-PRIVACY-1" "No obvious raw password logging in app code" "(Log\\.[vdiew]|println\\().*(password|passwd|secret|token)" "${ROOT_DIR}/android-watchdog/app/src/main/java"
run_rg_presence "MASVS-AUTH-1" "Sensitive flows enforce guardian override policy" "GuardianOverridePolicy\\.requestApproval" "${ROOT_DIR}/android-watchdog/app/src/main/java"
run_rg_presence "MASVS-RESILIENCE-1" "Root and Play Integrity posture are assessed" "SecurityScanner\\.currentRootPosture|playStrongIntegrityReady" "${ROOT_DIR}/android-watchdog/app/src/main/java"
run_rg_presence "MASVS-PLATFORM-1" "Modern Android target/compile SDK configured (API 35+)" "targetSdk\\s*=\\s*(3[5-9]|[4-9][0-9])|compileSdk\\s*=\\s*(3[5-9]|[4-9][0-9])" "${ROOT_DIR}/android-watchdog/app/build.gradle.kts"
run_rg_presence "MASVS-OPERATIONS-1" "Watchdog logs are timestamped for audits" "now_utc\\(\\)|captured_at" "${ROOT_DIR}/watchdog/watchdog.py"
run_rg_presence "MASVS-PRIVACY-2" "Public privacy/terms disclosures exist" "Privacy Policy|Terms of Use" "${ROOT_DIR}/docs/privacy.html ${ROOT_DIR}/docs/terms.html"
run_rg_presence "PLAY-RELEASE-1" "Release signing and rollout helper scripts are present" "build_play_release\\.sh|play_release_rollout_checklist" "${ROOT_DIR}/scripts/ops ${ROOT_DIR}/docs/integration"

if (( RUN_GRADLE )); then
  run_cmd_check \
    "MASVS-CODE-1" \
    "Android lint and unit tests pass" \
    bash -lc "cd '${ANDROID_DIR}' && ./gradlew lintDebug testDebugUnitTest"
else
  append_result "MASVS-CODE-1" "Android lint and unit tests pass" "SKIPPED" "--skip-gradle"
fi

echo >> "${REPORT_PATH}"
if (( failures > 0 )); then
  echo "## Result" >> "${REPORT_PATH}"
  echo "FAILED (${failures} control(s) need remediation)." >> "${REPORT_PATH}"
  echo "[!] Phase 6 MASVS sweep failed. Report: ${REPORT_PATH}"
  exit 1
fi

echo "## Result" >> "${REPORT_PATH}"
echo "PASS (all required controls validated in this sweep run)." >> "${REPORT_PATH}"
echo "[+] Phase 6 MASVS sweep passed. Report: ${REPORT_PATH}"
