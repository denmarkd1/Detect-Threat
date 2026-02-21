#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "1) Check runtime watchdog status"
credential-defense watchdog-status

echo "2) Detect browsers"
credential-defense detect-browsers

echo "3) Initialize vault"
credential-defense init

echo "4) Import browser exports from ./imports"
credential-defense import-exports --imports-dir imports

echo "5) Run guided triage session (password checks online)"
credential-defense session --online-password-check

echo "6) Execute queued actions"
credential-defense run-actions
