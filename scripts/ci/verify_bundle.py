"""Validate the actual shipped archive in a fresh home, not the build directory."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from package_bundle import digest
from verify_licenses import verify as verify_licenses
from inventory_provider import verify as verify_provider_inventory
from prepare_provider import verify as verify_provider_runtime
from install_canvas_native import verify as verify_canvas_native
from package_canvas_materials import verify as verify_canvas_materials
from package_librsvg_materials import verify as verify_librsvg_materials
from package_quickjs_materials import verify as verify_quickjs_materials
from package_deno_materials import verify as verify_deno_materials


def extract(archive_path, destination):
    destination = Path(destination).resolve()
    with zipfile.ZipFile(archive_path) as archive:
        for entry in archive.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination) or "\\" in entry.filename:
                raise ValueError("Archive contains an unsafe path")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Release ZIP must contain files, not symbolic links")
        archive.extractall(destination)
        if os.name != "nt":
            for entry in archive.infolist():
                if not entry.is_dir():
                    (destination / entry.filename).chmod((entry.external_attr >> 16) & 0o777 or 0o644)


def verify_manifest(bundle):
    bundle = Path(bundle).resolve()
    listed = set()
    for line in (bundle / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        expected, name = line.split("  ", 1)
        path = (bundle / name).resolve()
        if not re.fullmatch(r"[a-f0-9]{64}", expected) or not path.is_relative_to(bundle):
            raise ValueError("Invalid checksum entry")
        if name in listed or digest(path) != expected:
            raise ValueError("Bundle checksum mismatch")
        listed.add(name)
    actual = {p.relative_to(bundle).as_posix() for p in bundle.rglob("*") if p.is_file()}
    if actual != listed | {"SHA256SUMS"}:
        raise ValueError("Checksum manifest does not cover the complete bundle")


def verify(archive_path, report_path):
    archive_path, report_path = Path(archive_path).resolve(), Path(report_path).resolve()
    expected = Path(str(archive_path) + ".sha256").read_text().split()[0]
    if digest(archive_path) != expected:
        raise ValueError("Archive checksum mismatch")
    # Spaces and parentheses exercise Windows launcher quoting as well as relocation.
    with tempfile.TemporaryDirectory(prefix="NeoMusicBot verify (clean) ") as work:
        # Deno permission scopes must use the same long paths as its module
        # resolver, not Windows TEMP's possible RUNNER~1 spelling.
        work = Path(work).resolve()
        bundle = work / "release"
        bundle.mkdir()
        extract(archive_path, bundle)
        verify_manifest(bundle)
        verify_licenses(bundle / "licenses/maven", bundle / "NeoMusicBot.cdx.json")
        verify_provider_runtime(bundle / "tools/bgutil-provider", os.environ["POT_PROVIDER_COMMIT"])
        verify_canvas_native(bundle / "tools/bgutil-provider",
                             "windows-x86-64" if os.name == "nt" else "linux-x86-64")
        if os.name == "nt":
            verify_canvas_materials(bundle)
            verify_librsvg_materials(bundle)
        verify_quickjs_materials(bundle)
        verify_deno_materials(bundle, "windows-x86-64" if os.name == "nt" else "linux-x86-64")
        verify_provider_inventory(bundle / "tools/bgutil-provider", bundle / "provider-dependencies.json",
                                  bundle / "licenses/provider")
        for name in ("README.md", "docs/install-and-upgrade.md", "licenses/NeoMusicBot-Apache-2.0.txt",
                     "licenses/maven/licenses.xml", "licenses/yt-dlp-THIRD_PARTY_LICENSES.txt",
                     "sources/yt-dlp.tar.gz", "tools/bgutil-provider/LICENSE",
                     "NeoMusicBot.cdx.json", "NeoMusicBot.cdx.xml"):
            if not (bundle / name).is_file():
                raise ValueError("Missing release document: " + name)
        with zipfile.ZipFile(bundle / "NeoMusicBot.jar") as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        version = re.search(r"^Implementation-Version: ([^\r\n]+)", manifest, re.MULTILINE).group(1)
        sbom = json.loads((bundle / "NeoMusicBot.cdx.json").read_text(encoding="utf-8"))
        if sbom["metadata"]["component"]["version"] != version:
            raise ValueError("JAR and SBOM versions differ")
        env = {k: v for k, v in os.environ.items() if k.upper() in
               {"PATH", "JAVA_HOME", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT"}}
        home = work / "home"
        home.mkdir()
        for key in ("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "TMPDIR", "XDG_CACHE_HOME"):
            env[key] = str(home)
        env["JDK_JAVA_OPTIONS"] = f'-Duser.home="{home}" -Dnoprompt=true'
        env["DENO_NO_PROMPT"] = "1"
        env["DENO_NO_UPDATE_CHECK"] = "1"
        env["FORCE_COLOR"] = "false"
        launcher = [str(bundle / "run_neomusicbot.cmd")] if os.name == "nt" else ["sh", str(bundle / "run_neomusicbot.sh")]

        def run(command, expected_code=0, timeout=45):
            result = subprocess.run(command, cwd=bundle, env=env, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
            output = result.stdout.decode("utf-8", errors="replace")
            if result.returncode != expected_code:
                # These probes never consume credentials, but still keep CI output bounded.
                raise RuntimeError(f"Bundle probe {Path(command[0]).name} failed ({result.returncode}): " + output[-3000:])
            return output

        if "Usage: NeoMusicBot" not in run([*launcher, "--help"]):
            raise ValueError("Launcher help failed")
        if "NeoMusicBot " + version not in run([*launcher, "--version"]):
            raise ValueError("Launcher version mismatch")
        run([*launcher, "generate-config"])
        config_hash = digest(bundle / "config.txt")
        run([*launcher, "generate-config"], 1)
        if digest(bundle / "config.txt") != config_hash:
            raise ValueError("Existing configuration was overwritten")
        run(launcher, 1, 20)  # No credentials: must exit, without prompting or contacting Discord.
        self_test = run([*launcher, "--self-test"])
        for marker in ("native.dave=passed", "native.opus=passed", "crypto.rtp=passed",
                       "audio.m4a=passed", "audio.ogg=passed", "audio.mp3=passed", "self-test=passed"):
            if marker not in self_test:
                raise ValueError("Packaged native/codec self-test failed")
        suffix = ".exe" if os.name == "nt" else ""
        yt_dlp = bundle / "tools" / ("yt-dlp" + suffix)
        deno = bundle / "tools" / ("deno" + suffix)
        with yt_dlp.open("rb") as executable:
            magic = executable.read(4)
        if not (magic.startswith(b"MZ") if os.name == "nt" else magic == b"\x7fELF"):
            raise ValueError("yt-dlp must be a standalone executable, not a host Python script")
        if run([str(yt_dlp), "--ignore-config", "--no-plugin-dirs", "--version"]).strip() != os.environ["YTDLP_VERSION"]:
            raise ValueError("Bundled yt-dlp version mismatch")
        if not run([str(deno), "--version"]).startswith("deno " + os.environ["DENO_VERSION"] + " "):
            raise ValueError("Bundled Deno version mismatch")
        provider = bundle / "tools/bgutil-provider/server"
        env["DENO_DIR"] = str(provider / ".deno-dir")
        cache = home / "bgutil-ytdlp-pot-provider"
        cache.mkdir()
        output = run([str(deno), "run", "--cached-only", "--frozen", "--node-modules-linker=hoisted",
                      "--allow-env", "--deny-net", f"--allow-ffi={provider / 'node_modules'}",
                      f"--allow-read={cache},{provider / 'node_modules'}", f"--allow-write={cache}",
                      str(provider / "src/generate_once.ts"), "--version"])
        if os.environ["POT_PROVIDER_VERSION"] not in output.splitlines():
            raise ValueError("Relocated provider could not run offline")
        canvas_probe = work / "canvas-probe.cjs"
        shutil.copyfile(Path(__file__).with_name("canvas_probe.cjs"), canvas_probe)
        canvas_output = run([str(deno), "run", "--cached-only", "--frozen", "--node-modules-linker=hoisted",
                             "--node-modules-dir=manual",
                             "--allow-env", "--deny-net", f"--allow-ffi={provider / 'node_modules'}",
                             f"--allow-read={provider / 'node_modules'}", str(canvas_probe),
                             str(provider / "node_modules/canvas")])
        if "canvas.native=passed" not in canvas_output.splitlines():
            raise ValueError("Relocated Canvas native rendering failed")
        lines = ["bundle=passed", f"neomusicbot.commit={os.environ['GITHUB_SHA']}",
                 f"version={version}", f"archive.sha256={expected}", "config=passed", "native.dave=passed",
                 "native.opus=passed", "crypto.rtp=passed",
                 "audio.aac=passed", "audio.opus=passed", "audio.mp3=passed", "provider.offline=passed",
                 "provider.inventory=passed", "provider.native=passed", "provider.quickjs=passed", "runtime.deno.materials=passed",
                 "discord.voice=not-tested", "online.media=not-tested"]
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("Clean extracted bundle verified: " + archive_path.name)


if __name__ == "__main__":
    verify(*sys.argv[1:])
