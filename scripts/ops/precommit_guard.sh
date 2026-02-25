#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

REPO_ROOT="${WORKSPACE_ROOT}"
INCLUDE_UNSTAGED=0
RUN_ANDROID_BUILD=1
RUN_PY_COMPILE=1
WARN_BINARY_MB=5
FAIL_BINARY_MB=25

usage() {
  cat <<'EOF'
Usage: precommit_guard.sh [options]

Deterministic pre-commit fallback checks for this workspace when zen precommit is unavailable/timing out.

Options:
  --repo <path>             Git repository root (default: workspace root)
  --include-unstaged        Include unstaged files in checks (default: staged only)
  --skip-android-build      Skip Android compile check
  --skip-python-compile     Skip Python syntax check
  --warn-binary-mb <int>    Warn for staged binary files at/above this size (default: 5)
  --fail-binary-mb <int>    Fail for staged binary files at/above this size (default: 25)
  -h, --help                Show this help text
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      REPO_ROOT="$(cd "${2:?missing value for --repo}" && pwd)"
      shift 2
      ;;
    --include-unstaged)
      INCLUDE_UNSTAGED=1
      shift
      ;;
    --skip-android-build)
      RUN_ANDROID_BUILD=0
      shift
      ;;
    --skip-python-compile)
      RUN_PY_COMPILE=0
      shift
      ;;
    --warn-binary-mb)
      WARN_BINARY_MB="${2:?missing value for --warn-binary-mb}"
      shift 2
      ;;
    --fail-binary-mb)
      FAIL_BINARY_MB="${2:?missing value for --fail-binary-mb}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[!] Unknown option: $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

if ! git -C "${REPO_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "[!] Not a git repository: ${REPO_ROOT}" >&2
  exit 1
fi

if ! [[ "${WARN_BINARY_MB}" =~ ^[0-9]+$ && "${FAIL_BINARY_MB}" =~ ^[0-9]+$ ]]; then
  echo "[!] --warn-binary-mb and --fail-binary-mb must be positive integers." >&2
  exit 1
fi

if (( WARN_BINARY_MB > FAIL_BINARY_MB )); then
  echo "[!] --warn-binary-mb cannot be greater than --fail-binary-mb." >&2
  exit 1
fi

echo "[+] precommit_guard: repository=${REPO_ROOT}"
if (( INCLUDE_UNSTAGED )); then
  echo "[+] precommit_guard: scope=staged+unstaged"
else
  echo "[+] precommit_guard: scope=staged"
fi

declare -a staged_files=()
declare -a unstaged_files=()
declare -a untracked_files=()
declare -A changed_map=()
declare -A staged_map=()
declare -A staged_binary_map=()

mapfile -t staged_files < <(git -C "${REPO_ROOT}" diff --cached --name-only --diff-filter=ACMR)
for path in "${staged_files[@]}"; do
  [[ -n "${path}" ]] || continue
  changed_map["${path}"]=1
  staged_map["${path}"]=1
done

if (( INCLUDE_UNSTAGED )); then
  mapfile -t unstaged_files < <(git -C "${REPO_ROOT}" diff --name-only --diff-filter=ACMR)
  for path in "${unstaged_files[@]}"; do
    [[ -n "${path}" ]] || continue
    changed_map["${path}"]=1
  done
  mapfile -t untracked_files < <(git -C "${REPO_ROOT}" ls-files --others --exclude-standard)
  for path in "${untracked_files[@]}"; do
    [[ -n "${path}" ]] || continue
    changed_map["${path}"]=1
  done
fi

mapfile -t staged_binary_rows < <(git -C "${REPO_ROOT}" diff --cached --numstat --diff-filter=ACMR)
for row in "${staged_binary_rows[@]}"; do
  IFS=$'\t' read -r added removed path <<<"${row}"
  [[ -n "${path:-}" ]] || continue
  if [[ "${added}" == "-" || "${removed}" == "-" ]]; then
    staged_binary_map["${path}"]=1
  fi
done

