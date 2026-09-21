import json
from pathlib import Path
import tempfile
import unittest
from inventory_provider import inventory, verify, write
from package_bundle import package
from verify_bundle import extract


class ProviderInventoryTests(unittest.TestCase):
    def fixture(self, root):
        server = root / "server"
        server.mkdir()
        (server / "deno.lock").write_text(json.dumps({"npm": {
            "@scope/first@1.0.0": {"integrity": "sha512-first"},
            "nested@2.0.0_peer@3.0.0": {"integrity": "sha512-nested"},
        }}))
        for path, name, version in (
                ("node_modules/@scope/first", "@scope/first", "1.0.0"),
                ("node_modules/@scope/first/node_modules/nested", "nested", "2.0.0"),
                (".deno-dir/npm/registry.npmjs.org/@scope/first/1.0.0", "@scope/first", "1.0.0")):
            package = server / path
            package.mkdir(parents=True)
            (package / "package.json").write_text(json.dumps({"name": name, "version": version, "license": "MIT"}))
            (package / "README.md").write_text("License: MIT; candidate only")
        return server

    def test_scoped_nested_and_cached_packages_are_inventoried_without_approval(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.fixture(root)
            result = inventory(root)
            self.assertFalse(result["license_review_complete"])
            first, nested = result["packages"]
            self.assertEqual(first["name"], "@scope/first")
            self.assertEqual(len(first["package_descriptors"]), 2)
            self.assertEqual(len(first["license_candidates"]), 2)
            self.assertEqual(nested["name"], "nested")
            self.assertEqual(len(nested["package_descriptors"]), 1)
            self.assertEqual(len(nested["license_candidates"]), 1)

    def test_unlocked_or_conflicting_installed_packages_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            server = self.fixture(root)
            descriptor = server / "node_modules/@scope/first/package.json"
            data = json.loads(descriptor.read_text())
            data["version"] = "9.0.0"
            descriptor.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "npm lock integrity"):
                inventory(root)
            data["version"] = "1.0.0"
            data["license"] = "Apache-2.0"
            descriptor.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "license metadata differ"):
                inventory(root)

    def test_inventory_survives_relocation_and_detects_missing_or_modified_material(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            root = folder / "build"
            root.mkdir()
            self.fixture(root)
            write(root, root / "inventory.json")
            archive = folder / "provider.zip"
            package(root, archive)
            relocated = folder / "clean extracted (test)"
            relocated.mkdir()
            extract(archive, relocated)
            verify(relocated, relocated / "inventory.json")
            candidate = relocated / "server/node_modules/@scope/first/README.md"
            candidate.write_text("A different license")
            with self.assertRaisesRegex(ValueError, "does not match"):
                verify(relocated, relocated / "inventory.json")
            candidate.unlink()
            with self.assertRaisesRegex(ValueError, "does not match"):
                verify(relocated, relocated / "inventory.json")


if __name__ == "__main__":
    unittest.main()
