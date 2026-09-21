"""Verify that shading preserves upstream license/notice resources verbatim."""
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile


def verify(shaded_jar, sbom_path, reports):
    sbom = json.loads(Path(sbom_path).read_text(encoding="utf-8"))
    expected = {c["name"] + "-" + c["version"] + ".jar" for c in sbom["components"]}
    if len(expected) != len(sbom["components"]):
        raise ValueError("Ambiguous runtime artifact filenames")
    report = next(Path(reports).glob("TEST-*.xml"))
    classpath = ET.parse(report).find("./properties/property[@name='java.class.path']").get("value")
    jars = {Path(p).name: Path(p) for p in classpath.split(os.pathsep) if Path(p).name in expected}
    if set(jars) != expected:
        raise ValueError("Runtime dependencies missing from tested classpath")
    count = 0
    with zipfile.ZipFile(shaded_jar) as shaded:
        for path in jars.values():
            with zipfile.ZipFile(path) as source:
                for name in source.namelist():
                    if name.endswith(("/", ".class")) or not any(k in name.upper() for k in ("LICENSE", "NOTICE", "COPYING")):
                        continue
                    if name not in shaded.namelist() or source.read(name).strip() not in shaded.read(name):
                        raise ValueError(f"Shading lost attribution: {path.name}/{name}")
                    count += 1
    if count == 0:
        raise ValueError("No upstream attribution resources found")
    print(f"Shaded JAR preserves all {count} upstream license/notice resources")


if __name__ == "__main__":
    verify(*sys.argv[1:])
