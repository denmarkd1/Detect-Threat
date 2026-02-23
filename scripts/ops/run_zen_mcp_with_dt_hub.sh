#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MCP_WRAPPER="${WORKSPACE_ROOT}/scripts/ops/dt_hub_mcp_exec.sh"

ZEN_MCP_PYTHON="${ZEN_MCP_PYTHON:-/home/danicous/trading2/.venv/bin/python}"
ZEN_MCP_SERVER_PATH="${ZEN_MCP_SERVER_PATH:-/home/danicous/trading2/zen-mcp-server/server.py}"

exec "${MCP_WRAPPER}" "${ZEN_MCP_PYTHON}" "${ZEN_MCP_SERVER_PATH}" "$@"
