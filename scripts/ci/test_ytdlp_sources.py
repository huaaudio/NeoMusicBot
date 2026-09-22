from pathlib import Path
import json
import shutil
import tempfile
import unittest
from package_ytdlp_sources import DEFINITION, validate


class YtDlpSourceDefinitionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.definition = Path(self.temp.name) / 'definition'
        shutil.copytree(DEFINITION, self.definition)
        self.data = json.loads((self.definition / 'manifest.json').read_text())

    def save(self):
        (self.definition / 'manifest.json').write_text(json.dumps(self.data))

    def test_current_source_definition_matches_actual_binary_identities(self):
        self.assertEqual(len(validate(self.definition)['archives']), 86)

    def test_wrong_binary_identity_is_rejected(self):
        self.data['platforms']['linux-x86-64'] = '0' * 64
        self.save()
        with self.assertRaises(ValueError): validate(self.definition)

    def test_source_cannot_escape_material_root(self):
        self.data['archives'][0]['file'] = '../outside.tar.gz'
        self.save()
        with self.assertRaises(ValueError): validate(self.definition)

    def test_modified_original_provenance_is_rejected(self):
        record = self.data['provenance'][0]
        (self.definition / record['file']).write_bytes(b'changed')
        with self.assertRaises(ValueError): validate(self.definition)

    def test_duplicate_source_is_rejected(self):
        self.data['archives'].append(self.data['archives'][0])
        self.save()
        with self.assertRaises(ValueError): validate(self.definition)


if __name__ == '__main__':
    unittest.main()
