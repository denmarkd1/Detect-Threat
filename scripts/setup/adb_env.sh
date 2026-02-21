#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADB_DIR="${ROOT_DIR}/tools/android/platform-tools"

if [[ ! -d "${ADB_DIR}" ]]; then
  echo "[-] ${ADB_DIR} not found. Run scripts/setup/install_platform_tools.sh first."
  return 1 2>/dev/null || exit 1
fi

export PATH="${ADB_DIR}:${PATH}"
echo "[+] PATH updated with ${ADB_DIR}"

