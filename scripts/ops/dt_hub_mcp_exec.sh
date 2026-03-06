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

is_git_repo() {
  local repo_root="$1"
  git -C "${repo_root}" rev-parse --is-inside-work-tree >/dev/null 2>&1
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
AUTOCOMMIT_SCOPE="${DT_HUB_AUTOCOMMIT_SCOPE:-hub_only}"
HUB_ROOT="${DT_HUB_ROOT:-${D_T_HUB_ROOT:-${WORKSPACE_ROOT}}}"
COMMAND_LABEL="$(basename "$1")"

declare -a TARGET_REPOS=()
case "${AUTOCOMMIT_SCOPE}" in
  hub_only)
    TARGET_REPOS+=("${HUB_ROOT}")
    ;;
  workspace_only)
    TARGET_REPOS+=("${WORKSPACE_ROOT}")
    ;;
  hub_and_workspace)
    TARGET_REPOS+=("${HUB_ROOT}" "${WORKSPACE_ROOT}")
    ;;
  *)
    echo "[dt-hub] Unknown DT_HUB_AUTOCOMMIT_SCOPE='${AUTOCOMMIT_SCOPE}', defaulting to hub_only." >&2
    TARGET_REPOS+=("${HUB_ROOT}")
    ;;
esac

declare -a UNIQUE_REPOS=()
declare -A REPO_SEEN=()
declare -A PRE_CLEAN=()
for candidate in "${TARGET_REPOS[@]}"; do
  if [[ -z "${candidate}" || ! -d "${candidate}" ]]; then
    continue
  fi
  canonical="$(cd "${candidate}" && pwd)"
  if [[ -n "${REPO_SEEN[${canonical}]:-}" ]]; then
    continue
  fi
  REPO_SEEN["${canonical}"]=1
  UNIQUE_REPOS+=("${canonical}")
  if is_git_repo "${canonical}" && repo_is_clean "${canonical}"; then
    PRE_CLEAN["${canonical}"]=1
  else
    PRE_CLEAN["${canonical}"]=0
  fi
done

set +e
"$@"
COMMAND_EXIT=$?
set -e

if is_truthy "${AUTOCOMMIT_ENABLED}" && [[ "${COMMAND_EXIT}" -eq 0 ]]; then
  for repo_root in "${UNIQUE_REPOS[@]}"; do
    maybe_auto_commit_repo "${repo_root}" "${PRE_CLEAN[${repo_root}]:-0}" "${COMMAND_LABEL}"
  done
fi

exit "${COMMAND_EXIT}"
