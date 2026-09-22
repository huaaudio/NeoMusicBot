"""Ship fixed QuickJS/WASI sources and bind them to every distributed WASM copy."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
import urllib.request

from inventory_provider import inventory
from package_bundle import digest
from package_librsvg_materials import checked_file, safe_relative

ROOT = Path(__file__).resolve().parents[2]
DEFINITION = ROOT / 'src/license/quickjs'
SOURCE_DIR = 'sources/quickjs'
LICENSE_DIR = 'licenses/quickjs'
REPOSITORIES = {'quickjs-wasi': 'vercel-labs/quickjs-wasi', 'quickjs-ng': 'quickjs-ng/quickjs',
                'wasi-sdk': 'WebAssembly/wasi-sdk', 'wasi-libc': 'WebAssembly/wasi-libc',
                'llvm-project': 'llvm/llvm-project'}


def validate(definition=DEFINITION):
    data = json.loads((Path(definition) / 'manifest.json').read_text(encoding='utf-8'))
    lock = json.loads((ROOT / 'src/provider-runtime/deno.lock').read_text(encoding='utf-8'))
    coordinate = data['package'] + '@' + data['version']
    if (data['schema_version'] != 1 or data['package'] != 'quickjs-wasi'
            or data['npm_integrity'] != lock['npm'].get(coordinate, {}).get('integrity')):
        raise ValueError('QuickJS material differs from runtime lock')
    commits = data['source_commits']
    if set(commits) != set(REPOSITORIES) or any(not re.fullmatch('[a-f0-9]{40}', c) for c in commits.values()):
        raise ValueError('Missing or invalid QuickJS source identities')
    if not data['compiler'].endswith(' (https://github.com/llvm/llvm-project ' + commits['llvm-project'] + ')'):
        raise ValueError('WASM compiler differs from SDK source')
    seen, paths, archives = set(), set(), set()
    for component in data['materials']:
        name, archive = component['component'], component['archive']
        if name in seen or name not in {*REPOSITORIES, 'quickjs-npm'}:
            raise ValueError('Duplicate or unknown QuickJS material')
        seen.add(name)
        if archive['filename'] in archives or (name != 'quickjs-npm' and not component['documents']):
            raise ValueError('Duplicate source filename or missing QuickJS notices')
        archives.add(archive['filename'])
        members = set()
        expected_url = ('https://registry.npmjs.org/quickjs-wasi/-/quickjs-wasi-' + data['version'] + '.tgz'
                        if name == 'quickjs-npm' else 'https://codeload.github.com/' + REPOSITORIES[name] + '/tar.gz/' + commits[name])
        if (archive['url'] != expected_url or '/' in safe_relative(archive['filename'])
                or not re.fullmatch('[a-f0-9]{64}', archive['sha256']) or archive['bytes'] <= 0):
            raise ValueError('Unpinned QuickJS source archive')
        for document in component['documents']:
            path = safe_relative(document['file'])
            member = safe_relative(document['archive_member'])
            if member in members or not path.startswith(name + '/') or path.casefold() in paths:
                raise ValueError('Invalid or duplicate QuickJS notice path')
            paths.add(path.casefold())
            members.add(member)
    if seen != {*REPOSITORIES, 'quickjs-npm'}:
        raise ValueError('Missing QuickJS source archive')
    files = [safe_relative(item['file']) for item in data['wasm_files']]
    if len(files) != len(set(files)) or not files or 'quickjs.wasm' not in files:
        raise ValueError('Missing or duplicate WASM record')
    return data


def producers(body):
    if body[:8] != b'\0asm\x01\0\0\0':
        raise ValueError('Invalid WASM header')
    def leb(pos, end):
        value = 0
        for shift in range(0, 35, 7):
            if pos >= end:
                raise ValueError('Truncated WASM integer')
            byte = body[pos]
            pos += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, pos
        raise ValueError('Invalid WASM integer')
    def string(pos, end):
        size, pos = leb(pos, end)
        if pos + size > end:
            raise ValueError('Truncated WASM string')
        return body[pos:pos + size].decode('utf-8'), pos + size
    pos, result, seen = 8, [], False
    while pos < len(body):
        kind = body[pos]
        size, start = leb(pos + 1, len(body))
        end = start + size
        if end > len(body):
            raise ValueError('Truncated WASM section')
        pos = end
        if kind != 0:
            continue
        name, cursor = string(start, end)
        if name != 'producers':
            continue
        if seen:
            raise ValueError('Duplicate WASM producers')
        seen = True
        count, cursor = leb(cursor, end)
        for _ in range(count):
            field, cursor = string(cursor, end)
            length, cursor = leb(cursor, end)
            for _ in range(length):
                name, cursor = string(cursor, end)
                value, cursor = string(cursor, end)
                if field == 'processed-by' and name == 'clang':
                    result.append(value)
        if cursor != end:
            raise ValueError('Trailing WASM producer data')
    return result


def verify_wasm_copies(provider, data):
    provider = Path(provider).resolve()
    packages = [p for p in inventory(provider)['packages'] if p['name'] == 'quickjs-wasi']
    if len(packages) != 1 or packages[0]['version'] != data['version'] or packages[0]['integrity'] != data['npm_integrity']:
        raise ValueError('Installed QuickJS version or integrity differs')
    expected = {item['file']: item for item in data['wasm_files']}
    copies = 0
    for descriptor in packages[0]['package_descriptors']:
        root = (provider / descriptor['path']).parent
        actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() and p.suffix in ('.wasm', '.so')}
        if actual != expected.keys():
            raise ValueError('Installed QuickJS WASM file set differs')
        for name, record in expected.items():
            path = root / name
            if not path.resolve().is_relative_to(provider):
                raise ValueError('WASM path escapes provider')
            checked_file(path, record)
            if producers(path.read_bytes()) != [data['compiler']]:
                raise ValueError('Installed WASM compiler differs from sources')
        copies += 1
    if copies == 0:
        raise ValueError('Missing installed QuickJS copy')
    return copies


def original_documents(path, component, data):
    expected = {d['archive_member']: d for d in component['documents']}
    found, wasm, identities = set(), set(), set()
    wasm_records = {x['file']: x for x in data['wasm_files']}
    with tarfile.open(path) as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            relative = name.removeprefix('package/')
            source_relative = name.split('/', 1)[-1]
            identity = component['component'] == 'quickjs-wasi' and source_relative in ('package.json', 'Makefile')
            if identity:
                if source_relative in identities or not member.isfile() or member.size > 1024 * 1024:
                    raise ValueError('Invalid QuickJS recipe identity')
                content = archive.extractfile(member).read().decode('utf-8')
                if source_relative == 'package.json':
                    package = json.loads(content)
                    if package.get('name') != data['package'] or package.get('version') != data['version']:
                        raise ValueError('QuickJS source version differs from runtime')
                else:
                    sdk = re.search(r'^WASI_SDK_VERSION_REQUIRED = ([0-9]+)$', content, re.M)
                    if sdk is None or sdk.group(1) != str(data['wasi_sdk_major']):
                        raise ValueError('QuickJS recipe SDK version differs')
                identities.add(source_relative)
            is_wasm = component['component'] == 'quickjs-npm' and name.endswith(('.wasm', '.so'))
            if name not in expected and not is_wasm:
                continue
            if not member.isfile() or member.size > 16 * 1024 * 1024 or name in found:
                raise ValueError('Invalid or duplicate QuickJS archive member')
            found.add(name)
            body = archive.extractfile(member).read()
            record = expected.get(name) if not is_wasm else wasm_records.get(relative)
            if record is None or member.size != record['bytes'] or hashlib.sha256(body).hexdigest() != record['sha256']:
                raise ValueError('QuickJS archive member checksum mismatch')
            if is_wasm:
                wasm.add(relative)
                if producers(body) != [data['compiler']]:
                    raise ValueError('npm WASM compiler differs from sources')
            else:
                yield record, body
    if component['component'] == 'quickjs-wasi' and identities != {'package.json', 'Makefile'}:
        raise ValueError('QuickJS source identity missing')
    if not expected.keys() <= found or (component['component'] == 'quickjs-npm' and wasm != wasm_records.keys()):
        raise ValueError('QuickJS original archive member missing')


def verify(bundle, definition=DEFINITION):
    bundle, definition = Path(bundle), Path(definition)
    data = validate(definition)
    copies = verify_wasm_copies(bundle / 'tools/bgutil-provider', data)
    sources, licenses = bundle / SOURCE_DIR, bundle / LICENSE_DIR
    if json.loads((licenses / 'manifest.json').read_text(encoding='utf-8')) != data or (licenses / 'README.md').read_bytes() != (definition / 'README.md').read_bytes():
        raise ValueError('Shipped QuickJS definition differs')
    source_paths, license_paths = set(), {'manifest.json', 'README.md'}
    for component in data['materials']:
        record = component['archive']
        path = sources / record['filename']
        source_paths.add(record['filename'])
        checked_file(path, record)
        if component['component'] == 'quickjs-npm':
            with path.open('rb') as stream:
                integrity = 'sha512-' + base64.b64encode(hashlib.file_digest(stream, 'sha512').digest()).decode()
            if integrity != data['npm_integrity']:
                raise ValueError('QuickJS npm archive integrity differs')
        for document, _ in original_documents(path, component, data):
            license_paths.add(document['file'])
            checked_file(licenses / document['file'], document)
    for root, expected in ((sources, source_paths), (licenses, license_paths)):
        if {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()} != expected:
            raise ValueError('QuickJS material file set differs')
    return dict(wasm_files=len(data['wasm_files']), installed_copies=copies, source_archives=len(data['materials']),
                original_documents=sum(len(c['documents']) for c in data['materials']))


def prepare(bundle, definition=DEFINITION, cache=None):
    bundle, definition = Path(bundle), Path(definition)
    data = validate(definition)
    verify_wasm_copies(bundle / 'tools/bgutil-provider', data)
    sources, licenses = bundle / SOURCE_DIR, bundle / LICENSE_DIR
    sources.mkdir(parents=True, exist_ok=True)
    licenses.mkdir(parents=True, exist_ok=True)
    def collect(component):
        record = component['archive']
        destination = sources / record['filename']
        cached = Path(cache) / component['component'] / record['filename'] if cache else None
        if cached and cached.is_file():
            checked_file(cached, record)
            shutil.copyfile(cached, destination)
        else:
            with urllib.request.urlopen(record['url'], timeout=120) as response, destination.open('wb') as output:
                shutil.copyfileobj(response, output)
        checked_file(destination, record)
        for document, body in original_documents(destination, component, data):
            target = licenses / document['file']
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(collect, data['materials']))
    for name in ('manifest.json', 'README.md'):
        shutil.copyfile(definition / name, licenses / name)
    return verify(bundle, definition)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify(args.bundle) if args.verify_only else prepare(args.bundle, cache=args.cache)))
