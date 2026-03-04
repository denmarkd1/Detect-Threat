#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

LAUNCHER_SCRIPT="${ROOT_DIR}/scripts/ops/open_android_studio_gui.sh"
DESKTOP_NAME="dt-security-android-studio.desktop"
DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
DESKTOP_PATH="${DESKTOP_DIR}/${DESKTOP_NAME}"
STUDIO_ICON="${ROOT_DIR}/android-studio-panda1-patch1-linux/android-studio/bin/studio.png"

mkdir -p "${DESKTOP_DIR}"

if [[ ! -x "${LAUNCHER_SCRIPT}" ]]; then
  echo "[-] Launcher script is not executable: ${LAUNCHER_SCRIPT}"
  exit 1
fi

if [[ -f "${STUDIO_ICON}" ]]; then
  ICON_PATH="${STUDIO_ICON}"
else
  ICON_PATH="com.android.studio"
fi

cat <<EOF_DESKTOP > "${DESKTOP_PATH}"
[Desktop Entry]
Version=1.1
Type=Application
Name=DT Security Android Studio
Comment=Open Android Studio on the android-watchdog project with monitor
Exec=${LAUNCHER_SCRIPT}
Icon=${ICON_PATH}
Terminal=false
Categories=Development;IDE;Security;
StartupNotify=true
Keywords=android;studio;security
EOF_DESKTOP

chmod 644 "${DESKTOP_PATH}"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "${DESKTOP_DIR}" >/dev/null 2>&1 || true
fi

echo "[+] Desktop launcher installed: ${DESKTOP_PATH}"
echo "[i] Open Show Applications and search for: DT Security Android Studio"
echo "[i] Or run: ${LAUNCHER_SCRIPT}"
