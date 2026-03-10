#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MCP_WRAPPER="${WORKSPACE_ROOT}/scripts/ops/dt_hub_mcp_exec.sh"

resolve_zen_root() {
  local candidate
  for candidate in \
    "${DT_ZEN_MCP_ROOT:-}" \
    "${ZEN_MCP_ROOT:-}" \
    "/home/danicous/D_T_Zen_MCP_Workspace" \
    "/home/danicous/Danicous_Troubleshooter/zen-mcp-server" \
    "/home/danicous/trading2/zen-mcp-server"; do
    if [[ -n "${candidate}" && -f "${candidate}/server.py" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

resolve_python() {
  local candidate
  for candidate in \
    "${ZEN_MCP_PYTHON:-}" \
    "/home/danicous/Danicous_Troubleshooter/.venv/bin/python" \
    "/home/danicous/trading2/.venv/bin/python" \
    "python3"; do
    if [[ -n "${candidate}" ]] && command -v "${candidate}" >/dev/null 2>&1; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

ZEN_MCP_ROOT="$(resolve_zen_root)"
ZEN_MCP_PYTHON="$(resolve_python)"
ZEN_MCP_SERVER_PATH="${ZEN_MCP_SERVER_PATH:-${ZEN_MCP_ROOT}/server.py}"

export DT_ZEN_MCP_ROOT="${ZEN_MCP_ROOT}"
export ZEN_MCP_ROOT="${ZEN_MCP_ROOT}"
export ZEN_CODEX_FALLBACK_ENABLED="${ZEN_CODEX_FALLBACK_ENABLED:-1}"
export ZEN_CODEX_WORKDIR="${ZEN_CODEX_WORKDIR:-${WORKSPACE_ROOT}}"
export ZEN_PUTER_BRIDGE_REQUEST_TIMEOUT_SECONDS="${ZEN_PUTER_BRIDGE_REQUEST_TIMEOUT_SECONDS:-45}"
export ZEN_PUTER_BRIDGE_POLL_INTERVAL_SECONDS="${ZEN_PUTER_BRIDGE_POLL_INTERVAL_SECONDS:-0.5}"
export ZEN_CODEX_TIMEOUT_SECONDS="${ZEN_CODEX_TIMEOUT_SECONDS:-60}"

cd "${ZEN_MCP_ROOT}"
exec "${MCP_WRAPPER}" "${ZEN_MCP_PYTHON}" "${ZEN_MCP_SERVER_PATH}" "$@"
