"""Collect and verify the fixed yt-dlp runtime source companion without builds."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request

from package_librsvg_materials import checked_file, safe_relative
from package_ytdlp_notices import validate as notices

DEFINITION = Path(__file__).resolve().parents[2] / 'src/license/ytdlp-sources'
SOURCES = 'sources/yt-dlp-runtime'
MATERIALS = 'licenses/yt-dlp-sources'


def validate(definition=DEFINITION):
    definition = Path(definition)
    data = json.loads((definition / 'manifest.json').read_text(encoding='utf-8'))
    parent = notices()
    if (data['schema_version'] != 1 or data['version'] != parent['version']
            or data['platforms'] != {k: v['sha256'] for k, v in parent['platforms'].items()}):
        raise ValueError('yt-dlp source identity differs from executable definition')
    seen = set()
    for record in data['archives']:
        name = safe_relative(record['file'])
        if name.casefold() in seen or not record['url'].startswith('https://'):
            raise ValueError('Duplicate source path or unsupported source URL')
        seen.add(name.casefold())
        if not record['platforms'] or not set(record['platforms']) <= data['platforms'].keys():
            raise ValueError('Unsupported source platform')
    seen = set()
    for record in data['provenance']:
        name = safe_relative(record['file'])
        if name.casefold() in seen:
            raise ValueError('Duplicate source provenance record')
        seen.add(name.casefold())
        checked_file(definition / name, record)
    return data


def verify(output, definition=DEFINITION):
    output, definition = Path(output), Path(definition)
    data = validate(definition)
    root = output / MATERIALS
    if (root / 'manifest.json').read_bytes() != (definition / 'manifest.json').read_bytes():
        raise ValueError('Shipped yt-dlp source definition differs')
    for group, folder in [('archives', output / SOURCES), ('provenance', root)]:
        expected = {r['file'] for r in data[group]}
        if group == 'provenance': expected.add('manifest.json')
        actual = {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}
        if actual != expected or any(p.is_symlink() for p in folder.rglob('*')):
            raise ValueError('Source companion file set differs')
        for record in data[group]:checked_file(folder / record['file'], record)
    return dict(version=data['version'], archives=len(data['archives']),
                original_source_bytes=sum(r['bytes'] for r in data['archives']),
                fixed_source_payload='passed', binary_identities=data['platforms'])


def prepare(output, cache=None, definition=DEFINITION):
    output, definition = Path(output), Path(definition)
    data = validate(definition)
    if output.exists():raise ValueError('Use a new source material output directory')
    (output / SOURCES).mkdir(parents=True)
    (output / MATERIALS).mkdir(parents=True)
    shutil.copyfile(definition / 'manifest.json', output / MATERIALS / 'manifest.json')
    for record in data['provenance']:
        target = output / MATERIALS / record['file'];target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(definition / record['file'], target)
    for record in data['archives']:
        target = output / SOURCES / record['file'];target.parent.mkdir(parents=True, exist_ok=True)
        cached = Path(cache) / record['file'] if cache else None
        if cached is not None and cached.is_file():
            checked_file(cached, record)
            shutil.copyfile(cached, target)
        else:
            with urllib.request.urlopen(record['url'], timeout=120) as response, target.open('wb') as stream:
                shutil.copyfileobj(response, stream)
        checked_file(target, record)
    return verify(output, definition)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify(args.output) if args.verify_only else prepare(args.output, args.cache)), flush=True)
