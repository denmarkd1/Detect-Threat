#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PROJECT_DIR="${ROOT_DIR}/android-watchdog"
STUDIO_DIR="${ROOT_DIR}/android-studio-panda1-patch1-linux/android-studio"
MONITOR_LOG="${ROOT_DIR}/logs/android_studio_collab.log"
STATE_PATH="${ROOT_DIR}/state/android_studio_collab_session.json"
ENABLE_MONITOR=1

usage() {
  cat <<USAGE
Usage:
  bash scripts/ops/start_android_studio_collab.sh [options]

Options:
  --project-dir PATH   Android project path (default: ${ROOT_DIR}/android-watchdog)
  --studio-dir PATH    Android Studio root path (default: ${ROOT_DIR}/android-studio-panda1-patch1-linux/android-studio)
  --monitor-log PATH   Collaboration monitor log path (default: ${ROOT_DIR}/logs/android_studio_collab.log)
  --no-monitor         Skip launching the file-change monitor
  --help               Show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir)
      PROJECT_DIR="$2"
      shift 2
      ;;
    --studio-dir)
      STUDIO_DIR="$2"
      shift 2
      ;;
    --monitor-log)
      MONITOR_LOG="$2"
      shift 2
      ;;
    --no-monitor)
      ENABLE_MONITOR=0
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "[-] Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

STUDIO_BIN="${STUDIO_DIR}/bin/studio.sh"
WATCH_SCRIPT="${SCRIPT_DIR}/watch_android_project_changes.sh"

if [[ ! -d "${PROJECT_DIR}" ]]; then
  echo "[-] Android project not found: ${PROJECT_DIR}"
  exit 1
fi

if [[ ! -x "${STUDIO_BIN}" ]]; then
  echo "[-] Android Studio launcher not executable: ${STUDIO_BIN}"
  echo "[i] Pass --studio-dir if Studio is in another location."
  exit 1
fi

if [[ "${ENABLE_MONITOR}" -eq 1 && ! -x "${WATCH_SCRIPT}" ]]; then
  echo "[-] Monitor script missing or not executable: ${WATCH_SCRIPT}"
  exit 1
fi

mkdir -p "${ROOT_DIR}/logs" "${ROOT_DIR}/state"

MONITOR_PID=""
if [[ "${ENABLE_MONITOR}" -eq 1 ]]; then
  nohup "${WATCH_SCRIPT}" \
    --project-dir "${PROJECT_DIR}" \
    --log-file "${MONITOR_LOG}" \
    > "${ROOT_DIR}/logs/android_studio_collab_monitor.stdout.log" 2>&1 &
  MONITOR_PID="$!"
  sleep 1
  if ! kill -0 "${MONITOR_PID}" 2>/dev/null; then
    echo "[-] Collaboration monitor failed to start. See logs/android_studio_collab_monitor.stdout.log"
    exit 1
  fi
fi

cleanup_monitor_on_error() {
  if [[ -n "${MONITOR_PID}" ]] && kill -0 "${MONITOR_PID}" 2>/dev/null; then
    kill "${MONITOR_PID}" 2>/dev/null || true
    wait "${MONITOR_PID}" 2>/dev/null || true
  fi
}

nohup "${STUDIO_BIN}" "${PROJECT_DIR}" \
  > "${ROOT_DIR}/logs/android_studio.stdout.log" 2>&1 &
STUDIO_PID="$!"
sleep 1
if ! kill -0 "${STUDIO_PID}" 2>/dev/null; then
  cleanup_monitor_on_error
  echo "[-] Android Studio failed to start. See logs/android_studio.stdout.log"
  exit 1
fi

if [[ -d "${STUDIO_DIR}/plugins/gemini" ]]; then
  GEMINI_PLUGIN="present"
else
  GEMINI_PLUGIN="missing"
fi

STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
export STARTED_AT PROJECT_DIR STUDIO_BIN STUDIO_PID MONITOR_PID MONITOR_LOG GEMINI_PLUGIN STATE_PATH
python3 - <<'PY'
import json
import os
from pathlib import Path

state = {
    "started_at": os.environ["STARTED_AT"],
    "project_dir": os.environ["PROJECT_DIR"],
    "studio_bin": os.environ["STUDIO_BIN"],
    "studio_pid": int(os.environ["STUDIO_PID"]),
    "monitor_pid": int(os.environ["MONITOR_PID"]) if os.environ["MONITOR_PID"] else None,
    "monitor_log": os.environ["MONITOR_LOG"],
    "gemini_plugin": os.environ["GEMINI_PLUGIN"],
}
Path(os.environ["STATE_PATH"]).write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
PY

echo "[+] Android Studio collaboration session started"
echo "[i] Project: ${PROJECT_DIR}"
echo "[i] Studio PID: ${STUDIO_PID}"
if [[ -n "${MONITOR_PID}" ]]; then
  echo "[i] Monitor PID: ${MONITOR_PID}"
  echo "[i] Monitor log: ${MONITOR_LOG}"
else
  echo "[i] Monitor: disabled (--no-monitor)"
fi
echo "[i] Gemini plugin: ${GEMINI_PLUGIN}"
echo "[i] Session state: ${STATE_PATH}"

echo
echo "[i] Watch live changes:"
echo "    tail -f ${MONITOR_LOG}"
