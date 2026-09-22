"""Preserve upstream yt-dlp notices and explicit supplements for exact binaries."""
import argparse
import json
from pathlib import Path
import shutil

from package_bundle import digest
from package_librsvg_materials import checked_file, safe_relative

DEFINITION = Path(__file__).resolve().parents[2] / 'src/license/ytdlp'
DESTINATION = 'licenses/ytdlp'


def validate(definition=DEFINITION):
    definition = Path(definition)
    data = json.loads((definition / 'manifest.json').read_text(encoding='utf-8'))
    if data['schema_version'] != 1 or set(data['platforms']) != {'windows-x86-64', 'linux-x86-64'}:
        raise ValueError('Invalid yt-dlp notice definition')
    expected = {'manifest.json', 'README.md', '.gitattributes'}
    for record in data['documents']:
        name = safe_relative(record['file'])
        if name.casefold() in {p.casefold() for p in expected}:
            raise ValueError('Duplicate yt-dlp notice path')
        expected.add(name)
        checked_file(definition / name, record)
    actual = {p.relative_to(definition).as_posix() for p in definition.rglob('*') if p.is_file()}
    if actual != expected or any(p.is_symlink() for p in definition.rglob('*')):
        raise ValueError('Unexpected yt-dlp notice files')
    for platform, record in data['platforms'].items():
        inventory = json.loads((definition / 'inventory' / (platform + '.json')).read_text(encoding='utf-8'))
        if inventory['binary_sha256'] != record['sha256'] or len(inventory['entries']) != record['embedded_entries']:
            raise ValueError('yt-dlp static inventory binding differs')
    return data


def binary_binding(bundle, platform, data):
    if platform not in data['platforms']:
        raise ValueError('Unsupported yt-dlp platform')
    executable = Path(bundle) / 'tools' / ('yt-dlp.exe' if platform == 'windows-x86-64' else 'yt-dlp')
    if executable.is_symlink() or digest(executable) != data['platforms'][platform]['sha256']:
        raise ValueError('Actual yt-dlp executable differs from notice inventory')


def verify(bundle, platform, definition=DEFINITION):
    data = validate(definition)
    binary_binding(bundle, platform, data)
    destination = Path(bundle) / DESTINATION
    expected = {'manifest.json', 'README.md'} | {r['file'] for r in data['documents']}
    actual = {p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()}
    if actual != expected or any(p.is_symlink() for p in destination.rglob('*')):
        raise ValueError('Shipped yt-dlp notice set differs')
    for name in expected:
        if (destination / name).read_bytes() != (Path(definition) / name).read_bytes():
            raise ValueError('Shipped yt-dlp original notice differs: ' + name)
    aggregate = Path(bundle) / 'licenses/yt-dlp-THIRD_PARTY_LICENSES.txt'
    if aggregate.read_bytes() != (destination / 'originals/THIRD_PARTY_LICENSES.txt').read_bytes():
        raise ValueError('Existing upstream yt-dlp aggregate differs')
    return dict(platform=platform, executable_bound=True, notices='passed', source_delivery_complete=False)


def prepare(bundle, platform, definition=DEFINITION):
    data = validate(definition)
    binary_binding(bundle, platform, data)
    root = Path(bundle).resolve()
    destination = root / DESTINATION
    if not destination.resolve().is_relative_to(root) or destination.exists():
        raise ValueError('yt-dlp notice destination must be new and inside bundle')
    shutil.copytree(definition, destination, ignore=shutil.ignore_patterns('.gitattributes'))
    return verify(bundle, platform, definition)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    print(json.dumps((verify if args.verify_only else prepare)(args.bundle, args.platform)))
