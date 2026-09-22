"""Import the verified Linux native candidate and its materials into a bundle.

The selected runtime is declared in canvas-native.json. This importer binds the installed Canvas bytes to the exact reviewed CI material archive.
"""
import argparse
import json
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request

from install_canvas_native import package_root, verify_files, definition_for, DEFINITION as NATIVE_DEFINITION
from package_librsvg_materials import checked_file

ROOT = Path(__file__).resolve().parents[2]
DEFINITION = ROOT / 'src/provider-runtime/native-linux/asset-candidate.json'
EVIDENCE = 'sources/canvas/linux/native-build'
RECORD = 'licenses/canvas/linux-asset.json'


def native_tools():
    sys.path.insert(0, str(ROOT / 'scripts/native'))
    from package_linux_sources import verify as sources
    from package_linux_cargo import verify as cargo
    from verify_linux_canvas import verify as native
    return sources, cargo, native


def definition(path=DEFINITION):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if (data['schema_version'] != 1 or data['platform'] != 'linux-x86-64'
            or data['status'] != 'verified-candidate-not-selected'):
        raise ValueError('Unexpected Linux candidate definition')
    return data


def bind(bundle, data):
    evidence = Path(bundle) / EVIDENCE
    report = json.loads((evidence / 'native-build-report.json').read_text(encoding='utf-8'))
    files = [{k: row[k] for k in ('path', 'bytes', 'sha256')} for row in report['files']]
    if report['archive'] != data['native_archive'] or files != data['files']:
        raise ValueError('Native evidence differs from fixed candidate')
    checked_file(evidence / data['native_archive']['filename'], data['native_archive'])
    canvas = package_root(Path(bundle) / 'tools/bgutil-provider', data['canvas_version'])
    verify_files(canvas, {row['path']: row for row in data['files']})


def verify(bundle, candidate=DEFINITION):
    bundle = Path(bundle)
    data = definition(candidate)
    if (bundle / RECORD).read_bytes() != Path(candidate).read_bytes():
        raise ValueError('Shipped Linux asset definition differs')
    _, selected, _ = definition_for(NATIVE_DEFINITION, 'linux-x86-64')
    if (selected['sha256'] != data['native_archive']['sha256']
            or selected['files'] != data['files']
            or selected['materials']['sha256'] != data['materials']['sha256']):
        raise ValueError('Selected runtime differs from retained source materials')
    bind(bundle, data)
    sources, cargo, native = native_tools()
    evidence = bundle / EVIDENCE
    result = dict(native=native(evidence), sources=sources(bundle, evidence), cargo=cargo(bundle))
    result.update(installed_binary_binding='passed', remote_asset_selected=True)
    return result


def prepare(bundle, archive, candidate=DEFINITION):
    from verify_bundle import extract, verify_manifest
    bundle, archive = Path(bundle).resolve(), Path(archive)
    data = definition(candidate)
    checked_file(archive, data['materials'])
    sources, cargo, native = native_tools()
    from package_linux_material_bundle import NATIVE_FILES
    with tempfile.TemporaryDirectory(prefix='linux-native-import-') as temporary:
        work = Path(temporary)
        extract(archive, work)
        verify_manifest(work)
        evidence = work / 'native'
        if {p.name for p in evidence.iterdir()} != set(NATIVE_FILES):
            raise ValueError('Unexpected native evidence set')
        native(evidence)
        sources(work, evidence)
        cargo(work)
        mappings = [
            ('sources/canvas/linux/native-sources', 'sources/canvas/linux/native-sources'),
            ('sources/canvas/linux/librsvg-cargo', 'sources/canvas/linux/librsvg-cargo'),
            ('licenses/canvas/linux-sources', 'licenses/canvas/linux-sources'),
            ('licenses/canvas/linux-librsvg-rust', 'licenses/canvas/linux-librsvg-rust'),
            ('native', EVIDENCE),
        ]
        for _, target in mappings:
            destination = bundle / target
            if not destination.resolve().is_relative_to(bundle) or destination.exists():
                raise ValueError('Material destination must be new and inside bundle')
        if not (bundle / RECORD).resolve().is_relative_to(bundle) or (bundle / RECORD).exists():
            raise ValueError('Asset record destination must be new and inside bundle')
        for origin, target in mappings:
            shutil.copytree(work / origin, bundle / target)
        shutil.copyfile(candidate, bundle / RECORD)
    return verify(bundle, candidate)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--materials', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    native_tools()
    if args.verify_only:
        result = verify(args.bundle)
    else:
        if args.materials:
            result = prepare(args.bundle, args.materials)
        else:
            _, selected, _ = definition_for(NATIVE_DEFINITION, 'linux-x86-64')
            with tempfile.TemporaryDirectory(prefix='linux-material-download-') as temporary:
                archive = Path(temporary) / 'materials.zip'
                with urllib.request.urlopen(selected['materials']['url'], timeout=120) as response, archive.open('wb') as output:
                    shutil.copyfileobj(response, output)
                result = prepare(args.bundle, archive)
    print(json.dumps(result), flush=True)
