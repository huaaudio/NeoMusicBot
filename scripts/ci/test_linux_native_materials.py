import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from package_linux_native_materials import bind, EVIDENCE


class LinuxNativeBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.canvas = self.root / 'tools/bgutil-provider/server/node_modules/canvas'
        (self.canvas / 'build/Release').mkdir(parents=True)
        (self.canvas / 'package.json').write_text(json.dumps({'name': 'canvas', 'version': '3.2.3'}))
        self.module = self.canvas / 'build/Release/canvas.node'
        self.module.write_bytes(b'native')
        self.evidence = self.root / EVIDENCE
        self.evidence.mkdir(parents=True)
        (self.evidence / 'native.tar.gz').write_bytes(b'archive')
        def record(body):
            return dict(bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
        self.data = dict(canvas_version='3.2.3',
                        native_archive=dict(filename='native.tar.gz', **record(b'archive')),
                        files=[dict(path='build/Release/canvas.node', **record(b'native'))])
        (self.evidence / 'native-build-report.json').write_text(json.dumps({
            'archive': self.data['native_archive'], 'files': self.data['files']}))

    def test_installed_bytes_match_retained_candidate(self):
        bind(self.root, self.data)

    def test_different_installed_library_is_rejected(self):
        self.module.write_bytes(b'altered')
        with self.assertRaises(ValueError):
            bind(self.root, self.data)

    def test_extra_installed_library_is_rejected(self):
        (self.module.parent / 'old.so').write_bytes(b'old')
        with self.assertRaises(ValueError):
            bind(self.root, self.data)

    def test_materials_from_a_different_native_build_are_rejected(self):
        other = copy.deepcopy(self.data)
        other['native_archive']['sha256'] = '0' * 64
        with self.assertRaises(ValueError):
            bind(self.root, other)

    def test_modified_retained_archive_is_rejected(self):
        (self.evidence / 'native.tar.gz').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            bind(self.root, self.data)


if __name__ == '__main__':
    unittest.main()
