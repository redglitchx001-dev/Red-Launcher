#!/bin/bash
# --- CI DEBUG WRAPPER (temporary, REMOVE AFTER DIAGNOSIS) ---
# Captures full build console to ci-debug/console.log; on failure publishes error.txt via:
#   1) git push to refs/heads/ci-debug-log
#   2) GitHub API: new check-run "ci-debug" (output.summary)
#   3) GitHub API: new issue "CI build error"
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
        # channel 1: push to debug branch
        git config user.email ci-debug@local 2>/dev/null
        git config user.name 'CI Debug' 2>/dev/null
        git add -f ci-debug/error.txt 2>/dev/null
        git commit -m 'CI-ERROR: build failed, see file' >/dev/null 2>&1
        git push -f origin HEAD:refs/heads/ci-debug-log >/dev/null 2>&1
        # channel 2: check-run summary via API
        if [ -n "$GITHUB_TOKEN" ] && [ -n "$GITHUB_REPOSITORY" ]; then
            PAYLOAD=$(python3 - "$WS/ci-debug/error.txt" <<'PYEOF'
import json, sys
data = open(sys.argv[1], encoding="utf-8", errors="replace").read()[:15000]
print(json.dumps({
    "name": "ci-debug",
    "head_sha": __import__("os").environ.get("GITHUB_SHA", ""),
    "status": "completed",
    "conclusion": "failure",
    "output": {"title": "BUILD ERROR (captured by gradlew wrapper)", "summary": data}
}))
PYEOF
)
            curl -s --max-time 30 -X POST \
                -H "Authorization: token $GITHUB_TOKEN" \
                -H "Accept: application/vnd.github+json" \
                "https://api.github.com/repos/$GITHUB_REPOSITORY/check-runs" \
                -d "$PAYLOAD" >/dev/null 2>&1
            # channel 3: issue
            ISSUE_PAYLOAD=$(python3 - "$WS/ci-debug/error.txt" <<'PYEOF'
import json, sys
data = open(sys.argv[1], encoding="utf-8", errors="replace").read()[:20000]
print(json.dumps({"title": "CI build error (auto, delete me)", "body": data}))
PYEOF
)
            curl -s --max-time 30 -X POST \
                -H "Authorization: token $GITHUB_TOKEN" \
                -H "Accept: application/vnd.github+json" \
                "https://api.github.com/repos/$GITHUB_REPOSITORY/issues" \
                -d "$ISSUE_PAYLOAD" >/dev/null 2>&1
        fi
    fi
    exit $status
fi
exec bash gradlew-orig "$@"
