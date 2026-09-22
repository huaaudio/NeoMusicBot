import copy
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest

from build_linux_canvas import digest
from package_linux_canvas import DENO_SHA256, FONT_SHA256
from verify_linux_canvas import ARCHIVE, verify


class NativeArchiveReadbackTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.definition = self.root / 'definition'
        self.output = self.root / 'artifact'
        self.definition.mkdir()
        self.output.mkdir()
        self.lock = self.root / 'Cargo.lock'
        self.lock.write_bytes(b'pinned source lock')
        self.build = dict(expected_native_files=['canvas.node'], expected_shared_versions={'cairo': '1.18.6'})
        (self.definition / 'inputs.json').write_text(json.dumps(self.build))
        for name in ('package.json', 'package-lock.json'):
            (self.definition / name).write_text('{}')
        for path in self.definition.iterdir():
            shutil.copyfile(path, self.output / path.name)
        self.body = b'native fixture'
        native = self.root / 'native'
        native.write_bytes(self.body)
        self.report = dict(schema_version=1, platform='linux-x86-64',
            input_hashes={p.name: digest(p) for p in self.definition.iterdir()},
            source_lock_sha256=digest(self.lock), shared_versions=self.build['expected_shared_versions'],
            probes=dict(formats='passed', explicit_font='passed', gif='passed', deno_sha256=DENO_SHA256,
                font_sha256=FONT_SHA256, host_graphics_libraries='not-mounted', host_fonts='not-mounted',
                build_directories='not-mounted', network='unshared-and-denied', explicit_LD_LIBRARY_PATH=False),
            archive_readback='passed', files=[dict(path='build/Release/canvas.node', bytes=len(self.body), sha256=digest(native))])
        self.archive(self.body)

    def archive(self, body, extra=False):
        path = self.output / ARCHIVE
        with tarfile.open(path, 'w:gz') as archive:
            names = ['build/Release/canvas.node'] + (['build/Release/extra.so'] if extra else [])
            for name in names:
                member = tarfile.TarInfo(name)
                member.size = len(body)
                archive.addfile(member, io.BytesIO(body))
        self.report['archive'] = dict(filename=ARCHIVE, bytes=path.stat().st_size, sha256=digest(path))
        self.save()

    def save(self):
        (self.output / 'native-build-report.json').write_text(json.dumps(self.report))

    def verify(self):
        return verify(self.output, self.definition, self.lock)

    def test_archive_survives_relocation_and_is_read_back(self):
        moved = self.root / 'downloaded artifact'
        self.output.rename(moved)
        self.output = moved
        self.assertEqual('passed', self.verify()['archive_readback'])

    def test_report_without_archive_is_not_accepted(self):
        (self.output / ARCHIVE).unlink()
        with self.assertRaisesRegex(ValueError, 'missing'):
            self.verify()

    def test_other_build_archive_is_rejected(self):
        with (self.output / ARCHIVE).open('ab') as stream:
            stream.write(b'different build')
        with self.assertRaisesRegex(ValueError, 'differs from its build report'):
            self.verify()

    def test_outer_hash_cannot_hide_changed_native_bytes(self):
        self.archive(b'changed native')
        with self.assertRaises(ValueError):
            self.verify()

    def test_extra_archive_member_is_rejected(self):
        self.archive(self.body, extra=True)
        with self.assertRaisesRegex(ValueError, 'Unexpected Canvas archive entry'):
            self.verify()

    def test_missing_probe_or_changed_input_is_rejected(self):
        baseline = copy.deepcopy(self.report)
        for field in ('gif', 'network', 'deno_sha256'):
            with self.subTest(field=field):
                self.report = copy.deepcopy(baseline)
                self.report['probes'].pop(field)
                self.save()
                with self.assertRaisesRegex(ValueError, 'probe evidence'):
                    self.verify()
        self.report = baseline
        self.save()
        (self.output / 'inputs.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Retained build input'):
            self.verify()

    def test_duplicate_or_missing_native_record_is_rejected(self):
        original = copy.deepcopy(self.report['files'])
        for records in ([], original * 2):
            self.report['files'] = records
            self.save()
            with self.assertRaises(ValueError):
                self.verify()


if __name__ == '__main__':
    unittest.main()
