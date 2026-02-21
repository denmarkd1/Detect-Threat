#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
credential-defense watchdog-daemon --interval 60

