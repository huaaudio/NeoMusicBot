"""Require a saved license document for every runtime component in the SBOM."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(directory, sbom_path):
    directory = Path(directory).resolve()
    root = ET.parse(directory / "licenses.xml").getroot()
    documented = set()
    for dependency in root.findall("./dependencies/dependency"):
        coordinate = tuple(dependency.findtext(key) for key in ("groupId", "artifactId", "version"))
        if not all(coordinate) or coordinate in documented:
            raise ValueError("Invalid or duplicate license dependency")
        licenses = dependency.findall("./licenses/license")
        if not licenses:
            raise ValueError("Missing licenses: " + ":".join(coordinate))
        for license in licenses:
            name = license.findtext("file")
            path = (directory / (name or "")).resolve()
            if not name or not path.is_relative_to(directory) or not path.is_file() or not path.stat().st_size:
                raise ValueError("Missing license text: " + ":".join(coordinate))
        documented.add(coordinate)
    sbom = json.loads(Path(sbom_path).read_text(encoding="utf-8"))
    components = {(c.get("group", ""), c["name"], c["version"]) for c in sbom["components"]}
    if not components or components != documented:
        raise ValueError(f"SBOM/license coverage mismatch: missing={sorted(components - documented)}, "
                         f"extra={sorted(documented - components)}")
    print(f"License files verified for all {len(components)} SBOM dependencies")


if __name__ == "__main__":
    verify(*sys.argv[1:])
