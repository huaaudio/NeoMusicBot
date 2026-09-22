"""Validate fixed Deno source definitions and independently read original archives.

This is the source verification layer; it does not certify the full distribution
or replace final bundle integration and per-platform release validation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile
import tomllib

from package_librsvg_materials import checked_file, safe_relative

ROOT = Path(__file__).resolve().parents[2]
DEFINITION = ROOT / 'src/license/deno'
REGISTRY = 'registry+https://github.com/rust-lang/crates.io-index'


def identity(item):
    return item['name'] + '@' + item['version']


def check_record(record, allow_empty=False):
    if (not re.fullmatch('[a-f0-9]{64}', record['sha256'])
            or type(record['bytes']) is not int or record['bytes'] < (0 if allow_empty else 1)):
        raise ValueError('Invalid Deno material checksum record')


def validate(definition=DEFINITION):
    definition = Path(definition)
    data = json.loads((definition / 'manifest.json').read_text(encoding='utf-8'))
    lock_body = (definition / 'Cargo.lock').read_bytes()
    lock = tomllib.loads(lock_body.decode('utf-8'))
    if (data['schema_version'] != 1 or data['name'] != 'deno'
            or data['version'] != data['parent']['version']
            or hashlib.sha256(lock_body).hexdigest() != data['parent']['lock_sha256']):
        raise ValueError('Deno release source identity mismatch')
    expected_registry = {identity(p): p['checksum'] for p in lock['package'] if p.get('source') == REGISTRY}
    expected_workspace = {identity(p) for p in lock['package'] if 'source' not in p}
    if any(p.get('source', REGISTRY) != REGISTRY for p in lock['package']):
        raise ValueError('Unreviewed Cargo source kind')
    workflow = (ROOT / '.github/workflows/build-and-test.yml').read_text(encoding='utf-8')
    if re.search(r'^  DENO_VERSION: "([^"]+)"$', workflow, re.M).group(1) != data['version']:
        raise ValueError('Deno material version differs from release tool selection')
    registry = {}
    for package in data['registry_packages']:
        coordinate = identity(package)
        if coordinate in registry:
            raise ValueError('Duplicate Deno registry source')
        registry[coordinate] = package
        check_record(package)
        if (package['file'] != package['name'] + '-' + package['version'] + '.crate'
                or package['url'] != 'https://static.crates.io/crates/' + package['name'] + '/' + package['file']):
            raise ValueError('Deno crate URL or filename differs')
        safe_relative(package['file'])
        metadata = package.get('metadata', {})
        expected_metadata = {'Cargo.toml'} | ({'.cargo_vcs_info.json'} if package.get('vcs') is not None else set())
        if set(metadata) != expected_metadata:
            raise ValueError('Missing original Deno crate metadata')
        for name, record in metadata.items():
            if record['archive_member'] != package['name'] + '-' + package['version'] + '/' + name:
                raise ValueError('Wrong Deno crate metadata member')
            check_record(record)
        paths = set()
        for document in package['documents']:
            path = safe_relative(document['path'])
            check_record(document, allow_empty=True)
            if path in paths:
                raise ValueError('Duplicate Deno crate notice')
            paths.add(path)
    if {k: p['sha256'] for k, p in registry.items()} != expected_registry:
        raise ValueError('Deno registry source collection differs from Cargo.lock')
    workspace = data['workspace']['packages']
    if len(workspace) != len(expected_workspace) or {identity(p) for p in workspace} != expected_workspace:
        raise ValueError('Deno workspace source collection differs from Cargo.lock')
    for package in workspace:
        safe_relative(package['path'])
    sources = {}
    for source in data['source_archives']:
        if source['name'] in sources:
            raise ValueError('Duplicate Deno upstream source')
        sources[source['name']] = source
        check_record(source)
        safe_relative(source['file'])
        if 'googlesource.com/' in source['url']:
            if source.get('normalization') != 'gitiles-mtime-zero-pax-tar-v1' or not source['file'].endswith('.tar'):
                raise ValueError('Gitiles source requires pinned timestamp-normalized archive')
            check_record(source['collected_upstream_archive'])
        elif source.get('normalization'):
            raise ValueError('Unreviewed source normalization')
        if not source['url'].startswith('https://') or not re.fullmatch('[a-f0-9]{40}', source['commit']):
            raise ValueError('Unpinned Deno upstream source')
        notice_paths, members = set(), set()
        for document in source['documents']:
            if document['path'] in notice_paths or document['archive_member'].removeprefix('./') in members:
                raise ValueError('Duplicate Deno upstream original notice')
            notice_paths.add(document['path'])
            members.add(document['archive_member'].removeprefix('./'))
            safe_relative(document['path'])
            safe_relative(document['archive_member'].removeprefix('./'))
            check_record(document, allow_empty=True)
    if (set(sources) != {'deno', 'rusty_v8'} | {'v8-module-' + p['path'].replace('/', '-') for p in data['v8_submodules']}
            or sources['deno']['commit'] != data['parent']['commit']
            or data['workspace']['source_archive'] != sources['deno']['sha256']):
        raise ValueError('Deno/V8 source closure mismatch')
    v8 = [p for p in registry.values() if p['name'] == 'v8']
    if len(v8) != 1 or sources['rusty_v8']['commit'] != v8[0]['vcs']['git']['sha1']:
        raise ValueError('V8 source differs from actual locked crate revision')
    for module in data['v8_submodules']:
        source = sources['v8-module-' + module['path'].replace('/', '-')]
        if source['commit'] != module['sha'] or source['submodule'] != module['path']:
            raise ValueError('V8 submodule revision mismatch')
    rust = data['rust_source']
    check_record(rust['archive'])
    if rust['archive']['version'] != data['rust_version']:
        raise ValueError('Deno Rust source version mismatch')
    rust_commit = rust['identity']['rustc-' + data['rust_version'] + '-src/git-commit-hash']
    if (set(data['platforms']) != {'windows-x86-64', 'linux-x86-64'}
            or data['platforms']['windows-x86-64']['embedded_rustc_commits'] != [rust_commit]):
        raise ValueError('Deno binary/compiler source association mismatch')
    for platform in data['platforms'].values():
        check_record(platform)
    if {p['component'] for p in data['typescript']} != {'typescript-npm', 'typescript-source'}:
        raise ValueError('Missing original TypeScript materials')
    for item in data['typescript']:
        check_record(item['archive'])
        if item['version'] != data['typescript_version']:
            raise ValueError('Deno TypeScript source version mismatch')
    supplement = data['base_supplements']
    documents = {}
    for document in supplement['documents']:
        path = safe_relative(document['path'])
        if path in documents:
            raise ValueError('Duplicate Deno supplement')
        documents[path] = document
        checked_file(definition / 'supplements' / path, document)
    covered = set()
    for item in supplement['packages']:
        coordinate = item['package']
        if coordinate in covered or coordinate not in registry or item['declared_license'] != registry[coordinate]['license']:
            raise ValueError('Deno license statement/version mismatch')
        covered.add(coordinate)
        if not item['documents'] or not set(item['documents']) <= documents.keys():
            raise ValueError('Missing Deno supplemental text')
        if item['origin'] == 'exact-vcs-ancestor-originals':
            if item['commit'] != registry[coordinate]['vcs']['git']['sha1']:
                raise ValueError('Deno license original has wrong revision')
        elif item['origin'] == 'standard-text-with-original-package-metadata':
            if item['selected_license'] not in item['declared_license'] or 'original_statement' not in item:
                raise ValueError('Standard text lacks original package declaration')
        else:
            raise ValueError('Unknown Deno supplement origin')
    if covered != {k for k, p in registry.items() if not p['documents']}:
        raise ValueError('Deno missing-notice package coverage differs')
    return data


def verify_package_metadata(package, body):
    metadata = tomllib.loads(body.decode('utf-8'))['package']
    actual = (metadata['name'], metadata['version'], metadata.get('license'), metadata.get('license-file'))
    expected = (package['name'], package['version'], package.get('license'), package.get('license_file'))
    if actual != expected:
        raise ValueError('Deno original package license declaration differs')
    if metadata.get('license-file') and metadata['license-file'] not in {d['path'] for d in package['documents']}:
        raise ValueError('Declared Deno license-file missing from originals')


def read_selected(archive_path, expected):
    found = set()
    with tarfile.open(archive_path) as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            if name not in expected:
                continue
            if name in found or not member.isfile() or member.size > 32 * 1024 * 1024:
                raise ValueError('Invalid or duplicate Deno source member')
            found.add(name)
            body = archive.extractfile(member).read()
            record = expected[name]
            if hashlib.sha256(body).hexdigest() != record['sha256'] or ('bytes' in record and len(body) != record['bytes']):
                raise ValueError('Deno original source member checksum mismatch')
            yield name, body
    if found != expected.keys():
        raise ValueError('Deno original source member missing')


def verify_cached_sources(cache, definition=DEFINITION):
    cache = Path(cache)
    data = validate(definition)
    statements = {p['package']: p['original_statement'] for p in data['base_supplements']['packages'] if 'original_statement' in p}
    documents = 0
    for package in data['registry_packages']:
        path = cache / 'archives' / package['file']
        checked_file(path, package)
        prefix = package['name'] + '-' + package['version'] + '/'
        selected = {prefix + d['path']: d for d in package['documents']}
        selected.update({r['archive_member']: r for r in package['metadata'].values()})
        statement = statements.get(identity(package))
        if statement:
            selected[statement['archive_member']] = statement
        if identity(package) == data['icu_data']['crate']:
            selected[prefix + 'src/icudtl.dat'] = data['icu_data']
        for name, body in read_selected(path, selected):
            if name == prefix + 'Cargo.toml':
                verify_package_metadata(package, body)
            if name == prefix + '.cargo_vcs_info.json' and json.loads(body) != package['vcs']:
                raise ValueError('Deno original package VCS identity differs')
        documents += len(package['documents'])
    for source in data['source_archives']:
        path = cache / 'source-archives' / source['file']
        checked_file(path, source)
        selected = {d['archive_member'].removeprefix('./'): d for d in source['documents']}
        if source['name'] == 'deno':
            prefix = 'deno-' + source['commit'] + '/'
            selected[prefix + 'Cargo.lock'] = {'sha256': data['parent']['lock_sha256']}
            selected.update({prefix + p['path']: p for p in data['workspace']['packages']})
        if source['name'] == data['icu_data']['source_component']:
            selected[data['icu_data']['source_member']] = data['icu_data']
        list(read_selected(path, selected))
        documents += len(source['documents'])
    rust = data['rust_source']
    path = cache / 'rust-source' / rust['archive']['filename']
    checked_file(path, rust['archive'])
    list(read_selected(path, {d['archive_member']: d for d in rust['documents']}))
    with tarfile.open(path) as archive:
        for name, expected in rust['identity'].items():
            if archive.extractfile(name).read().decode('utf-8').strip() != expected:
                raise ValueError('Deno Rust source identity differs')
    documents += len(rust['documents'])
    for item in data['typescript']:
        path = cache / 'typescript' / item['archive']['filename']
        checked_file(path, item['archive'])
        list(read_selected(path, {d['archive_member']: d for d in item['documents']}))
        documents += len(item['documents'])
    return dict(registry_sources=len(data['registry_packages']), workspace_sources=len(data['workspace']['packages']),
                upstream_sources=len(data['source_archives']) + 1 + len(data['typescript']),
                original_documents=documents, supplemental_documents=len(data['base_supplements']['documents']),
                source_license_review_complete=False, final_bundle_integration_complete=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path)
    args = parser.parse_args()
    print(json.dumps(verify_cached_sources(args.cache) if args.cache else {'definition': 'passed', 'registry_sources': len(validate()['registry_packages'])}))
