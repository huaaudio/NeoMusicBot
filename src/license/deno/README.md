# Deno 2.9.7 source material definition

This definition pins the actual Windows and Linux executable hashes, the Deno
release commit, its Cargo.lock and 82 workspace source manifests, all 1,046
registry source archives, and the 22 fixed Deno/rusty_v8/submodule source archives.
It additionally retains the Rust 1.95.0 full source release and the TypeScript
6.0.3 npm release plus its source commit. Sources, original embedded declarations,
and build recipes remain intact. No source or executable bytes are modified.

The full Cargo/V8 source collection intentionally includes build, test, optional
and other-platform dependencies. It is not a claim that every package is linked
into either executable. Per-binary hashes and the release recipe provide the
runtime/source association. The Windows executable embeds Rust commit
59807616e1fa2540724bfbac14d7976d7e4a3860, matching the collected Rust source.
The Linux executable does not expose that compiler-path marker; its association
uses the fixed official release and the retained Rust toolchain recipe.

The deno_core_icudata 0.77.0 data exactly matches common/icudtl.dat in the fixed
V8 ICU source (SHA-256 1cf67874b5a87a8363a86fb3f81e3cbbed54d389062dab8fb52308d5cf8c8612).
The full ICU source retains the Unicode/data notices. Deno's modified TypeScript
JavaScript and update instructions remain in the Deno source archive; original
TypeScript sources and third-party notices are also retained. This does not
claim an unmodified TypeScript compiler or a bit-reproducible Deno rebuild.

Of 117 crates without conventionally named original notice files, 66 have
complete originals mapped to the exact package VCS revision and containing
source directory. The mapping keeps any dirty-VCS flag and does not claim that
a published dirty crate is byte-identical to its commit. The other 51 retain
their complete original crate, Cargo license/author statement, any available
original copyright statement, and clearly labeled SPDX standard text for a
license declared by that exact package. No copyright holder or year is invented.
The two gpu-descriptor COPYING files contain copyright statements but only
references to full licenses; their referenced repository files returned 404.
They are retained alongside the selected Apache-2.0 standard text.

Standard texts are distinguished from upstream originals. This base-license
mapping alone does not certify embedded subcomponents or the entire distribution.
The definition is a reviewed collection scope, not yet a closed AUD-005 review:
The assembly scripts now include this material and require clean extraction
verification through runtime.deno.materials. Actual final application ZIP
verification and remaining runtime scope/notice review must complete before a
release can claim material closure.

Original notice-path records also preserve upstream test fixtures, including
empty LICENSE fixtures in Deno tests. Empty fixtures are not license grants or
evidence of licensing coverage; source archive hashes retain them unchanged.
