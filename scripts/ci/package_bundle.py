"""Create a full release ZIP, including hidden caches, with per-file checksums."""
import hashlib
from pathlib import Path
import sys
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def package(bundle, destination):
    bundle, destination = Path(bundle).resolve(), Path(destination).resolve()
    if destination.is_relative_to(bundle):
        raise ValueError("Archive must be outside the bundle")
    files = sorted(p for p in bundle.rglob("*") if p.is_file() and p != bundle / "SHA256SUMS")
    for path in files:
        if not path.resolve().is_relative_to(bundle):
            raise ValueError("Bundle contains an external symlink")
    manifest = bundle / "SHA256SUMS"
    manifest.write_text("".join(f"{digest(p)}  {p.relative_to(bundle).as_posix()}\n" for p in files), encoding="utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in [*files, manifest]:
            archive.write(path, path.relative_to(bundle).as_posix())
    Path(str(destination) + ".sha256").write_text(f"{digest(destination)}  {destination.name}\n", encoding="ascii")
    print(f"Packaged {len(files) + 1} files: {destination.name}")


if __name__ == "__main__":
    package(*sys.argv[1:])
