"""Read back retained source-built Canvas bytes against the reviewed inputs.

Run after downloading the native CI artifact from a verified run. A matching
report is evidence of its probes, not a replacement for the actual archive.
This command neither executes native code nor certifies licensing completeness.
"""
import argparse
import json
from pathlib import Path
import sys
import tempfile

from build_linux_canvas import DEFINITION, ROOT, digest
from package_linux_canvas import DENO_SHA256, FONT_SHA256

sys.path.insert(0, str(ROOT / "scripts/ci"))
from install_canvas_native import safe_name, unpack

ARCHIVE = "canvas-v3.2.3-linux-x86-64-source-built.tar.gz"


def verify(output, definition=DEFINITION, source_lock=None):
    output, definition = Path(output), Path(definition)
    if source_lock is None:
        source_lock = ROOT / "src/license/canvas/linux-librsvg-rust/Cargo.lock"
    report = json.loads((output / "native-build-report.json").read_text(encoding="utf-8"))
    build = json.loads((definition / "inputs.json").read_text(encoding="utf-8"))
    expected_inputs = {name: digest(definition / name) for name in ("inputs.json", "package.json", "package-lock.json")}
    if (report.get("schema_version") != 1 or report.get("platform") != "linux-x86-64"
            or report.get("input_hashes") != expected_inputs
            or report.get("source_lock_sha256") != digest(source_lock)):
        raise ValueError("Native report differs from reviewed build inputs")
    for name, expected in expected_inputs.items():
        if (output / name).is_symlink() or digest(output / name) != expected:
            raise ValueError("Retained build input differs from repository: " + name)
    if report.get("shared_versions") != build["expected_shared_versions"]:
        raise ValueError("Native report has unexpected shared-library versions")
    probes = report.get("probes", {})
    expected_probes = dict(formats="passed", explicit_font="passed", gif="passed",
                           deno_sha256=DENO_SHA256, font_sha256=FONT_SHA256,
                           host_graphics_libraries="not-mounted", host_fonts="not-mounted",
                           build_directories="not-mounted", network="unshared-and-denied",
                           explicit_LD_LIBRARY_PATH=False)
    if any(probes.get(name) != value for name, value in expected_probes.items()) or report.get("archive_readback") != "passed":
        raise ValueError("Native report lacks required isolated probe evidence")
    files = {}
    for item in report["files"]:
        name = item["path"]
        safe_name(name)
        if name in files or name not in {"build/Release/" + entry for entry in build["expected_native_files"]}:
            raise ValueError("Duplicate or unexpected native file record")
        files[name] = item
    if set(files) != {"build/Release/" + name for name in build["expected_native_files"]}:
        raise ValueError("Native file records are incomplete")
    archive = report["archive"]
    if archive["filename"] != ARCHIVE:
        raise ValueError("Unexpected native archive filename")
    path = output / ARCHIVE
    if path.is_symlink() or not path.is_file():
        raise ValueError("Actual native archive is missing or linked")
    if path.stat().st_size != archive["bytes"] or digest(path) != archive["sha256"]:
        raise ValueError("Native archive differs from its build report")
    with tempfile.TemporaryDirectory(prefix="native-readback-") as temporary:
        unpack(path, Path(temporary), files)
    return dict(native_files=len(files), archive_sha256=archive["sha256"],
                reviewed_inputs="passed", archive_readback="passed",
                license_review_complete=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.output)))
