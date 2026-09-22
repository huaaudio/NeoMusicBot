"""Collect pinned Linux Canvas sources/notices and bind them to actual native bytes.

Collection runs on Linux to inspect Debian packages without installing them.
Readback is portable and compares the shipped material with repository definitions.
The tool records scope; it does not declare whole-distribution license review done.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

from build_linux_canvas import DEFINITION as BUILD_DEFINITION, ROOT, digest, download
from verify_linux_canvas import verify as verify_native
from package_linux_cargo import check_tree
from package_librsvg_materials import checked_file, safe_relative

DEFINITION = ROOT / 'src/license/canvas/linux-sources'
SOURCE_DIR = 'sources/canvas/linux/native-sources'
LICENSE_DIR = 'licenses/canvas/linux-sources'
RECIPE_FILES = ('scripts/native/build_linux_canvas.py', 'scripts/native/linux_canvas_steps.sh',
                'scripts/native/package_linux_canvas.py', 'scripts/native/shared_versions.py',
                'scripts/native/font_probe.cjs', 'scripts/native/gif_probe.cjs',
                'scripts/ci/canvas_probe.cjs', 'src/provider-runtime/native-linux/inputs.json',
                'src/provider-runtime/native-linux/package.json', 'src/provider-runtime/native-linux/package-lock.json')


def filename(record):
    return safe_relative(record.get('filename', record.get('file', record['url'].rsplit('/', 1)[1])))


def check_record(record):
    name = filename(record)
    if ('/' in name or not record['url'].startswith('https://')
            or not re.fullmatch('[a-f0-9]{64}', record['sha256'])
            or type(record['bytes']) is not int or record['bytes'] <= 0):
        raise ValueError('Invalid pinned material archive')


def source_folder(source):
    return safe_relative(source['source_package'] + '-' + source['source_version'].replace(':', '_'))


def validate(definition=DEFINITION, build_definition=BUILD_DEFINITION):
    definition, build_definition = Path(definition), Path(build_definition)
    data = json.loads((definition / 'manifest.json').read_text(encoding='utf-8'))
    build = json.loads((build_definition / 'inputs.json').read_text(encoding='utf-8'))
    if (data.get('schema_version') != 1 or data.get('platform') != 'linux-x86-64'
            or data.get('build_inputs_sha256') != digest(build_definition / 'inputs.json')):
        raise ValueError('Source materials differ from reviewed build inputs')
    source_ids, archive_names = set(), set()
    source_records = {}
    for source in data['ubuntu_sources']:
        identity = source['source_package'] + '=' + source['source_version']
        if identity in source_ids or '/' in source_folder(source):
            raise ValueError('Duplicate or invalid Ubuntu source identity')
        source_ids.add(identity)
        descriptors = []
        for record in source['files']:
            check_record(record)
            name = filename(record)
            if name in archive_names or record['role'] not in ('source-control-file', 'source-archive'):
                raise ValueError('Duplicate or invalid Ubuntu source file')
            archive_names.add(name)
            source_records[name] = record
            if record['role'] == 'source-control-file':
                descriptors.append(name)
        if len(descriptors) != 1 or not descriptors[0].endswith('.dsc'):
            raise ValueError('Ubuntu source needs exactly one descriptor')
    if source_ids != set(build['native_source_collection']):
        raise ValueError('Ubuntu source collection differs from native closure')
    expected = {item['name']: item for item in build['sources']}
    expected['node'] = build['tools']['node']
    expected.update({item['name']: item for item in build['tools']['rust']['components'] if item['name'] in ('rustc', 'rust-std')})
    addon = json.loads((build_definition / 'package-lock.json').read_text())['packages']['node_modules/node-addon-api']
    expected['node-addon-api'] = dict(name='node-addon-api', version=addon['version'], url=addon['resolved'], npm_integrity=addon['integrity'])
    seen = set()
    for component in data['upstream_materials']:
        name, record = component['component'], component['archive']
        if name in seen or name not in expected or any(record.get(k) != v for k, v in expected[name].items()):
            raise ValueError('Upstream material differs from pinned build source or SDK')
        seen.add(name)
        check_record(record)
        paths = set()
        for document in component['documents']:
            member, path = safe_relative(document['archive_member']), safe_relative(document['file'])
            if path != name + '/' + member or path.casefold() in paths:
                raise ValueError('Invalid or duplicate original notice path')
            paths.add(path.casefold())
        if not paths:
            raise ValueError('Upstream source lacks original notices')
    if seen != expected.keys():
        raise ValueError('Missing upstream source or SDK notice collection')
    sdk = {item['package']: item for item in build['sdk_packages']}
    source_by_name = {item['name']: item for item in build['sources']}
    origins, binary_packages = {}, set()
    for item in data['native_origins']:
        path = safe_relative(item['path'])
        safe_relative(item['original_path'])
        if path in origins:
            raise ValueError('Duplicate native origin')
        origins[path] = item
        if item['origin_type'] == 'upstream-source-build':
            if item['source'] != source_by_name.get(item['source']['name']):
                raise ValueError('Native origin differs from pinned upstream source')
        elif item['origin_type'] == 'ubuntu-binary-package':
            package = item['package']
            member = safe_relative(item['archive_member'])
            if (package != sdk.get(package['package']) or item['original_path'] != 'sdk/' + member
                    or item['source_package'] + '=' + item['source_version'] not in source_ids
                    or not re.fullmatch('[a-f0-9]{64}', item['original_sha256'])):
                raise ValueError('Native origin differs from pinned Ubuntu inputs')
            binary_packages.add(package['package'])
        else:
            raise ValueError('Unknown native origin type')
    if set(origins) != {'build/Release/' + name for name in build['expected_native_files']}:
        raise ValueError('Native origin coverage is incomplete')
    document_packages, document_paths = set(), set()
    for item in data['ubuntu_copyright_documents']:
        path = safe_relative(item['file'])
        safe_relative(item['archive_member'])
        if '/' in path or path.casefold() in document_paths or item['package'] not in binary_packages:
            raise ValueError('Invalid or duplicate Ubuntu notice')
        document_paths.add(path.casefold())
        document_packages.add(item['package'])
        if item['origin_type'] == 'matched-binary-package':
            if item['archive'] != sdk[item['package']]:
                raise ValueError('Ubuntu notice has wrong binary parent')
        elif item['origin_type'] == 'matching-parent-source-package':
            if item['archive'] != source_records.get(item['archive']['file']):
                raise ValueError('Ubuntu notice has wrong source archive')
            identities = {x['source_package'] + '=' + x['source_version'] for x in origins.values()
                          if x.get('package', {}).get('package') == item['package']}
            parents = {s['source_package'] + '=' + s['source_version'] for s in data['ubuntu_sources'] if item['archive'] in s['files']}
            if identities != parents:
                raise ValueError('Ubuntu notice parent source does not match native package')
        else:
            raise ValueError('Unknown Ubuntu notice origin')
        checked_file(definition / path, item)
    if document_packages != binary_packages:
        raise ValueError('Missing Ubuntu package copyright')
    return data


def verify_descriptor(folder, source):
    descriptors = [item for item in source['files'] if item['role'] == 'source-control-file']
    if len(descriptors) != 1:
        raise ValueError('Expected one source descriptor')
    body = (Path(folder) / descriptors[0]['file']).read_text(encoding='utf-8')
    fields = dict(row.split(': ', 1) for row in body.splitlines() if ': ' in row and not row.startswith(' '))
    if fields.get('Source') != source['source_package'] or fields.get('Version') != source['source_version']:
        raise ValueError('Ubuntu descriptor source identity mismatch')
    section = re.search(r'^Checksums-Sha256:\n((?: .+\n)+)', body, re.M)
    if not section:
        raise ValueError('Ubuntu descriptor lacks SHA256 source checksums')
    rows = [line.split() for line in section[1].splitlines()]
    if any(len(row) != 3 for row in rows) or len({row[2] for row in rows}) != len(rows):
        raise ValueError('Invalid or duplicate descriptor checksum')
    expected = {name: (sha, int(size)) for sha, size, name in rows}
    actual = {item['file']: (item['sha256'], item['bytes']) for item in source['files'] if item['role'] == 'source-archive'}
    if expected != actual:
        raise ValueError('Ubuntu source archives differ from descriptor checksums')


def notice_bytes(archive_path, documents):
    expected = {item['archive_member']: item for item in documents}
    found = set()
    with tarfile.open(archive_path) as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            if name not in expected:
                continue
            item = expected[name]
            if name in found or not member.isfile() or member.size != item['bytes'] or member.size > 16 * 1024 * 1024:
                raise ValueError('Invalid original notice member')
            body = archive.extractfile(member).read()
            if hashlib.sha256(body).hexdigest() != item['sha256']:
                raise ValueError('Original notice checksum mismatch')
            found.add(name)
            yield item, body
    if found != expected.keys():
        raise ValueError('Original notice missing from archive')


def binding(data, native_output):
    native_output = Path(native_output)
    verify_native(native_output)
    report = json.loads((native_output / 'native-build-report.json').read_text())
    actual = {item['path']: item for item in report['files']}
    result = []
    for origin in data['native_origins']:
        item = actual[origin['path']]
        if item['original_path'] != origin['original_path'] or (
                origin['origin_type'] == 'ubuntu-binary-package' and item['original_sha256'] != origin['original_sha256']):
            raise ValueError('Native bytes differ from reviewed source/package origin')
        result.append(dict(item, origin=origin))
    return dict(schema_version=1, native_archive=report['archive'],
                native_report_sha256=digest(native_output / 'native-build-report.json'),
                native_files=result, source_license_review_complete=False)


def verify_binary_origins(data, cache):
    """Reopen the actual fixed .deb bytes; never install packages or read host docs."""
    for package_name in sorted({item['package']['package'] for item in data['native_origins'] if 'package' in item}):
        origins = [item for item in data['native_origins'] if item.get('package', {}).get('package') == package_name]
        record = origins[0]['package']
        archive_path = download(record, cache)
        metadata = subprocess.check_output(['dpkg-deb', '--field', str(archive_path)], text=True)
        fields = dict(row.split(': ', 1) for row in metadata.splitlines() if ': ' in row and not row.startswith(' '))
        parent = re.fullmatch(r'([^ ]+)(?: \(([^)]+)\))?', fields.get('Source', fields['Package']))
        if fields['Package'] != record['package'] or fields['Version'] != record['version'] or not parent:
            raise ValueError('Debian binary identity differs from definition')
        if any((item['source_package'], item['source_version']) != (parent[1], parent[2] or fields['Version']) for item in origins):
            raise ValueError('Debian native/source identity mismatch')
        wanted = {item['archive_member']: item['original_sha256'] for item in origins}
        notices = [item for item in data['ubuntu_copyright_documents'] if item['package'] == package_name and item['origin_type'] == 'matched-binary-package']
        wanted.update({item['archive_member']: item['sha256'] for item in notices})
        found = set()
        with subprocess.Popen(['dpkg-deb', '--fsys-tarfile', str(archive_path)], stdout=subprocess.PIPE) as process:
            with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
                for member in archive:
                    name = member.name.removeprefix('./')
                    if name not in wanted:
                        continue
                    if name in found or not member.isfile() or hashlib.file_digest(archive.extractfile(member), 'sha256').hexdigest() != wanted[name]:
                        raise ValueError('Debian native or copyright bytes mismatch')
                    found.add(name)
            process.stdout.read()
            if process.wait() != 0 or found != wanted.keys():
                raise ValueError('Debian native or copyright member missing')


def verify(output, native_output, definition=DEFINITION):
    output, definition = Path(output), Path(definition)
    data = validate(definition)
    sources, licenses = output / SOURCE_DIR, output / LICENSE_DIR
    source_paths, license_paths = set(), {'manifest.json', 'native-provenance.json', 'README.md'}
    if json.loads((licenses / 'manifest.json').read_text()) != data:
        raise ValueError('Shipped source definition differs from repository')
    if json.loads((licenses / 'native-provenance.json').read_text()) != binding(data, native_output):
        raise ValueError('Shipped source binding differs from actual native archive')
    if (licenses / 'README.md').read_bytes() != (definition / 'README.md').read_bytes():
        raise ValueError('Source material scope notice differs from repository')
    for source in data['ubuntu_sources']:
        folder = 'ubuntu/' + source_folder(source)
        for record in source['files']:
            source_paths.add(folder + '/' + record['file'])
            checked_file(sources / folder / record['file'], record)
        verify_descriptor(sources / folder, source)
    for item in data['ubuntu_copyright_documents']:
        license_paths.add('ubuntu/' + item['file'])
        checked_file(licenses / 'ubuntu' / item['file'], item)
        if item['origin_type'] == 'matching-parent-source-package':
            source = next(s for s in data['ubuntu_sources'] if item['archive'] in s['files'])
            archive = sources / 'ubuntu' / source_folder(source) / item['archive']['file']
            list(notice_bytes(archive, [item]))
    for component in data['upstream_materials']:
        archive = component['archive']
        relative = 'upstream/' + component['component'] + '/' + filename(archive)
        source_paths.add(relative)
        checked_file(sources / relative, archive)
        if 'npm_integrity' in archive:
            with (sources / relative).open('rb') as stream:
                integrity = 'sha512-' + base64.b64encode(hashlib.file_digest(stream, 'sha512').digest()).decode()
            if integrity != archive['npm_integrity']:
                raise ValueError('Runtime header archive differs from npm integrity')
        for document, _ in notice_bytes(sources / relative, component['documents']):
            license_paths.add('upstream/' + document['file'])
            checked_file(licenses / 'upstream' / document['file'], document)
    for name in RECIPE_FILES:
        source_paths.add('recipe/' + name)
        if (sources / 'recipe' / name).read_bytes() != (ROOT / name).read_bytes():
            raise ValueError('Shipped native recipe differs from repository')
    check_tree(sources, source_paths)
    check_tree(licenses, license_paths)
    return dict(native_files=len(data['native_origins']), ubuntu_source_packages=len(data['ubuntu_sources']),
                ubuntu_source_files=sum(len(s['files']) for s in data['ubuntu_sources']),
                ubuntu_copyright_documents=len(data['ubuntu_copyright_documents']), upstream_archives=len(data['upstream_materials']),
                upstream_original_documents=sum(len(c['documents']) for c in data['upstream_materials']),
                actual_native_archive_bound=True, source_license_review_complete=False)


def package(output, cache, native_output, definition=DEFINITION):
    output, cache, definition = Path(output), Path(cache), Path(definition)
    data = validate(definition)
    provenance = binding(data, native_output)
    cache.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=False)
    sources, licenses = output / SOURCE_DIR, output / LICENSE_DIR
    sources.mkdir(parents=True)
    licenses.mkdir(parents=True)
    jobs = []
    for source in data['ubuntu_sources']:
        jobs.extend((dict(record, filename=record['file']), sources / 'ubuntu' / source_folder(source) / record['file']) for record in source['files'])
    for component in data['upstream_materials']:
        record = component['archive']
        jobs.append((record, sources / 'upstream' / component['component'] / filename(record)))
    def collect(job):
        record, target = job
        archive = download(record, cache)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(archive, target)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(collect, jobs))
    verify_binary_origins(data, cache)
    for item in data['ubuntu_copyright_documents']:
        target = licenses / 'ubuntu' / item['file']
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(definition / item['file'], target)
    for component in data['upstream_materials']:
        archive = sources / 'upstream' / component['component'] / filename(component['archive'])
        for document, body in notice_bytes(archive, component['documents']):
            target = licenses / 'upstream' / document['file']
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
    for name in RECIPE_FILES:
        target = sources / 'recipe' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, target)
    for name in ('manifest.json', 'README.md'):
        shutil.copyfile(definition / name, licenses / name)
    (licenses / 'native-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    return verify(output, native_output, definition)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--native-output', type=Path, required=True)
    parser.add_argument('--cache-dir', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    if args.verify_only:
        result = verify(args.output_dir, args.native_output)
    else:
        if args.cache_dir is None:
            parser.error('--cache-dir is required for collection')
        result = package(args.output_dir, args.cache_dir, args.native_output)
    print(json.dumps(result), flush=True)
