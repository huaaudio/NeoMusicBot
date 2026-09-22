"""Collect the fixed Windows Canvas and librsvg Cargo sources as a separate asset."""
import argparse
import json
from pathlib import Path
from package_canvas_materials import validate as canvas_definition, DEFINITION, NATIVE_DEFINITION
from package_librsvg_materials import validate as cargo_definition, SOURCE_DIR
from package_shared_sources import collect as collect_sources, verify as verify_sources


def source_records():
    records = {}
    canvas = canvas_definition(DEFINITION, NATIVE_DEFINITION)
    for item in canvas['components']:
        record = item['source']
        records['sources/canvas/windows/' + record['file']] = {k:record[k] for k in ('bytes','sha256')}
    cargo = cargo_definition()
    for record in [*cargo['packages'], *cargo['source_documents']]:
        records[SOURCE_DIR + '/' + record['file']] = {k:record[k] for k in ('bytes','sha256')}
    return records


def verify(archive):
    return verify_sources(archive, source_records())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-archive', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    result = verify(args.output) if args.verify_only else collect_sources(args.runtime_archive, args.output, source_records(), ('sources/canvas/windows/',))
    print(json.dumps(dict(archive=str(args.output), **result)), flush=True)
