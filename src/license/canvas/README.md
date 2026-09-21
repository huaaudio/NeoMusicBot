# Canvas native distribution materials

The Windows Canvas 3.2.3 prebuild includes 44 DLLs from 34 MSYS2 packages.
`windows/manifest.json` maps every DLL hash in the pinned native definition to
an official binary package with identical file bytes. Filename case is ignored
only when locating the original `libLerc.dll`; the shipped `liblerc.dll` bytes
are unchanged.

For each package, the corresponding source archive contains a `PKGBUILD` whose
SHA-256 matches the binary package's `.BUILDINFO` record. The source archives
include the original build recipes and patches; archives using Git sources
also retain their source repositories. They are downloaded from the official
MSYS2 source repository with fixed size/hash checks and included under
`sources/canvas/windows` in the Windows distribution. An MSYS2 UCRT64 build
environment and the dependencies described in the recipes are needed to build
them; a successful local rebuild is not claimed here.

The 55 license files are preserved byte-for-byte from the matching binary
packages, except giflib's missing packaged notice: that original `COPYING` is
extracted from `giflib-5.2.2.tar.gz` inside its verified source archive. Manifest
entries retain each document's original package/source path. Included GPL/LGPL,
exceptions, and other terms apply to the identified components; this is not a
relicensing of NeoMusicBot's independently licensed source.

This is package-level material coverage for those 44 Windows DLLs. It does not
close the complete distribution review. The separate `librsvg-rust/` definition
adds the full union of the original and recipe-reconstructed Cargo lock graphs:
363 original source archives, 642 extracted original documents, and 10 supplemental
texts. Its README explains the reconstruction and the deliberately broader scope
than the linked DLL. Linux Canvas libraries, the Canvas build recipe itself, and other
native/WASM components are tracked separately in `docs/distribution-licenses.md`.
The manifest deliberately marks the embedded dependency review incomplete.

`scripts/ci/package_canvas_materials.py` checks that these records cover exactly
the DLL hashes in `src/provider-runtime/canvas-native.json`. Windows assembly
collects the reviewed documents and fixed source archives. Verification repeats
after clean ZIP extraction, comparing the shipped definition with the repository
definition, actual DLLs, source archives, and every license document.
