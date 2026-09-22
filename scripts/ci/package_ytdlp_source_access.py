"""Bind shipped yt-dlp binaries to their independently downloadable source asset."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

from package_bundle import digest
from package_librsvg_materials import checked_file
from package_ytdlp_notices import binary_binding, validate as notice_definition
from package_ytdlp_sources import DEFINITION, MATERIALS, SOURCES, validate as source_definition

ASSET = DEFINITION / 'asset.json'
RECORD = 'licenses/yt-dlp-source-access.json'
CATALOG = 'licenses/yt-dlp-corresponding-sources.json'


def definition():
    data = json.loads(ASSET.read_text(encoding='utf-8'))
    sources = source_definition()
    if (data['schema_version'] != 1 or data['version'] != sources['version']
            or data['binary_identities'] != sources['platforms']
            or data['manifest_sha256'] != digest(DEFINITION / 'manifest.json')
            or not data['url'].startswith('https://github.com/huaaudio/NeoMusicBot/releases/download/')):
        raise ValueError('Corresponding-source access does not match current binaries')
    return data, sources


def verify_archive(archive):
    data, sources = definition()
    checked_file(Path(archive), data)
    expected = {MATERIALS + '/manifest.json': dict(bytes=(DEFINITION / 'manifest.json').stat().st_size,
                                                 sha256=data['manifest_sha256'])}
    expected.update({SOURCES + '/' + r['file']: r for r in sources['archives']})
    expected.update({MATERIALS + '/' + r['file']: r for r in sources['provenance']})
    with zipfile.ZipFile(archive) as zipped:
        names = zipped.namelist()
        if len(names) != len(set(names)) or set(names) != set(expected) | {'SHA256SUMS'}:
            raise ValueError('Source companion has unexpected members')
        for name, record in expected.items():
            info = zipped.getinfo(name)
            if info.file_size != record['bytes'] or (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Invalid source member')
            with zipped.open(name) as body:
                if hashlib.file_digest(body, 'sha256').hexdigest() != record['sha256']:
                    raise ValueError('Source member checksum differs')
        checksum_lines = {line for line in zipped.read('SHA256SUMS').decode('utf-8').splitlines()}
        if checksum_lines != {r['sha256'] + '  ' + name for name, r in expected.items()}:
            raise ValueError('Source companion checksum list differs')
    return data


def verify(bundle, platform, archive=None):
    bundle = Path(bundle)
    data, _ = definition()
    binary_binding(bundle, platform, notice_definition())
    if (bundle / RECORD).read_bytes() != ASSET.read_bytes():
        raise ValueError('Shipped source download record differs')
    if (bundle / CATALOG).read_bytes() != (DEFINITION / 'manifest.json').read_bytes():
        raise ValueError('Shipped corresponding-source catalog differs')
    if archive is None:
        with tempfile.TemporaryDirectory(prefix='ytdlp-source-access-') as temporary:
            downloaded = Path(temporary) / 'sources.zip'
            with urllib.request.urlopen(data['url'], timeout=120) as response, downloaded.open('wb') as output:
                shutil.copyfileobj(response, output)
            verify_archive(downloaded)
    else:
        verify_archive(archive)
    return dict(platform=platform, actual_binary_bound=True, corresponding_sources='passed',
                delivery='separate source companion', source_url_verified=(archive is None),
                source_sha256=data['sha256'])


def prepare(bundle, platform):
    bundle = Path(bundle)
    definition()
    binary_binding(bundle, platform, notice_definition())
    for source, target in ((ASSET, RECORD), (DEFINITION / 'manifest.json', CATALOG)):
        destination = bundle / target
        if not destination.resolve().is_relative_to(bundle.resolve()) or destination.exists():
            raise ValueError('Source access destination must be new and inside bundle')
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    if args.verify_only:
        print(json.dumps(verify(args.bundle, args.platform, args.archive)), flush=True)
    else:
        prepare(args.bundle, args.platform)
