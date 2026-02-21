#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ANDROID_DIR="${ROOT_DIR}/android-watchdog"

required_env=(
  DT_RELEASE_KEYSTORE_PATH
  DT_RELEASE_STORE_PASSWORD
  DT_RELEASE_KEY_ALIAS
  DT_RELEASE_KEY_PASSWORD
)

missing=0
for name in "${required_env[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "[-] Missing env var: ${name}"
    missing=1
  fi
done

if [[ "${missing}" -ne 0 ]]; then
  echo
  echo "[i] Export signing vars first, e.g.:"
  echo "    export DT_RELEASE_KEYSTORE_PATH=/absolute/path/to/upload-keystore.jks"
  echo "    export DT_RELEASE_STORE_PASSWORD='***'"
  echo "    export DT_RELEASE_KEY_ALIAS='upload'"
  echo "    export DT_RELEASE_KEY_PASSWORD='***'"
  exit 1
fi

if [[ ! -f "${DT_RELEASE_KEYSTORE_PATH}" ]]; then
  echo "[-] Keystore not found at DT_RELEASE_KEYSTORE_PATH=${DT_RELEASE_KEYSTORE_PATH}"
  exit 2
fi

pushd "${ANDROID_DIR}" >/dev/null

echo "[+] Building signed release artifacts (AAB + APK)"
./gradlew clean app:bundleRelease app:assembleRelease

AAB_PATH="${ANDROID_DIR}/app/build/outputs/bundle/release/app-release.aab"
APK_PATH="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"

if [[ ! -f "${AAB_PATH}" ]]; then
  echo "[-] Expected AAB not found: ${AAB_PATH}"
  popd >/dev/null
  exit 3
fi

if [[ -f "${APK_PATH}" ]]; then
  echo "[+] APK: ${APK_PATH}"
  sha256sum "${APK_PATH}"
fi

echo "[+] AAB: ${AAB_PATH}"
sha256sum "${AAB_PATH}"

echo
keytool -list -v \
  -keystore "${DT_RELEASE_KEYSTORE_PATH}" \
  -storepass "${DT_RELEASE_STORE_PASSWORD}" \
  -alias "${DT_RELEASE_KEY_ALIAS}" \
  -keypass "${DT_RELEASE_KEY_PASSWORD}" | rg "Alias name:|SHA1:|SHA256:"

popd >/dev/null

echo
 echo "[+] Release build complete. Upload the AAB to Google Play Console internal testing first."
