#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB_BIN="${ROOT_DIR}/tools/android/platform-tools/adb"
PACKAGE_NAME="com.realyn.watchdog"
TARGET_FILE="files/dt_play_integrity_verdict.json"

usage() {
  cat <<'EOF'
Usage:
  manage_play_integrity_verdict.sh push <decoded_verdict.json> [--package <app_id>]
  manage_play_integrity_verdict.sh show [--package <app_id>]
  manage_play_integrity_verdict.sh clear [--package <app_id>]

Examples:
  bash scripts/ops/manage_play_integrity_verdict.sh push /tmp/play_integrity_verdict.json
  bash scripts/ops/manage_play_integrity_verdict.sh show
  bash scripts/ops/manage_play_integrity_verdict.sh clear
EOF
}

ACTION="${1:-}"
if [[ -z "${ACTION}" ]]; then
  usage
  exit 1
fi
shift || true

VERDICT_FILE=""
if [[ "${ACTION}" == "push" ]]; then
  VERDICT_FILE="${1:-}"
  if [[ -z "${VERDICT_FILE}" ]]; then
    echo "[-] Missing decoded verdict JSON path for push."
    usage
    exit 1
  fi
  shift || true
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)
      PACKAGE_NAME="${2:-}"
      shift 2
      ;;
    *)
      echo "[-] Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ ! -x "${ADB_BIN}" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi
if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  echo "[-] adb not found. Run scripts/setup/install_platform_tools.sh first."
  exit 1
fi

echo "[+] Using adb: ${ADB_BIN}"
"${ADB_BIN}" start-server >/dev/null

DEVICES="$("${ADB_BIN}" devices -l)"
if ! echo "${DEVICES}" | awk 'NR>1 {print $2}' | grep -q "^device$"; then
  echo "[-] No authorized Android device connected."
  echo "${DEVICES}"
  exit 1
fi

if ! "${ADB_BIN}" shell pm path "${PACKAGE_NAME}" >/dev/null 2>&1; then
  echo "[-] Package not installed: ${PACKAGE_NAME}"
  exit 1
fi

if ! "${ADB_BIN}" shell run-as "${PACKAGE_NAME}" id >/dev/null 2>&1; then
  echo "[-] run-as not available for ${PACKAGE_NAME}. Use a debuggable build."
  exit 1
fi

case "${ACTION}" in
  push)
    if [[ ! -f "${VERDICT_FILE}" ]]; then
      echo "[-] Verdict file not found: ${VERDICT_FILE}"
      exit 1
    fi
    if ! python3 -m json.tool "${VERDICT_FILE}" >/dev/null 2>&1; then
      echo "[-] Verdict file is not valid JSON: ${VERDICT_FILE}"
      exit 1
    fi
    cat "${VERDICT_FILE}" | "${ADB_BIN}" shell "run-as ${PACKAGE_NAME} sh -c 'cat > ${TARGET_FILE}'"
    echo "[+] Verdict pushed to ${PACKAGE_NAME}:${TARGET_FILE}"
    ;;
  show)
    echo "[+] ${PACKAGE_NAME}:${TARGET_FILE}"
    "${ADB_BIN}" exec-out run-as "${PACKAGE_NAME}" sh -c "cat ${TARGET_FILE} 2>/dev/null || echo verdict_file_missing"
    ;;
  clear)
    "${ADB_BIN}" shell "run-as ${PACKAGE_NAME} sh -c 'rm -f ${TARGET_FILE}'"
    echo "[+] Cleared ${PACKAGE_NAME}:${TARGET_FILE}"
    ;;
  *)
    echo "[-] Unsupported action: ${ACTION}"
    usage
    exit 1
    ;;
esac
