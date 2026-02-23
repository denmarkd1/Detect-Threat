#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 1 ]]; then
  echo "Usage: $(basename "$0") <command> [args...]" >&2
  exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNTIME_SYNC_SCRIPT="${WORKSPACE_ROOT}/scripts/ops/dt_hub_runtime_sync.sh"

if [[ ! -x "${RUNTIME_SYNC_SCRIPT}" ]]; then
  echo "[!] Missing runtime sync script: ${RUNTIME_SYNC_SCRIPT}" >&2
  exit 1
fi

RUNTIME_FILE="${DT_RUNTIME_FILE:-}"
if [[ -z "${RUNTIME_FILE}" || ! -f "${RUNTIME_FILE}" ]]; then
  RUNTIME_FILE="$("${RUNTIME_SYNC_SCRIPT}" --quiet)"
fi

if [[ -f "${RUNTIME_FILE}" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "${RUNTIME_FILE}"
  set +a
fi

exec "$@"
