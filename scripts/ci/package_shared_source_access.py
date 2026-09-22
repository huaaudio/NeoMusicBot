"""Separate reviewed source archives from runtime ZIPs, preserving material verification."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile
from package_librsvg_materials import checked_file
from package_shared_sources import source_records, verify as verify_sources

ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / 'src/license/shared-sources.json'
RECORD = 'licenses/shared-source-access.json'
SOURCE_ROOTS = ('sources/deno', 'sources/quickjs')


def definition(asset=ASSET, records=None):
    data = json.loads(asset.read_text(encoding='utf-8'))
    if data['schema_version'] != 1 or data['files'] != (source_records() if records is None else records):
        raise ValueError('Shared source access differs from current definitions')
    return data


def prepare(bundle, asset=ASSET, source_roots=SOURCE_ROOTS, records=None, access_record=RECORD):
    bundle = Path(bundle).resolve()
    data = definition(asset, records)
    roots = [bundle / relative for relative in source_roots]
    for root in roots:
        if not root.resolve().is_relative_to(bundle) or root.is_symlink():
            raise ValueError('Source directory escapes bundle')
    actual = {p.relative_to(bundle).as_posix() for root in roots for p in root.rglob('*') if p.is_file()}
    if actual != set(data['files']):
        raise ValueError('Source file set differs before separation')
    for name, record in data['files'].items():
        path = bundle / name
        if not path.resolve().is_relative_to(bundle) or path.is_symlink():
            raise ValueError('Source file escapes bundle')
        checked_file(path, record)
    target = bundle / access_record
    if target.exists() or not target.resolve().is_relative_to(bundle):
        raise ValueError('Source access record must be new and inside bundle')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(asset, target)
    for root in roots:
        shutil.rmtree(root)


def restore_for_verification(bundle, archive=None, asset=ASSET, source_roots=SOURCE_ROOTS, records=None, access_record=RECORD, verifier=verify_sources):
    """Populate only absent source directories in the disposable verification tree."""
    bundle = Path(bundle).resolve()
    data = definition(asset, records)
    if (bundle / access_record).read_bytes() != asset.read_bytes():
        raise ValueError('Shipped shared source access record differs')
    for relative in source_roots:
        path = bundle / relative
        if path.exists() or path.is_symlink() or not path.resolve().is_relative_to(bundle):
            raise ValueError('External source destination must be absent and inside verification tree')
    with tempfile.TemporaryDirectory(prefix='shared-source-download-') as temporary:
        if archive is None:
            path = Path(temporary) / 'sources.zip'
            with urllib.request.urlopen(data['url'], timeout=120) as response, path.open('wb') as target:
                shutil.copyfileobj(response, target)
        else:
            path = Path(archive)
        checked_file(path, data)
        verifier(path)
        with zipfile.ZipFile(path) as zipped:
            for name in data['files']:
                target = bundle / name
                if not target.resolve().is_relative_to(bundle):
                    raise ValueError('Source member escapes verification tree')
                target.parent.mkdir(parents=True, exist_ok=True)
                with zipped.open(name) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
    return dict(source_url_verified=archive is None, files=len(data['files']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    prepare(args.bundle)
