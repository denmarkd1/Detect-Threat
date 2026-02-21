#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADB_BIN="${ROOT_DIR}/tools/android/platform-tools/adb"

if [[ ! -x "${ADB_BIN}" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi

if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  echo "[-] adb not found. Run scripts/setup/install_platform_tools.sh first."
  exit 1
fi

echo "[+] Using adb: ${ADB_BIN}"
"${ADB_BIN}" start-server >/dev/null
DEVICES="$("${ADB_BIN}" devices -l)"
echo "${DEVICES}"

CONNECTED_COUNT="$(echo "${DEVICES}" | awk 'NR>1 && $2=="device"{count++} END{print count+0}')"
if [[ "${CONNECTED_COUNT}" -eq 0 ]]; then
  echo
  echo "[-] No authorized Android device detected."
  echo "[i] Confirm USB debugging is enabled and the trust prompt is accepted on the phone."
  exit 2
fi

echo
echo "[+] Android device link is active."

