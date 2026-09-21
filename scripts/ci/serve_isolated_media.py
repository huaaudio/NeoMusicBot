"""Provision one isolated JIT runner per trusted default-branch media CI job.

Run this controller on the chosen Windows/WSL or Ubuntu host. Administrator
credentials stay in this process; only one-use JIT configuration reaches the
runner. No service is installed. --watch keeps serving while the process runs.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "huaaudio/NeoMusicBot"
WORKFLOW = ".github/workflows/build-and-test.yml"
JOB = "Locked media resolver canary"


class JobChanged(RuntimeError):
    """The queued job was superseded before it could be served."""


def runner_label(run):
    if any(type(run.get(key)) is not int or run[key] < 1 for key in ("id", "run_attempt")):
        raise ValueError("Invalid workflow run identity")
    return f"neomusicbot-media-{run['id']}-{run['run_attempt']}"


def eligible_job(run, jobs, repository, local_sha, local_branch):
    """Return one queued job, rejecting foreign code, branches and stale attempts."""
    if (repository.get("full_name") != REPOSITORY
            or local_branch != repository.get("default_branch")
            or not re.fullmatch(r"[a-f0-9]{40}", local_sha)
            or run.get("path") != WORKFLOW or run.get("name") != "Build and Test"
            or run.get("event") not in ("push", "workflow_dispatch")
            or run.get("repository", {}).get("full_name") != REPOSITORY
            or run.get("head_repository", {}).get("full_name") != REPOSITORY
            or run.get("head_sha") != local_sha or run.get("head_branch") != local_branch
            or run.get("status") not in ("queued", "in_progress")):
        return None
    label = runner_label(run)
    matches = [job for job in jobs if job.get("name") == JOB]
    if len(matches) != 1:
        return None
    job = matches[0]
    if (job.get("run_id") != run["id"] or job.get("head_sha") != local_sha
            or job.get("status") != "queued" or job.get("conclusion") is not None
            or job.get("labels") != [label]):
        return None
    return job


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, timeout=30).strip()


class Github:
    def __init__(self):
        env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never")
        result = subprocess.run(["git", "credential", "fill"], cwd=ROOT, env=env,
                                input="protocol=https\nhost=github.com\n\n", text=True,
                                capture_output=True, timeout=30, check=False)
        credential = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        if result.returncode or not credential.get("password"):
            raise RuntimeError("Existing GitHub credential is unavailable")
        self.token = credential["password"]

    def __call__(self, path, method="GET", data=None):
        request = urllib.request.Request(
            "https://api.github.com/repos/" + REPOSITORY + ("/" + path if path else ""),
            method=method, data=None if data is None else json.dumps(data).encode(),
            headers={"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                     "Content-Type": "application/json", "User-Agent": "NeoMusicBot-isolated-CI",
                     "X-GitHub-Api-Version": "2022-11-28"})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    content = response.read()
                return json.loads(content) if content else None
            except (urllib.error.URLError, TimeoutError) as error:
                transient = not isinstance(error, urllib.error.HTTPError) or error.code in (429, 500, 502, 503, 504)
                # Never repeat an uncertain mutation such as runner registration.
                if method != "GET" or not transient or attempt == 2:
                    raise
                time.sleep(2 ** attempt)


def save(path, state):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


@contextmanager
def controller_lock(path):
    """OS lock is released on process exit; a leftover file is not a live lock."""
    with path.open("a+b") as stream:
        stream.seek(0, os.SEEK_END)
        if not stream.tell():
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            if os.name == "nt":
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def launcher(distro):
    script = ROOT / "scripts/ci/isolated_media_runner.sh"
    if os.name != "nt":
        return ["bash", str(script), "--controlled"]
    path = subprocess.check_output(["wsl.exe", "-d", distro, "--exec", "wslpath", "-a", "-u", str(script)],
                                   text=True, timeout=30).strip()
    return ["wsl.exe", "-d", distro, "--exec", "bash", path, "--controlled"]


def snapshot(api, run_id):
    repository = api("")
    if not repository.get("permissions", {}).get("admin"):
        raise RuntimeError("Repository administrator access is required to provision a JIT runner")
    sha, branch = git("rev-parse", "HEAD"), git("branch", "--show-current")
    if git("status", "--porcelain") or api("commits/" + repository["default_branch"])["sha"] != sha:
        return None
    run = api("actions/runs/" + str(run_id))
    jobs = api(f"actions/runs/{run_id}/attempts/{run['run_attempt']}/jobs?per_page=100")["jobs"]
    job = eligible_job(run, jobs, repository, sha, branch)
    return (run, job) if job else None


def serve(api, run, job, output, command):
    label = runner_label(run)
    state_path = output / (label + ".json")
    state = dict(run_id=run["id"], attempt=run["run_attempt"], sha=run["head_sha"], job_id=job["id"],
                 label=label, started_at=datetime.now(timezone.utc).isoformat(), runner_id=None)
    save(state_path, state)
    ready = threading.Event()
    process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

    def drain():
        with (output / (label + ".log")).open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if line.strip() == "runner.ready-for-jit=true":
                    ready.set()

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    try:
        deadline = time.monotonic() + 300
        while not ready.wait(1):
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("Runner preflight did not become ready")
        # Default branch, local checkout, attempt and queued job must still match
        # after preflight. A superseding push or rerun must use its own runner.
        current = snapshot(api, run["id"])
        if not current or runner_label(current[0]) != label or current[1]["id"] != job["id"]:
            raise JobChanged("The queued media job changed during preflight")
        config = api("actions/runners/generate-jitconfig", "POST", {
            "name": label, "runner_group_id": 1, "labels": [label], "work_folder": "_work"})
        state["runner_id"] = config["runner"]["id"]
        save(state_path, state)
        if config["runner"]["name"] != label or {x["name"] for x in config["runner"]["labels"]} != {label}:
            raise RuntimeError("Unexpected runner registration")
        process.stdin.write(config["encoded_jit_config"] + "\n")
        process.stdin.flush()
        del config
        print(f"ServingMediaRun={run['id']} Attempt={run['run_attempt']} Runner={state['runner_id']}", flush=True)
        deadline = time.monotonic() + 3000
        while process.poll() is None:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                current_job = api("actions/jobs/" + str(job["id"]))
                if current_job.get("conclusion") == "cancelled":
                    raise JobChanged("The media job was cancelled")
                if time.monotonic() > deadline:
                    raise TimeoutError("The isolated runner exceeded its time limit")
        state["runner_exit_code"] = process.returncode
        reader.join(timeout=5)
        # Runner exit 0 says nothing about the job's result; inspect GitHub.
        actual = api("actions/jobs/" + str(job["id"]))
        deadline = time.monotonic() + 30
        while actual["status"] != "completed" and time.monotonic() < deadline:
            time.sleep(2)
            actual = api("actions/jobs/" + str(job["id"]))
        state["job_status"], state["job_conclusion"] = actual["status"], actual["conclusion"]
        if actual.get("runner_id") != state["runner_id"]:
            raise RuntimeError("The intended job was not executed by this runner")
    except BaseException as error:
        state["failure_type"] = type(error).__name__
        raise
    finally:
        if process.stdin and not process.stdin.closed:
            process.stdin.close()
        if process.poll() is None:
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.terminate()
                state["launcher_termination_requested"] = True
        reader.join(timeout=5)
        if state["runner_id"]:
            try:
                api("actions/runners/" + str(state["runner_id"]), "DELETE")
                state["registration_cleanup"] = "removed"
            except urllib.error.HTTPError as error:
                state["registration_cleanup"] = "already-removed" if error.code == 404 else "HTTP-" + str(error.code)
            except Exception as error:
                state["registration_cleanup"] = type(error).__name__
        save(state_path, state)
        print(f"MediaRun={run['id']} Result={state.get('job_conclusion', 'not-completed')} "
              f"Cleanup={state.get('registration_cleanup', 'not-registered')}", flush=True)
    if state.get("registration_cleanup") not in ("removed", "already-removed"):
        raise RuntimeError("Runner registration cleanup needs attention")
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--watch", action="store_true", help="Serve eligible jobs while this foreground controller runs")
    mode.add_argument("--run-id", type=int, help="Serve one currently queued media job")
    parser.add_argument("--distro", default="Ubuntu", help="Existing WSL distribution (Windows only)")
    args = parser.parse_args()
    if args.run_id is not None and args.run_id < 1:
        parser.error("--run-id must be positive")
    output = ROOT / "tools/isolated-media-controller"
    output.mkdir(parents=True, exist_ok=True)
    with controller_lock(output / "controller.lock"):
        api, command = Github(), launcher(args.distro)
        save(output / "controller.json", dict(pid=os.getpid(), watch=args.watch,
             started_at=datetime.now(timezone.utc).isoformat()))
        attempted = set()
        print("MediaControllerReady=true", flush=True)
        while True:
            if args.run_id:
                run_ids = [args.run_id]
            else:
                runs = api("actions/workflows/build-and-test.yml/runs?per_page=30")["workflow_runs"]
                run_ids = [run["id"] for run in runs if run["status"] in ("queued", "in_progress")]
            found = False
            for run_id in run_ids:
                candidate = snapshot(api, run_id)
                if not candidate or runner_label(candidate[0]) in attempted:
                    continue
                found = True
                attempted.add(runner_label(candidate[0]))
                try:
                    state = serve(api, *candidate, output, command)
                except JobChanged:
                    if not args.watch:
                        raise
                    continue
                if not args.watch and state.get("job_conclusion") != "success":
                    return 1
            if not args.watch:
                if not found:
                    raise RuntimeError("No eligible queued job for the clean current default-branch checkout")
                return 0
            time.sleep(20)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("MediaControllerStopped=true", flush=True)
        raise SystemExit(130)
    except Exception as error:
        # Do not print URLs, request headers, response bodies or credential-helper output.
        code = " HTTP=" + str(error.code) if isinstance(error, urllib.error.HTTPError) else ""
        print("MediaControllerFailure=" + type(error).__name__ + code, flush=True)
        raise SystemExit(1)
