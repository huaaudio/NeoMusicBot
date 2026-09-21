import json
from pathlib import Path
import shutil
import tempfile
import unittest

from package_linux_cargo import DEFINITION, validate


class LinuxCargoDefinitionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="native-cargo-test-")
        self.addCleanup(self.temporary.cleanup)
        self.definition = Path(self.temporary.name) / "definition"
        shutil.copytree(DEFINITION, self.definition)
        self.manifest = self.definition / "manifest.json"
        self.data = json.loads(self.manifest.read_text(encoding="utf-8"))

    def save(self):
        self.manifest.write_text(json.dumps(self.data), encoding="utf-8")

    def test_reviewed_graph_matches_original_source_lock(self):
        result = validate(self.definition)
        self.assertEqual(357, len(result["packages"]))
        self.assertEqual("2.63.2", result["parent"]["version"])

    def test_omitting_a_package_cannot_turn_full_graph_into_a_subset(self):
        self.data["packages"].pop()
        self.save()
        with self.assertRaisesRegex(ValueError, "complete source lock"):
            validate(self.definition)

    def test_materials_for_a_different_parent_source_are_rejected(self):
        self.data["parent"]["sha256"] = "0" * 64
        self.save()
        with self.assertRaisesRegex(ValueError, "pinned source build"):
            validate(self.definition)

    def test_supplement_tampering_is_rejected(self):
        document = next(item for item in self.data["license_documents"] if item.get("kind") == "upstream-license")
        path = self.definition / document["file"]
        body = bytearray(path.read_bytes())
        body[0] ^= 1  # Same size, different content.
        path.write_bytes(body)
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            validate(self.definition)

    def test_a_crate_without_original_docs_still_requires_declared_material(self):
        item = next(item for item in self.data["packages"] if not item["documents"])
        item["supplements"] = []
        self.save()
        with self.assertRaisesRegex(ValueError, "declared supplement"):
            validate(self.definition)


if __name__ == "__main__":
    unittest.main()
