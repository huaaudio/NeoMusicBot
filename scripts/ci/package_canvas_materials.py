"""Collect and verify source/license materials for the pinned Windows Canvas DLLs.

This checks the reviewed package records, not completeness of embedded Rust or
other subcomponent licensing. See src/license/canvas/README.md for that boundary.
"""
import argparse
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request

from install_canvas_native import DEFINITION as NATIVE_DEFINITION, definition_for, package_root
from package_bundle import digest

DEFINITION = Path(__file__).resolve().parents[2] / "src/license/canvas/windows"


def filename(value):
    if not value or Path(value).name != value or value in (".", "..") or "\\" in value or ":" in value:
        raise ValueError("Unsafe Canvas material filename")
    return value


def validate(definition, native_definition):
    definition = Path(definition)
    data = json.loads((definition / "manifest.json").read_text(encoding="utf-8"))
    native, spec, native_files = definition_for(native_definition, "windows-x86-64")
    if (data.get("schema_version") != 1 or data.get("platform") != "windows-x86-64"
            or data.get("canvas_version") != native["version"] or data.get("canvas_archive_sha256") != spec["sha256"]):
        raise ValueError("Canvas materials refer to a different native prebuild")
    expected = {name: item["sha256"] for name, item in native_files.items() if name.endswith(".dll")}
    covered, packages, sources, licenses = {}, set(), set(), set()
    for component in data["components"]:
        if component["package"] in packages or not component["licenses"]:
            raise ValueError("Duplicate package or missing Canvas license records")
        packages.add(component["package"])
        source = component["source"]
        name = filename(source["file"])
        if name in sources or source["url"] != "https://repo.msys2.org/mingw/sources/" + name:
            raise ValueError("Duplicate or unexpected Canvas source archive")
        sources.add(name)
        for item in component["files"]:
            if item["path"] in covered:
                raise ValueError("Duplicate Canvas DLL record")
            covered[item["path"]] = item["sha256"]
        for document in component["licenses"]:
            name = filename(document["file"])
            if name in licenses:
                raise ValueError("Duplicate Canvas license record")
            licenses.add(name)
            if digest(definition / name) != document["sha256"]:
                raise ValueError("Canvas license definition checksum mismatch")
    if not covered or covered != expected:
        raise ValueError("Canvas material records do not cover the pinned DLLs")
    return data


def verify(bundle, definition=DEFINITION, native_definition=NATIVE_DEFINITION):
    bundle = Path(bundle).resolve()
    data = validate(definition, native_definition)
    documents = bundle / "licenses/canvas/windows"
    if json.loads((documents / "manifest.json").read_text(encoding="utf-8")) != data:
        raise ValueError("Shipped Canvas material records differ from reviewed definition")
    canvas = package_root(bundle / "tools/bgutil-provider", data["canvas_version"])
    for component in data["components"]:
        for item in component["files"]:
            if digest(canvas / item["path"]) != item["sha256"]:
                raise ValueError("Canvas DLL differs from its source package record")
        for item in component["licenses"]:
            if digest(documents / item["file"]) != item["sha256"]:
                raise ValueError("Shipped Canvas license checksum mismatch")
        source = component["source"]
        path = bundle / "sources/canvas/windows" / source["file"]
        if path.stat().st_size != source["bytes"] or digest(path) != source["sha256"]:
            raise ValueError("Canvas source archive checksum mismatch")
    print(f"Canvas Windows materials: {len(data['components'])} source packages and license records verified")


def prepare(bundle, definition=DEFINITION, native_definition=NATIVE_DEFINITION, source_cache=None):
    bundle, definition = Path(bundle).resolve(), Path(definition)
    data = validate(definition, native_definition)
    documents = bundle / "licenses/canvas/windows"
    sources = bundle / "sources/canvas/windows"
    documents.mkdir(parents=True, exist_ok=True)
    sources.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(definition / "manifest.json", documents / "manifest.json")
    shutil.copyfile(definition.parent / "README.md", documents.parent / "README.md")
    for component in data["components"]:
        for document in component["licenses"]:
            shutil.copyfile(definition / document["file"], documents / document["file"])
        source = component["source"]
        destination = sources / source["file"]
        with tempfile.TemporaryDirectory(prefix=".source-", dir=sources) as work:
            temporary = Path(work) / source["file"]
            if source_cache is None:
                with urllib.request.urlopen(source["url"], timeout=90) as response, temporary.open("xb") as target:
                    shutil.copyfileobj(response, target)
            else:
                shutil.copyfile(Path(source_cache) / source["file"], temporary)
            if temporary.stat().st_size != source["bytes"] or digest(temporary) != source["sha256"]:
                raise ValueError("Downloaded Canvas source archive checksum mismatch")
            temporary.replace(destination)
    verify(bundle, definition, native_definition)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--source-cache", type=Path, help="Use local source archives with the same pinned hashes")
    args = parser.parse_args()
    prepare(args.bundle, source_cache=args.source_cache)
