#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PROJECT_DIR="${ROOT_DIR}/android-watchdog"
LOG_FILE="${ROOT_DIR}/logs/android_studio_collab.log"
POLL_INTERVAL=2
USE_INOTIFY=1

usage() {
  cat <<USAGE
Usage:
  bash scripts/ops/watch_android_project_changes.sh [options]

Options:
  --project-dir PATH    Project path to monitor (default: ${ROOT_DIR}/android-watchdog)
  --log-file PATH       Output log file (default: ${ROOT_DIR}/logs/android_studio_collab.log)
  --poll-interval SEC   Polling interval in seconds when inotify is unavailable (default: 2)
  --no-inotify          Force polling mode
  --help                Show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir)
      PROJECT_DIR="$2"
      shift 2
      ;;
    --log-file)
      LOG_FILE="$2"
      shift 2
      ;;
    --poll-interval)
      POLL_INTERVAL="$2"
      shift 2
      ;;
    --no-inotify)
      USE_INOTIFY=0
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

if [[ ! -d "${PROJECT_DIR}" ]]; then
  echo "[-] Project directory not found: ${PROJECT_DIR}"
  exit 1
fi

if ! [[ "${POLL_INTERVAL}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "[-] --poll-interval must be a positive number"
  exit 1
fi

mkdir -p "$(dirname "${LOG_FILE}")"

timestamp() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

log_event() {
  local event="$1"
  local path="$2"
  printf '%s event=%s path=%s\n' "$(timestamp)" "${event}" "${path}" >> "${LOG_FILE}"
}

log_event "watch_start" "${PROJECT_DIR}"

if [[ "${USE_INOTIFY}" -eq 1 ]] && command -v inotifywait >/dev/null 2>&1; then
  log_event "watch_mode" "inotify"
  EXCLUDE_REGEX='(^|/)(\.git|\.gradle|build|captures|\.idea|\.kotlin|tmp)(/|$)'

  inotifywait -m -r \
    -e modify,create,delete,move,attrib \
    --exclude "${EXCLUDE_REGEX}" \
    --format '%w%f|%e' \
    "${PROJECT_DIR}" |
    while IFS='|' read -r changed_path event; do
      rel_path="${changed_path#${PROJECT_DIR}/}"
      if [[ "${rel_path}" == "${changed_path}" ]]; then
        rel_path="."
      fi
      normalized_event="${event// /_}"
      log_event "${normalized_event}" "${rel_path}"
    done
  exit 0
fi

log_event "watch_mode" "polling"

declare -A PREV_SNAPSHOT
declare -A CUR_SNAPSHOT

get_file_mtime_epoch() {
  local file="$1"
  local mtime=""

  if mtime="$(stat -c '%Y' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${mtime}"
    return 0
  fi

  # BSD/macOS fallback.
  if mtime="$(stat -f '%m' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${mtime}"
    return 0
  fi

  return 1
}

scan_files() {
  local -n out_map=$1
  out_map=()

  while IFS= read -r -d '' file; do
    local rel_path
    rel_path="${file#${PROJECT_DIR}/}"

    local mtime
    mtime="$(get_file_mtime_epoch "${file}" || true)"
    if [[ -z "${mtime}" ]]; then
      # File can disappear between `find` and `stat`; skip without crashing watcher.
      continue
    fi
    out_map["${rel_path}"]="${mtime}"
  done < <(
    find "${PROJECT_DIR}" \
      \( -path "${PROJECT_DIR}/.git" \
         -o -path "${PROJECT_DIR}/.gradle" \
         -o -path "${PROJECT_DIR}/.idea" \
         -o -path "${PROJECT_DIR}/.kotlin" \
         -o -path "${PROJECT_DIR}/build" \
         -o -path "${PROJECT_DIR}/captures" \
         -o -path "${PROJECT_DIR}/app/build" \
      \) -prune -o -type f -print0
  )
}

copy_snapshot() {
  local -n src_map=$1
  local -n dst_map=$2
  dst_map=()
  for key in "${!src_map[@]}"; do
    dst_map["${key}"]="${src_map[${key}]}"
  done
}

scan_files PREV_SNAPSHOT

while true; do
  sleep "${POLL_INTERVAL}"
  scan_files CUR_SNAPSHOT

  for path in "${!CUR_SNAPSHOT[@]}"; do
    if [[ -z "${PREV_SNAPSHOT[${path}]+x}" ]]; then
      log_event "CREATE" "${path}"
    elif [[ "${CUR_SNAPSHOT[${path}]}" != "${PREV_SNAPSHOT[${path}]}" ]]; then
      log_event "MODIFY" "${path}"
    fi
  done

  for path in "${!PREV_SNAPSHOT[@]}"; do
    if [[ -z "${CUR_SNAPSHOT[${path}]+x}" ]]; then
      log_event "DELETE" "${path}"
    fi
  done

  copy_snapshot CUR_SNAPSHOT PREV_SNAPSHOT
  CUR_SNAPSHOT=()
done
