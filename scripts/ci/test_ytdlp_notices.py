from pathlib import Path
import hashlib
import shutil
import tempfile
import unittest
from package_ytdlp_notices import DEFINITION, validate, binary_binding


class YtDlpNoticeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.definition = self.root / 'definition'
        shutil.copytree(DEFINITION, self.definition)

    def test_original_material_definition_is_consistent(self):
        self.assertFalse(validate(self.definition)['source_delivery_complete'])

    def test_modified_original_notice_is_rejected(self):
        (self.definition / 'originals/THIRD_PARTY_LICENSES.txt').write_bytes(b'replaced')
        with self.assertRaises(ValueError):
            validate(self.definition)

    def test_missing_supplement_is_rejected(self):
        (self.definition / 'originals/python-3.10.11/LICENSE.txt').unlink()
        with self.assertRaises((ValueError, FileNotFoundError)):
            validate(self.definition)

    def test_unrecorded_notice_is_rejected(self):
        (self.definition / 'unexpected.txt').write_bytes(b'extra')
        with self.assertRaises(ValueError):
            validate(self.definition)

    def test_executable_binding_rejects_another_binary(self):
        (self.root / 'tools').mkdir()
        executable = self.root / 'tools/yt-dlp'
        executable.write_bytes(b'expected')
        data = {'platforms': {'linux-x86-64': {'sha256': hashlib.sha256(b'expected').hexdigest()}}}
        binary_binding(self.root, 'linux-x86-64', data)
        executable.write_bytes(b'different')
        with self.assertRaises(ValueError):
            binary_binding(self.root, 'linux-x86-64', data)


if __name__ == '__main__':
    unittest.main()
