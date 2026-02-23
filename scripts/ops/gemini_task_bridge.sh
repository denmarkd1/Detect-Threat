#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

STATE_DIR="${ROOT_DIR}/state/gemini_bridge"
LOG_FILE="${ROOT_DIR}/logs/gemini_bridge.log"
PROJECT_DIR="${ROOT_DIR}/android-watchdog"
MODEL="gemini-2.5-pro"
SEND_MODE=0
EXEC_CMD=""
FILES_CSV=""
TASK_TEXT=""
TASK_FILE=""

usage() {
  cat <<USAGE
Usage:
  bash scripts/ops/gemini_task_bridge.sh [options]

Options:
  --task TEXT             Task text to send through the bridge
  --task-file PATH        Load task text from a local file
  --files CSV             Comma-separated project-relative file paths
  --project-dir PATH      Project scope path shown in the prompt (default: ${ROOT_DIR}/android-watchdog)
  --model NAME            Gemini model label for metadata (default: gemini-2.5-pro)
  --send                  Execute external command using sanitized prompt
  --exec-cmd COMMAND      External command to execute when --send is used (stdin receives prompt)
  --help                  Show this help

Examples:
  bash scripts/ops/gemini_task_bridge.sh \\
    --task "Refactor watchdog scan screen state handling" \\
    --files "app/src/main/java/com/dtguardian/ui/scan/ScanViewModel.kt"

  bash scripts/ops/gemini_task_bridge.sh \\
    --task-file /tmp/task.txt \\
    --files "app/src/main/java/com/dtguardian/ui/scan/ScanViewModel.kt,app/src/main/res/layout/activity_main.xml" \\
    --send \\
    --exec-cmd "gemini -m gemini-2.5-pro"
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --task)
      TASK_TEXT="$2"
      shift 2
      ;;
    --task-file)
      TASK_FILE="$2"
      shift 2
      ;;
    --files)
      FILES_CSV="$2"
      shift 2
      ;;
    --project-dir)
      PROJECT_DIR="$2"
      shift 2
      ;;
    --model)
      MODEL="$2"
      shift 2
      ;;
    --send)
      SEND_MODE=1
      shift
      ;;
    --exec-cmd)
      EXEC_CMD="$2"
      shift 2
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

if [[ -n "${TASK_FILE}" && ! -f "${TASK_FILE}" ]]; then
  echo "[-] Task file not found: ${TASK_FILE}"
  exit 1
fi

if [[ -z "${TASK_TEXT}" && -n "${TASK_FILE}" ]]; then
  TASK_TEXT="$(cat "${TASK_FILE}")"
fi

if [[ -z "${TASK_TEXT}" ]]; then
  echo "[-] Provide --task or --task-file"
  exit 1
fi

if [[ "${SEND_MODE}" -eq 1 && -z "${EXEC_CMD}" ]]; then
  echo "[-] --send requires --exec-cmd so outbound model use is always explicit."
  exit 1
fi

mkdir -p "${STATE_DIR}" "$(dirname "${LOG_FILE}")"

SANITIZED_TASK_PATH="${STATE_DIR}/task_sanitized.tmp"
PROMPT_PATH="${STATE_DIR}/last_prompt.txt"
PROMPT_META_PATH="${STATE_DIR}/last_prompt_meta.json"
RESPONSE_PATH="${STATE_DIR}/last_response.txt"

cleanup_temp() {
  rm -f "${SANITIZED_TASK_PATH}"
}
trap cleanup_temp EXIT

export TASK_TEXT SANITIZED_TASK_PATH
REDACTIONS="$(python3 - <<'PY'
import os
import re
from pathlib import Path

raw = os.environ["TASK_TEXT"]

patterns = [
    (re.compile(r"(?i)(password\s*[:=]\s*)([^\s]+)"), r"\1<REDACTED_BY_POLICY>"),
    (re.compile(r"(?i)(passphrase\s*[:=]\s*)([^\s]+)"), r"\1<REDACTED_BY_POLICY>"),
    (re.compile(r"(?i)(api[_-]?key\s*[:=]\s*)([^\s]+)"), r"\1<REDACTED_BY_POLICY>"),
    (re.compile(r"(?i)(secret\s*[:=]\s*)([^\s]+)"), r"\1<REDACTED_BY_POLICY>"),
    (re.compile(r"(?i)(token\s*[:=]\s*)([^\s]+)"), r"\1<REDACTED_BY_POLICY>"),
    (re.compile(r"(?i)(authorization:\s*bearer\s+)([^\s]+)"), r"\1<REDACTED_BY_POLICY>"),
    (re.compile(r"AKIA[0-9A-Z]{16}"), "<REDACTED_BY_POLICY>"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.DOTALL), "<REDACTED_PRIVATE_KEY_BLOCK>"),
]

