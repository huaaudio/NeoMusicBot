"""Package and test a source-built Linux Canvas closure without publishing it.

The graphics libraries must all come from the private SDK. The isolated Deno
tests see only the relocated bundle, an explicit font, and OS glibc/gconv.
This validates reconstruction and relocation, not bit-identical recompilation.
"""
import argparse
import gzip
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

from build_linux_canvas import DEFINITION, ROOT, digest

GLIBC = {"libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0", "librt.so.1", "libresolv.so.2"}
# Deno 2.9.7 linux-x86-64 executable from its checksum-verified upstream ZIP.
DENO_SHA256 = "ce6a052beb97c2b92de67077e3f3924ba7c9661ede0d8dc2f66a116a3c841f21"
FONT_SHA256 = "69ccd07023a72ceb27a5e5c22f728627353b60a198170f5e58dd7014221abf01"


def dependencies(path, env):
    output = subprocess.check_output(["ldd", str(path)], text=True, env=env)
    if "not found" in output:
        raise ValueError("Missing ELF dependency: " + path.name)
    return {name: Path(source).resolve() for name, source in
            re.findall(r"^\s*(\S+)\s+=>\s+(/\S+)", output, re.M)}


def baseline(files):
    records = []
    for path in files:
        output = subprocess.check_output(["readelf", "--wide", "--version-info", str(path)], text=True)
        needs = output.split("Version needs section", 1)[-1] if "Version needs section" in output else ""
        symbols = sorted(set(re.findall(r"Name: ((?:GLIBC|GLIBCXX|CXXABI)_[0-9.]+)", needs)))
        records.append(dict(name=path.name, required_symbol_versions=symbols))
    symbols = {symbol for item in records for symbol in item["required_symbol_versions"]}
    maximum = {name: max((symbol for symbol in symbols if symbol.startswith(name + "_")),
                        key=lambda symbol: tuple(map(int, symbol.split("_")[-1].split("."))), default=None)
               for name in ("GLIBC", "GLIBCXX", "CXXABI")}
    return dict(native_files=records, maximum_required=maximum,
                symbol_analysis_is_not_an_independent_older_os_test=True)


