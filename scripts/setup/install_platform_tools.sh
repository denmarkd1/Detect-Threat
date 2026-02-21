#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TOOLS_DIR="${ROOT_DIR}/tools/android"
ZIP_PATH="${TOOLS_DIR}/platform-tools-latest-linux.zip"
URL="https://dl.google.com/android/repository/platform-tools-latest-linux.zip"

mkdir -p "${TOOLS_DIR}"

echo "[+] Downloading Android platform-tools..."
curl -fsSL "${URL}" -o "${ZIP_PATH}"

echo "[+] Unpacking platform-tools into ${TOOLS_DIR}..."
unzip -o -q "${ZIP_PATH}" -d "${TOOLS_DIR}"

if [[ ! -x "${TOOLS_DIR}/platform-tools/adb" ]]; then
  echo "[-] adb was not installed correctly."
  exit 1
fi

echo "[+] Installed local adb at:"
echo "    ${TOOLS_DIR}/platform-tools/adb"
echo
echo "[i] For this shell session run:"
echo "    source \"${ROOT_DIR}/scripts/setup/adb_env.sh\""

