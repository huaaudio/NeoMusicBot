# QuickJS WASI 2.2.0 source materials

This collection covers the seven WASM binaries shipped by the exact npm package,
including all six extension modules, on both platforms. Every installed and
cached copy must match the npm archive's actual bytes. The archive is checked
against the runtime lock's SHA-512 integrity, in addition to its pinned SHA-256.

The fixed QuickJS WASI source contains its interface, build recipe, Ada sources,
Mbed TLS / TF-PSA / Everest sources and their original inline notices. The pinned
QuickJS-NG submodule is collected separately. Existing provider license
supplements retain the QuickJS-NG, Ada, tl::expected and Mbed TLS texts. The wrapper
package declares MIT in package.json; the provider supplement explicitly labels
its MIT text as standard text, not an invented upstream copyright notice.

The recipe requires WASI SDK 32. Each actual WASM producer section identifies
Clang 22.1.0-wasi-sdk and LLVM commit 4434dabb69916856b824f68a64b029c67175e532,
matching the SDK's llvm-project submodule. The SDK's pinned wasi-libc source and
full LLVM source preserve libc, libc++, libc++abi and compiler-rt source and
inline notices. Original root/runtime license documents are also extracted for
readability. WASI libc's root notice identifies musl, cloudlibc, allocators and
musl-fts; their sources and original declarations remain intact in its archive.

The URL extension explicitly links libc++ string.cpp.o; the main reactor links
WASI libc, compiler support and emulated process-clock/signal libraries. The
complete source archives also contain build, test and other-platform code: they
are not an assertion that every source object is linked. SDK build-only GNU
config is referenced by its original submodule commit in the retained recipe;
we do not distribute that tool as a runtime binary.

Source associations use the fixed release recipe, npm bytes, submodule commits
and observed compiler producer metadata. They do not claim a bit-reproducible
rebuild. No upstream source or WASM bytes are modified. This collection does not
close the separate Deno, yt-dlp, Canvas or whole-distribution licensing review.
