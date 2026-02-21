#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADB_BIN="${ROOT_DIR}/tools/android/platform-tools/adb"
PACKAGE_NAME="com.realyn.watchdog"
MAIN_ACTIVITY="com.realyn.watchdog.MainActivity"
OVERLAY_SERVICE="com.realyn.watchdog.CredentialOverlayService"
APK_PATH="${ROOT_DIR}/android-watchdog/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -x "${ADB_BIN}" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi

if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  echo "[-] adb not found. Run: bash scripts/setup/install_platform_tools.sh"
  exit 1
fi

"${ADB_BIN}" start-server >/dev/null
DEVICES="$("${ADB_BIN}" devices -l)"
echo "${DEVICES}"
CONNECTED_COUNT="$(echo "${DEVICES}" | awk 'NR>1 && $2=="device"{count++} END{print count+0}')"
if [[ "${CONNECTED_COUNT}" -eq 0 ]]; then
  echo
  echo "[-] No authorized Android device detected."
  echo "[i] Connect phone, enable USB debugging, and accept trust prompt."
  exit 2
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "[-] APK not found at ${APK_PATH}"
  echo "[i] Build first: source scripts/setup/java_env.sh && cd android-watchdog && ./gradlew assembleDebug"
  exit 3
fi

echo "[+] Installing APK: ${APK_PATH}"
"${ADB_BIN}" install -r "${APK_PATH}"

echo "[+] Launching main activity"
"${ADB_BIN}" shell am start -n "${PACKAGE_NAME}/${MAIN_ACTIVITY}" >/dev/null
sleep 1

echo "[i] Credential Defense Center is non-exported (expected); open it manually from app UI."

echo "[+] Overlay permission state (best effort)"
"${ADB_BIN}" shell appops get "${PACKAGE_NAME}" SYSTEM_ALERT_WINDOW || true

echo "[+] Opening overlay permission settings"
"${ADB_BIN}" shell am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION -d "package:${PACKAGE_NAME}" >/dev/null || true

echo "[+] Attempting to start overlay service with validation payload"
"${ADB_BIN}" shell am startservice \
  -n "${PACKAGE_NAME}/${OVERLAY_SERVICE}" \
  -a "com.realyn.watchdog.action.SHOW_OVERLAY" \
  --es "com.realyn.watchdog.extra.OVERLAY_PASSWORD" "ValidationOnly-DoNotReuse-123!" \
  --es "com.realyn.watchdog.extra.OVERLAY_TARGET_URL" "https://example.com/security" >/dev/null || true
sleep 1

echo "[i] Overlay service is non-exported (expected); trigger overlay from app UI after granting permission."

echo "[+] Overlay service status (best effort)"
"${ADB_BIN}" shell dumpsys activity services | grep -n "${OVERLAY_SERVICE}" || true

echo "[+] Queue file status (debug build run-as best effort)"
"${ADB_BIN}" shell run-as "${PACKAGE_NAME}" sh -c 'ls -l files/credential_action_queue.json 2>/dev/null || echo queue_file_missing' || true

echo
cat <<'CHECKLIST'
Manual validation checklist:
1. In the app, open Credential Defense Center.
2. Enter Service, Username, and URL; tap "Queue rotation action".
3. Verify queue count increments and entry appears in queue summary.
4. Tap "Generate local strong password" then "Copy generated password".
5. Grant overlay permission in settings screen.
6. Tap "Start overlay assistant" and confirm overlay appears above other apps.
7. From overlay, test "Copy generated password" and "Open target site/app URL".
8. Return to app and verify no crashes; rescan main watchdog screen.
CHECKLIST

echo

echo "[+] Validation command flow complete."
