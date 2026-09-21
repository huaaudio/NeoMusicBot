"""Inventory shipped npm packages and license candidates; not a license approval."""
import json
import os
from pathlib import Path
import re
import sys
from package_bundle import digest


def inventory(provider):
    provider = Path(provider).resolve()
    server = provider / "server"
    lock_path = server / "deno.lock"
    if not lock_path.resolve().is_relative_to(provider):
        raise ValueError("Provider inventory contains an external lock path")
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    packages = {}
    visited = set()

    def relative(path):
        if not path.resolve().is_relative_to(provider):
            raise ValueError("Provider inventory contains an external path")
        return path.relative_to(provider).as_posix()

    def record_file(path):
        name = relative(path)
        return {"path": name, "sha256": digest(path)}

    def package(root):
        relative(root)
        if root in visited or not (root / "package.json").is_file():
            return
        visited.add(root)
        descriptor = record_file(root / "package.json")
        data = json.loads((root / "package.json").read_text(encoding="utf-8"))
        coordinate = data["name"] + "@" + data["version"]
        # Deno may suffix lock keys with peer dependency resolutions.
        entries = [value for key, value in lock["npm"].items()
                   if key == coordinate or key.startswith(coordinate + "_")]
        integrities = {entry.get("integrity") for entry in entries}
        if len(integrities) != 1 or not next(iter(integrities), None):
            raise ValueError("Missing or ambiguous npm lock integrity: " + coordinate)
        declared_license = data.get("license", data.get("licenses"))
        item = packages.setdefault(coordinate, {
            "name": data["name"], "version": data["version"],
            "integrity": next(iter(integrities)), "declared_license": declared_license,
            "package_descriptors": [], "license_candidates": [],
        })
        if item["declared_license"] != declared_license:
            raise ValueError("Installed and cached license metadata differ: " + coordinate)
        item["package_descriptors"].append(descriptor)
        for directory, dirs, files in os.walk(root, followlinks=False):
            dirs[:] = sorted(d for d in dirs if d not in {"node_modules", ".git"})
            for name in sorted(files):
                # Some packages put the complete base license in their README;
                # other files cover embedded components. Neither is auto-approved.
                if re.match(r"^(licen[sc]e|copying|notice)(\W|$)", name, re.I) or (
                        Path(directory) == root and name.lower().startswith("readme")):
                    item["license_candidates"].append(record_file(Path(directory) / name))
        if (root / "node_modules").is_dir():
            modules(root / "node_modules")

    def modules(root):
        relative(root)
        for child in sorted(root.iterdir()):
            if not child.is_dir() or child.name.startswith("."):
                continue
            if child.name.startswith("@"):
                for scoped in sorted(child.iterdir()):
                    if scoped.is_dir():
                        package(scoped)
            else:
                package(child)

    modules(server / "node_modules")
    cache = server / ".deno-dir/npm"
    if cache.is_dir():
        for registry in sorted(cache.iterdir()):
            if not registry.is_dir():
                continue
            for name in sorted(registry.iterdir()):
                if not name.is_dir():
                    continue
                names = sorted(name.iterdir()) if name.name.startswith("@") else [name]
                for cached_name in names:
                    if cached_name.is_dir():
                        for version in sorted(cached_name.iterdir()):
                            if version.is_dir():
                                package(version)
    if not packages:
        raise ValueError("Provider contains no npm package descriptors")
    return {
        "schema_version": 1,
        "scope": "npm packages in provider node_modules and bundled Deno npm cache",
        "license_review_complete": False,
        "note": "License candidates and declared identifiers require review; native/WASM dependencies are not certified.",
        "lockfile": record_file(lock_path),
        "packages": [packages[key] for key in sorted(packages)],
    }


def write(provider, destination):
    result = inventory(provider)
    Path(destination).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Inventoried {len(result['packages'])} provider npm packages; license review remains open")


def verify(provider, manifest):
    if json.loads(Path(manifest).read_text(encoding="utf-8")) != inventory(provider):
        raise ValueError("Provider dependency inventory does not match the extracted bundle")


if __name__ == "__main__":
    write(*sys.argv[1:])
