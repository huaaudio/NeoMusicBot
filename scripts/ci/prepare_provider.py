"""Apply a reviewed runtime-only dependency graph without changing package payloads."""
import hashlib
import json
from pathlib import Path
import re
import sys


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def canonical_hash(value):
    # A Git checkout may change line endings. Compare the complete JSON data.
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=True).encode("utf-8")).hexdigest()


def coordinate(key):
    match = re.fullmatch(r"((?:@[^/]+/)?[^@]+)@([^_]+)(?:_.*)?", key)
    if not match:
        raise ValueError("Unrecognized provider dependency coordinate")
    return match[1], match[2]


def validate(package, upstream, runtime, profile, expected_commit=None):
    if (profile["schema_version"] != 1 or package["name"] != "bgutil-ytdlp-pot-provider"
            or package["version"] != profile["provider_version"]
            or (expected_commit is not None and profile["provider_commit"] != expected_commit)):
        raise ValueError("Provider runtime profile does not match the pinned source")
    for value, field in ((package, "upstream_package_canonical_sha256"),
                         (upstream, "upstream_lock_canonical_sha256"),
                         (runtime, "runtime_lock_canonical_sha256")):
        if canonical_hash(value) != profile[field]:
            raise ValueError("Provider runtime profile checksum mismatch: " + field)
    if set(runtime) != {"version", "specifiers", "npm", "workspace"} or runtime["version"] != upstream["version"]:
        raise ValueError("Unsupported provider runtime lock format")
    roots = set(package["dependencies"]) | set(package.get("optionalDependencies", {}))
    required = {key: version for key, version in upstream["specifiers"].items()
                if key.startswith("npm:") and coordinate(key[4:])[0] in roots}
    if (not roots or {coordinate(key[4:])[0] for key in required} != roots
            or runtime["specifiers"] != required
            or runtime["workspace"] != {"packageJson": {"dependencies": sorted(required)}}):
        raise ValueError("Provider runtime dependency roots changed")
    # Deno normalizes two redundant peer-context suffixes after pruning. Their
    # package versions, integrities and complete dependency records must match.
    originals = {}
    for key, value in upstream["npm"].items():
        originals.setdefault(coordinate(key), []).append(value)
    for key, value in runtime["npm"].items():
        if not value.get("integrity") or value not in originals.get(coordinate(key), []):
            raise ValueError("Provider runtime package differs from upstream: " + key)


def runtime_package(package):
    return {key: value for key, value in package.items() if key != "devDependencies"}


def prepare(provider, definition, expected_commit):
    server = Path(provider) / "server"
    definition = Path(definition)
    package = read(server / "package.json")
    upstream = read(server / "deno.lock")
    runtime = read(definition / "deno.lock")
    profile = read(definition / "profile.json")
    validate(package, upstream, runtime, profile, expected_commit)
    if any((server / name).exists() for name in
           ("package.json.upstream", "deno.lock.upstream", "neomusicbot-runtime.json")):
        raise ValueError("Provider runtime profile has already been applied")
    for name in ("package.json", "deno.lock"):
        (server / (name + ".upstream")).write_bytes((server / name).read_bytes())
    (server / "package.json").write_text(json.dumps(runtime_package(package), indent=2) + "\n", encoding="utf-8")
    (server / "deno.lock").write_bytes((definition / "deno.lock").read_bytes())
    (server / "neomusicbot-runtime.json").write_bytes((definition / "profile.json").read_bytes())
    (server / "NEOMUSICBOT-RUNTIME.md").write_bytes((definition / "README.md").read_bytes())
    verify(provider, expected_commit)


def verify(provider, expected_commit=None):
    server = Path(provider) / "server"
    profile = read(server / "neomusicbot-runtime.json")
    package = read(server / "package.json.upstream")
    validate(package, read(server / "deno.lock.upstream"), read(server / "deno.lock"),
             profile, expected_commit)
    if read(server / "package.json") != runtime_package(package):
        raise ValueError("Provider runtime package configuration changed")


if __name__ == "__main__":
    prepare(*sys.argv[1:])
