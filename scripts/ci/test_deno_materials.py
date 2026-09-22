import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest

from package_deno_materials import binary_binding
from validate_deno_materials import DEFINITION, read_selected, validate, verify_package_metadata


class DenoMaterialDefinitionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.definition = self.root / 'definition'
        shutil.copytree(DEFINITION, self.definition)
        self.data = json.loads((self.definition / 'manifest.json').read_text(encoding='utf-8'))

    def save(self):
        (self.definition / 'manifest.json').write_text(json.dumps(self.data), encoding='utf-8')

    def test_current_definition_covers_the_actual_lock(self):
        data = validate(self.definition)
        self.assertEqual(1046, len(data['registry_packages']))
        self.assertEqual(82, len(data['workspace']['packages']))
        self.assertEqual(117, len(data['base_supplements']['packages']))

    def test_registry_archive_cannot_be_omitted_or_rehashed(self):
        baseline = copy.deepcopy(self.data)
        for change in ('omit', 'hash'):
            self.data = copy.deepcopy(baseline)
            if change == 'omit':
                self.data['registry_packages'].pop()
            else:
                self.data['registry_packages'][0]['sha256'] = '0' * 64
            self.save()
            with self.assertRaisesRegex(ValueError, 'Cargo.lock'):
                validate(self.definition)

    def test_workspace_and_v8_sources_cannot_be_omitted(self):
        baseline = copy.deepcopy(self.data)
        for collection in ('workspace', 'source_archives'):
            self.data = copy.deepcopy(baseline)
            (self.data['workspace']['packages'] if collection == 'workspace' else self.data['source_archives']).pop()
            self.save()
            with self.assertRaisesRegex(ValueError, 'collection|closure'):
                validate(self.definition)

    def test_missing_original_metadata_is_rejected(self):
        self.data['registry_packages'][0]['metadata'].pop('Cargo.toml')
        self.save()
        with self.assertRaisesRegex(ValueError, 'original Deno crate metadata'):
            validate(self.definition)

    def test_standard_license_cannot_replace_the_original_statement(self):
        row = next(p for p in self.data['base_supplements']['packages'] if p['origin'] == 'standard-text-with-original-package-metadata')
        row.pop('original_statement')
        self.save()
        with self.assertRaisesRegex(ValueError, 'original package declaration'):
            validate(self.definition)

    def test_supplement_revision_cannot_be_reassigned(self):
        row = next(p for p in self.data['base_supplements']['packages'] if p['origin'] == 'exact-vcs-ancestor-originals')
        row['commit'] = '0' * 40
        self.save()
        with self.assertRaisesRegex(ValueError, 'wrong revision'):
            validate(self.definition)

    def test_notice_tampering_is_rejected(self):
        document = self.data['base_supplements']['documents'][0]
        path = self.definition / 'supplements' / document['path']
        path.write_bytes(path.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            validate(self.definition)

    def test_rust_source_cannot_be_rebound_to_another_compiler(self):
        self.data['platforms']['windows-x86-64']['embedded_rustc_commits'] = ['0' * 40]
        self.save()
        with self.assertRaisesRegex(ValueError, 'binary/compiler'):
            validate(self.definition)

    def test_duplicate_notice_and_unsafe_path_are_rejected(self):
        baseline = copy.deepcopy(self.data)
        for mode in ('duplicate', 'unsafe'):
            self.data = copy.deepcopy(baseline)
            source = next(s for s in self.data['source_archives'] if s['documents'])
            if mode == 'duplicate':
                source['documents'].append(source['documents'][0])
            else:
                source['documents'][0]['path'] = '../outside'
            self.save()
            with self.assertRaises(ValueError):
                validate(self.definition)

    def test_license_file_only_metadata_retains_and_requires_the_original(self):
        package = dict(name='component', version='1.0', license=None, license_file='COPYING', documents=[{'path': 'COPYING'}])
        body = b'[package]\nname = "component"\nversion = "1.0"\nlicense-file = "COPYING"\n'
        verify_package_metadata(package, body)
        package['documents'] = []
        with self.assertRaisesRegex(ValueError, 'license-file missing'):
            verify_package_metadata(package, body)
        package['documents'] = [{'path': 'COPYING'}]
        with self.assertRaisesRegex(ValueError, 'declaration differs'):
            verify_package_metadata(package, body.replace(b'COPYING', b'OTHER'))

    def executable_fixture(self):
        tools = self.root / 'tools'
        tools.mkdir()
        record = self.data['platforms']['windows-x86-64']
        body = b'MZfixture/rustc/' + record['embedded_rustc_commits'][0].encode() + b'/library/std'
        record.update(sha256=hashlib.sha256(body).hexdigest(), bytes=len(body))
        path = tools / 'deno.exe'
        path.write_bytes(body)
        return path, body

    def test_actual_executable_hash_must_match_the_source_binding(self):
        path, body = self.executable_fixture()
        binary_binding(self.root, self.data, 'windows-x86-64')
        path.write_bytes(body + b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            binary_binding(self.root, self.data, 'windows-x86-64')

    def test_executable_compiler_marker_must_match_its_source(self):
        path, body = self.executable_fixture()
        marker = self.data['platforms']['windows-x86-64']['embedded_rustc_commits'][0].encode()
        body = body.replace(marker, b'0' * 40)
        path.write_bytes(body)
        self.data['platforms']['windows-x86-64']['sha256'] = hashlib.sha256(body).hexdigest()
        with self.assertRaisesRegex(ValueError, 'compiler identity differs'):
            binary_binding(self.root, self.data, 'windows-x86-64')

    def test_unreviewed_platform_cannot_reuse_a_source_binding(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            binary_binding(self.root, self.data, 'linux-arm64')

    def test_original_archive_member_checks_reject_missing_duplicate_and_tampering(self):
        path = self.root / 'source.tar.gz'
        body = b'Original source notice\n'
        expected = {'source/LICENSE': {'sha256': hashlib.sha256(body).hexdigest(), 'bytes': len(body)}}
        for contents, fails in (([body], False), ([], True), ([body, body], True), ([b'changed'], True)):
            with tarfile.open(path, 'w:gz') as archive:
                for data in contents:
                    member = tarfile.TarInfo('source/LICENSE')
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
            if fails:
                with self.assertRaises(ValueError):
                    list(read_selected(path, expected))
            else:
                self.assertEqual(body, list(read_selected(path, expected))[0][1])


if __name__ == '__main__':
    unittest.main()
