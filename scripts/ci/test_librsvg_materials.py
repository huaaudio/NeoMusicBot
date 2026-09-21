import copy
import hashlib
import io
import json
from pathlib import Path
import tarfile
import unittest

from package_bundle import digest, package
from package_librsvg_materials import (LICENSE_DIR, SOURCE_DIR, REGISTRY, extract_documents,
                                      prepare, validate, verify)
import test_canvas_materials
from verify_bundle import extract


class LibrsvgMaterialsTests(unittest.TestCase):
    def setUp(self):
        # Reuse the pinned native/source fixture; exercise the parent binding too.
        self.parent = test_canvas_materials.CanvasMaterialsTests()
        self.addCleanup(self.parent.doCleanups)
        self.parent.setUp()
        self.parent.prepare()
        self.root, self.bundle, self.cache = self.parent.root, self.parent.bundle, self.parent.cache
        self.definition = self.root / "definitions/librsvg-rust"
        self.definition.mkdir()
        packages = [self.crate(name) for name in ("shared", "original", "modified")]
        self.data = dict(schema_version=1, platform="windows-x86-64", canvas_version="3.2.3",
                         parent=copy.deepcopy(self.parent.data["components"][0]),
                         source_documents=[], license_documents=[], packages=packages,
                         locks=["Cargo.lock", "Cargo.lock.msys2"])
        for file, selected in zip(self.data["locks"], (packages[:2], packages[::2])):
            text = "version = 4\n" + "".join(
                f'[[package]]\nname = "{p["name"]}"\nversion = "1.0.0"\nsource = "{REGISTRY}"\n'
                f'checksum = "{p["sha256"]}"\n' for p in selected)
            path = self.definition / file
            path.write_text(text)
            self.data["source_documents"].append(self.record(path))
        for file, kind in (("README.md", "scope-explanation"), ("MIT-reference.txt", "standard-reference")):
            path = self.definition / file
            path.write_bytes(b"Reference only; keep original source headers\r\n")
            self.data["license_documents"].append(dict(self.record(path), kind=kind))
        self.write_definition()

    @staticmethod
    def record(path):
        return dict(file=path.name, bytes=path.stat().st_size, sha256=digest(path))

    @staticmethod
    def archive(path, entries):
        with tarfile.open(path, "w:gz") as archive:
            for name, content in entries:
                member = tarfile.TarInfo(name)
                if content is None:
                    member.type = tarfile.SYMTYPE
                    member.linkname = "../outside"
                    archive.addfile(member)
                else:
                    member.size = len(content)
                    archive.addfile(member, io.BytesIO(content))

    def crate(self, name):
        prefix = name + "-1.0.0/"
        metadata = f'[package]\nname = "{name}"\nversion = "1.0.0"\nlicense = "MIT"\n'.encode()
        license = b"Original authors and license\r\n"
        entries = [(prefix + "Cargo.toml", metadata), (prefix + "LICENSE", license)]
        path = self.cache / (prefix[:-1] + ".crate")
        self.archive(path, entries)
        return dict(name=name, version="1.0.0", **self.record(path),
                    url=f"https://static.crates.io/crates/{name}/{path.name}",
                    license="MIT", license_file=None, supplements=[],
                    documents=[dict(path="LICENSE", sha256=hashlib.sha256(license).hexdigest(), bytes=len(license))])

    def write_definition(self):
        (self.definition / "manifest.json").write_text(json.dumps(self.data))

    def args(self, bundle=None):
        return (bundle or self.bundle, self.definition, self.parent.definition, self.parent.native_definition)

    def prepare(self):
        prepare(*self.args(), source_cache=self.cache)

    def test_full_lock_union_and_original_documents_survive_zip_relocation(self):
        self.prepare()
        package(self.bundle, self.root / "release.zip")
        clean = self.root / "clean (relocated)"
        clean.mkdir()
        extract(self.root / "release.zip", clean)
        verify(*self.args(clean))
        self.assertEqual({p.name for p in (clean / SOURCE_DIR).glob("*.crate")},
                         {p["file"] for p in self.data["packages"]})
        self.assertEqual((clean / LICENSE_DIR / "original-1.0.0/LICENSE").read_bytes(),
                         b"Original authors and license\r\n")

    def test_missing_graph_entries_duplicate_records_and_wrong_parent_rejected(self):
        original = copy.deepcopy(self.data)
        for change in ("missing", "duplicate", "checksum", "one lock", "parent", "license", "url", "path"):
            self.data = copy.deepcopy(original)
            item = self.data["packages"][0]
            if change == "missing":
                self.data["packages"].pop()
            elif change == "duplicate":
                self.data["packages"].append(item)
            elif change == "checksum":
                item["sha256"] = "0" * 64
            elif change == "one lock":
                self.data["locks"] = ["Cargo.lock"]
            elif change == "parent":
                self.data["parent"]["source"]["recipe_sha256"] = "0" * 64
            elif change == "license":
                item["documents"] = []
            elif change == "url":
                item["url"] = "https://example.com/unreviewed.crate"
            else:
                item["documents"][0]["path"] = "../LICENSE"
            self.write_definition()
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate(self.definition, self.parent.definition, self.parent.native_definition)

    def test_corrupted_archive_never_installed(self):
        item = self.data["packages"][0]
        (self.cache / item["file"]).write_bytes(b"corrupt download")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.prepare()
        self.assertFalse((self.bundle / SOURCE_DIR / item["file"]).exists())

    def test_shipped_sources_documents_and_parent_tampering_rejected(self):
        self.prepare()
        for name in (
            SOURCE_DIR + "/Cargo.lock.msys2", SOURCE_DIR + "/original-1.0.0.crate",
            LICENSE_DIR + "/original-1.0.0/LICENSE", LICENSE_DIR + "/MIT-reference.txt",
            "sources/canvas/windows/library-1.src.tar.zst",
            "tools/bgutil-provider/server/node_modules/canvas/build/Release/library.dll",
        ):
            path = self.bundle / name
            original = path.read_bytes()
            path.write_bytes(b"changed")
            with self.subTest(file=name), self.assertRaises(ValueError):
                verify(*self.args())
            path.write_bytes(original)
        manifest = self.bundle / LICENSE_DIR / "manifest.json"
        manifest.write_text("{}")
        with self.assertRaisesRegex(ValueError, "reviewed definition"):
            verify(*self.args())

    def test_unsafe_archive_entries_and_misleading_documents_rejected(self):
        item = self.data["packages"][0]
        prefix = item["name"] + "-1.0.0/"
        metadata = f'[package]\nname = "{item["name"]}"\nversion = "1.0.0"\nlicense = "MIT"\n'.encode()
        original = [(prefix + "Cargo.toml", metadata), (prefix + "LICENSE", b"Original authors and license\r\n")]
        destination = self.root / "read-documents"
        path = self.cache / "malicious.crate"
        for change in ("../escape", prefix + "../escape", prefix + "bad\\name", prefix + "CON.txt",
                       "link", "duplicate", "metadata", "license", "missing"):
            entries = list(original)
            if change == "link":
                entries.append((prefix + "linked", None))
            elif change == "duplicate":
                entries.append(entries[0])
            elif change == "metadata":
                entries[0] = (entries[0][0], metadata.replace(b'"MIT"', b'"Apache-2.0"'))
            elif change == "license":
                entries[1] = (entries[1][0], b"fake")
            elif change == "missing":
                entries.pop()
            else:
                entries.append((change, b"escape"))
            self.archive(path, entries)
            with self.subTest(change=change), self.assertRaises(ValueError):
                extract_documents(path, item, destination)
        self.assertFalse((self.root / "escape").exists())


if __name__ == "__main__":
    unittest.main()
