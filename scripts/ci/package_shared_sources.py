"""Collect the already reviewed Deno and QuickJS source archives into one companion."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

from package_bundle import digest
from package_deno_materials import archive_jobs
from validate_deno_materials import validate as deno_definition
from package_quickjs_materials import validate as quickjs_definition


def source_records():
    records = {}
    for relative, record, _ in archive_jobs(deno_definition()):
        records['sources/deno/' + relative] = {'bytes': record['bytes'], 'sha256': record['sha256']}
    for item in quickjs_definition()['materials']:
        record = item['archive']
        records['sources/quickjs/' + record['filename']] = {'bytes': record['bytes'], 'sha256': record['sha256']}
    return records


def verify(archive):
    expected = source_records()
    with zipfile.ZipFile(archive) as zipped:
        names = zipped.namelist()
        if len(names) != len(set(names)) or set(names) != set(expected):
            raise ValueError('Shared source companion member set differs')
        for name, record in expected.items():
            info = zipped.getinfo(name)
            if info.file_size != record['bytes'] or (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Shared source member size or type differs')
            with zipped.open(name) as body:
                if hashlib.file_digest(body, 'sha256').hexdigest() != record['sha256']:
                    raise ValueError('Shared source member checksum differs: ' + name)
    return {'files': len(expected), 'bytes': Path(archive).stat().st_size, 'sha256': digest(archive)}


def collect(runtime_archive, output):
    output = Path(output)
    if output.exists():
        raise ValueError('Source companion output already exists')
    expected = source_records()
    with zipfile.ZipFile(runtime_archive) as source:
        selected = [x.filename for x in source.infolist()
                    if x.filename.startswith(('sources/deno/', 'sources/quickjs/')) and not x.is_dir()]
        if len(selected) != len(set(selected)) or set(selected) != set(expected):
            raise ValueError('Runtime source file set differs from reviewed definitions')
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as destination:
            for name in sorted(expected):
                info = zipfile.ZipInfo(name, date_time=(2026, 9, 22, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with source.open(name) as body, destination.open(info, 'w', force_zip64=True) as target:
                    shutil.copyfileobj(body, target)
    return verify(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-archive', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    result = verify(args.output) if args.verify_only else collect(args.runtime_archive, args.output)
    print(json.dumps(dict(archive=str(args.output), **result)), flush=True)
