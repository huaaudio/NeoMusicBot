# Provider license supplements

These are captured upstream documents, or an explicitly described verbatim license
comment, for the exact packages in `manifest.json`. The original package source,
README notices, and bundled cache files remain untouched. The manifest records
source URLs, applicability, and SHA-256; `.gitattributes` preserves original bytes.

Assembly copies this directory to `licenses/provider`. Inventory and clean extraction
verification reject missing/modified documents, unsafe paths, or a mapped package
whose installed version has changed. Additional upstream components can have their
own terms: these files do not mark the whole provider or its native/WASM payloads
as reviewed. See `docs/distribution-licenses.md` for the remaining work.

The matching parent package's MIT license supplies the package-level text for its
listed native npm builds. This does not assert coverage of those builds' Rust/C
dependencies. For QuickJS, the package tag resolves to commit
`cc1fea4a6a4ac1d960e0db68d35e1459064a1a23`; its recorded QuickJS-NG submodule is
`dec012362bd93876449f3ecff4f835b2eba89bab`. Its Ada header reports version 3.4.3
and embeds `tl::expected` 1.1.0. Both Ada and Mbed TLS license alternatives are
preserved in full. QuickJS's wrapper, WASI toolchain and other embedded material
still require review.
