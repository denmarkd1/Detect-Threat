#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA_HOME_DEFAULT="$WORKSPACE_ROOT/tools/jdk/temurin-17"

if [[ -n "${DT_JAVA_HOME_OVERRIDE:-}" ]]; then
  JAVA_HOME="$DT_JAVA_HOME_OVERRIDE"
else
  JAVA_HOME="$JAVA_HOME_DEFAULT"
fi

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "[!] JDK not found at: $JAVA_HOME"
  echo "[i] Run: bash scripts/setup/install_jdk17_local.sh"
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME
case ":$PATH:" in
  *":$JAVA_HOME/bin:"*) ;;
  *) export PATH="$JAVA_HOME/bin:$PATH" ;;
esac

echo "[+] JAVA_HOME=$JAVA_HOME"
java -version
