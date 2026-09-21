from contextlib import redirect_stdout
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from serve_isolated_media import (REPOSITORY, WORKFLOW, JOB, controller_lock, eligible_job,
                                 runner_label, serve)

SHA = "a" * 40
REPO = {"full_name": REPOSITORY, "default_branch": "bili"}
RUN = dict(id=123, run_attempt=2, name="Build and Test", path=WORKFLOW, event="push",
           repository=REPO, head_repository=REPO, head_sha=SHA, head_branch="bili", status="in_progress")
QUEUED_JOB = dict(id=456, run_id=123, name=JOB, head_sha=SHA, status="queued", conclusion=None,
                  labels=["neomusicbot-media-123-2"])


class FakeProcess:
    def __init__(self):
        self.stdin = io.StringIO()
        self.stdout = io.StringIO("runner.ready-for-jit=true\n")
        self.code = None
        self.returncode = None

    def poll(self):
        return self.code

    def wait(self, timeout=None):
        self.code = 0
        self.returncode = 0
        return self.code


class IsolatedMediaControllerTests(unittest.TestCase):
    def test_selects_only_this_default_branch_run_and_attempt(self):
        self.assertEqual(QUEUED_JOB, eligible_job(RUN, [QUEUED_JOB], REPO, SHA, "bili"))
        dispatch = dict(RUN, event="workflow_dispatch")
        self.assertEqual(QUEUED_JOB, eligible_job(dispatch, [QUEUED_JOB], REPO, SHA, "bili"))
        for field, value in (("event", "pull_request"), ("event", "pull_request_target"),
                             ("head_sha", "b" * 40), ("head_branch", "unreviewed"),
                             ("repository", {"full_name": "foreign/repo"}),
                             ("head_repository", {"full_name": "foreign/repo"}),
                             ("path", ".github/workflows/untrusted.yml"), ("run_attempt", 3),
                             ("status", "completed")):
            with self.subTest(field=field, value=value):
                self.assertIsNone(eligible_job(dict(RUN, **{field: value}), [QUEUED_JOB], REPO, SHA, "bili"))
        self.assertIsNone(eligible_job(RUN, [QUEUED_JOB], REPO, SHA, "other-local-branch"))
        self.assertIsNone(eligible_job(RUN, [QUEUED_JOB], REPO, "not-a-sha", "bili"))

    def test_rejects_wrong_label_job_state_and_ambiguous_job(self):
        for field, value in (("run_id", 124), ("head_sha", "b" * 40), ("name", "Other job"),
                             ("labels", ["ubuntu-latest"]), ("labels", [runner_label(RUN), "extra"]),
                             ("status", "in_progress"), ("conclusion", "success")):
            with self.subTest(field=field, value=value):
                self.assertIsNone(eligible_job(RUN, [dict(QUEUED_JOB, **{field: value})], REPO, SHA, "bili"))
        self.assertIsNone(eligible_job(RUN, [QUEUED_JOB, QUEUED_JOB], REPO, SHA, "bili"))

    def test_runner_result_is_not_confused_with_media_job_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            calls = []

            def api(path, method="GET", data=None):
                calls.append((path, method, data))
                if method == "POST":
                    return dict(runner=dict(id=42, name=runner_label(RUN), labels=[dict(name=runner_label(RUN))]),
                                encoded_jit_config="SECRET-ONE-USE-CONFIG")
                if path == "actions/jobs/456":
                    return dict(status="completed", conclusion="failure", runner_id=42)
                if path == "actions/runners/42" and method == "DELETE":
                    return None
                self.fail("Unexpected API operation")

            captured = io.StringIO()
            with patch("serve_isolated_media.subprocess.Popen", return_value=FakeProcess()), \
                    patch("serve_isolated_media.snapshot", return_value=(RUN, QUEUED_JOB)), redirect_stdout(captured):
                state = serve(api, RUN, QUEUED_JOB, output, ["fixture"])
            self.assertEqual(0, state["runner_exit_code"])
            self.assertEqual("failure", state["job_conclusion"])
            self.assertEqual("removed", state["registration_cleanup"])
            self.assertIn(("actions/runners/42", "DELETE", None), calls)
            self.assertNotIn("SECRET-ONE-USE-CONFIG", captured.getvalue())
            self.assertNotIn("SECRET-ONE-USE-CONFIG", (output / (runner_label(RUN) + ".json")).read_text())

    def test_superseding_run_is_rejected_before_registration(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch("serve_isolated_media.subprocess.Popen", return_value=FakeProcess()), \
                    patch("serve_isolated_media.snapshot", return_value=(dict(RUN, run_attempt=3), QUEUED_JOB)):
                with self.assertRaisesRegex(RuntimeError, "changed during preflight"):
                    serve(lambda *args: self.fail("No registration should occur"), RUN, QUEUED_JOB,
                          Path(temporary), ["fixture"])

    def test_wrong_registration_is_removed_without_starting_the_job(self):
        with tempfile.TemporaryDirectory() as temporary:
            calls = []

            def api(path, method="GET", data=None):
                calls.append((path, method))
                if method == "POST":
                    return dict(runner=dict(id=42, name=runner_label(RUN), labels=[dict(name="wrong")]),
                                encoded_jit_config="SECRET-ONE-USE-CONFIG")
                if method == "DELETE":
                    return None
                self.fail("No job should have been started")

            with patch("serve_isolated_media.subprocess.Popen", return_value=FakeProcess()), \
                    patch("serve_isolated_media.snapshot", return_value=(RUN, QUEUED_JOB)):
                with self.assertRaisesRegex(RuntimeError, "Unexpected runner"):
                    serve(api, RUN, QUEUED_JOB, Path(temporary), ["fixture"])
            self.assertIn(("actions/runners/42", "DELETE"), calls)

    def test_local_lock_prevents_duplicate_controllers_and_releases_on_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "controller.lock"
            with controller_lock(path):
                with self.assertRaises(OSError), controller_lock(path):
                    self.fail("Second controller acquired an active lock")
            with controller_lock(path):
                self.assertTrue(path.is_file())


if __name__ == "__main__":
    unittest.main()
