#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADB_BIN="${ROOT_DIR}/tools/android/platform-tools/adb"
PACKAGE_NAME="com.realyn.watchdog"
SOURCE="manual"

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") grant [--package <id>] [--source <manual|device_allowlist>]
  $(basename "$0") revoke [--package <id>]
  $(basename "$0") status [--package <id>]

Examples:
  $(basename "$0") grant
  $(basename "$0") grant --source device_allowlist
  $(basename "$0") revoke --package com.realyn.watchdog
  $(basename "$0") status
USAGE
}

if [[ ! -x "${ADB_BIN}" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi

if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  echo "[-] adb not found. Run scripts/setup/install_platform_tools.sh first."
  exit 1
fi

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

ACTION="$1"
shift

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)
      PACKAGE_NAME="${2:-}"
      shift 2
      ;;
    --source)
      SOURCE="${2:-manual}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[-] Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ "$ACTION" != "grant" && "$ACTION" != "revoke" && "$ACTION" != "status" ]]; then
  echo "[-] Invalid action: ${ACTION}"
  usage
  exit 1
fi

if [[ "$ACTION" == "grant" ]]; then
  case "$SOURCE" in
    manual|device_allowlist) ;;
    *)
      echo "[-] Invalid --source value: ${SOURCE}. Use manual or device_allowlist."
      exit 1
      ;;
  esac
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

if ! "${ADB_BIN}" shell pm path "${PACKAGE_NAME}" >/dev/null 2>&1; then
  echo "[-] Package not found on device: ${PACKAGE_NAME}"
  exit 3
fi

# run-as requires debug-enabled app install.
if ! "${ADB_BIN}" shell run-as "${PACKAGE_NAME}" id >/dev/null 2>&1; then
  echo "[-] run-as failed for ${PACKAGE_NAME}. This usually means the installed app is not a debug build."
  echo "[i] Install debug APK for entitlement ops, or use in-app billing/lifetime path for production builds."
  exit 4
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT
PREFS_FILE="${TMP_DIR}/dt_scanner_prefs.xml"
UPDATED_FILE="${TMP_DIR}/dt_scanner_prefs.updated.xml"

"${ADB_BIN}" exec-out run-as "${PACKAGE_NAME}" sh -c 'cat shared_prefs/dt_scanner_prefs.xml 2>/dev/null || true' > "${PREFS_FILE}"

if [[ ! -s "${PREFS_FILE}" ]]; then
  cat > "${PREFS_FILE}" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
</map>
XML
fi

if [[ "${ACTION}" == "status" ]]; then
  echo
  echo "[+] Current entitlement status"
  rg -n "pricing_lifetime_pro_enabled|pricing_lifetime_pro_source|pricing_selected_plan" "${PREFS_FILE}" || true
  exit 0
fi

python3 - "$PREFS_FILE" "$UPDATED_FILE" "$ACTION" "$SOURCE" <<'PY'
import sys
import xml.etree.ElementTree as ET

in_path, out_path, action, source = sys.argv[1:5]

parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
tree = ET.parse(in_path, parser=parser)
root = tree.getroot()
if root.tag != "map":
    raise SystemExit("Unexpected shared_prefs root; expected <map>")

def find_child(tag, name):
    for child in root.findall(tag):
        if child.get("name") == name:
            return child
    return None

def upsert_bool(name, value):
    node = find_child("boolean", name)
    if node is None:
        node = ET.SubElement(root, "boolean")
        node.set("name", name)
    node.set("value", "true" if value else "false")

def upsert_string(name, value):
    node = find_child("string", name)
    if node is None:
        node = ET.SubElement(root, "string")
        node.set("name", name)
    node.text = value

if action == "grant":
    upsert_bool("pricing_lifetime_pro_enabled", True)
    upsert_string("pricing_lifetime_pro_source", source)
elif action == "revoke":
    upsert_bool("pricing_lifetime_pro_enabled", False)
    upsert_string("pricing_lifetime_pro_source", "none")
else:
    raise SystemExit(f"Unsupported action: {action}")

ET.indent(tree, space="    ")
tree.write(out_path, encoding="utf-8", xml_declaration=True)
PY

cat "${UPDATED_FILE}" | "${ADB_BIN}" shell "run-as ${PACKAGE_NAME} sh -c 'cat > shared_prefs/dt_scanner_prefs.xml'"
"${ADB_BIN}" shell am force-stop "${PACKAGE_NAME}" >/dev/null 2>&1 || true

echo
if [[ "${ACTION}" == "grant" ]]; then
  echo "[+] Lifetime Pro granted for ${PACKAGE_NAME} (source=${SOURCE})."
else
  echo "[+] Lifetime Pro revoked for ${PACKAGE_NAME}."
fi

"${ADB_BIN}" exec-out run-as "${PACKAGE_NAME}" cat shared_prefs/dt_scanner_prefs.xml | rg -n "pricing_lifetime_pro_enabled|pricing_lifetime_pro_source|pricing_selected_plan" || true
