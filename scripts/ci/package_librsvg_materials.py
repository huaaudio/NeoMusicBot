"""Ship the locked Cargo source graphs supporting the Windows librsvg package.

The union includes build, test, optional and other-platform dependencies. It is
not a census of crates linked into the DLL. No Cargo/npm scripts are executed.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile
import tomllib
import urllib.request

from install_canvas_native import DEFINITION as NATIVE_DEFINITION, package_root
from package_bundle import digest
from package_canvas_materials import DEFINITION as PARENT_DEFINITION, validate as validate_parent

DEFINITION = Path(__file__).resolve().parents[2] / "src/license/canvas/librsvg-rust"
SOURCE_DIR = "sources/canvas/windows/librsvg-cargo"
LICENSE_DIR = "licenses/canvas/librsvg-rust"
REGISTRY = "registry+https://github.com/rust-lang/crates.io-index"


def safe_relative(value):
    path = PurePosixPath(value)
    if (not value or value in (".", "..") or path.is_absolute() or path.as_posix() != value
            or any(part in (".", "..") or part.endswith((".", " "))
                   or re.search(r'[<>:"\\|?*\x00-\x1f]', part)
                   or re.fullmatch(r"(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?", part)
                   for part in path.parts)):
        raise ValueError("Unsafe Cargo material path")
    return value


def checked_file(path, record):
    if (path.is_symlink() or path.stat().st_size != record["bytes"]
            or digest(path) != record["sha256"]):
        raise ValueError("Cargo material checksum mismatch: " + path.name)


def locked_packages(path):
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    packages = {}
    for package in data["package"]:
        if "source" not in package:  # Workspace sources are in the parent source package.
            continue
        if package["source"] != REGISTRY:
            raise ValueError("Cargo lock has an unsupported source")
        key = (package["name"], package["version"])
        if key in packages:
            raise ValueError("Duplicate locked Cargo package")
        packages[key] = package["checksum"]
    return packages


def validate(definition=DEFINITION, parent_definition=PARENT_DEFINITION, native_definition=NATIVE_DEFINITION):
    definition = Path(definition)
    data = json.loads((definition / "manifest.json").read_text(encoding="utf-8"))
    parent = validate_parent(parent_definition, native_definition)
    if (data.get("schema_version") != 1 or data.get("platform") != parent["platform"]
            or data.get("canvas_version") != parent["canvas_version"]
            or data.get("parent") not in parent["components"]):
        raise ValueError("Cargo materials refer to a different native source package")
    for kind in ("source_documents", "license_documents"):
        seen = set()
        for item in data[kind]:
            name = safe_relative(item["file"])
            if "/" in name or name.casefold() in seen or name == "manifest.json":
                raise ValueError("Duplicate or invalid Cargo document")
            seen.add(name.casefold())
            checked_file(definition / name, item)
    source_names = {item["file"] for item in data["source_documents"]}
    expected = {}
    if len(data["locks"]) != 2 or len(set(data["locks"])) != 2:
        raise ValueError("Both original and reconstructed Cargo locks are required")
    for name in data["locks"]:
        if name not in source_names:
            raise ValueError("Cargo lock is not a checked source document")
        for key, checksum in locked_packages(definition / name).items():
            if key in expected and expected[key] != checksum:
                raise ValueError("Cargo locks disagree about a package checksum")
            expected[key] = checksum
    supplements = {item["file"] for item in data["license_documents"] if item.get("kind") in (
        "upstream-license", "standard-reference")}
    actual, filenames = {}, set()
    for item in data["packages"]:
        name, version = item["name"], item["version"]
        if not re.fullmatch(r"[A-Za-z0-9_-]+", name) or not re.fullmatch(r"[A-Za-z0-9.+-]+", version):
            raise ValueError("Invalid Cargo package identity")
        key = (name, version)
        archive = f"{name}-{version}.crate"
        if (key in actual or item["file"] != archive or archive.casefold() in filenames
                or item["url"] != f"https://static.crates.io/crates/{name}/{archive}"
                or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]) or item["bytes"] <= 0):
            raise ValueError("Duplicate or invalid Cargo archive record")
        actual[key] = item["sha256"]
        filenames.add(archive.casefold())
        documents = set()
        for document in item["documents"]:
            path = safe_relative(document["path"])
            if path.casefold() in documents:
                raise ValueError("Duplicate Cargo license entry")
            documents.add(path.casefold())
        if (not documents and not item["supplements"]
                or not set(item["supplements"]).issubset(supplements)):
            raise ValueError("Missing Cargo license or declared reference text")
    if not expected or actual != expected:
        raise ValueError("Cargo source records do not cover the complete lock union")
    return data


def extract_documents(archive_path, item, destination):
    """Read checked regular entries; never extract an untrusted archive tree."""
    prefix = f"{item['name']}-{item['version']}/"
    wanted = {document["path"]: document for document in item["documents"]}
    with tarfile.open(archive_path, "r:gz") as archive:
        members = {}
        for member in archive:
            name = member.name.rstrip("/") if member.isdir() else member.name
            safe_relative(name)
            if (not name.startswith(prefix) and name != prefix[:-1]
                    or not (member.isfile() or member.isdir()) or name in members):
                raise ValueError("Unexpected Cargo archive entry")
            members[name] = member

        def read(relative, limit):
            member = members.get(prefix + relative)
            if member is None or not member.isfile() or member.size > limit:
                raise ValueError("Missing or invalid Cargo archive document")
            with archive.extractfile(member) as stream:
                return stream.read()

        metadata = tomllib.loads(read("Cargo.toml", 1024 * 1024).decode("utf-8"))["package"]
        if (metadata["name"] != item["name"] or metadata["version"] != item["version"]
                or metadata.get("license") != item["license"]
                or metadata.get("license-file") != item["license_file"]):
            raise ValueError("Cargo package metadata differs from reviewed definition")
        for relative, document in wanted.items():
            content = read(relative, 4 * 1024 * 1024)
            if len(content) != document["bytes"] or hashlib.sha256(content).hexdigest() != document["sha256"]:
                raise ValueError("Cargo archive license checksum mismatch")
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)


def verify(bundle, definition=DEFINITION, parent_definition=PARENT_DEFINITION, native_definition=NATIVE_DEFINITION):
    bundle = Path(bundle).resolve()
    data = validate(definition, parent_definition, native_definition)
    licenses, sources = bundle / LICENSE_DIR, bundle / SOURCE_DIR
    if json.loads((licenses / "manifest.json").read_text(encoding="utf-8")) != data:
        raise ValueError("Shipped Cargo records differ from reviewed definition")
    canvas = package_root(bundle / "tools/bgutil-provider", data["canvas_version"])
    for item in data["parent"]["files"]:
        if digest(canvas / item["path"]) != item["sha256"]:
            raise ValueError("Cargo materials do not match the shipped librsvg DLL")
    checked_file(bundle / "sources/canvas/windows" / data["parent"]["source"]["file"], data["parent"]["source"])
    for records, directory in ((data["source_documents"], sources), (data["license_documents"], licenses)):
        for item in records:
            checked_file(directory / item["file"], item)
    for item in data["packages"]:
        checked_file(sources / item["file"], item)
        for document in item["documents"]:
            checked_file(licenses / f"{item['name']}-{item['version']}" / document["path"], document)
    print(f"librsvg Cargo materials: {len(data['packages'])} locked source archives and "
          f"{sum(len(item['documents']) for item in data['packages'])} original documents verified")


def prepare(bundle, definition=DEFINITION, parent_definition=PARENT_DEFINITION,
            native_definition=NATIVE_DEFINITION, source_cache=None):
    bundle, definition = Path(bundle).resolve(), Path(definition)
    data = validate(definition, parent_definition, native_definition)
    sources, licenses = bundle / SOURCE_DIR, bundle / LICENSE_DIR
    sources.mkdir(parents=True, exist_ok=True)
    licenses.mkdir(parents=True, exist_ok=True)
    for records, directory in ((data["source_documents"], sources), (data["license_documents"], licenses)):
        for item in records:
            shutil.copyfile(definition / item["file"], directory / item["file"])
    shutil.copyfile(definition / "manifest.json", licenses / "manifest.json")

    def collect(item):
        with tempfile.TemporaryDirectory(prefix=".cargo-", dir=sources) as work:
            temporary = Path(work) / item["file"]
            if source_cache is None:
                with urllib.request.urlopen(item["url"], timeout=90) as response, temporary.open("xb") as target:
                    shutil.copyfileobj(response, target)
            else:
                shutil.copyfile(Path(source_cache) / item["file"], temporary)
            checked_file(temporary, item)
            extract_documents(temporary, item, licenses / f"{item['name']}-{item['version']}")
            temporary.replace(sources / item["file"])

    with ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(collect, data["packages"]))
    verify(bundle, definition, parent_definition, native_definition)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--source-cache", type=Path, help="Use local archives with the same pinned hashes")
    args = parser.parse_args()
    prepare(args.bundle, source_cache=args.source_cache)
