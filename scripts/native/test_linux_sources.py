import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from package_linux_sources import DEFINITION, binding, notice_bytes, validate, verify_descriptor, verify_rust_identity


class LinuxSourceDefinitionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.definition = self.root / 'definition'
        shutil.copytree(DEFINITION, self.definition)
        self.data = json.loads((self.definition / 'manifest.json').read_text())

    def save(self):
        (self.definition / 'manifest.json').write_text(json.dumps(self.data))

    def test_current_definition_covers_reviewed_native_closure(self):
        data = validate(self.definition)
        self.assertEqual(37, len(data['native_origins']))
        self.assertEqual(20, len(data['ubuntu_sources']))
        self.assertEqual(4044, sum(len(c['documents']) for c in data['upstream_materials']))

    def test_source_omission_cannot_shrink_required_collection(self):
        self.data['ubuntu_sources'].pop()
        self.save()
        with self.assertRaisesRegex(ValueError, 'native closure'):
            validate(self.definition)

    def test_sdk_version_change_cannot_reuse_old_notice_mapping(self):
        next(c for c in self.data['upstream_materials'] if c['component'] == 'node')['archive']['version'] = 'wrong'
        self.save()
        with self.assertRaisesRegex(ValueError, 'pinned build source or SDK'):
            validate(self.definition)

    def test_rust_source_identity_must_match_the_compiler(self):
        source = next(c for c in self.data['upstream_materials'] if c['component'] == 'rust-source')
        baseline = copy.deepcopy(source)
        for key in ('version', 'git-commit-hash', 'git-commit-info', 'src/version'):
            source.clear()
            source.update(copy.deepcopy(baseline))
            source['identity']['rustc-1.98.1-src/' + key] = 'wrong'
            self.save()
            with self.assertRaisesRegex(ValueError, 'Rust source'):
                validate(self.definition)

    def test_rust_source_cannot_be_omitted(self):
        self.data['upstream_materials'] = [c for c in self.data['upstream_materials'] if c['component'] != 'rust-source']
        self.save()
        with self.assertRaisesRegex(ValueError, 'Missing upstream'):
            validate(self.definition)

    def test_source_parent_cannot_be_swapped_for_a_different_package(self):
        item = next(d for d in self.data['ubuntu_copyright_documents'] if d['origin_type'] == 'matching-parent-source-package')
        source = next(s for s in self.data['ubuntu_sources'] if s['source_package'] == 'brotli')
        item['archive'] = source['files'][-1]
        self.save()
        with self.assertRaisesRegex(ValueError, 'parent source does not match'):
            validate(self.definition)

    def test_native_origins_cannot_be_omitted_or_duplicated(self):
        baseline = copy.deepcopy(self.data)
        for duplicate in (False, True):
            self.data = copy.deepcopy(baseline)
            if duplicate:
                self.data['native_origins'].append(self.data['native_origins'][0])
            else:
                self.data['native_origins'].pop()
            self.save()
            with self.assertRaises(ValueError):
                validate(self.definition)

    def test_notice_path_traversal_is_rejected(self):
        self.data['upstream_materials'][0]['documents'][0]['file'] = '../outside'
        self.save()
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            validate(self.definition)

    def test_original_copyright_tampering_is_rejected(self):
        path = self.definition / self.data['ubuntu_copyright_documents'][0]['file']
        body = bytearray(path.read_bytes())
        body[0] ^= 1
        path.write_bytes(body)
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            validate(self.definition)

    def test_binary_origin_hash_must_match_the_actual_build_report(self):
        records = []
        for origin in self.data['native_origins']:
            records.append(dict(path=origin['path'], original_path=origin['original_path'],
                                original_sha256=origin.get('original_sha256', 'a' * 64)))
        report = dict(files=records, archive={'sha256': 'b' * 64})
        path = self.root / 'native-build-report.json'
        path.write_text(json.dumps(report))
        with patch('package_linux_sources.verify_native') as verified_archive:
            bound = binding(self.data, self.root)
            verified_archive.assert_called_once_with(self.root)
            self.assertEqual(report['archive'], bound['native_archive'])
            origin = next(x for x in self.data['native_origins'] if x['origin_type'] == 'ubuntu-binary-package')
            next(x for x in records if x['path'] == origin['path'])['original_sha256'] = '0' * 64
            path.write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, 'Native bytes differ'):
                binding(self.data, self.root)


class SourceArchiveChecksTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_descriptor_rejects_wrong_identity_and_omitted_source_archive(self):
        sha = 'a' * 64
        source = dict(source_package='library', source_version='1.0', files=[
            dict(file='library.dsc', role='source-control-file'),
            dict(file='library.tar.gz', role='source-archive', sha256=sha, bytes=42)])
        text = 'Source: library\nVersion: 1.0\nChecksums-Sha256:\n ' + sha + ' 42 library.tar.gz\n'
        path = self.root / 'library.dsc'
        path.write_text(text)
        verify_descriptor(self.root, source)
        path.write_text(text.replace('Version: 1.0', 'Version: 2.0'))
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            verify_descriptor(self.root, source)
        path.write_text(text)
        source['files'].pop()
        with self.assertRaisesRegex(ValueError, 'descriptor checksums'):
            verify_descriptor(self.root, source)

    def test_rust_archive_rejects_missing_duplicate_and_changed_identity(self):
        path = self.root / 'rust.tar.gz'
        component = dict(component='rust-source', identity={'rust/version': '1.98.1'})
        for entries, fails in (([b'1.98.1\n'], False), ([], True),
                               ([b'1.98.1', b'1.98.1'], True), ([b'1.98.0'], True)):
            with tarfile.open(path, 'w:gz') as archive:
                for body in entries:
                    item = tarfile.TarInfo('rust/version')
                    item.size = len(body)
                    archive.addfile(item, io.BytesIO(body))
            if fails:
                with self.assertRaises(ValueError):
                    verify_rust_identity(path, component)
            else:
                verify_rust_identity(path, component)

    def test_notice_extraction_rejects_duplicate_or_modified_original(self):
        original = b'Original copyright\r\n'
        document = dict(archive_member='source/LICENSE', file='component/source/LICENSE',
                        sha256=hashlib.sha256(original).hexdigest(), bytes=len(original))
        path = self.root / 'source.tar.gz'
        for count, body, fails in ((1, original, False), (2, original, True), (1, original.replace(b'O', b'X'), True)):
            with tarfile.open(path, 'w:gz') as archive:
                for _ in range(count):
                    item = tarfile.TarInfo('source/LICENSE')
                    item.size = len(body)
                    archive.addfile(item, io.BytesIO(body))
            if fails:
                with self.assertRaises(ValueError):
                    list(notice_bytes(path, [document]))
            else:
                self.assertEqual(original, list(notice_bytes(path, [document]))[0][1])


if __name__ == '__main__':
    unittest.main()
