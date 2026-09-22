import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import package_shared_sources as shared


class SharedSourceTests(unittest.TestCase):
    def test_catalog_covers_both_reviewed_components(self):
        records = shared.source_records()
        self.assertTrue(any(x.startswith('sources/deno/') for x in records))
        self.assertTrue(any(x.startswith('sources/quickjs/') for x in records))
        self.assertTrue(all(r['bytes'] > 0 and len(r['sha256']) == 64 for r in records.values()))

    def test_repacked_archive_with_changed_content_is_rejected(self):
        name = 'sources/quickjs/source.tar.gz'
        record = {name: {'bytes': 4, 'sha256': hashlib.sha256(b'good').hexdigest()}}
        with tempfile.TemporaryDirectory() as work:
            archive = Path(work) / 'sources.zip'
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr(name, b'evil')
            with patch.object(shared, 'source_records', return_value=record):
                with self.assertRaisesRegex(ValueError, 'checksum differs'):
                    shared.verify(archive)

    def test_extra_file_rejected_even_when_expected_content_matches(self):
        name = 'sources/quickjs/source.tar.gz'
        record = {name: {'bytes': 4, 'sha256': hashlib.sha256(b'good').hexdigest()}}
        with tempfile.TemporaryDirectory() as work:
            archive = Path(work) / 'sources.zip'
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr(name, b'good')
                output.writestr('../unexpected', b'extra')
            with patch.object(shared, 'source_records', return_value=record):
                with self.assertRaisesRegex(ValueError, 'member set differs'):
                    shared.verify(archive)


if __name__ == '__main__':
    unittest.main()
