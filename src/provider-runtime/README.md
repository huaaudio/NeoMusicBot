# NeoMusicBot provider distribution adjustment

This distribution uses the original bgutil-ytdlp-pot-provider 2.0.0 source at
commit `37169ee2656e08c5c2e5dc9df4c598c0cb4c88a8`, with two installation files
adjusted by Huaaudio (2026): `server/package.json` omits `devDependencies`, and
`server/deno.lock` retains the resulting runtime dependency graph.

The original files are preserved beside them as `package.json.upstream` and
`deno.lock.upstream`. No provider source, runtime root dependency, package version,
integrity value, or npm dependency record has been changed. Original copyright
and license files remain in the distribution. Development tools can be installed
in a separate source checkout using the original files.

The runtime lock was generated with Deno 2.9.7 using `deno install
--lockfile-only --node-modules-linker=hoisted --frozen=false` after removing only
the development dependency declaration. It was then reviewed against the original
lock. Two CSS package peer-context keys were normalized; their versions,
integrities and dependency records are identical. The complete runtime lock is
committed in NeoMusicBot, so release builds do not regenerate or update it.

`scripts/ci/prepare_provider.py` checks the pinned source commit, complete JSON
fingerprints, runtime roots and all retained npm records before applying this
adjustment. Installation and caching still use `--frozen`; clean-bundle checks
verify the adjusted and original files and run the provider with `--cached-only`.
JSON fingerprints use sorted keys and compact separators, so checkout line-ending
differences do not change their meaning. The original file bytes are also covered
by the distribution inventory and checksums.

This adjustment removes development tools from the installation. It does not
certify third-party licensing or prove online token generation succeeds.
