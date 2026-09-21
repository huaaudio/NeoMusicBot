import tempfile
from pathlib import Path
import unittest
import zipfile
from package_bundle import package
from verify_bundle import extract, verify_manifest


class BundleIntegrityTest(unittest.TestCase):
    def test_hidden_cache_and_tampering_detection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundle = root / "input"
            (bundle / ".cache").mkdir(parents=True)
            (bundle / ".cache/hidden").write_bytes(b"offline dependency")
            archive = root / "release.zip"
            package(bundle, archive)
            output = root / "output"
            extract(archive, output)
            verify_manifest(output)
            (output / ".cache/hidden").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                verify_manifest(output)

    def test_unsafe_archive_paths_are_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "release.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("../escaped", b"bad")
            with self.assertRaises(ValueError):
                extract(archive, root / "output")
            self.assertFalse((root / "escaped").exists())


if __name__ == "__main__":
    unittest.main()
