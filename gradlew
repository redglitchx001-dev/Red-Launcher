#!/bin/bash
# --- CI DEBUG WRAPPER (temporary, REMOVE AFTER DIAGNOSIS) ---
# Captures full build console to ci-debug/console.log; on failure pushes tail to ci-debug-log branch.
WS="${GITHUB_WORKSPACE:-}"
cd "$(dirname "$0")" || exit 1
if [ -n "$WS" ]; then
    mkdir -p "$WS/ci-debug"
    bash gradlew-orig "$@" > >(tee "$WS/ci-debug/console.log")
    status=$?
    sleep 1
    if [ $status -ne 0 ]; then
        {
            echo "BUILD FAILED (exit $status)"
            echo ""
            echo "===== CONSOLE TAIL (last 350 lines) ====="
            tail -350 "$WS/ci-debug/console.log"
        } > "$WS/ci-debug/error.txt"
        git config user.email ci-debug@local 2>/dev/null
        git config user.name 'CI Debug' 2>/dev/null
        git add -f ci-debug/error.txt 2>/dev/null
        git commit -m 'CI-ERROR: build failed, see file' >/dev/null 2>&1
        git push -f origin HEAD:refs/heads/ci-debug-log >/dev/null 2>&1
    fi
    exit $status
fi
exec bash gradlew-orig "$@"
