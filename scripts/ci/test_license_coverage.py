import json
from pathlib import Path
import tempfile
import unittest
from verify_licenses import verify


class LicenseCoverageTest(unittest.TestCase):
    def test_sbom_component_cannot_be_released_without_its_license_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{"group": "example", "name": "lib", "version": "1"}]}))
            (root / "licenses.xml").write_text("<licenseSummary><dependencies /></licenseSummary>")
            with self.assertRaisesRegex(ValueError, "coverage mismatch"):
                verify(root, sbom)
            (root / "licenses.xml").write_text("""<licenseSummary><dependencies><dependency>
              <groupId>example</groupId><artifactId>lib</artifactId><version>1</version>
              <licenses><license><name>MIT</name><file>lib.txt</file></license></licenses>
              </dependency></dependencies></licenseSummary>""")
            with self.assertRaisesRegex(ValueError, "Missing license text"):
                verify(root, sbom)
            (root / "lib.txt").write_text("Example license fixture")
            verify(root, sbom)


if __name__ == "__main__":
    unittest.main()
