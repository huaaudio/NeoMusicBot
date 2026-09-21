# Provider license supplements

These are captured upstream documents, an explicitly described verbatim license
comment, or an explicitly identified standard license text, for the exact packages
in `manifest.json`. The original package source,
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
preserved in full. The WASI toolchain and other embedded material still require review.

`MIT-standard-text.txt` is the unmodified SPDX reference text for the explicit
`"license": "MIT"` declarations in `proxy-agent-negotiate@1.1.0` and
`quickjs-wasi@2.2.0`. Neither installed package supplies a separate wrapper-level
license file. It is identified as a standard text, not an upstream project-specific
copyright notice; the reference's year/holder placeholders are not filled with
guesses. Both packages' original metadata and source remain in the bundle,
including proxy-agent-negotiate's author field. QuickJS's embedded component
licenses remain separate. See the [npm license field documentation](https://docs.npmjs.com/cli/v11/configuring-npm/package-json/#license)
and [SPDX MIT text](https://spdx.org/licenses/MIT.html).
