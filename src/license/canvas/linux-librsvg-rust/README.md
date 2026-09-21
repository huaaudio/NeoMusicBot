# Linux librsvg Cargo source materials

This collection belongs to the pinned, unmodified librsvg 2.63.2 source archive
in `src/provider-runtime/native-linux/inputs.json`. Its Meson recipe invokes
Cargo with `--locked`; `Cargo.lock` here is extracted byte-for-byte from that
archive. No Windows/MSYS2 lock-file update is applied to this Linux build.

The full lock graph contains 357 checksum-pinned original `.crate` archives,
including build, test, optional, and other-platform dependencies. This is not
a census of the crates linked into the Linux shared library. Workspace members
remain in the parent librsvg source archive.

The manifest records 641 original license, notice, copyright, or author files
inside those archives. Eleven packages lack the usual root license documents;
their declared terms are associated with eight supplementary texts:

- For defmt-parser 1.0.0 and mutants 0.0.4, parent-repository license texts come
  from each crate's `.cargo_vcs_info.json` commit. Source declarations and VCS
  metadata are preserved in `source-declarations.json`.
- The two winapi architecture crates are byte-identical to the 0.4.0 sources
  already reviewed for Windows. Their upstream texts refer to the fixed
  winapi 0.3.9 commit identifying those architecture package versions.
- The anes, block, difflib, malloc_buf, objc-foundation and objc_id archives
  are also byte-identical to the previously reviewed Windows collection.
  Their MIT/Apache declarations are associated with labelled SPDX references.
- selectors 0.40.0 declares MPL-2.0 in Cargo.toml and its source headers. Its
  recorded stylo commit has no root LICENSE document, so the supplement is
  explicitly the MPL-2.0 standard reference, not a purported author notice.

Original source archives retain all authorship and source notices unchanged.
No copyright holder or year is invented. A `standard-reference` is the
standard license text, not a replacement for the package's source declarations.

`scripts/native/package_linux_cargo.py` verifies the exact parent source
definition, complete lock graph, archive hashes and sizes, package licensing
metadata, and the bytes of every extracted or supplementary document. It does
not execute Cargo build scripts. These checks establish source/material
correspondence; they do not finish the distribution-wide license review.

The application still uses the existing fixed native assets. Association with
the final source-built native binary and integration into the application
release bundle are pending. This source collection does not change runtime code.
