"""Collect the complete pinned librsvg Linux Cargo source graph and documents.

This does not select or publish a native runtime. Its parent is the original
source archive; binary association is completed when the native asset is fixed.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
import urllib.request

from build_linux_canvas import DEFINITION as BUILD_DEFINITION, ROOT

sys.path.insert(0, str(ROOT / "scripts/ci"))
from package_librsvg_materials import checked_file, extract_documents, locked_packages, safe_relative

DEFINITION = ROOT / "src/license/canvas/linux-librsvg-rust"
SOURCE_DIR = "sources/canvas/linux/librsvg-cargo"
LICENSE_DIR = "licenses/canvas/linux-librsvg-rust"


def validate(definition=DEFINITION, build_definition=BUILD_DEFINITION):
    definition = Path(definition)
    data = json.loads((definition / "manifest.json").read_text(encoding="utf-8"))
    build = json.loads((Path(build_definition) / "inputs.json").read_text(encoding="utf-8"))
    parent = next(item for item in build["sources"] if item["name"] == "librsvg")
    canvas = next(item for item in build["sources"] if item["name"] == "canvas")
    if (data.get("schema_version") != 1 or data.get("platform") != "linux-x86-64"
            or data.get("parent") != parent or data.get("canvas_version") != canvas["version"]
            or data.get("locks") != ["Cargo.lock"]):
        raise ValueError("Linux Cargo materials differ from the pinned source build")
    source_names = set()
    supplement_names = set()
    for group in ("source_documents", "license_documents"):
        seen = set()
        for document in data[group]:
            name = safe_relative(document["file"])
            if "/" in name or name.casefold() in seen or name == "manifest.json":
                raise ValueError("Duplicate or invalid Linux Cargo document")
            seen.add(name.casefold())
            checked_file(definition / name, document)
            if group == "source_documents":
                source_names.add(name)
            elif document.get("kind") in ("upstream-license", "standard-reference"):
                supplement_names.add(name)
    if "Cargo.lock" not in source_names:
        raise ValueError("The source lock is not a checked document")
    expected = locked_packages(definition / "Cargo.lock")
    actual = {}
    filenames = set()
    for item in data["packages"]:
        name, version = item["name"], item["version"]
        if not re.fullmatch(r"[A-Za-z0-9_-]+", name) or not re.fullmatch(r"[A-Za-z0-9.+-]+", version):
            raise ValueError("Invalid Cargo package identity")
        identity = (name, version)
        filename = f"{name}-{version}.crate"
        if (identity in actual or item["file"] != filename or filename.casefold() in filenames
                or item["url"] != f"https://static.crates.io/crates/{name}/{filename}"
                or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]) or item["bytes"] <= 0):
            raise ValueError("Duplicate or invalid Linux Cargo archive record")
        actual[identity] = item["sha256"]
        filenames.add(filename.casefold())
        documents = set()
        for document in item["documents"]:
            relative = safe_relative(document["path"])
            if relative.casefold() in documents:
                raise ValueError("Duplicate Cargo document path")
            documents.add(relative.casefold())
        if (not documents and not item["supplements"]
                or not set(item["supplements"]).issubset(supplement_names)):
            raise ValueError("Missing original document or declared supplement")
    if not expected or actual != expected:
        raise ValueError("Linux Cargo records do not match the complete source lock")
    return data


def download(item, cache):
    target = cache / item["file"]
    if target.exists() or target.is_symlink():
        checked_file(target, item)
        return target
    with tempfile.NamedTemporaryFile(dir=cache, prefix="cargo-", delete=False) as temporary:
        path = Path(temporary.name)
        with urllib.request.urlopen(item["url"], timeout=90) as response:
            shutil.copyfileobj(response, temporary)
    checked_file(path, item)
    path.replace(target)
    return target


def check_tree(directory, expected):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Missing or linked Linux Cargo material directory")
    entries = list(directory.rglob("*"))
    if any(path.is_symlink() or not (path.is_file() or path.is_dir()) for path in entries):
        raise ValueError("Linked or special Linux Cargo material")
    if {path.relative_to(directory).as_posix() for path in entries if path.is_file()} != expected:
        raise ValueError("Linux Cargo material file set differs from the definition")


def verify(output, definition=DEFINITION, build_definition=BUILD_DEFINITION):
    definition, output = Path(definition), Path(output)
    data = validate(definition, build_definition)
    sources, licenses = output / SOURCE_DIR, output / LICENSE_DIR
    expected_sources = {item["file"] for item in data["source_documents"] + data["packages"]}
    expected_licenses = {"manifest.json", *(item["file"] for item in data["license_documents"])}
    for item in data["packages"]:
        expected_licenses.update(item["name"] + "-" + item["version"] + "/" + document["path"]
                                 for document in item["documents"])
    check_tree(sources, expected_sources)
    check_tree(licenses, expected_licenses)
    if json.loads((licenses / "manifest.json").read_text(encoding="utf-8")) != data:
        raise ValueError("Shipped Linux Cargo manifest differs from the repository")
    for item in data["source_documents"]:
        checked_file(sources / item["file"], item)
    for item in data["license_documents"]:
        checked_file(licenses / item["file"], item)
    with tempfile.TemporaryDirectory(prefix="linux-cargo-check-") as temporary:
        for item in data["packages"]:
            archive = sources / item["file"]
            checked_file(archive, item)
            extract_documents(archive, item, Path(temporary) / (item["name"] + "-" + item["version"]))
            for document in item["documents"]:
                checked_file(licenses / (item["name"] + "-" + item["version"]) / document["path"], document)
    return dict(packages=len(data["packages"]), original_documents=sum(len(item["documents"]) for item in data["packages"]),
                supplements=sum(item.get("kind") in ("upstream-license", "standard-reference") for item in data["license_documents"]))


def package(output, cache, definition=DEFINITION, build_definition=BUILD_DEFINITION):
    data = validate(definition, build_definition)
    output, cache, definition = Path(output), Path(cache), Path(definition)
    output.mkdir(parents=True, exist_ok=False)
    cache.mkdir(parents=True, exist_ok=True)
    sources, licenses = output / SOURCE_DIR, output / LICENSE_DIR
    sources.mkdir(parents=True)
    licenses.mkdir(parents=True)
    for group, destination in (("source_documents", sources), ("license_documents", licenses)):
        for item in data[group]:
            shutil.copyfile(definition / item["file"], destination / item["file"])
    shutil.copyfile(definition / "manifest.json", licenses / "manifest.json")

    def collect(item):
        archive = download(item, cache)
        shutil.copyfile(archive, sources / item["file"])
        extract_documents(archive, item, licenses / (item["name"] + "-" + item["version"]))
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(collect, data["packages"]))
    return verify(output, definition, build_definition)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.verify_only:
        result = verify(args.output_dir)
    else:
        if args.cache_dir is None:
            parser.error("--cache-dir is required for collection")
        result = package(args.output_dir, args.cache_dir)
    print(json.dumps(dict(result, native_binary_association="pending-final-source-build-asset")), flush=True)


if __name__ == "__main__":
    main()
