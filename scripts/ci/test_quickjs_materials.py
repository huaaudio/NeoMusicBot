import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest

from package_quickjs_materials import DEFINITION, original_documents, producers, validate, verify_wasm_copies


def leb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def string(value):
    body = value.encode()
    return leb(len(body)) + body


def wasm(compiler):
    section = string('producers') + b'\1' + string('processed-by') + b'\1' + string('clang') + string(compiler)
    return b'\0asm\x01\0\0\0\0' + leb(len(section)) + section


class QuickjsMaterialsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.definition = self.root / 'definition'
        shutil.copytree(DEFINITION, self.definition)
        self.data = json.loads((self.definition / 'manifest.json').read_text(encoding='utf-8'))

    def save(self):
        (self.definition / 'manifest.json').write_text(json.dumps(self.data), encoding='utf-8')

    def test_current_sources_match_the_runtime_lock(self):
        validate(self.definition)

    def test_source_or_original_notices_cannot_be_omitted(self):
        baseline = copy.deepcopy(self.data)
        for omission in ('source', 'notices'):
            self.data = copy.deepcopy(baseline)
            if omission == 'source':
                self.data['materials'].pop()
            else:
                self.data['materials'][0]['documents'] = []
            self.save()
            with self.assertRaises(ValueError):
                validate(self.definition)

    def test_changed_package_version_cannot_reuse_materials(self):
        self.data['version'] = '0.0.0'
        self.save()
        with self.assertRaisesRegex(ValueError, 'runtime lock'):
            validate(self.definition)

    def test_wrong_compiler_commit_and_duplicate_archive_are_rejected(self):
        baseline = copy.deepcopy(self.data)
        self.data['source_commits']['llvm-project'] = '0' * 40
        self.save()
        with self.assertRaisesRegex(ValueError, 'compiler differs'):
            validate(self.definition)
        self.data = baseline
        self.data['materials'][1]['archive']['filename'] = self.data['materials'][0]['archive']['filename']
        self.save()
        with self.assertRaisesRegex(ValueError, 'Duplicate source filename'):
            validate(self.definition)

    def test_archive_member_cannot_be_mapped_to_two_notice_paths(self):
        component = self.data['materials'][0]
        document = dict(component['documents'][0], file=component['component'] + '/different')
        component['documents'].append(document)
        self.save()
        with self.assertRaisesRegex(ValueError, 'duplicate QuickJS notice'):
            validate(self.definition)

    def test_notice_path_cannot_escape_output(self):
        self.data['materials'][0]['documents'][0]['file'] = '../outside'
        self.save()
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            validate(self.definition)

    def test_producer_parser_rejects_truncation_and_duplicates(self):
        body = wasm(self.data['compiler'])
        self.assertEqual([self.data['compiler']], producers(body))
        for invalid in (body[:7], body[:-1], body + body[8:]):
            with self.assertRaises(ValueError):
                producers(invalid)

    def installed_fixture(self):
        provider = self.root / 'provider'
        server = provider / 'server'
        roots = [server / 'node_modules/quickjs-wasi', server / '.deno-dir/npm/registry.npmjs.org/quickjs-wasi/2.2.0']
        body = wasm(self.data['compiler'])
        data = dict(self.data, wasm_files=[dict(file='quickjs.wasm', sha256=hashlib.sha256(body).hexdigest(), bytes=len(body))])
        for root in roots:
            root.mkdir(parents=True)
            (root / 'package.json').write_text(json.dumps({'name': 'quickjs-wasi', 'version': '2.2.0', 'license': 'MIT'}))
            (root / 'quickjs.wasm').write_bytes(body)
        (server / 'deno.lock').write_text(json.dumps({'npm': {'quickjs-wasi@2.2.0': {'integrity': data['npm_integrity']}}}))
        return provider, roots, data

    def test_cached_wasm_tampering_is_rejected(self):
        provider, roots, data = self.installed_fixture()
        self.assertEqual(2, verify_wasm_copies(provider, data))
        (roots[1] / 'quickjs.wasm').write_bytes(b'bad cache')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            verify_wasm_copies(provider, data)

    def test_extra_or_missing_extension_is_rejected(self):
        provider, roots, data = self.installed_fixture()
        extra = roots[0] / 'extra.so'
        extra.write_bytes(wasm(self.data['compiler']))
        with self.assertRaisesRegex(ValueError, 'file set differs'):
            verify_wasm_copies(provider, data)
        extra.unlink()
        (roots[0] / 'quickjs.wasm').unlink()
        with self.assertRaisesRegex(ValueError, 'file set differs'):
            verify_wasm_copies(provider, data)

    def test_recipe_version_and_sdk_must_match_the_actual_source(self):
        path = self.root / 'recipe.tar.gz'
        component = dict(component='quickjs-wasi', documents=[])
        for version, sdk in (('2.2.0', '32'), ('2.1.0', '32'), ('2.2.0', '31')):
            bodies = {'package.json': json.dumps({'name': 'quickjs-wasi', 'version': version}).encode(),
                      'Makefile': ('WASI_SDK_VERSION_REQUIRED = ' + sdk + '\n').encode()}
            with tarfile.open(path, 'w:gz') as archive:
                for name, body in bodies.items():
                    item = tarfile.TarInfo('source/' + name)
                    item.size = len(body)
                    archive.addfile(item, io.BytesIO(body))
            if version == '2.2.0' and sdk == '32':
                self.assertEqual([], list(original_documents(path, component, self.data)))
            else:
                with self.assertRaisesRegex(ValueError, 'version differs'):
                    list(original_documents(path, component, self.data))

    def test_archive_originals_reject_missing_duplicate_and_modified_notice(self):
        path = self.root / 'source.tar.gz'
        body = b'Original notice\r\n'
        record = dict(archive_member='root/LICENSE', file='component/LICENSE', bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
        component = dict(component='wasi-libc', documents=[record])
        for contents, fails in (([body], False), ([], True), ([body, body], True), ([b'other'], True)):
            with tarfile.open(path, 'w:gz') as archive:
                for content in contents:
                    item = tarfile.TarInfo('root/LICENSE')
                    item.size = len(content)
                    archive.addfile(item, io.BytesIO(content))
            if fails:
                with self.assertRaises(ValueError):
                    list(original_documents(path, component, self.data))
            else:
                self.assertEqual(body, list(original_documents(path, component, self.data))[0][1])


if __name__ == '__main__':
    unittest.main()
