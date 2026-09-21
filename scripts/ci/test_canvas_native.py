import copy
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from install_canvas_native import RECORD, install, verify
from package_bundle import digest, package
from verify_bundle import extract


class CanvasNativeTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.provider = self.root / "provider"
        self.canvas = self.provider / "server/node_modules/canvas"
        self.canvas.mkdir(parents=True)
        (self.canvas / "package.json").write_text(json.dumps({"name": "canvas", "version": "3.2.3"}))
        self.files = {"build/Release/canvas.node": b"native fixture", "build/config.gypi": b"build fixture"}
        self.archive = self.root / "native.tar.gz"
        self.definition = self.root / "definition.json"
        self.write_archive()
        spec = {"url": "https://github.com/Automattic/node-canvas/releases/download/v3.2.3/fixture.tar.gz",
                "sha256": digest(self.archive),
                "files": [{"path": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
                          for name, raw in self.files.items()]}
        self.data = {"schema_version": 1, "package": "canvas", "version": "3.2.3",
                     "platforms": {key: copy.deepcopy(spec) for key in ("linux-x86-64", "windows-x86-64")}}
        self.definition.write_text(json.dumps(self.data))

    def write_archive(self, entries=None):
        with tarfile.open(self.archive, "w:gz") as archive:
            for name, raw, kind in (entries if entries is not None else
                                    [(name, raw, tarfile.REGTYPE) for name, raw in self.files.items()]):
                item = tarfile.TarInfo(name)
                item.type = kind
                if kind == tarfile.REGTYPE:
                    item.size = len(raw)
                else:
                    item.linkname = "../../outside"
                archive.addfile(item, io.BytesIO(raw) if kind == tarfile.REGTYPE else None)

    def install(self, platform="windows-x86-64"):
        install(self.provider, platform, self.definition, self.archive)

    def test_installs_pinned_files_and_refuses_to_overwrite(self):
        self.install()
        verify(self.provider, "windows-x86-64", self.definition)
        for name, raw in self.files.items():
            self.assertEqual(raw, (self.canvas / name).read_bytes())
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.install()
        verify(self.provider, "windows-x86-64", self.definition)

    def test_rejects_wrong_package_version_and_unsupported_platform_before_installation(self):
        with self.assertRaisesRegex(ValueError, "Unsupported Canvas platform"):
            self.install("unsupported")
        (self.canvas / "package.json").write_text(json.dumps({"name": "canvas", "version": "9.0.0"}))
        with self.assertRaisesRegex(ValueError, "pinned version"):
            self.install()
        self.assertFalse((self.canvas / "build").exists())

    def test_corrupted_download_is_rejected_before_unpacking(self):
        with patch("install_canvas_native.urllib.request.urlopen", return_value=io.BytesIO(b"corrupt")):
            with self.assertRaisesRegex(ValueError, "archive checksum"):
                install(self.provider, "windows-x86-64", self.definition)
        self.assertFalse((self.canvas / "build").exists())
        self.assertFalse((self.provider / "server" / RECORD).exists())

    def test_rejects_missing_extra_duplicate_link_and_traversal_archive_entries(self):
        original = [(name, raw, tarfile.REGTYPE) for name, raw in self.files.items()]
        for entries in (original[:-1], original + original[:1],
                        original + [("build/extra.dll", b"extra", tarfile.REGTYPE)],
                        [("../outside", b"escape", tarfile.REGTYPE)],
                        [("build/Release/canvas.node", b"", tarfile.SYMTYPE)],
                        [("build/Release/canvas.node", b"", tarfile.LNKTYPE)],
                        [("build/Release/canvas.node", b"", tarfile.FIFOTYPE)],
                        [("build/Release/canvas.node", b"changed", tarfile.REGTYPE)],
                        [("build/Release/canvas.node", b"Native fixture", tarfile.REGTYPE), original[1]]):
            with self.subTest(entries=entries):
                self.write_archive(entries)
                # Give the malformed archive a matching outer checksum so these
                # assertions exercise extraction/file checks, not only the download check.
                data = copy.deepcopy(self.data)
                data["platforms"]["windows-x86-64"]["sha256"] = digest(self.archive)
                self.definition.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    self.install()
                self.assertFalse((self.canvas / "build").exists())
                self.assertFalse((self.provider / "server" / RECORD).exists())
                self.assertFalse((self.root / "outside").exists())
                self.assertEqual(["package.json"], sorted(p.name for p in self.canvas.iterdir()))

    def test_relocated_bundle_verifies_against_definition_and_detects_native_tampering(self):
        self.install()
        package(self.provider, self.root / "provider.zip")
        relocated = self.root / "clean relocated (provider)"
        relocated.mkdir()
        extract(self.root / "provider.zip", relocated)
        verify(relocated, "windows-x86-64", self.definition)
        with self.assertRaisesRegex(ValueError, "provenance"):
            verify(relocated, "linux-x86-64", self.definition)
        native = relocated / "server/node_modules/canvas/build/Release/canvas.node"
        original = native.read_bytes()
        native.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            verify(relocated, "windows-x86-64", self.definition)
        native.unlink()
        with self.assertRaisesRegex(ValueError, "file set"):
            verify(relocated, "windows-x86-64", self.definition)
        native.write_bytes(original)
        extra = native.with_name("unreviewed.dll")
        extra.write_bytes(b"unexpected")
        with self.assertRaisesRegex(ValueError, "file set"):
            verify(relocated, "windows-x86-64", self.definition)
        extra.unlink()
        record = relocated / "server" / RECORD
        data = json.loads(record.read_text())
        data["platforms"]["windows-x86-64"]["sha256"] = "0" * 64
        record.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "provenance"):
            verify(relocated, "windows-x86-64", self.definition)


if __name__ == "__main__":
    unittest.main()
