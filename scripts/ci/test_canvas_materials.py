import copy
import json
from pathlib import Path
import tempfile
import unittest

from package_bundle import digest, package
from package_canvas_materials import prepare, validate, verify
from verify_bundle import extract


class CanvasMaterialsTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.definition = self.root / "definitions/windows"
        self.definition.mkdir(parents=True)
        (self.definition.parent / "README.md").write_text("Package-level scope only")
        license = self.definition / "library-COPYING.txt"
        license.write_bytes(b"Original copyright and license\r\n")
        self.cache = self.root / "sources"
        self.cache.mkdir()
        source = self.cache / "library-1.src.tar.zst"
        source.write_bytes(b"source archive fixture")
        self.bundle = self.root / "bundle"
        self.canvas = self.bundle / "tools/bgutil-provider/server/node_modules/canvas"
        (self.canvas / "build/Release").mkdir(parents=True)
        (self.canvas / "package.json").write_text(json.dumps({"name": "canvas", "version": "3.2.3"}))
        native = self.canvas / "build/Release/library.dll"
        native.write_bytes(b"native fixture")
        self.native_definition = self.root / "native.json"
        self.native_definition.write_text(json.dumps({"schema_version": 1, "package": "canvas", "version": "3.2.3",
            "platforms": {"windows-x86-64": {"sha256": "a" * 64, "files": [
                {"path": "build/Release/library.dll", "sha256": digest(native), "bytes": native.stat().st_size},
                {"path": "build/Release/canvas.node", "sha256": "b" * 64, "bytes": 1}]}}}))
        self.data = {"schema_version": 1, "platform": "windows-x86-64", "canvas_version": "3.2.3",
            "canvas_archive_sha256": "a" * 64, "components": [{"package": "library", "version": "1",
                "files": [{"path": "build/Release/library.dll", "sha256": digest(native)}],
                "licenses": [{"file": license.name, "sha256": digest(license)}],
                "source": {"file": source.name, "url": "https://repo.msys2.org/mingw/sources/" + source.name,
                           "sha256": digest(source), "bytes": source.stat().st_size}}]}
        self.write_definition(self.data)

    def write_definition(self, data):
        (self.definition / "manifest.json").write_text(json.dumps(data))

    def prepare(self):
        prepare(self.bundle, self.definition, self.native_definition, self.cache)

    def test_collects_and_verifies_original_materials_after_zip_relocation(self):
        self.prepare()
        package(self.bundle, self.root / "bundle.zip")
        clean = self.root / "clean material bundle"
        clean.mkdir()
        extract(self.root / "bundle.zip", clean)
        verify(clean, self.definition, self.native_definition)
        self.assertEqual((self.definition / "library-COPYING.txt").read_bytes(),
                         (clean / "licenses/canvas/windows/library-COPYING.txt").read_bytes())

    def test_rejects_incomplete_duplicate_or_wrong_prebuild_records(self):
        for change in ("missing", "duplicate", "dll hash", "archive hash", "license", "path"):
            data = copy.deepcopy(self.data)
            component = data["components"][0]
            if change == "missing":
                component["files"] = []
            elif change == "duplicate":
                component["files"] *= 2
            elif change == "dll hash":
                component["files"][0]["sha256"] = "0" * 64
            elif change == "archive hash":
                data["canvas_archive_sha256"] = "0" * 64
            elif change == "license":
                component["licenses"] = []
            else:
                component["source"]["file"] = "../escape.tar.zst"
            self.write_definition(data)
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate(self.definition, self.native_definition)

    def test_rejects_corrupted_source_download_and_shipped_files(self):
        source = self.cache / "library-1.src.tar.zst"
        original = source.read_bytes()
        source.write_bytes(b"modified source archive")
        with self.assertRaisesRegex(ValueError, "Downloaded Canvas source"):
            self.prepare()
        self.assertFalse((self.bundle / "sources/canvas/windows" / source.name).exists())
        source.write_bytes(original)
        self.prepare()
        for name in ("tools/bgutil-provider/server/node_modules/canvas/build/Release/library.dll",
                     "licenses/canvas/windows/library-COPYING.txt", "sources/canvas/windows/library-1.src.tar.zst"):
            path = self.bundle / name
            original = path.read_bytes()
            path.write_bytes(b"tampered")
            with self.subTest(file=name), self.assertRaises(ValueError):
                verify(self.bundle, self.definition, self.native_definition)
            path.write_bytes(original)
        manifest = self.bundle / "licenses/canvas/windows/manifest.json"
        data = copy.deepcopy(self.data)
        data["components"][0]["version"] = "changed"
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "reviewed definition"):
            verify(self.bundle, self.definition, self.native_definition)


if __name__ == "__main__":
    unittest.main()
