#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
INSTALLER="${WORKSPACE_ROOT}/D_T_System/scripts/install_dt_workspace_tracking_hooks.sh"

if [[ ! -f "${INSTALLER}" ]]; then
  echo "[!] Missing hook installer: ${INSTALLER}" >&2
  exit 1
fi

exec bash "${INSTALLER}" "$@"
