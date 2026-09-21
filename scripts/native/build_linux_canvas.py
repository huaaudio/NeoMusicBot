"""Build the Linux Canvas runtime from pinned sources in a private SDK.

Requires Ubuntu 24.04 x86-64, GCC 13, make, ninja, pkg-config, dpkg-deb,
patchelf's runtime dependencies, Python 3.11+, and normal build utilities.
No package is installed on the host. This does not publish a native asset.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DEFINITION = ROOT / "src/provider-runtime/native-linux"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for body in iter(lambda: source.read(1024 * 1024), b""):
            value.update(body)
    return value.hexdigest()


def safe_filename(value):
    if not re.fullmatch(r"[A-Za-z0-9.+~_%-]+", value):
        raise ValueError("Invalid pinned input filename")
    return value


def download(record, cache):
    filename = safe_filename(record.get("filename", record["url"].rsplit("/", 1)[1]))
    expected = record["sha256"]
    if not record["url"].startswith("https://") or not re.fullmatch("[a-f0-9]{64}", expected):
        raise ValueError("Unpinned input")
    # The digest in the name separates different inputs with the same basename.
    path = cache / (expected + "-" + filename)
    if path.is_symlink():
        raise ValueError("Linked download cache entry")
    if not path.exists():
        temporary = path.with_name(path.name + ".partial")
        if temporary.is_symlink():
            raise ValueError("Linked partial cache entry")
        with urllib.request.urlopen(record["url"], timeout=90) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        if digest(temporary) != expected:
            raise ValueError("Input download checksum mismatch: " + filename)
        temporary.replace(path)
    if digest(path) != expected or "bytes" in record and path.stat().st_size != record["bytes"]:
        raise ValueError("Cached input differs from definition: " + filename)
    return path


def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as package:
        package.extractall(destination, filter="data")


def prepare(data, work, cache, env):
    marker = work / "prepared.json"
    expected = {name: digest(DEFINITION / name) for name in ("inputs.json", "package.json", "package-lock.json")}
    if marker.exists():
        if json.loads(marker.read_text()) != expected:
            raise ValueError("Build inputs changed; select a fresh work directory")
        return
    if any((work / name).exists() for name in ("sdk", "sources", "tools")):
        raise ValueError("Incomplete preparation; select a fresh work directory")
    records = [*data["sources"], *data["sdk_packages"], data["tools"]["meson"], data["tools"]["node"],
               data["tools"]["cargo_c"], *data["tools"]["rust"]["components"]]
    with ThreadPoolExecutor(max_workers=4) as pool:
        downloaded = list(pool.map(lambda record: download(record, cache), records))
    archives = {record["sha256"]: path for record, path in zip(records, downloaded)}
    for record in data["sources"]:
        extract(archives[record["sha256"]], work / "sources" / safe_filename(record["name"]))
    sdk = work / "sdk"
    sdk.mkdir()
    for record in data["sdk_packages"]:
        subprocess.run(["dpkg-deb", "--extract", str(archives[record["sha256"]]), str(sdk)], check=True, env=env)
    tools = work / "tools"
    tools.mkdir()
    with zipfile.ZipFile(archives[data["tools"]["meson"]["sha256"]]) as package:
        destination = tools / "meson"
        if any(not (destination / name).resolve().is_relative_to(destination.resolve()) for name in package.namelist()):
            raise ValueError("Unsafe Meson wheel path")
        package.extractall(destination)
    extract(archives[data["tools"]["node"]["sha256"]], tools / "node")
    extract(archives[data["tools"]["cargo_c"]["sha256"]], tools / "cargo-c")
    for record in data["tools"]["rust"]["components"]:
        archive = archives[record["sha256"]]
        folder = record["url"].rsplit("/", 1)[1].removesuffix(".tar.xz")
        extract(archive, tools / "rust-components")
        subprocess.run(["bash", str(tools / "rust-components" / folder / "install.sh"),
                        "--prefix=" + str(tools / "rust"), "--disable-ldconfig"], check=True, env=env)
    node = tools / "node" / data["tools"]["node"]["source_folder"]
    npm = node / "lib/node_modules/npm/bin/npm-cli.js"
    npm_root = work / "build-tools"
    npm_root.mkdir()
    for name in ("package.json", "package-lock.json"):
        shutil.copyfile(DEFINITION / name, npm_root / name)
    npm_env = dict(env, PATH=str(node / "bin") + ":" + env["PATH"], npm_config_cache=str(work / "npm-cache"))
    subprocess.run([str(node / "bin/node"), str(npm), "ci", "--ignore-scripts", "--no-audit", "--no-fund"],
                   check=True, cwd=npm_root, env=npm_env)
    canvas = work / "sources/canvas/package"
    (canvas / "node_modules").symlink_to(npm_root / "node_modules", target_is_directory=True)
    marker.write_text(json.dumps(expected, indent=2) + "\n")
    print("NativeBuildInputsPrepared=true", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.jobs <= 32:
        parser.error("--jobs must be between 1 and 32")
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("This recipe requires Linux x86-64")
    os_release = platform.freedesktop_os_release()
    if os_release.get("ID") != "ubuntu" or os_release.get("VERSION_ID") != "24.04":
        raise RuntimeError("This recipe currently requires Ubuntu 24.04")
    for name in ("gcc", "g++", "make", "ninja", "pkg-config", "dpkg-deb", "bash", "readelf"):
        if not shutil.which(name):
            raise RuntimeError("Required build tool is missing: " + name)
    compiler = subprocess.check_output(["gcc", "-dumpfullversion"], text=True).strip()
    if not compiler.startswith("13."):
        raise RuntimeError("This recipe currently requires GCC 13")
    work, cache = args.work_dir.resolve(), args.cache_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    import fcntl
    with (work / "build.lock").open("a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        data = json.loads((DEFINITION / "inputs.json").read_text())
        if data.get("schema_version") != 1 or data.get("platform") != "linux-x86-64":
            raise ValueError("Unsupported build input definition")
        (work / "home").mkdir(exist_ok=True)
        env = dict(PATH="/usr/bin:/bin", HOME=str(work / "home"), LANG="C.UTF-8",
                   XDG_CACHE_HOME=str(work / "cache"), CARGO_HOME=str(work / "cargo-home"),
                   CARGO_NET_RETRY="3", CARGO_BUILD_JOBS=str(args.jobs))
        prepare(data, work, cache, env)
        if not args.prepare_only:
            subprocess.run(["bash", str(Path(__file__).with_name("linux_canvas_steps.sh")),
                            str(work), str(args.jobs)], check=True, env=env)
        print("NativeBuildWork=" + str(work), flush=True)


if __name__ == "__main__":
    main()
