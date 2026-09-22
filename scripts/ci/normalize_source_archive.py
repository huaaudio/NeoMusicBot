"""Remove Gitiles request timestamps while preserving all source members."""
import copy
from pathlib import Path
import tarfile
from package_librsvg_materials import safe_relative


def normalize_gitiles_archive(source, destination):
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve():
        raise ValueError("Source normalization requires a separate destination")
    with tarfile.open(source) as original:
        members = original.getmembers()
        names = set()
        for member in members:
            name = member.name.removeprefix("./").rstrip("/")
            safe_relative(name)
            if name in names or not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError("Duplicate or unsupported source archive member")
            names.add(name)
        with tarfile.open(destination, "w", format=tarfile.PAX_FORMAT) as normalized:
            for member in sorted(members, key=lambda item: item.name):
                record = copy.copy(member)
                record.pax_headers = dict(member.pax_headers)
                record.pax_headers.pop("mtime", None)
                record.mtime = 0
                if member.isfile():
                    with original.extractfile(member) as body:
                        normalized.addfile(record, body)
                else:
                    normalized.addfile(record)
