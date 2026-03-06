#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 1 ]]; then
  echo "Usage: $(basename "$0") <command> [args...]" >&2
  exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNTIME_SYNC_SCRIPT="${WORKSPACE_ROOT}/scripts/ops/dt_hub_runtime_sync.sh"

if [[ ! -x "${RUNTIME_SYNC_SCRIPT}" ]]; then
  echo "[!] Missing runtime sync script: ${RUNTIME_SYNC_SCRIPT}" >&2
  exit 1
fi

RUNTIME_FILE="${DT_RUNTIME_FILE:-}"
if [[ -z "${RUNTIME_FILE}" || ! -f "${RUNTIME_FILE}" ]]; then
  RUNTIME_FILE="$("${RUNTIME_SYNC_SCRIPT}" --quiet)"
fi

if [[ -f "${RUNTIME_FILE}" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "${RUNTIME_FILE}"
  set +a
fi

is_truthy() {
  local raw="${1:-}"
  case "${raw,,}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

canonical_dir() {
  local candidate="$1"
  if [[ -z "${candidate}" || ! -d "${candidate}" ]]; then
    return 1
  fi
  (cd "${candidate}" && pwd)
}

is_git_repo() {
  local repo_root="$1"
  git -C "${repo_root}" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

resolve_git_toplevel() {
  local candidate="$1"
  local base_path=""
  if [[ -z "${candidate}" ]]; then
    return 1
  fi
  if [[ -d "${candidate}" ]]; then
    base_path="$(canonical_dir "${candidate}" || true)"
  elif [[ -e "${candidate}" ]]; then
    base_path="$(canonical_dir "$(dirname "${candidate}")" || true)"
  fi
  if [[ -z "${base_path}" ]]; then
    return 1
  fi
  git -C "${base_path}" rev-parse --show-toplevel 2>/dev/null
}

is_cloud_remote_repo() {
  local repo_root="$1"
  local cloud_pattern="${DT_HUB_AUTOCOMMIT_CLOUD_REMOTE_PATTERN:-(github\\.com|gitlab\\.com|bitbucket\\.org|dev\\.azure\\.com|visualstudio\\.com|sourcehut\\.org)}"
  git -C "${repo_root}" remote -v 2>/dev/null | rg -qi "${cloud_pattern}"
}

local_preference_rank() {
  local repo_root="$1"
  local preferred_roots="${DT_HUB_LOCAL_PREFERRED_ROOTS:-${HOME}:/mnt:/media:/srv}"
  local rank=0
  local entry=""
  IFS=':' read -r -a ROOTS <<< "${preferred_roots}"
  for entry in "${ROOTS[@]}"; do
    [[ -z "${entry}" ]] && continue
    local canonical=""
    canonical="$(canonical_dir "${entry}" || true)"
    [[ -z "${canonical}" ]] && continue
    if [[ "${repo_root}" == "${canonical}"* ]]; then
      echo "${rank}"
      return 0
    fi
    rank=$((rank + 1))
  done
  echo 999
}

build_ordered_repo_list() {
  local candidate=""
  local repo_root=""
  local local_rank=""
  local cloud_rank=0
  local path_len=0
  local score_line=""

  declare -a scored_lines=()
  for candidate in "${TARGET_PATHS[@]}"; do
    repo_root="$(resolve_git_toplevel "${candidate}" || true)"
    [[ -z "${repo_root}" ]] && continue
    repo_root="$(canonical_dir "${repo_root}" || true)"
    [[ -z "${repo_root}" ]] && continue
    if [[ -n "${REPO_SEEN[${repo_root}]:-}" ]]; then
      continue
    fi
    REPO_SEEN["${repo_root}"]=1
    UNIQUE_REPOS+=("${repo_root}")
    if is_git_repo "${repo_root}" && repo_is_clean "${repo_root}"; then
      PRE_CLEAN["${repo_root}"]=1
    else
      PRE_CLEAN["${repo_root}"]=0
    fi
    local_rank="$(local_preference_rank "${repo_root}")"
    cloud_rank=0
    if is_cloud_remote_repo "${repo_root}"; then
      cloud_rank=1
    fi
    path_len=${#repo_root}
    score_line="$(printf "%04d|%d|%05d|%s" "${local_rank}" "${cloud_rank}" "${path_len}" "${repo_root}")"
    scored_lines+=("${score_line}")
  done

  if [[ "${#scored_lines[@]}" -eq 0 ]]; then
    return 0
  fi

  mapfile -t ORDERED_REPOS < <(
    printf '%s\n' "${scored_lines[@]}" | sort | cut -d'|' -f4-
  )
}

repo_is_clean() {
  local repo_root="$1"
  [[ -z "$(git -C "${repo_root}" status --porcelain --untracked-files=all 2>/dev/null)" ]]
}

maybe_auto_commit_repo() {
  local repo_root="$1"
  local pre_clean="$2"
  local command_label="$3"
  local require_clean="${DT_HUB_AUTOCOMMIT_REQUIRE_CLEAN_BASE:-1}"
  local sensitive_pattern="${DT_HUB_AUTOCOMMIT_SENSITIVE_PATH_PATTERN:-(^|/)(\\.env(\\..*)?$|.*\\.(pem|key|p12|jks|keystore)$|id_rsa$|id_dsa$|.*credentials.*|.*secrets?.*)}"
  local subject="${DT_HUB_AUTOCOMMIT_SUBJECT:-chore(dt-hub): auto-commit command-applied changes}"
  local now_utc
  local commit_body
  local head_sha
  local staged_files

  if ! is_git_repo "${repo_root}"; then
    return 0
  fi

  if is_truthy "${require_clean}" && [[ "${pre_clean}" != "1" ]]; then
    echo "[dt-hub] Auto-commit skipped for ${repo_root}: repository was not clean before command run." >&2
    return 0
  fi

  if repo_is_clean "${repo_root}"; then
    return 0
  fi

  if ! git -C "${repo_root}" add -A; then
    echo "[dt-hub] Auto-commit failed in ${repo_root}: could not stage changes." >&2
    return 0
  fi
  staged_files="$(git -C "${repo_root}" diff --cached --name-only || true)"
  if [[ -z "${staged_files}" ]]; then
    return 0
  fi
  if printf '%s\n' "${staged_files}" | rg -q "${sensitive_pattern}"; then
    echo "[dt-hub] Auto-commit blocked in ${repo_root}: sensitive path pattern matched staged files." >&2
    return 0
  fi
  if git -C "${repo_root}" diff --cached --quiet; then
    return 0
  fi
  echo "[dt-hub] Auto-commit candidate in ${repo_root}: $(printf '%s' "${staged_files}" | tr '\n' ' ')" >&2

  now_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  commit_body=$(
    cat <<EOF_BODY
Command: ${command_label}
UTC: ${now_utc}
Policy: DT hub auto-commit after applied change
EOF_BODY
  )

  if git -C "${repo_root}" commit -m "${subject}" -m "${commit_body}" >/dev/null 2>&1; then
    head_sha="$(git -C "${repo_root}" rev-parse --short HEAD 2>/dev/null || true)"
    echo "[dt-hub] Auto-commit created in ${repo_root} @ ${head_sha:-unknown}" >&2
  else
    echo "[dt-hub] Auto-commit failed in ${repo_root}; changes remain in working tree." >&2
  fi
}

AUTOCOMMIT_ENABLED="${DT_HUB_AUTOCOMMIT_ENABLED:-1}"
AUTOCOMMIT_SCOPE="${DT_HUB_AUTOCOMMIT_SCOPE:-dynamic_local_first}"
HUB_ROOT="${DT_HUB_ROOT:-${D_T_HUB_ROOT:-${WORKSPACE_ROOT}}}"
COMMAND_LABEL="$(basename "$1")"
AUTOCOMMIT_DYNAMIC_CONTINUE="${DT_HUB_AUTOCOMMIT_DYNAMIC_CONTINUE:-0}"

declare -a TARGET_PATHS=()
case "${AUTOCOMMIT_SCOPE}" in
  hub_only)
    TARGET_PATHS+=("${HUB_ROOT}")
    ;;
  workspace_only)
    TARGET_PATHS+=("${WORKSPACE_ROOT}")
    ;;
  hub_and_workspace)
    TARGET_PATHS+=("${HUB_ROOT}" "${WORKSPACE_ROOT}")
    ;;
  dynamic_local_first|dynamic_all)
    TARGET_PATHS+=("${HUB_ROOT}" "${WORKSPACE_ROOT}" "${PWD}")
    for arg in "$@"; do
      if [[ -e "${arg}" ]]; then
        TARGET_PATHS+=("${arg}")
      fi
    done
    ;;
  *)
    echo "[dt-hub] Unknown DT_HUB_AUTOCOMMIT_SCOPE='${AUTOCOMMIT_SCOPE}', defaulting to dynamic_local_first." >&2
    TARGET_PATHS+=("${HUB_ROOT}" "${WORKSPACE_ROOT}" "${PWD}")
    ;;
