"""Access the independently delivered Windows Canvas corresponding sources."""
import argparse
from pathlib import Path
from package_windows_sources import source_records, verify
from package_shared_source_access import prepare as separate, restore_for_verification as restore

ASSET = Path(__file__).resolve().parents[2] / 'src/license/canvas/windows-source-access.json'
RECORD = 'licenses/canvas/windows-source-access.json'


def options():
    return dict(asset=ASSET, source_roots=('sources/canvas/windows',), records=source_records(), access_record=RECORD)


def prepare(bundle):
    separate(bundle, **options())


def restore_for_verification(bundle, archive=None):
    return restore(bundle, archive, verifier=verify, **options())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    prepare(args.bundle)
