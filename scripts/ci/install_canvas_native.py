"""Install and verify pinned Canvas prebuilds without running npm install scripts."""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import urllib.request

from package_bundle import digest


DEFINITION = Path(__file__).resolve().parents[2] / "src/provider-runtime/canvas-native.json"
RECORD = "neomusicbot-canvas.json"


def safe_name(name):
    path = PurePosixPath(name)
    if (not name or "\\" in name or ":" in name or path.is_absolute()
            or ".." in path.parts or path.as_posix() != name
            or path.parts[0] != "build"):
        raise ValueError("Unsafe Canvas archive path")
    return path


def definition_for(definition, platform):
    data = json.loads(Path(definition).read_text(encoding="utf-8"))
    if data.get("schema_version") != 1 or data.get("package") != "canvas":
        raise ValueError("Unsupported Canvas native definition")
    if platform not in data["platforms"]:
        raise ValueError("Unsupported Canvas platform")
    spec = data["platforms"][platform]
    files = {}
    for item in spec["files"]:
        name = item["path"]
        safe_name(name)
        if name == "build" or name.casefold() in {p.casefold() for p in files}:
            raise ValueError("Duplicate or invalid Canvas file")
        files[name] = item
    if "build/Release/canvas.node" not in files:
        raise ValueError("Canvas native module is missing from definition")
    return data, spec, files


def package_root(provider, version):
    provider = Path(provider).resolve()
    canvas = (provider / "server/node_modules/canvas").resolve()
    if not canvas.is_relative_to(provider):
        raise ValueError("Canvas package is outside provider")
    package = json.loads((canvas / "package.json").read_text(encoding="utf-8"))
    if package.get("name") != "canvas" or package.get("version") != version:
        raise ValueError("Installed Canvas package differs from pinned version")
    return canvas


def verify_files(canvas, files):
    build = canvas / "build"
    if build.is_symlink() or not build.is_dir():
        raise ValueError("Canvas native build is missing or linked")
    paths = list(build.rglob("*"))
    if any(path.is_symlink() or not (path.is_file() or path.is_dir()) for path in paths):
        raise ValueError("Canvas native build contains a link or special file")
    actual = {p.relative_to(canvas).as_posix(): p for p in paths if p.is_file()}
    if actual.keys() != files.keys():
        raise ValueError("Canvas native file set differs from definition")
    for name, path in actual.items():
        if path.stat().st_size != files[name]["bytes"] or digest(path) != files[name]["sha256"]:
            raise ValueError("Canvas native file checksum mismatch: " + name)


def verify(provider, platform, definition=DEFINITION):
    data, _, files = definition_for(definition, platform)
    canvas = package_root(provider, data["version"])
    record = Path(provider) / "server" / RECORD
    if record.is_symlink() or json.loads(record.read_text(encoding="utf-8")) != dict(data, installed_platform=platform):
        raise ValueError("Canvas native provenance differs from pinned definition")
    verify_files(canvas, files)


def unpack(archive_path, stage, files):
    allowed_dirs = {parent.as_posix() for name in files for parent in PurePosixPath(name).parents
                    if parent != PurePosixPath(".")}
    seen = set()
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive:
            name = member.name.removeprefix("./").rstrip("/")
            safe_name(name)
            if name.casefold() in seen:
                raise ValueError("Duplicate Canvas archive entry")
            seen.add(name.casefold())
            if member.isdir() and name in allowed_dirs:
                (stage / name).mkdir(parents=True, exist_ok=True)
                continue
            if not member.isfile() or name not in files or member.size != files[name]["bytes"]:
                raise ValueError("Unexpected Canvas archive entry")
            destination = stage / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, destination.open("xb") as target:
                shutil.copyfileobj(source, target)
            destination.chmod(0o755 if member.mode & 0o111 else 0o644)
    verify_files(stage, files)


def install(provider, platform, definition=DEFINITION, archive_path=None):
    data, spec, files = definition_for(definition, platform)
    canvas = package_root(provider, data["version"])
    record = Path(provider) / "server" / RECORD
    if (canvas / "build").exists() or (canvas / "build").is_symlink() or record.exists() or record.is_symlink():
        raise ValueError("Canvas native build or provenance already exists; use a fresh installation")
    # Stage under the validated package, on the same filesystem as the final build.
    # Invalid downloads/archives never modify the installed package or record.
    with tempfile.TemporaryDirectory(prefix=".neomusicbot-native-", dir=canvas) as work:
        work = Path(work)
        if archive_path is None:
            archive_path = work / "canvas.tar.gz"
            if not spec["url"].startswith("https://github.com/Automattic/node-canvas/releases/download/"):
                raise ValueError("Unexpected Canvas download origin")
            with urllib.request.urlopen(spec["url"], timeout=60) as response, archive_path.open("xb") as target:
                shutil.copyfileobj(response, target)
        if digest(Path(archive_path)) != spec["sha256"]:
            raise ValueError("Canvas archive checksum mismatch")
        stage = work / "unpacked"
        stage.mkdir()
        unpack(archive_path, stage, files)
        (stage / "build").rename(canvas / "build")
        with record.open("x", encoding="utf-8", newline="\n") as target:
            json.dump(dict(data, installed_platform=platform), target, indent=2)
            target.write("\n")
    verify(provider, platform, definition)
    print(f"Canvas {data['version']} {platform}: {len(files)} pinned native files verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("provider", type=Path)
    parser.add_argument("platform", choices=("linux-x86-64", "windows-x86-64"))
    parser.add_argument("--archive", type=Path, help="Use a local archive with the same pinned checksum")
    args = parser.parse_args()
    install(args.provider, args.platform, archive_path=args.archive)
