#!/usr/bin/env bash
# The launcher mounts this policy directory read-only, outside the runner tree.
set -u
trap '' INT TERM
if /usr/bin/python3 -I /policy/verify_isolated_job.py /policy/expected.json; then
    exit 0
fi

# A failed step alone can be followed by a workflow's `if: always()` step.
# Stop our own Worker parent, notify the outer launcher, and never return.
# The launcher terminates the entire disposable runner namespace.
if IFS= read -r parent_name < "/proc/${PPID}/comm" && [[ "${parent_name}" == Runner.Worker ]]; then
    kill -STOP "${PPID}" || true
fi
printf '%s\n' denied > /control/denied || true
while :; do /usr/bin/sleep 1; done