redactions = 0
sanitized = raw
for pattern, replacement in patterns:
    sanitized, count = pattern.subn(replacement, sanitized)
    redactions += count

Path(os.environ["SANITIZED_TASK_PATH"]).write_text(sanitized, encoding="utf-8")
print(redactions)
PY
)"

if [[ "${REDACTIONS}" -gt 0 ]]; then
  echo "[i] Sanitization applied to ${REDACTIONS} sensitive pattern match(es)."
fi

if [[ -n "${FILES_CSV}" ]]; then
  FILE_SCOPE=$(printf '%s' "${FILES_CSV}" | tr ',' '\n' | sed 's/^/- /')
else
  FILE_SCOPE="- (not specified)"
fi

SANITIZED_TASK="$(cat "${SANITIZED_TASK_PATH}")"

cat > "${PROMPT_PATH}" <<EOF_PROMPT
You are assisting with a defensive Android security project.

Workspace root: ${ROOT_DIR}
Android Studio project path: ${PROJECT_DIR}
Security guardrails:
- Defensive security only.
- Do not include offensive techniques, malware behavior, or persistence mechanisms.
- Do not output or request raw passwords, secrets, tokens, or private keys.
- Keep solutions local-first and reproducible via CLI where possible.

Task:
${SANITIZED_TASK}

Preferred file scope:
${FILE_SCOPE}

Response format:
1. Concrete implementation steps in Android Studio.
2. Exact code edits (file paths + snippets).
3. Verification commands and expected outcomes.
EOF_PROMPT

PROMPT_SHA256="$(sha256sum "${PROMPT_PATH}" | awk '{print $1}')"
STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
export STARTED_AT MODEL PROMPT_SHA256 PROMPT_PATH PROMPT_META_PATH PROJECT_DIR SEND_MODE EXEC_CMD FILES_CSV REDACTIONS RESPONSE_PATH
python3 - <<'PY'
import json
import os
from pathlib import Path

meta = {
    "created_at": os.environ["STARTED_AT"],
    "model": os.environ["MODEL"],
    "project_dir": os.environ["PROJECT_DIR"],
    "prompt_path": os.environ["PROMPT_PATH"],
    "prompt_sha256": os.environ["PROMPT_SHA256"],
    "send_mode": bool(int(os.environ["SEND_MODE"])),
    "exec_cmd_set": bool(os.environ["EXEC_CMD"]),
    "files_csv": os.environ["FILES_CSV"],
    "sanitization_applied": bool(int(os.environ["REDACTIONS"])),
    "response_path": os.environ["RESPONSE_PATH"],
}
Path(os.environ["PROMPT_META_PATH"]).write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
PY

printf '%s action=prepare prompt_sha256=%s send_mode=%s sanitization=%s\n' \
  "${STARTED_AT}" "${PROMPT_SHA256}" "${SEND_MODE}" "${REDACTIONS}" >> "${LOG_FILE}"

if [[ "${SEND_MODE}" -eq 0 ]]; then
  echo "[+] Gemini bridge prompt prepared (local only)."
  echo "[i] Prompt path: ${PROMPT_PATH}"
  echo "[i] Metadata: ${PROMPT_META_PATH}"
  echo "[i] Log: ${LOG_FILE}"
  echo
  echo "[i] To execute with explicit outbound command:"
  echo "    bash scripts/ops/gemini_task_bridge.sh --task-file ${TASK_FILE:-<path>} --send --exec-cmd 'gemini -m ${MODEL}'"
  exit 0
fi

set +e
bash -lc "${EXEC_CMD}" < "${PROMPT_PATH}" > "${RESPONSE_PATH}" 2>&1
STATUS=$?
set -e

FINISHED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
printf '%s action=send status=%s prompt_sha256=%s\n' \
  "${FINISHED_AT}" "${STATUS}" "${PROMPT_SHA256}" >> "${LOG_FILE}"

if [[ "${STATUS}" -ne 0 ]]; then
  echo "[-] Gemini bridge command failed with status ${STATUS}."
  echo "[i] Inspect response/output at ${RESPONSE_PATH}"
  exit "${STATUS}"
fi

echo "[+] Gemini bridge command completed successfully."
echo "[i] Prompt path: ${PROMPT_PATH}"
echo "[i] Response path: ${RESPONSE_PATH}"
echo "[i] Metadata: ${PROMPT_META_PATH}"
echo "[i] Log: ${LOG_FILE}"
