import copy
import json
from pathlib import Path
import tempfile
import unittest

from prepare_provider import canonical_hash, prepare, read, validate, verify
from package_bundle import package as zip_package
from verify_bundle import extract


class ProviderRuntimeTests(unittest.TestCase):
    def fixture(self, folder):
        provider = folder / "provider"
        server = provider / "server"
        server.mkdir(parents=True)
        definition = folder / "definition"
        definition.mkdir()
        package = {"name": "bgutil-ytdlp-pot-provider", "version": "2.0.0", "license": "GPL-3.0-only",
                   "dependencies": {"app": "^1.0.0"}, "devDependencies": {"lint": "^2.0.0"},
                   "scripts": {"lint": "lint"}, "contributors": ["Original author"]}
        upstream = {"version": "5", "specifiers": {"npm:app@^1.0.0": "1.0.0", "npm:lint@^2.0.0": "2.0.0"},
                    "npm": {"app@1.0.0": {"integrity": "sha512-app", "dependencies": ["sub_pkg"]},
                            "sub_pkg@3.0.0_peer@4.0.0__peer@4.0.0": {"integrity": "sha512-sub"},
                            "lint@2.0.0": {"integrity": "sha512-lint"}},
                    "workspace": {"packageJson": {"dependencies": ["npm:app@^1.0.0", "npm:lint@^2.0.0"]}}}
        runtime = {"version": "5", "specifiers": {"npm:app@^1.0.0": "1.0.0"},
                   "npm": {"app@1.0.0": upstream["npm"]["app@1.0.0"],
                           "sub_pkg@3.0.0_peer@4.0.0": {"integrity": "sha512-sub"}},
                   "workspace": {"packageJson": {"dependencies": ["npm:app@^1.0.0"]}}}
        profile = {"schema_version": 1, "provider_version": "2.0.0", "provider_commit": "a" * 40,
                   "upstream_package_canonical_sha256": canonical_hash(package),
                   "upstream_lock_canonical_sha256": canonical_hash(upstream),
                   "runtime_lock_canonical_sha256": canonical_hash(runtime)}
        for root, name, data in ((server, "package.json", package), (server, "deno.lock", upstream),
                                 (definition, "deno.lock", runtime), (definition, "profile.json", profile)):
            # Deliberately use different checkout line endings for original files.
            text = json.dumps(data, indent=2) + "\n"
            (root / name).write_bytes(text.replace("\n", "\r\n").encode() if root == server else text.encode())
        (definition / "README.md").write_text("Modification notice and original-file locations\n")
        return provider, definition, package, upstream, runtime, profile

    def test_preparation_preserves_original_bytes_and_all_non_development_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            provider, definition, original, _, _, profile = self.fixture(Path(tmp))
            server = provider / "server"
            originals = {name: (server / name).read_bytes() for name in ("package.json", "deno.lock")}
            prepare(provider, definition, profile["provider_commit"])
            verify(provider, profile["provider_commit"])
            for name, content in originals.items():
                self.assertEqual(content, (server / (name + ".upstream")).read_bytes())
            self.assertEqual({k: v for k, v in original.items() if k != "devDependencies"}, read(server / "package.json"))
            with self.assertRaises(ValueError):
                prepare(provider, definition, profile["provider_commit"])

    def test_wrong_source_is_rejected_before_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            provider, definition, _, _, _, profile = self.fixture(Path(tmp))
            with self.assertRaisesRegex(ValueError, "pinned source"):
                prepare(provider, definition, "b" * 40)
            server = provider / "server"
            changed = read(server / "package.json")
            changed["dependencies"]["app"] = "^9.0.0"
            (server / "package.json").write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                prepare(provider, definition, profile["provider_commit"])
            self.assertFalse((server / "package.json.upstream").exists())
            self.assertEqual(changed, read(server / "package.json"))

    def test_changed_package_version_integrity_or_dependency_records_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, _, package, upstream, runtime, profile = self.fixture(Path(tmp))
            for change in ("version", "integrity", "dependencies"):
                candidate = copy.deepcopy(runtime)
                if change == "version":
                    candidate["npm"]["app@9.0.0"] = candidate["npm"].pop("app@1.0.0")
                else:
                    candidate["npm"]["app@1.0.0"][change] = "sha512-other" if change == "integrity" else ["lint"]
                updated = dict(profile, runtime_lock_canonical_sha256=canonical_hash(candidate))
                with self.subTest(change=change), self.assertRaisesRegex(ValueError, "differs from upstream"):
                    validate(package, upstream, candidate, updated)

    def test_runtime_roots_cannot_be_removed_or_replaced_with_development_tools(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, _, package, upstream, runtime, profile = self.fixture(Path(tmp))
            for replacement in ({}, {"npm:lint@^2.0.0": "2.0.0"}):
                candidate = copy.deepcopy(runtime)
                candidate["specifiers"] = replacement
                candidate["workspace"]["packageJson"]["dependencies"] = sorted(replacement)
                updated = dict(profile, runtime_lock_canonical_sha256=canonical_hash(candidate))
                with self.assertRaisesRegex(ValueError, "roots changed"):
                    validate(package, upstream, candidate, updated)

    def test_relocated_archive_retains_provenance_and_detects_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            provider, definition, _, _, _, profile = self.fixture(folder)
            prepare(provider, definition, profile["provider_commit"])
            zip_package(provider, folder / "runtime.zip")
            relocated = folder / "clean relocated (provider)"
            relocated.mkdir()
            extract(folder / "runtime.zip", relocated)
            verify(relocated, profile["provider_commit"])
            for name in ("package.json", "package.json.upstream", "deno.lock", "deno.lock.upstream"):
                path = relocated / "server" / name
                original = path.read_bytes()
                modified = read(path)
                modified["unexpected"] = "modified"
                path.write_text(json.dumps(modified))
                with self.subTest(name=name), self.assertRaises(ValueError):
                    verify(relocated, profile["provider_commit"])
                path.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
