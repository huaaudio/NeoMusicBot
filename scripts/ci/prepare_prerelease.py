"""Bind prerelease assets to a successful build of the exact publishing commit."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile

from package_bundle import digest

PLATFORMS = ("linux-x86-64", "windows-x86-64")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def prerelease_version(tag):
    number = r"(?:0|[1-9][0-9]*)"
    require(re.fullmatch(rf"v{number}\.{number}\.{number}-(?:alpha|beta|rc)\.{number}", tag),
            "Use a prerelease tag such as v0.5.0-beta.1")
    return tag[1:]


def validate_source(run, repository, env, pom):
    version = prerelease_version(env["VERSION_NUMBER"])
    name, sha = env["GITHUB_REPOSITORY"], env["GITHUB_SHA"]
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "Invalid source commit")
    require(re.fullmatch(r"[1-9][0-9]*", env["BUILD_RUN_ID"]), "Invalid build run ID")
    require(repository.get("full_name") == name, "Repository mismatch")
    branch = repository["default_branch"]
    require(env["GITHUB_REF"] == "refs/heads/" + branch, "Publish from the default branch")
    require(run.get("id") == int(env["BUILD_RUN_ID"]), "Build run ID mismatch")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "The source workflow must have completed successfully, including media checks")
    require(run.get("name") == "Build and Test"
            and run.get("path") == ".github/workflows/build-and-test.yml", "Wrong source workflow")
    require(run.get("repository", {}).get("full_name") == name
            and run.get("head_repository", {}).get("full_name") == name, "Foreign build repository")
    require(run.get("event") in ("push", "workflow_dispatch"), "Untrusted build trigger")
    require(run.get("head_branch") == branch and run.get("head_sha") == sha,
            "Build and publishing checkout must be the same default-branch commit")
    require(ET.parse(pom).getroot().findtext("{*}version") == version, "POM and tag versions differ")


def fields(text):
    result = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        require(separator and key and key not in result, "Malformed or duplicate report field")
        result[key] = value
    return result


def expect_fields(actual, expected):
    for key, value in expected.items():
        require(actual.get(key) == value, "Report mismatch: " + key)


def verify_platform(folder, platform, sha, version):
    archive = folder / f"NeoMusicBot-{platform}.zip"
    checksum = digest(archive)
    require(Path(str(archive) + ".sha256").read_text(encoding="ascii").strip()
            == f"{checksum}  {archive.name}", "Archive checksum mismatch")
    report = fields((folder / "bundle-verification.txt").read_text(encoding="utf-8"))
    expect_fields(report, {
        "bundle": "passed", "neomusicbot.commit": sha, "version": version,
        "archive.sha256": checksum, "config": "passed", "native.dave": "passed",
        "native.opus": "passed", "crypto.rtp": "passed", "audio.aac": "passed",
        "audio.opus": "passed", "audio.mp3": "passed", "provider.offline": "passed",
        "provider.inventory": "passed", "provider.native": "passed", "provider.native.materials": "passed", "provider.quickjs": "passed", "runtime.deno.materials": "passed", "runtime.ytdlp.notices": "passed",
    })
    with zipfile.ZipFile(archive) as zipped:
        names = zipped.namelist()
        require(len(names) == len(set(names)), "Duplicate archive entry")
        versions = fields(zipped.read("THIRD_PARTY_VERSIONS.txt").decode("utf-8"))
        expect_fields(versions, {"NeoMusicBot commit": sha, "platform": platform})
        for extension in ("json", "xml"):
            raw = zipped.read("NeoMusicBot.cdx." + extension)
            require(raw == (folder / ("bom." + extension)).read_bytes(), "External SBOM differs from bundle")
            actual_version = (json.loads(raw)["metadata"]["component"]["version"] if extension == "json"
                              else ET.fromstring(raw).findtext("{*}metadata/{*}component/{*}version"))
            require(actual_version == version, "SBOM and tag versions differ")
        with zipfile.ZipFile(io.BytesIO(zipped.read("NeoMusicBot.jar"))) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        matches = re.findall(r"^Implementation-Version: ([^\r\n]+)", manifest, re.MULTILINE)
        require(matches == [version], "JAR and tag versions differ")
        suffix = ".exe" if platform.startswith("windows") else ""
        # deno.sha256 identifies the upstream ZIP, not its extracted executable;
        # assembly verifies that ZIP and the clean-bundle report binds its output.
        tools = {"yt-dlp": "tools/yt-dlp" + suffix,
                 "bgutil-provider.plugin": "tools/yt-dlp-plugins/bgutil-ytdlp-pot-provider.zip"}
        for tool, name in tools.items():
            with zipped.open(name) as stream:
                actual_hash = hashlib.file_digest(stream, "sha256").hexdigest()
            require(versions.get(tool + ".sha256") == actual_hash, "Bundled tool hash mismatch: " + tool)
    return versions


def prepare(artifacts, destination, env):
    artifacts, destination = Path(artifacts), Path(destination)
    version, sha = prerelease_version(env["VERSION_NUMBER"]), env["GITHUB_SHA"]
    versions = {}
    for platform in PLATFORMS:
        versions[platform] = verify_platform(artifacts / ("NeoMusicBot-" + platform), platform, sha, version)
    shared = ("yt-dlp.version", "deno.version", "bgutil-provider.version", "bgutil-provider.commit",
              "bgutil-provider.plugin.sha256")
    linux, windows = (versions[platform] for platform in PLATFORMS)
    for key in shared:
        require(linux.get(key) and linux.get(key) == windows.get(key), "Platform tool versions differ: " + key)
    candidate = artifacts / "NeoMusicBot-media-canary/candidate.txt"
    media = fields(candidate.read_text(encoding="utf-8"))
    expected = {key: linux[key] for key in (*shared, "yt-dlp.sha256", "deno.sha256")}
    expected.update({"mode": "locked", "repository": "yt-dlp/yt-dlp", "release.tag": linux["yt-dlp.version"],
                     "neomusicbot.commit": sha, "youtube.anonymous": "passed",
                     "youtube.mweb-provider": "passed", "bilibili.anonymous": "passed"})
    expect_fields(media, expected)
    # All validation precedes copying. An existing output directory is never overwritten.
    destination.mkdir()
    for platform in PLATFORMS:
        folder = artifacts / ("NeoMusicBot-" + platform)
        for extension in ("zip", "zip.sha256"):
            name = f"NeoMusicBot-{platform}.{extension}"
            shutil.copyfile(folder / name, destination / name)
        for extension in ("json", "xml"):
            shutil.copyfile(folder / ("bom." + extension), destination / f"NeoMusicBot-{platform}.cdx.{extension}")
        shutil.copyfile(folder / "bundle-verification.txt", destination / f"NeoMusicBot-{platform}-verification.txt")
    shutil.copyfile(candidate, destination / "NeoMusicBot-media-canary.txt")
    provenance = {"repository": env["GITHUB_REPOSITORY"], "commit": sha, "tag": env["VERSION_NUMBER"],
                  "build_run_id": int(env["BUILD_RUN_ID"]), "version": version}
    (destination / "NeoMusicBot-build.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    files = sorted(destination.iterdir())
    (destination / "SHA256SUMS").write_text("".join(f"{digest(p)}  {p.name}\n" for p in files), encoding="ascii")


def verify_download(expected, downloaded):
    expected, downloaded = Path(expected), Path(downloaded)
    originals = {p.name: digest(p) for p in expected.iterdir() if p.is_file()}
    fetched = {p.name: digest(p) for p in downloaded.iterdir() if p.is_file()}
    require(bool(originals) and originals == fetched, "Uploaded release assets differ from verified build files")


def verify_release(release, tag_ref, env, draft):
    prerelease_version(env["VERSION_NUMBER"])
    require(release.get("tag_name") == env["VERSION_NUMBER"] and release.get("draft") is draft
            and release.get("prerelease") is True, "Release is not in the expected prerelease state")
    require(tag_ref.get("ref") == "refs/tags/" + env["VERSION_NUMBER"]
            and tag_ref.get("object", {}).get("type") == "commit"
            and tag_ref["object"].get("sha") == env["GITHUB_SHA"], "Release tag points to another commit")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


if __name__ == "__main__":
    command, *args = sys.argv[1:]
    if command == "source":
        validate_source(read_json(args[0]), read_json(args[1]), os.environ, "pom.xml")
    elif command == "prepare":
        prepare(*args, os.environ)
    elif command == "platform":
        verify_platform(Path(args[0]), args[1], os.environ["GITHUB_SHA"],
                        ET.parse("pom.xml").getroot().findtext("{*}version"))
        print("Platform release evidence verified: " + args[1])
    elif command == "download":
        verify_download(*args)
    elif command in ("draft", "published"):
        verify_release(read_json(args[0]), read_json(args[1]), os.environ, command == "draft")
    else:
        raise ValueError("Unknown validation command")
