import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from normalize_source_archive import normalize_gitiles_archive


class SourceArchiveNormalizationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def archive(self, name, timestamp, body=b'original source', mode=0o644, link='source.c'):
        path = self.root / name
        with tarfile.open(path, 'w:gz', format=tarfile.PAX_FORMAT) as archive:
            entry = tarfile.TarInfo('source.c')
            entry.mode, entry.mtime, entry.size = mode, timestamp, len(body)
            entry.pax_headers = {'mtime': str(timestamp)}
            archive.addfile(entry, io.BytesIO(body))
            symlink = tarfile.TarInfo('alias.c')
            symlink.type, symlink.linkname, symlink.mtime = tarfile.SYMTYPE, link, timestamp
            archive.addfile(symlink)
        return path

    def normalized_hash(self, path):
        target = self.root / (path.name + '.tar')
        normalize_gitiles_archive(path, target)
        return hashlib.sha256(target.read_bytes()).hexdigest()

    def test_request_timestamps_do_not_change_normalized_source(self):
        first = self.archive('first.gz', 1000.125)
        second = self.archive('second.gz', 9000.875)
        self.assertNotEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(self.normalized_hash(first), self.normalized_hash(second))

    def test_content_permissions_and_symlink_changes_remain_detectable(self):
        baseline = self.normalized_hash(self.archive('baseline.gz', 1000))
        for number, kwargs in enumerate(({'body': b'changed source'}, {'mode': 0o755}, {'link': 'other.c'})):
            with self.subTest(kwargs=kwargs):
                self.assertNotEqual(baseline, self.normalized_hash(self.archive(str(number)+'.gz', 2000, **kwargs)))

    def test_duplicate_and_unsafe_paths_are_rejected(self):
        for names in (('same', 'same'), ('../outside',)):
            with self.subTest(names=names):
                source = self.root / 'unsafe.tar'
                with tarfile.open(source, 'w') as archive:
                    for name in names:
                        archive.addfile(tarfile.TarInfo(name))
                with self.assertRaises(ValueError):
                    normalize_gitiles_archive(source, self.root / 'out.tar')
