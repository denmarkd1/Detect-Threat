#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOST="${DT_SUPPORT_HOST:-0.0.0.0}"
PORT="${DT_SUPPORT_PORT:-8787}"
DOCS_ROOT="${DT_SUPPORT_DOCS_ROOT:-$ROOT_DIR/docs}"

cd "$ROOT_DIR"
python3 -m pip install -e . >/dev/null
echo "Starting DT support hub on ${HOST}:${PORT}"
echo "Docs root: ${DOCS_ROOT}"
credential-defense support-server --host "$HOST" --port "$PORT" --docs-root "$DOCS_ROOT"
