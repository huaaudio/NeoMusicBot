# Windows librsvg Cargo source materials

The pinned Canvas DLL is byte-identical to MSYS2's
`mingw-w64-ucrt-x86_64-librsvg 2.62.0-1`. The parent Windows manifest identifies
that binary, its source archive and the matching `PKGBUILD` hash. These Cargo
materials supplement that source package, which does not vendor its Rust crates.

The build recipe runs `cargo update -p windows-sys@0.61.2 --precise 0.60.2`
before `cargo fetch --locked`. `Cargo.lock` is the original source lock;
`Cargo.lock.msys2` is a reconstruction made by running that exact update using
Cargo 1.93.1, matching the Rust version recorded by the binary's `.BUILDINFO`.
It is not an archived final lock file from the original MSYS2 build, nor proof
of a reproducible binary rebuild. The command replaces one package and adds
nine others. Package records common to both locks are unchanged. The ten added
versions and their checksums were checked against crates.io version metadata;
all were published before the binary package build date. Those dates are
preserved in `added-version-dates.json` with links to the metadata endpoints.

The distribution includes the union of both lock files: 363 checksum-pinned,
unmodified `.crate` source archives. This intentionally includes build, test,
optional and other-platform dependencies; it is **not** a claim that 363 crates
are linked into the Windows DLL. Local workspace members are already in the
parent librsvg source archive. This collection does not update runtime code.

Under `sources/canvas/windows/librsvg-cargo/` are the two locks, matching recipe,
binary build metadata, publication-date evidence and all crate archives. Under
`licenses/canvas/librsvg-rust/` are the reviewed manifest, this explanation,
original license/notice documents extracted from the archives without changing
their bytes, and supplemental texts. All crate metadata, source headers and any
embedded notices also remain in the original archives.

Fourteen archives lack the usual root license files. The manifest maps their
declared terms to supplemental material. For cssparser-color, mutants and
wasip2, original parent-repository texts come from the crate's recorded VCS
commit. For the winapi architecture support crates, texts come from the fixed
winapi 0.3.9 commit whose architecture manifests identify version 0.4.0.
The remaining references are SPDX standard license texts, labelled
`standard-reference`; they are not represented as those authors' original
license notices. Existing authorship and source notices remain untouched, and
no copyright holder or year has been invented.

The assembler verifies both lock graphs, archive size/SHA-256, package identity
and declared licensing metadata, and each extracted document. The clean ZIP
verifier compares the shipped material with the repository definition, the
actual DLL and its parent source archive. It does not run Cargo build scripts.
These checks establish source/material correspondence, not a legal opinion or
completion of the distribution-wide review. Other native/WASM components and
the Linux libraries remain separately tracked in `docs/distribution-licenses.md`.
