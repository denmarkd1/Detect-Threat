#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TRACKER_SCRIPT="${WORKSPACE_ROOT}/D_T_System/scripts/dt_workspace_change_track.sh"
PRECOMMIT_GUARD_SCRIPT="${WORKSPACE_ROOT}/scripts/ops/precommit_guard.sh"

resolve_hub_root() {
  local config_path="${WORKSPACE_ROOT}/D_T_System/satellite_config.json"
  local env_root="${DT_HUB_ROOT:-${D_T_HUB_ROOT:-}}"
  if [[ -n "${env_root}" && -f "${env_root}/D_T_System/src/dt_hub_routing.py" ]]; then
    printf '%s\n' "${env_root}"
    return 0
  fi

  if [[ -f "${config_path}" ]]; then
    local configured_root
    configured_root="$(python3 - <<'PY' "${config_path}"
import json
import sys
from pathlib import Path

config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
value = str(config.get("hub_path", "") or "").strip()
print(value)
PY
)"
    if [[ -n "${configured_root}" && "${configured_root}" != "AUTO_DETECT" && -f "${configured_root}/D_T_System/src/dt_hub_routing.py" ]]; then
      printf '%s\n' "${configured_root}"
      return 0
    fi
  fi

  local candidate
  for candidate in \
    "${HOME}/D_T_SYSTEM_HUB" \
    "${WORKSPACE_ROOT}" \
    "$(cd "${WORKSPACE_ROOT}/.." && pwd)" \
    "$(cd "${WORKSPACE_ROOT}/../.." && pwd)"; do
    if [[ -f "${candidate}/D_T_System/src/dt_hub_routing.py" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  printf '%s\n' "${HOME}/D_T_SYSTEM_HUB"
}

resolve_hooks_dir() {
  local hooks_path_raw
  hooks_path_raw="$(git -C "${WORKSPACE_ROOT}" rev-parse --git-path hooks)"
  if [[ "${hooks_path_raw}" = /* ]]; then
    printf '%s\n' "${hooks_path_raw}"
  else
    printf '%s\n' "${WORKSPACE_ROOT}/${hooks_path_raw}"
  fi
}

write_tracking_hook() {
  local hook_path="$1"
  local hook_name="$2"
  local hub_root="$3"
  cat > "${hook_path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
TRACKER_SCRIPT="${TRACKER_SCRIPT}"
EVENT_NAME="${hook_name}"
EVENT_SOURCE="\$*"
HUB_ROOT="\${DT_HUB_ROOT:-\${D_T_HUB_ROOT:-${hub_root}}}"
WORKSPACE_ROOT="${WORKSPACE_ROOT}"

if [[ -x "\${TRACKER_SCRIPT}" ]]; then
  "\${TRACKER_SCRIPT}" "\${EVENT_NAME}" "\${EVENT_SOURCE}" >/dev/null 2>&1 || true
  exit 0
fi

AUTO_LINK_SCRIPT="\${HUB_ROOT}/D_T_System/scripts/dt_workspace_auto_link.py"
if command -v python3 >/dev/null 2>&1 && [[ -f "\${AUTO_LINK_SCRIPT}" ]]; then
  DT_AUTO_LINK_ENABLE=1 \
  DT_AUTO_LINK_APPLY=1 \
  DT_AUTO_LINK_INSTALL_SATELLITE=1 \
  DT_AUTO_LINK_SYNC_ASSETS=1 \
  python3 "\${AUTO_LINK_SCRIPT}" --workspace "\${WORKSPACE_ROOT}" --quiet >/dev/null 2>&1 || true
fi

exit 0
EOF
  chmod 755 "${hook_path}"
}

write_precommit_hook() {
  local hook_path="$1"
  cat > "${hook_path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
WORKSPACE_ROOT="${WORKSPACE_ROOT}"
PRECOMMIT_GUARD_SCRIPT="${PRECOMMIT_GUARD_SCRIPT}"

if [[ ! -x "\${PRECOMMIT_GUARD_SCRIPT}" ]]; then
  echo "[!] Missing precommit guard script: \${PRECOMMIT_GUARD_SCRIPT}" >&2
  exit 1
fi

declare -a guard_args=()
if [[ "\${DT_PRECOMMIT_INCLUDE_UNSTAGED:-0}" =~ ^(1|true|yes|on)$ ]]; then
  guard_args+=("--include-unstaged")
fi

exec bash "\${PRECOMMIT_GUARD_SCRIPT}" "\${guard_args[@]}"
EOF
  chmod 755 "${hook_path}"
}

main() {
  if ! git -C "${WORKSPACE_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "[!] Not a git repository: ${WORKSPACE_ROOT}" >&2
    exit 1
  fi

  local hooks_dir
  hooks_dir="$(resolve_hooks_dir)"
  mkdir -p "${hooks_dir}"

  local hub_root
  hub_root="$(resolve_hub_root)"

  local hook_name
  for hook_name in post-commit post-merge post-checkout; do
    write_tracking_hook "${hooks_dir}/${hook_name}" "${hook_name}" "${hub_root}"
  done
  write_precommit_hook "${hooks_dir}/pre-commit"

  echo "[+] Installed git hooks in ${hooks_dir}"
  echo "[+] pre-commit -> scripts/ops/precommit_guard.sh"
}

main "$@"
