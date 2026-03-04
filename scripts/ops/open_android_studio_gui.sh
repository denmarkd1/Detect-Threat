#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PROJECT_DIR="${ROOT_DIR}/android-watchdog"
STUDIO_DIR="${ROOT_DIR}/android-studio-panda1-patch1-linux/android-studio"
MONITOR_LOG="${ROOT_DIR}/logs/android_studio_collab.log"

extra_args=()
if [[ "${1:-}" == "--no-monitor" ]]; then
  extra_args=("--no-monitor")
fi

"${SCRIPT_DIR}/start_android_studio_collab.sh" \
  --project-dir "${PROJECT_DIR}" \
  --studio-dir "${STUDIO_DIR}" \
  --monitor-log "${MONITOR_LOG}" \
  "${extra_args[@]}"