if (( ${#changed_map[@]} == 0 )); then
  echo "[+] precommit_guard: no matching changes found."
  exit 0
fi

mapfile -t changed_files < <(printf '%s\n' "${!changed_map[@]}" | sort)

echo "[+] precommit_guard: changed files (${#changed_files[@]}):"
printf '    %s\n' "${changed_files[@]}"

KEY_ASSIGN_PATTERN='(password|passwd|secret|token|api[_-]?key|private[_-]?key)[[:space:]]*[:=]'
VALUE_ASSIGN_PATTERN='(password|passwd|secret|token|api[_-]?key|private[_-]?key)[[:space:]]*[:=][[:space:]]*[^[:space:],;]+'

tmp_file="$(mktemp)"
cleanup() {
  rm -f "${tmp_file}"
}
trap cleanup EXIT

declare -a secret_hits=()
scan_text_for_secret_assignments() {
  local relative_path="$1"
  local mode="$2"
  : > "${tmp_file}"

  if [[ "${mode}" == "index" ]]; then
    if ! git -C "${REPO_ROOT}" show ":${relative_path}" > "${tmp_file}" 2>/dev/null; then
      return 0
    fi
  else
    local full_path="${REPO_ROOT}/${relative_path}"
    [[ -f "${full_path}" ]] || return 0
    cat "${full_path}" > "${tmp_file}"
  fi

  if [[ ! -s "${tmp_file}" ]]; then
    return 0
  fi
  if ! LC_ALL=C grep -Iq . "${tmp_file}"; then
    return 0
  fi
  if ! grep -Eiq "${VALUE_ASSIGN_PATTERN}" "${tmp_file}"; then
    return 0
  fi

  local key_summary
  key_summary="$(
    grep -Eio "${KEY_ASSIGN_PATTERN}" "${tmp_file}" \
      | sed -E 's/[[:space:]]*[:=][[:space:]]*$//' \
      | tr '[:upper:]' '[:lower:]' \
      | sort -u \
      | paste -sd, -
  )"
  secret_hits+=("${relative_path}:${key_summary:-unknown}")
}

for path in "${changed_files[@]}"; do
  if [[ -n "${staged_map["${path}"]:-}" ]]; then
    if [[ -n "${staged_binary_map["${path}"]:-}" ]]; then
      continue
    fi
    scan_text_for_secret_assignments "${path}" "index"
  elif (( INCLUDE_UNSTAGED )); then
    scan_text_for_secret_assignments "${path}" "worktree"
  fi
done

failures=0

if (( ${#secret_hits[@]} > 0 )); then
  echo "[!] precommit_guard: potential secret assignments detected (token names only):"
  printf '    %s\n' "${secret_hits[@]}"
  echo "[!] precommit_guard: refusing commit until reviewed."
  failures=$((failures + 1))
else
  echo "[+] precommit_guard: secret assignment scan passed."
fi

declare -a binary_warn=()
declare -a binary_fail=()

for path in "${!staged_binary_map[@]}"; do
  full_path="${REPO_ROOT}/${path}"
  if [[ ! -f "${full_path}" ]]; then
    continue
  fi
  size_bytes="$(stat -c%s "${full_path}" 2>/dev/null || echo 0)"
  size_mb=$(( (size_bytes + 1048575) / 1048576 ))
  if (( size_mb >= FAIL_BINARY_MB )); then
    binary_fail+=("${path}:${size_mb}MB")
  elif (( size_mb >= WARN_BINARY_MB )); then
    binary_warn+=("${path}:${size_mb}MB")
  fi
done

if (( ${#binary_warn[@]} > 0 )); then
  echo "[!] precommit_guard: large staged binaries (warning threshold ${WARN_BINARY_MB}MB):"
  printf '    %s\n' "${binary_warn[@]}"
fi

if (( ${#binary_fail[@]} > 0 )); then
  echo "[!] precommit_guard: staged binaries exceeded fail threshold ${FAIL_BINARY_MB}MB:"
  printf '    %s\n' "${binary_fail[@]}"
  failures=$((failures + 1))
fi

if (( RUN_PY_COMPILE )); then
  declare -a changed_py=()
  for path in "${changed_files[@]}"; do
    if [[ "${path}" == *.py && -f "${REPO_ROOT}/${path}" ]]; then
      changed_py+=("${REPO_ROOT}/${path}")
    fi
  done
  if (( ${#changed_py[@]} > 0 )); then
    echo "[+] precommit_guard: running python syntax check (${#changed_py[@]} files)..."
    if ! python3 -m py_compile "${changed_py[@]}"; then
      echo "[!] precommit_guard: python syntax check failed."
      failures=$((failures + 1))
    else
      echo "[+] precommit_guard: python syntax check passed."
    fi
  fi
fi

if (( RUN_ANDROID_BUILD )); then
  android_trigger=0
  for path in "${changed_files[@]}"; do
    if [[ "${path}" == android-watchdog/* ]]; then
      case "${path}" in
        *.kt|*.kts|*.java|*.xml|*.gradle|*.properties|*/AndroidManifest.xml)
          android_trigger=1
          break
          ;;
      esac
    fi
  done

  if (( android_trigger )); then
    if [[ -d "${REPO_ROOT}/android-watchdog" ]]; then
      echo "[+] precommit_guard: running Android compile check (:app:compileDebugKotlin)..."
      if ! (cd "${REPO_ROOT}/android-watchdog" && ./gradlew :app:compileDebugKotlin); then
        echo "[!] precommit_guard: Android compile check failed."
        failures=$((failures + 1))
      else
        echo "[+] precommit_guard: Android compile check passed."
      fi
    else
      echo "[!] precommit_guard: android-watchdog directory not found, skipping compile."
      failures=$((failures + 1))
    fi
  fi
fi

if (( failures > 0 )); then
  echo "[!] precommit_guard: failed with ${failures} blocking issue(s)."
  exit 1
fi

echo "[+] precommit_guard: passed."
