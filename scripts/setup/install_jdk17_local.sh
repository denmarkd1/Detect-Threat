#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JDK_ROOT="$WORKSPACE_ROOT/tools/jdk"
JDK_VERSION_DIR="$JDK_ROOT/temurin-17.0.18"
JDK_LINK="$JDK_ROOT/temurin-17"
ARCHIVE_TMP="/tmp/temurin17.tar.gz"
ARCHIVE_CACHE="$JDK_ROOT/jdk17.tar.gz"
DOWNLOAD_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"

mkdir -p "$JDK_ROOT"

if [[ -x "$JDK_LINK/bin/java" ]]; then
  echo "[+] JDK 17 already installed at $JDK_LINK"
  "$JDK_LINK/bin/java" -version
  exit 0
fi

if [[ -f "$ARCHIVE_CACHE" ]]; then
  echo "[+] Reusing cached archive: $ARCHIVE_CACHE"
  cp "$ARCHIVE_CACHE" "$ARCHIVE_TMP"
else
  echo "[+] Downloading Temurin JDK 17..."
  curl -fL "$DOWNLOAD_URL" -o "$ARCHIVE_TMP"
  cp "$ARCHIVE_TMP" "$ARCHIVE_CACHE"
fi

echo "[+] Extracting JDK into $JDK_VERSION_DIR"
mkdir -p "$JDK_VERSION_DIR"
tar -xzf "$ARCHIVE_TMP" -C "$JDK_VERSION_DIR" --strip-components=1

ln -sfn "$JDK_VERSION_DIR" "$JDK_LINK"

if [[ ! -x "$JDK_LINK/bin/java" ]]; then
  echo "[-] Installation failed: java binary missing at $JDK_LINK/bin/java"
  exit 2
fi

echo "[+] Installed JDK 17 at $JDK_LINK"
"$JDK_LINK/bin/java" -version
"$JDK_LINK/bin/javac" -version

echo "[i] For current shell: source \"$WORKSPACE_ROOT/scripts/setup/java_env.sh\""
