import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import package_ytdlp_source_access as access


class SourceAccessTests(unittest.TestCase):
    def test_published_definition_matches_source_and_binary_catalogs(self):
        asset, sources = access.definition()
        self.assertEqual(asset['binary_identities'], sources['platforms'])

    def test_changed_source_catalog_binding_is_rejected(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'asset.json'
            data = json.loads(access.ASSET.read_text())
            data['manifest_sha256'] = '0' * 64
            path.write_text(json.dumps(data))
            with patch.object(access, 'ASSET', path), self.assertRaises(ValueError):
                access.definition()

    def test_modified_download_record_fails_before_network_access(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            record = root / access.RECORD
            record.parent.mkdir(parents=True)
            record.write_bytes(b'altered')
            with patch.object(access, 'binary_binding'), patch.object(access.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'download record differs'):
                    access.verify(root, 'linux-x86-64')
                network.assert_not_called()

    def test_modified_companion_rejected(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'sources.zip'
            path.write_bytes(b'not the published source archive')
            with self.assertRaises(ValueError):
                access.verify_archive(path)


if __name__ == '__main__':
    unittest.main()
