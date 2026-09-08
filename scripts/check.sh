#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-protocol.py
projects=(burn-in-camera burn-in-fixed)
if [[ $# -gt 0 ]]; then
  case "$1" in burn-in-camera|burn-in-fixed) projects=("$1");; *) echo "Unknown project: $1" >&2; exit 2;; esac
fi
for project in "${projects[@]}"; do
  "$project/gradlew" -p "$project" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
    --continue --no-daemon --max-workers=2 --console=plain
 done