esac

declare -a UNIQUE_REPOS=()
declare -a ORDERED_REPOS=()
declare -A REPO_SEEN=()
declare -A PRE_CLEAN=()
build_ordered_repo_list

set +e
"$@"
COMMAND_EXIT=$?
set -e

if is_truthy "${AUTOCOMMIT_ENABLED}" && [[ "${COMMAND_EXIT}" -eq 0 ]]; then
  if [[ "${AUTOCOMMIT_SCOPE}" == "dynamic_local_first" ]]; then
    require_clean="${DT_HUB_AUTOCOMMIT_REQUIRE_CLEAN_BASE:-1}"
    for repo_root in "${ORDERED_REPOS[@]}"; do
      if is_truthy "${require_clean}" && [[ "${PRE_CLEAN[${repo_root}]:-0}" != "1" ]]; then
        continue
      fi
      if repo_is_clean "${repo_root}"; then
        continue
      fi
      maybe_auto_commit_repo "${repo_root}" "${PRE_CLEAN[${repo_root}]:-0}" "${COMMAND_LABEL}"
      if ! is_truthy "${AUTOCOMMIT_DYNAMIC_CONTINUE}"; then
        break
      fi
    done
  else
    for repo_root in "${ORDERED_REPOS[@]}"; do
      maybe_auto_commit_repo "${repo_root}" "${PRE_CLEAN[${repo_root}]:-0}" "${COMMAND_LABEL}"
    done
  fi
fi

exit "${COMMAND_EXIT}"
