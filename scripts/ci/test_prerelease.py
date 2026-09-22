import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

from package_bundle import digest
from prepare_prerelease import (PLATFORMS, fields, prepare, prerelease_version,
                                validate_source, verify_download, verify_release)

SHA = "a" * 40
VERSION = "0.5.0-beta.1"
ENV = {"GITHUB_REPOSITORY": "owner/NeoMusicBot", "GITHUB_SHA": SHA, "GITHUB_REF": "refs/heads/bili",
       "BUILD_RUN_ID": "123", "VERSION_NUMBER": "v" + VERSION}
REPOSITORY = {"full_name": ENV["GITHUB_REPOSITORY"], "default_branch": "bili"}
RUN = {"id": 123, "status": "completed", "conclusion": "success", "name": "Build and Test",
       "path": ".github/workflows/build-and-test.yml", "repository": REPOSITORY,
       "head_repository": REPOSITORY, "event": "push", "head_branch": "bili", "head_sha": SHA}


def write_fields(path, data):
    path.write_text("".join(f"{key}={value}\n" for key, value in data.items()), encoding="utf-8")


class PrereleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pom = self.root / "pom.xml"
        self.pom.write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0"><version>{VERSION}</version></project>')
        self.artifacts = self.root / "artifacts"

    def bundle(self, platform, jar_version=VERSION, commit=SHA, corrupt_tool=False):
        folder = self.artifacts / ("NeoMusicBot-" + platform)
        folder.mkdir(parents=True, exist_ok=True)
        tools = {"yt-dlp": ("extractor-" + platform).encode(), "deno": ("javascript-" + platform).encode()}
        plugin = b"provider plugin"
        versions = {"NeoMusicBot commit": commit, "platform": platform, "yt-dlp.version": "2026.08.19",
                    "deno.version": "2.9.7", "bgutil-provider.version": "2.0.0",
                    "bgutil-provider.commit": "b" * 40, "bgutil-provider.plugin.sha256": hashlib.sha256(plugin).hexdigest()}
        versions.update({tool + ".sha256": hashlib.sha256(data).hexdigest() for tool, data in tools.items()})
        # The real metadata records the downloaded Deno ZIP's hash, not the executable's.
        versions["deno.sha256"] = hashlib.sha256(("deno-release-zip-" + platform).encode()).hexdigest()
        json_bom = json.dumps({"metadata": {"component": {"version": VERSION}}}).encode()
        xml_bom = f'<bom xmlns="http://cyclonedx.org/schema/bom/1.6"><metadata><component><version>{VERSION}</version></component></metadata></bom>'.encode()
        jar_data = io.BytesIO()
        with zipfile.ZipFile(jar_data, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", f"Implementation-Version: {jar_version}\r\n")
        archive = folder / f"NeoMusicBot-{platform}.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("NeoMusicBot.jar", jar_data.getvalue())
            zipped.writestr("NeoMusicBot.cdx.json", json_bom)
            zipped.writestr("NeoMusicBot.cdx.xml", xml_bom)
            zipped.writestr("THIRD_PARTY_VERSIONS.txt", "".join(f"{k}={v}\n" for k, v in versions.items()))
            zipped.writestr("tools/yt-dlp-plugins/bgutil-ytdlp-pot-provider.zip", plugin)
            suffix = ".exe" if platform.startswith("windows") else ""
            for name, data in tools.items():
                zipped.writestr("tools/" + name + suffix, data + (b"modified" if corrupt_tool else b""))
        (folder / "bom.json").write_bytes(json_bom)
        (folder / "bom.xml").write_bytes(xml_bom)
        Path(str(archive) + ".sha256").write_text(f"{digest(archive)}  {archive.name}\n")
        report = {key: "passed" for key in ("bundle", "config", "native.dave", "native.opus", "crypto.rtp",
                                          "audio.aac", "audio.opus", "audio.mp3", "provider.offline",
                                          "provider.inventory", "provider.native", "provider.native.materials", "provider.quickjs", "runtime.deno.materials", "runtime.ytdlp.notices")}
        report.update({"neomusicbot.commit": commit, "version": VERSION, "archive.sha256": digest(archive)})
        write_fields(folder / "bundle-verification.txt", report)
        return versions

    def candidate(self, versions):
        folder = self.artifacts / "NeoMusicBot-media-canary"
        folder.mkdir(parents=True, exist_ok=True)
        report = {k: v for k, v in versions.items() if k not in ("NeoMusicBot commit", "platform")}
        report.update({"mode": "locked", "repository": "yt-dlp/yt-dlp", "release.tag": versions["yt-dlp.version"],
                       "neomusicbot.commit": SHA, "youtube.anonymous": "passed",
                       "youtube.mweb-provider": "passed", "bilibili.anonymous": "passed"})
        write_fields(folder / "candidate.txt", report)

    def fixtures(self):
        linux = self.bundle(PLATFORMS[0])
        self.bundle(PLATFORMS[1])
        self.candidate(linux)

    def test_only_prerelease_versions_matching_the_pom_are_accepted(self):
        self.assertEqual(VERSION, prerelease_version("v" + VERSION))
        for tag in ("0.5.0-beta.1", "v0.5.0", "v0.5.0-SNAPSHOT", "v00.5.0-beta.1", "v0.5.0-beta.01", "v0.5.0-beta.1\n"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                prerelease_version(tag)
        with self.assertRaises(ValueError):
            validate_source(RUN, REPOSITORY, dict(ENV, VERSION_NUMBER="v0.5.0-beta.2"), self.pom)

    def test_source_rejects_other_commits_workflows_forks_branches_and_failed_runs(self):
        validate_source(RUN, REPOSITORY, ENV, self.pom)
        mutations = {"id": 124, "status": "in_progress", "conclusion": "failure", "name": "Other",
                     "path": ".github/workflows/other.yml", "head_sha": "d" * 40, "head_branch": "feature",
                     "event": "pull_request", "repository": {"full_name": "attacker/fork"},
                     "head_repository": {"full_name": "attacker/fork"}}
        for key, value in mutations.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_source(dict(RUN, **{key: value}), REPOSITORY, ENV, self.pom)
        for key, value in {"GITHUB_REF": "refs/heads/feature", "BUILD_RUN_ID": "../123", "GITHUB_SHA": "bili"}.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_source(RUN, REPOSITORY, dict(ENV, **{key: value}), self.pom)

    def test_verified_assets_are_copied_unchanged_and_cannot_be_overwritten(self):
        self.fixtures()
        output = self.root / "dist"
        prepare(self.artifacts, output, ENV)
        for platform in PLATFORMS:
            name = f"NeoMusicBot-{platform}.zip"
            self.assertEqual(digest(self.artifacts / ("NeoMusicBot-" + platform) / name), digest(output / name))
        self.assertEqual(13, len(list(output.iterdir())))
        downloaded = self.root / "downloaded"
        shutil.copytree(output, downloaded)
        verify_download(output, downloaded)
        with self.assertRaises(FileExistsError):
            prepare(self.artifacts, output, ENV)
        (downloaded / "NeoMusicBot-build.json").write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            verify_download(output, downloaded)
        (downloaded / "NeoMusicBot-build.json").unlink()
        with self.assertRaises(ValueError):
            verify_download(output, downloaded)

    def test_rejects_mismatched_bundle_commit_jar_tools_report_and_sbom(self):
        for changes in ({"commit": "e" * 40}, {"jar_version": "0.4.5"}, {"corrupt_tool": True}):
            with self.subTest(changes=changes):
                self.fixtures()
                self.bundle(PLATFORMS[0], **changes)
                with self.assertRaises(ValueError):
                    prepare(self.artifacts, self.root / "dist", ENV)
                self.assertFalse((self.root / "dist").exists())
        self.fixtures()
        folder = self.artifacts / ("NeoMusicBot-" + PLATFORMS[0])
        report_path = folder / "bundle-verification.txt"
        original = fields(report_path.read_text())
        for key in original:
            changed = copy.copy(original)
            changed.pop(key)
            write_fields(report_path, changed)
            with self.subTest(missing=key), self.assertRaises(ValueError):
                prepare(self.artifacts, self.root / "dist", ENV)
        write_fields(report_path, original)
        (folder / "bom.json").write_bytes(b"{}")
        with self.assertRaises(ValueError):
            prepare(self.artifacts, self.root / "dist", ENV)

    def test_media_gate_rejects_failures_other_toolchains_and_duplicate_fields(self):
        self.fixtures()
        candidate = self.artifacts / "NeoMusicBot-media-canary/candidate.txt"
        original = fields(candidate.read_text())
        for key in ("youtube.anonymous", "youtube.mweb-provider", "bilibili.anonymous", "mode",
                    "neomusicbot.commit", "yt-dlp.sha256", "bgutil-provider.commit", "deno.version"):
            write_fields(candidate, dict(original, **{key: "wrong"}))
            with self.subTest(key=key), self.assertRaises(ValueError):
                prepare(self.artifacts, self.root / "dist", ENV)
        with self.assertRaises(ValueError):
            fields("bundle=passed\nbundle=failed\n")

    def test_release_state_and_tag_must_match_before_and_after_publication(self):
        release = {"tag_name": ENV["VERSION_NUMBER"], "draft": True, "prerelease": True}
        ref = {"ref": "refs/tags/" + ENV["VERSION_NUMBER"], "object": {"type": "commit", "sha": SHA}}
        verify_release(release, ref, ENV, True)
        for changed in (dict(release, prerelease=False), dict(release, draft=False), dict(release, tag_name="v1.0.0")):
            with self.assertRaises(ValueError):
                verify_release(changed, ref, ENV, True)
        with self.assertRaises(ValueError):
            verify_release(release, dict(ref, object={"type": "commit", "sha": "f" * 40}), ENV, True)
        verify_release(dict(release, draft=False), ref, ENV, False)


if __name__ == "__main__":
    unittest.main()
