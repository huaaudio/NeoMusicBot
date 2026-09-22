# Linux Canvas source and notice collection

The manifest pins the original archives and notice bytes for the reviewed Linux
Canvas recipe. It maps all 37 native files to eight source-built components or
exact Ubuntu SDK packages. The 20 Ubuntu source identities are checked against
the fixed build inputs and each original .dsc checksum table. Ubuntu binary
metadata and native/copyright bytes are re-read with dpkg-deb without installing
packages. Two runtime packages use the copyright document from their exact
parent source archive, rather than following a host documentation symlink.

The thirteen upstream material archives comprise eight runtime source archives,
node-addon-api header sources, and the exact Node/Rust SDK archives whose
original notices were collected, plus the matching full Rust source release.
The Rust source version and commit identity are checked against the fixed SDK
and re-read from the actual source archive during collection and readback.
Node and Rust SDK archives are not represented
as full upstream source releases or as an inventory of linked runtime objects.
Their notices include build-only components. All 4,044 original documents retain
their archive paths and bytes. The complete librsvg Cargo source graph and its
supplemental documents remain in the separate linux-librsvg-rust collection.

package_linux_sources.py binds this material to the supplied actual native
archive and its matching build report. A report without the archive is rejected;
Ubuntu original-file hashes must match the reviewed package mapping. The recipe
and fixed build inputs are retained alongside source archives. Readback compares
the output with the repository definition and repeats all byte checks, descriptor
checks, notice extraction checks and native-archive verification.

This closes the reproducible collection and archive-association step for these
materials. It does not certify the whole distribution's licensing or the complete
coverage of every embedded component. In particular, Deno, yt-dlp, QuickJS/WASM
and remaining source/notice review are tracked in docs/distribution-licenses.md.
No runtime asset is selected or published by this collection tool.
