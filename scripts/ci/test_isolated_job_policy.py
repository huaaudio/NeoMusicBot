import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from verify_isolated_job import expected_job, validate_policy, verify


class IsolatedJobPolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = expected_job(dict(id=123, run_attempt=2, event="push", head_branch="bili", head_sha="a" * 40))

    def test_exact_job_identity_is_required_even_when_the_runner_label_matches(self):
        verify(self.policy, self.policy)
        mismatches = dict(GITHUB_REPOSITORY="foreign/repo", GITHUB_REF="refs/pull/1/merge",
                          GITHUB_WORKFLOW_REF="huaaudio/NeoMusicBot/.github/workflows/other.yml@refs/heads/bili",
                          GITHUB_SHA="b" * 40, GITHUB_EVENT_NAME="pull_request", GITHUB_RUN_ID="124",
                          GITHUB_RUN_ATTEMPT="3", GITHUB_JOB="other_job")
        for key, value in mismatches.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify(self.policy, dict(self.policy, **{key: value}))
        for key in self.policy:
            context = dict(self.policy)
            del context[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                verify(self.policy, context)

    def test_invalid_or_incomplete_expected_policies_are_rejected(self):
        for policy in (None, {}, dict(self.policy, GITHUB_EVENT_NAME="pull_request_target"),
                       dict(self.policy, GITHUB_RUN_ATTEMPT="0"), dict(self.policy, GITHUB_SHA="short"),
                       dict(self.policy, GITHUB_JOB="build"), dict(self.policy, unexpected="value")):
            with self.subTest(policy=policy), self.assertRaises(ValueError):
                validate_policy(policy)

    def test_real_cli_fails_without_disclosing_environment_values(self):
        script = Path(__file__).with_name("verify_isolated_job.py")
        with tempfile.TemporaryDirectory() as temporary:
            policy_path = Path(temporary) / "policy.json"
            policy_path.write_text(json.dumps(self.policy), encoding="utf-8")
            environment = dict(os.environ, **self.policy)
            accepted = subprocess.run([sys.executable, "-I", str(script), str(policy_path)],
                                      env=environment, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            self.assertEqual("runner.job-policy=passed", accepted.stdout.strip())
            environment["GITHUB_SHA"] = "SECRET-UNTRUSTED-ENVIRONMENT-VALUE"
            denied = subprocess.run([sys.executable, "-I", str(script), str(policy_path)],
                                    env=environment, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, denied.returncode)
            self.assertEqual("runner.job-policy=denied", denied.stdout.strip())
            self.assertNotIn("SECRET-UNTRUSTED", denied.stdout + denied.stderr)


if __name__ == "__main__":
    unittest.main()
