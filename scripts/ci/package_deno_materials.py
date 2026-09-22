"""Package pinned Deno sources and original notices with actual executable binding."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import shutil
import urllib.request

from normalize_source_archive import normalize_gitiles_archive
from package_librsvg_materials import checked_file, safe_relative
from validate_deno_materials import DEFINITION, validate, verify_cached_sources, read_selected

SOURCE_DIR = 'sources/deno'
LICENSE_DIR = 'licenses/deno'


def archive_jobs(data):
    jobs = []
    for package in data['registry_packages']:
        prefix = package['name'] + '-' + package['version']
        documents = [(dict(d, archive_member=prefix + '/' + d['path']), 'registry/' + prefix + '/' + d['path']) for d in package['documents']]
        jobs.append(('archives/' + package['file'], package, documents))
    for source in data['source_archives']:
        documents = [(d, 'upstream/' + source['name'] + '/' + d['path']) for d in source['documents']]
        jobs.append(('source-archives/' + source['file'], source, documents))
    rust = data['rust_source']
    jobs.append(('rust-source/' + rust['archive']['filename'], rust['archive'], [(d, d['file']) for d in rust['documents']]))
    for item in data['typescript']:
        jobs.append(('typescript/' + item['archive']['filename'], item['archive'], [(d, d['file']) for d in item['documents']]))
    for path, _, documents in jobs:
        safe_relative(path)
        for record, relative in documents:
            safe_relative(relative)
            safe_relative(record['archive_member'].removeprefix('./'))
    return jobs


def binary_binding(bundle, data, platform):
    if platform not in data['platforms']:
        raise ValueError('Unsupported Deno material platform')
    bundle = Path(bundle).resolve()
    path = bundle / 'tools' / ('deno.exe' if platform == 'windows-x86-64' else 'deno')
    if not path.resolve().is_relative_to(bundle):
        raise ValueError('Deno executable escapes bundle')
    record = data['platforms'][platform]
    checked_file(path, record)
    if platform == 'windows-x86-64':
        commits = sorted({m.decode() for m in re.findall(rb'/rustc/([a-f0-9]{40})/library', path.read_bytes())})
        if commits != record['embedded_rustc_commits']:
            raise ValueError('Actual Deno compiler identity differs')
    return dict(schema_version=1, platform=platform, version=data['version'],
                binary_sha256=record['sha256'], binary_bytes=record['bytes'],
                deno_source_commit=data['parent']['commit'], rust_version=data['rust_version'],
                typescript_version=data['typescript_version'], linked_component_census=False)


def verify(bundle, platform, definition=DEFINITION):
    bundle, definition = Path(bundle).resolve(), Path(definition)
    data = validate(definition)
    binding = binary_binding(bundle, data, platform)
    sources, licenses = bundle / SOURCE_DIR, bundle / LICENSE_DIR
    for name in ('manifest.json', 'Cargo.lock', 'README.md'):
        if (licenses / name).read_bytes() != (definition / name).read_bytes():
            raise ValueError('Shipped Deno material definition differs')
    if json.loads((licenses / 'binary-binding.json').read_text(encoding='utf-8')) != binding:
        raise ValueError('Shipped Deno source/binary binding differs')
    result = verify_cached_sources(sources, definition)
    source_paths, license_paths = set(), {'manifest.json', 'Cargo.lock', 'README.md', 'binary-binding.json'}
    for relative, _, documents in archive_jobs(data):
        source_paths.add(relative)
        for record, name in documents:
            license_paths.add('originals/' + name)
            checked_file(licenses / 'originals' / name, record)
    for document in data['base_supplements']['documents']:
        name = 'supplements/' + document['path']
        license_paths.add(name)
        checked_file(licenses / name, document)
    for root, expected in ((sources, source_paths), (licenses, license_paths)):
        actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
        if actual != expected:
            raise ValueError('Deno material file set differs')
    result.pop('final_bundle_integration_complete')
    result.update(platform=platform, executable_bound=True, material_layout_verified=True)
    return result


def prepare(bundle, platform, definition=DEFINITION, cache=None):
    bundle, definition = Path(bundle).resolve(), Path(definition)
    data = validate(definition)
    binding = binary_binding(bundle, data, platform)
    sources, licenses = bundle / SOURCE_DIR, bundle / LICENSE_DIR
    for root in (sources, licenses):
        if not root.resolve().is_relative_to(bundle):
            raise ValueError('Deno material destination escapes bundle')
        root.mkdir(parents=True, exist_ok=True)
    def collect(job):
        relative, record, documents = job
        target = sources / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        cached = Path(cache) / relative if cache else None
        if cached and cached.is_file():
            checked_file(cached, record)
            shutil.copyfile(cached, target)
        else:
            download = target.with_name(target.name + '.download') if record.get('normalization') else target
            try:
                with urllib.request.urlopen(record['url'], timeout=120) as response, download.open('wb') as output:
                    shutil.copyfileobj(response, output)
                if record.get('normalization'):
                    normalize_gitiles_archive(download, target)
            finally:
                if download != target:
                    download.unlink(missing_ok=True)
        checked_file(target, record)
        selected = {d['archive_member'].removeprefix('./'): d for d, _ in documents}
        names = {d['archive_member'].removeprefix('./'): name for d, name in documents}
        for member, body in read_selected(target, selected):
            destination = licenses / 'originals' / names[member]
            if not destination.resolve().is_relative_to(licenses.resolve()):
                raise ValueError('Deno notice destination escapes bundle')
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(body)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(collect, archive_jobs(data)))
    for document in data['base_supplements']['documents']:
        name = 'supplements/' + document['path']
        target = licenses / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(definition / name, target)
    for name in ('manifest.json', 'Cargo.lock', 'README.md'):
        shutil.copyfile(definition / name, licenses / name)
    (licenses / 'binary-binding.json').write_text(json.dumps(binding, indent=2) + '\n', encoding='utf-8')
    return verify(bundle, platform, definition)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--platform', choices=('windows-x86-64', 'linux-x86-64'), required=True)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify(args.bundle, args.platform) if args.verify_only else prepare(args.bundle, args.platform, cache=args.cache)))
