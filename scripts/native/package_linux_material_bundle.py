"""Assemble and read back a complete Linux native evidence/material ZIP.

The ZIP retains the exact tested native archive, pinned source materials, and
Cargo graph. This is a staging asset; collection does not approve a release.
"""
import argparse
import json
from pathlib import Path
import shutil
import sys
import tempfile

from build_linux_canvas import ROOT, digest
from package_linux_sources import verify as verify_sources
from package_linux_cargo import verify as verify_cargo
from verify_linux_canvas import verify as verify_native, ARCHIVE

sys.path.insert(0, str(ROOT / 'scripts/ci'))
from package_bundle import package
from verify_bundle import extract, verify_manifest

NATIVE_FILES = (ARCHIVE, 'native-build-report.json', 'inputs.json', 'package.json', 'package-lock.json')


def verify_archive(archive):
    archive = Path(archive)
    checksum = Path(str(archive) + '.sha256').read_text(encoding='ascii').strip().split('  ')
    if checksum != [digest(archive), archive.name]:
        raise ValueError('Linux material bundle checksum mismatch')
    with tempfile.TemporaryDirectory(prefix='native-material-readback-') as temporary:
        work = Path(temporary)
        extract(archive, work)
        verify_manifest(work)
        native = work / 'native'
        if {p.name for p in native.iterdir()} != set(NATIVE_FILES):
            raise ValueError('Native evidence file set mismatch')
        return dict(native=verify_native(native), sources=verify_sources(work, native), cargo=verify_cargo(work),
                    bundle_sha256=digest(archive), source_license_review_complete=False)


def assemble(sources, cargo, native, archive):
    sources, cargo, native, archive = map(Path, (sources, cargo, native, archive))
    verify_sources(sources, native)
    verify_cargo(cargo)
    if archive.exists() or Path(str(archive) + '.sha256').exists():
        raise ValueError('Use a new material bundle destination')
    with tempfile.TemporaryDirectory(prefix='native-material-package-') as temporary:
        work = Path(temporary)
        for origin in (sources, cargo):
            for name in ('sources', 'licenses'):
                shutil.copytree(origin / name, work / name, dirs_exist_ok=True)
        (work / 'native').mkdir()
        for name in NATIVE_FILES:
            shutil.copyfile(native / name, work / 'native' / name)
        package(work, archive)
    return verify_archive(archive)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--sources', type=Path)
    parser.add_argument('--cargo', type=Path)
    parser.add_argument('--native-output', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    if args.verify_only:
        result = verify_archive(args.archive)
    else:
        if any(value is None for value in (args.sources, args.cargo, args.native_output)):
            parser.error('--sources, --cargo and --native-output are required for collection')
        result = assemble(args.sources, args.cargo, args.native_output, args.archive)
    print(json.dumps(result), flush=True)
