#!/usr/bin/env bash
# Static scan for accidentally committed credentials (TEST_PLAN.md S8/S9).
# Usage: scripts/secret-scan.sh [path-to-apk ...]
# Exit codes: 0 clean, 1 something suspicious found, 2 the scan itself failed (never treated as clean).
set -euo pipefail
cd "$(dirname "$0")/.."
# grep -P requires a UTF-8 locale (Git Bash on Windows defaults to a non-UTF-8 one).
export LC_ALL=C.UTF-8

status=0
report() { echo "SECRET-SCAN: $1"; status=1; }

# Runs grep -P and distinguishes "no match" (1) from "grep is broken" (>1).
scan() {
  local pattern=$1; shift
  set +e
  out=$(grep -rnPIa -- "$pattern" "$@" 2>&1)
  rc=$?
  set -e
  if [ $rc -gt 1 ]; then
    echo "SECRET-SCAN: grep failed (rc=$rc): $out"
    exit 2
  fi
  [ $rc -eq 0 ] && printf '%s\n' "$out" | grep -v '^scripts/secret-scan.sh:' || true
}

# 1. Tracked files: real-looking JWTs, Web API keys, private keys, assignments of token-like values.
mapfile -t files < <(git ls-files)
patterns=(
  'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}'
  '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
  '(?i)steam[_-]?(web)?[_-]?api[_-]?key\s*[:=]\s*["'"'"']?[0-9A-F]{32}'
  '(?i)(refresh|access)_?token\s*[:=]\s*["'"'"'][A-Za-z0-9._-]{20,}'
  '(?i)shared_secret\s*[:=]\s*["'"'"'][A-Za-z0-9+/=]{20,}'
)
for p in "${patterns[@]}"; do
  hits=$(scan "$p" "${files[@]}")
  [ -n "$hits" ] && report "pattern '$p' matched:"$'\n'"$hits"
done

# 2. Signing material must never be tracked.
if printf '%s\n' "${files[@]}" | grep -E '\.(jks|keystore|p12|pem)$|keystore\.properties$'; then
  report "signing material is tracked"
fi

# 3. Optional: built APKs (resources, dex strings, assets).
for apk in "$@"; do
  tmp=$(mktemp -d)
  unzip -q -o "$apk" -d "$tmp"
  [ -n "$(scan 'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.' "$tmp")" ] && report "JWT-like string inside $apk"
  [ -n "$(scan '(?i)key=[0-9A-F]{32}' "$tmp")" ] && report "Web API key-like string inside $apk"
  rm -rf "$tmp"
done

[ $status -eq 0 ] && echo "SECRET-SCAN: clean"
exit $status
