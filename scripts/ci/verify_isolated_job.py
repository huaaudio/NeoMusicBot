"""Validate the assigned job before an isolated runner executes workflow steps."""
import json
import os
from pathlib import Path
import re
import sys

REPOSITORY = "huaaudio/NeoMusicBot"
WORKFLOW = ".github/workflows/build-and-test.yml"
KEYS = {"GITHUB_REPOSITORY", "GITHUB_WORKFLOW_REF", "GITHUB_REF", "GITHUB_SHA",
        "GITHUB_EVENT_NAME", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB"}


def expected_job(run):
    ref = "refs/heads/" + run["head_branch"]
    policy = dict(GITHUB_REPOSITORY=REPOSITORY, GITHUB_WORKFLOW_REF=REPOSITORY + "/" + WORKFLOW + "@" + ref,
                  GITHUB_REF=ref, GITHUB_SHA=run["head_sha"], GITHUB_EVENT_NAME=run["event"],
                  GITHUB_RUN_ID=str(run["id"]), GITHUB_RUN_ATTEMPT=str(run["run_attempt"]),
                  GITHUB_JOB="locked_media_canary")
    validate_policy(policy)
    return policy


def validate_policy(policy):
    if (not isinstance(policy, dict) or set(policy) != KEYS
            or any(not isinstance(value, str) for value in policy.values())
            or policy["GITHUB_REPOSITORY"] != REPOSITORY
            or policy["GITHUB_JOB"] != "locked_media_canary"
            or policy["GITHUB_EVENT_NAME"] not in ("push", "workflow_dispatch")
            or not policy["GITHUB_REF"].startswith("refs/heads/")
            or len(policy["GITHUB_REF"]) <= len("refs/heads/")
            or policy["GITHUB_WORKFLOW_REF"] != REPOSITORY + "/" + WORKFLOW + "@" + policy["GITHUB_REF"]
            or not re.fullmatch("[a-f0-9]{40}", policy["GITHUB_SHA"])
            or any(not re.fullmatch("[1-9][0-9]*", policy[key]) for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"))):
        raise ValueError("Invalid expected job policy")


def verify(policy, environment):
    validate_policy(policy)
    if any(environment.get(key) != value for key, value in policy.items()):
        raise ValueError("Assigned job does not match the expected workflow execution")


def main():
    validate_only = len(sys.argv) == 3 and sys.argv[1] == "--validate-policy"
    if not validate_only and len(sys.argv) != 2:
        raise ValueError("A policy file is required")
    policy = json.loads(Path(sys.argv[-1]).read_text(encoding="utf-8"))
    validate_policy(policy)
    if not validate_only:
        verify(policy, os.environ)
        print("runner.job-policy=passed", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Do not echo environment variables, webhook payloads or credentials.
        print("runner.job-policy=denied", flush=True)
        raise SystemExit(1)
