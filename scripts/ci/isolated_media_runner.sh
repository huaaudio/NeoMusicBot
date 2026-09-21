#!/usr/bin/env bash
# Launch one GitHub JIT job in a disposable Linux filesystem, using the local network.
set -euo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != --check && "$1" != --controlled ) ]]; then
    echo 'Usage: isolated_media_runner.sh [--check|--controlled]; job policy JSON and JIT configuration are read as two stdin lines' >&2
    exit 2
fi
for command in bwrap curl sha256sum tar timeout git python3 jq unzip; do
    command -v "${command}" >/dev/null
done
test "$(uname -m)" = x86_64
test "$(id -u)" -ne 0

runner_root="$(mktemp -d /tmp/neomusicbot-runner.XXXXXXXX)"
policy_root="$(mktemp -d /tmp/neomusicbot-policy.XXXXXXXX)"
runner_child=
stop_runner() {
    if [[ -n "${runner_child}" ]]; then
        # This PID is our own timeout child, never supplied by a caller or job.
        kill -TERM "${runner_child}" 2>/dev/null || true
        wait "${runner_child}" 2>/dev/null || true
        runner_child=
    fi
}
cleanup() {
    stop_runner
    # This directory is created by this script, never supplied by a caller/job.
    case "${runner_root}" in
        /tmp/neomusicbot-runner.*) rm -rf -- "${runner_root}" ;;
        *) echo 'Refusing unexpected runner cleanup path' >&2; return 1 ;;
    esac
    case "${policy_root}" in
        /tmp/neomusicbot-policy.*) rm -rf -- "${policy_root}" ;;
        *) echo 'Refusing unexpected policy cleanup path' >&2; return 1 ;;
    esac
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cp -- "${script_directory}/verify_isolated_job.py" "${policy_root}/verify_isolated_job.py"
cp -- "${script_directory}/isolated_job_hook.sh" "${policy_root}/job_hook.sh"
chmod 755 "${policy_root}/job_hook.sh"
mkdir "${runner_root}/control"

version=2.337.0
sha256=70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613
curl --fail --location --silent --show-error --retry 3 --retry-max-time 180 --connect-timeout 15 --max-time 180 \
    "https://github.com/actions/runner/releases/download/v${version}/actions-runner-linux-x64-${version}.tar.gz" \
    --output "${runner_root}/runner.tar.gz"
printf '%s  %s\n' "${sha256}" "${runner_root}/runner.tar.gz" | sha256sum --check --strict -
tar -xzf "${runner_root}/runner.tar.gz" -C "${runner_root}" --no-same-owner
rm -- "${runner_root}/runner.tar.gz"

# Use synthetic account files. Do not bind /home, /mnt, /run, host /tmp or sockets.
printf 'runner:x:%s:%s:CI runner:/home/runner:/bin/bash\n' "$(id -u)" "$(id -g)" > "${runner_root}/passwd"
printf 'runner:x:%s:\n' "$(id -g)" > "${runner_root}/group"
isolation=(
    bwrap --unshare-all --share-net --die-with-parent --new-session --clearenv
    --ro-bind /usr /usr
    --symlink usr/bin /bin --symlink usr/sbin /sbin
    --symlink usr/lib /lib --symlink usr/lib64 /lib64
    --proc /proc --dev /dev --tmpfs /tmp --tmpfs /home/runner
    --ro-bind /etc/ssl/certs /etc/ssl/certs
    --ro-bind /etc/resolv.conf /etc/resolv.conf
    --ro-bind /etc/nsswitch.conf /etc/nsswitch.conf
    --ro-bind /etc/hosts /etc/hosts --ro-bind /etc/os-release /etc/os-release
    --ro-bind "${runner_root}/passwd" /etc/passwd
    --ro-bind "${runner_root}/group" /etc/group
    --bind "${runner_root}" /runner --chdir /runner
    --ro-bind "${policy_root}" /policy --bind "${runner_root}/control" /control
    --setenv PATH /usr/bin:/bin --setenv HOME /home/runner
    --setenv LANG C.UTF-8 --setenv USER runner --setenv LOGNAME runner
    --setenv ACTIONS_RUNNER_HOOK_JOB_STARTED /policy/job_hook.sh
)

"${isolation[@]}" /bin/bash -c '
    test ! -e /mnt && test ! -e /run && test ! -e /home/runner/.ssh
    test ! -w /usr
    test ! -w /policy && test -w /control
    ./bin/Runner.Listener --version
'
if [[ "${1:-}" == --check ]]; then
    echo 'runner.isolation=passed; no runner was registered'
    exit 0
fi

echo 'runner.ready-for-jit=true'
# The repository administrator creates the JIT configuration outside the sandbox.
# Do not pass their API credential, Git configuration or environment to the job.
IFS= read -r expected_policy
printf '%s\n' "${expected_policy}" > "${policy_root}/expected.json"
python3 -I "${policy_root}/verify_isolated_job.py" --validate-policy "${policy_root}/expected.json"
unset expected_policy
IFS= read -r jit_config
test -n "$jit_config"
timeout --signal=TERM --kill-after=30s 45m "${isolation[@]}" /bin/bash -c '
    IFS= read -r jit_config
    exec ./run.sh --jitconfig "$jit_config"
' <<<"${jit_config}" &
runner_child=$!
unset jit_config
while kill -0 "${runner_child}" 2>/dev/null; do
    if [[ -f "${runner_root}/control/denied" ]]; then
        echo 'runner.job-policy=denied'
        stop_runner
        exit 125
    fi
    if [[ "${1:-}" == --controlled ]]; then
        # Closing the controller's liveness pipe cancels the child inside Linux.
        if IFS= read -r -t 1 control; then
            if [[ "${control}" == stop ]]; then
                stop_runner
                exit 130
            fi
        else
            read_status=$?
            if [[ "${read_status}" -eq 1 ]]; then
                stop_runner
                exit 130
            fi
        fi
    else
        sleep 1
    fi
done
if wait "${runner_child}"; then status=0; else status=$?; fi
runner_child=
exit "${status}"
