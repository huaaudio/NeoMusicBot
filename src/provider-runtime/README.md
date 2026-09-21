# NeoMusicBot provider distribution adjustment

This distribution uses the original bgutil-ytdlp-pot-provider 2.0.0 source at
commit `37169ee2656e08c5c2e5dc9df4c598c0cb4c88a8`, with three installation files
adjusted by Huaaudio (2026): `server/package.json` omits `devDependencies`, and
`server/deno.lock` retains the resulting runtime dependency graph, and
`server/deno.json` disables lifecycle scripts with `allowScripts: []`.

The original files are preserved beside them as `package.json.upstream` and
`deno.lock.upstream` and `deno.json.upstream`. No provider source, runtime root dependency, package version,
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

Canvas's npm lock entry covers its package tarball, but its lifecycle script
downloads a separate native prebuild. Distribution builds therefore omit
`--allow-scripts` and use `scripts/ci/install_canvas_native.py` after installing
and caching the frozen npm graph. `canvas-native.json` pins the official
Canvas 3.2.3 N-API v7 archives for Windows/Linux x86-64, their SHA-256 values,
every extracted file's size and hash, and the upstream source/build commits.
The upstream build recipe is on the separate `prebuilds` branch; its recorded
commit is not the Canvas source tag commit.

Omitting `--allow-scripts` alone is insufficient: the upstream `deno.json`
explicitly approves Canvas and SWC install scripts. The profile validates the
complete original configuration before clearing only that approval list, and
retains the original bytes. Bundle verification checks both configurations.

The installer accepts only the listed regular files and their directories,
checks the installed npm package version, and refuses an existing native build.
It preserves the original archive contents, including build metadata. The
distribution records this definition and selected platform in
`server/neomusicbot-canvas.json`. Clean-bundle verification compares that record
against NeoMusicBot's checked-in definition and checks the complete native file
set. The prerelease gate requires `provider.native=passed`; the media canary
uses the same installation procedure. The extracted bundle also performs an
offline native pixel-rendering and PNG-encoding probe before reporting success.

This adjustment removes development tools from the installation. It does not
certify third-party licensing or prove online token generation succeeds.