def isolated_probes(stage, bundle, deno, font, native_dependencies, env):
    os_lib = stage / "os-runtime"
    os_lib.mkdir()
    deno_dependencies = dependencies(deno, env)
    if set(deno_dependencies) - GLIBC - {"libgcc_s.so.1"}:
        raise ValueError("Deno needs unexpected OS libraries")
    for name, source in {**native_dependencies, **deno_dependencies}.items():
        if name in GLIBC:
            shutil.copyfile(source, os_lib / name)
    # Deno loads libgcc before the addon. Use the reviewed bundle member here,
    # so the dynamic loader cannot satisfy the addon's SONAME from a host copy.
    if "libgcc_s.so.1" in deno_dependencies:
        shutil.copyfile(bundle / "build/Release/libgcc_s.so.1", os_lib / "libgcc_s.so.1")
    loader = Path("/lib64/ld-linux-x86-64.so.2").resolve()
    gconv = Path("/usr/lib/x86_64-linux-gnu/gconv")
    if not gconv.is_dir():
        raise ValueError("OS glibc character conversion modules are missing")
    fixtures = stage / "fixtures"
    fixtures.mkdir()
    shutil.copyfile(font, fixtures / "DejaVuSans.ttf")
    (fixtures / "fonts.conf").write_text(
        '<?xml version="1.0"?><fontconfig><cachedir>/tmp/font-cache</cachedir></fontconfig>\n')
    command = [
        "bwrap", "--unshare-all", "--die-with-parent", "--new-session", "--clearenv",
        "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--dir", "/tmp/home",
        "--ro-bind", str(os_lib), "/lib/x86_64-linux-gnu",
        "--ro-bind", str(loader), "/lib64/ld-linux-x86-64.so.2",
        "--ro-bind", str(gconv), "/usr/lib/x86_64-linux-gnu/gconv",
        "--ro-bind", str(deno), "/runtime/deno",
        "--ro-bind", str(bundle), "/bundle",
        "--ro-bind", str(fixtures), "/fixtures",
        "--ro-bind", str(ROOT / "scripts/ci/canvas_probe.cjs"), "/probe/formats.cjs",
        "--ro-bind", str(Path(__file__).with_name("font_probe.cjs")), "/probe/font.cjs",
        "--setenv", "HOME", "/tmp/home", "--setenv", "DENO_DIR", "/tmp/deno-cache",
        "--setenv", "DENO_NO_UPDATE_CHECK", "1", "--setenv", "DENO_NO_PROMPT", "1",
        "--setenv", "LANG", "C.UTF-8", "--chdir", "/tmp",
        "--setenv", "FONTCONFIG_FILE", "/fixtures/fonts.conf",
        "/runtime/deno", "run", "--cached-only", "--deny-net", "--allow-env",
        "--allow-read=/bundle,/fixtures", "--allow-ffi=/bundle",
    ]
    for probe, marker, arguments in (
        ("formats", "canvas.native=passed", []),
        ("font", "canvas.font=passed", ["/fixtures/DejaVuSans.ttf"]),
    ):
        result = subprocess.run([*command, "/probe/" + probe + ".cjs", "/bundle/index.js", *arguments],
                                capture_output=True, text=True, timeout=60, env=env)
        (stage / (probe + ".log")).write_text(result.stdout + result.stderr)
        if result.returncode or marker not in result.stdout.splitlines():
            raise RuntimeError("Isolated native probe failed: " + probe + "; see " + str(stage))
    return dict(formats="passed", explicit_font="passed", deno_sha256=digest(deno),
                font_sha256=digest(font), os_runtime_baseline=sorted(path.name for path in os_lib.iterdir() if path.name in GLIBC),
                deno_runtime_from_bundle=sorted(set(deno_dependencies) - GLIBC),
                os_character_conversion="glibc gconv", host_graphics_libraries="not-mounted",
                host_fonts="not-mounted", build_directories="not-mounted", network="unshared-and-denied",
                explicit_LD_LIBRARY_PATH=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--deno", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    work, deno, output = args.work_dir.resolve(), args.deno.resolve(), args.output_dir.resolve()
    data = json.loads((DEFINITION / "inputs.json").read_text())
    prepared = json.loads((work / "prepared.json").read_text())
    if prepared != {name: digest(DEFINITION / name) for name in ("inputs.json", "package.json", "package-lock.json")}:
        raise ValueError("Build preparation differs from current inputs")
    source_lock = work / "sources/librsvg/librsvg-2.63.2/Cargo.lock"
    material_lock = ROOT / "src/license/canvas/linux-librsvg-rust/Cargo.lock"
    if digest(source_lock) != digest(material_lock):
        raise ValueError("Built librsvg lock differs from its source material collection")
    if digest(deno) != DENO_SHA256:
        raise ValueError("Expected the pinned Deno 2.9.7 Linux executable")
    sdk_lib = work / "sdk/usr/lib/x86_64-linux-gnu"
    canvas = work / "sources/canvas/package"
    addon = canvas / "build/Release/canvas.node"
    font = work / "sources/pango/pango-1.58.2/tests/fonts/DejaVuSans.ttf"
    if digest(font) != FONT_SHA256:
        raise ValueError("Unexpected source font fixture")
    env = dict(PATH="/usr/bin:/bin", LANG="C.UTF-8", LD_LIBRARY_PATH=str(sdk_lib))
    native_dependencies = dependencies(addon, env)
    sources = {"canvas.node": addon, **{name: path for name, path in native_dependencies.items() if name not in GLIBC}}
    if set(sources) != set(data["expected_native_files"]):
        raise ValueError("Native file set differs from the reviewed definition")
    for name, source in sources.items():
        if name != "canvas.node" and not source.is_relative_to(sdk_lib.resolve()):
            raise ValueError("Unexpected host graphics library: " + name)
    # A fresh output prevents an old report or archive from looking successful.
    output.mkdir(parents=True, exist_ok=False)
    stage = Path(tempfile.mkdtemp(prefix="relocation-", dir=work))
    bundle = stage / "canvas"
    release = bundle / "build/Release"
    release.mkdir(parents=True)
    for name in ("index.js", "package.json"):
        shutil.copyfile(canvas / name, bundle / name)
    shutil.copytree(canvas / "lib", bundle / "lib")
    records = []
    for name, source in sorted(sources.items()):
        target = release / name
        shutil.copyfile(source, target)
        subprocess.run([str(work / "sdk/usr/bin/patchelf"), "--set-rpath", "$ORIGIN", str(target)], check=True, env=env)
        records.append(dict(path="build/Release/" + name, sha256=digest(target), bytes=target.stat().st_size,
                            original_path=source.relative_to(work).as_posix(), original_sha256=digest(source)))
    plain_env = dict(PATH="/usr/bin:/bin", LANG="C.UTF-8")
    versions = json.loads(subprocess.check_output([
        "/usr/bin/python3", str(Path(__file__).with_name("shared_versions.py")), str(release)], text=True, env=plain_env))
    if versions != data["expected_shared_versions"]:
        raise ValueError("Actual shared-library versions differ from the definition")
    probes = isolated_probes(stage, bundle, deno, font, native_dependencies, plain_env)
    elf = baseline(sorted(release.iterdir()))
    archive_path = output / "canvas-v3.2.3-linux-x86-64-source-built.tar.gz"
    with archive_path.open("xb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w") as archive:
            for path in sorted(release.iterdir()):
                entry = tarfile.TarInfo("build/Release/" + path.name)
                entry.size, entry.mode, entry.mtime = path.stat().st_size, 0o644, 0
                with path.open("rb") as body:
                    archive.addfile(entry, body)
    # Exercise the existing production archive extractor and exact-file verifier.
    sys.path.insert(0, str(ROOT / "scripts/ci"))
    from install_canvas_native import unpack, verify_files
    extracted = stage / "archive-readback"
    extracted.mkdir()
    files = {item["path"]: item for item in records}
    unpack(archive_path, extracted, files)
    verify_files(extracted, files)
    report = dict(schema_version=1, platform="linux-x86-64", input_hashes=prepared,
                  source_lock_sha256=digest(source_lock),
                  shared_versions=versions, files=records, probes=probes, elf_baseline=elf,
                  archive=dict(filename=archive_path.name, bytes=archive_path.stat().st_size, sha256=digest(archive_path)),
                  archive_readback="passed", release_runtime_changed=False,
                  source_and_license_collection_complete=False)
    (output / "native-build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    for name in ("inputs.json", "package.json", "package-lock.json"):
        shutil.copyfile(DEFINITION / name, output / name)
    print(json.dumps(dict(native_files=len(records), shared_versions=versions,
                          isolated_formats="passed", isolated_font="passed", archive_readback="passed",
                          archive_sha256=digest(archive_path), maximum_required=elf["maximum_required"])), flush=True)


if __name__ == "__main__":
    main()
