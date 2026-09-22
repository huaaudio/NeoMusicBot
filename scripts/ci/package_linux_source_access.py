"""Deliver Linux Canvas sources through the existing immutable native material asset."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request
from install_canvas_native import definition_for, DEFINITION
from package_librsvg_materials import checked_file
from package_linux_native_materials import verify, native_tools, EVIDENCE

RECORD = 'licenses/canvas/linux-source-access.json'
SOURCE_ROOT = 'sources/canvas/linux'


def selected_materials():
    _, selected, _ = definition_for(DEFINITION, 'linux-x86-64')
    return selected['materials']


def prepare(bundle):
    bundle = Path(bundle).resolve()
    verify(bundle)
    source = bundle / SOURCE_ROOT
    target = bundle / RECORD
    if source.is_symlink() or not source.resolve().is_relative_to(bundle):
        raise ValueError('Native source directory escapes bundle')
    if target.exists() or not target.resolve().is_relative_to(bundle):
        raise ValueError('Native source access record must be new and inside bundle')
    target.write_text(json.dumps(selected_materials(), indent=2)+'\n', encoding='utf-8', newline='\n')
    shutil.rmtree(source)


def restore_for_verification(bundle, archive=None):
    bundle = Path(bundle).resolve()
    record = selected_materials()
    if json.loads((bundle / RECORD).read_text(encoding='utf-8')) != record:
        raise ValueError('Shipped native source access differs from selected asset')
    destination = bundle / SOURCE_ROOT
    if destination.exists() or destination.is_symlink() or not destination.resolve().is_relative_to(bundle):
        raise ValueError('Native sources must be absent from runtime bundle')
    from verify_bundle import extract, verify_manifest
    with tempfile.TemporaryDirectory(prefix='native-source-access-') as temporary:
        work = Path(temporary)
        if archive is None:
            path = work / 'materials.zip'
            with urllib.request.urlopen(record['url'], timeout=120) as response, path.open('wb') as target:
                shutil.copyfileobj(response, target)
        else:
            path = Path(archive)
        checked_file(path, record)
        material = work / 'material'
        extract(path, material)
        verify_manifest(material)
        sources, cargo, native = native_tools()
        native(material / 'native')
        sources(material, material / 'native')
        cargo(material)
        shutil.copytree(material / SOURCE_ROOT, destination)
        shutil.copytree(material / 'native', bundle / EVIDENCE)
    result = verify(bundle)
    result['source_url_verified'] = archive is None
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    prepare(args.bundle)
