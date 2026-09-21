# NeoMusicBot

Huaaudio's independently maintained Bilibili-focused fork of
[JMusicBot by jagrosh](https://github.com/jagrosh/MusicBot), licensed under
[Apache 2.0](LICENSE). This fork substantially changes the original playback,
command, and build systems; it is not an official upstream release.
Project home: [huaaudio/NeoMusicBot](https://github.com/huaaudio/NeoMusicBot).

这是 Huaaudio 维护的 Discord 音乐机器人，重点支持 Bilibili 视频与分 P 播放，
并支持 YouTube、SoundCloud 和 Discord 音频附件。源自 JMusicBot，保留原作者署名与开源许可证。
本仓库仍在开发中，当前验证结果及待办见[现代化审查记录](docs/modernization-audit.md)。

NeoMusicBot is a self-hosted Discord music bot. This branch targets Discord's current
voice stack: JDA 6 provides Voice Gateway and RTP transport support, while JDAVE
provides the DAVE end-to-end encryption required for non-Stage voice calls.

## Runtime requirements

- Java 25.
- Linux x86-64 or Windows x86-64. Release artifacts are platform-specific because
  they include the matching JDAVE native library.
- A Discord application and bot token.
- Release ZIPs bundle yt-dlp 2026.08.19, Deno 2.9.7, and the complete
  bgutil-ytdlp-pot-provider 2.0.0 Deno script installation. Custom deployments
  can instead set JMUSICBOT_YTDLP_PATH and JMUSICBOT_DENO_PATH.

Do not use an everyday YouTube or Bilibili account for automation. Cookie files are
optional, disabled by default, and should belong to a dedicated low-privilege
account. The bot does not bypass DRM, paid content, or access controls.

## Media sources

- YouTube through youtube-source for the normal low-latency path, with a bounded
  yt-dlp fallback for supported extractor failures.
- Bilibili through the same bounded yt-dlp resolver.
- SoundCloud.
- Discord attachments.
- Owner-managed playlist files may contain entries for the explicit network sources
  above. Arbitrary host filesystem paths are never accepted as playable media.

YouTube and Bilibili resolver-generated CDN URLs, request headers, cookies, and
signed query parameters are never serialized into bot state; queued tracks retain
only stable media keys. The PO-token provider's process cache is forced into an
owner-only, resolver-scoped temporary XDG cache instead of the host cache. It is
erased on normal resolver shutdown; a cleanup failure emits a generic warning
without a path or secret. Discord attachment tracks necessarily keep their signed
attachment URL in memory while queued, so an attachment that waits too
long may expire before playback. Sensitive URLs and credentials are redacted from
application logs.

## Configuration

Existing `JMUSICBOT_*` environment variables and configuration files remain
supported under their original names, so existing installations can migrate
without renaming secrets or settings. New release launchers are named
`run_neomusicbot.sh` and `run_neomusicbot.cmd`, and start `NeoMusicBot.jar`.

### Bilibili usage

Join a standard Discord voice channel, then use `/play query:...` with a Bilibili
HTTPS video URL, a `b23.tv` short link, or a bare BV/av identifier. A `?p=2` URL
selects the second part; a URL without a part selects the first part. Each request
queues one part. Keyword requests and `/search` currently search YouTube; Bilibili
keyword search and importing an entire Bilibili collection are not implemented.

Use `/queue`, `/now-playing`, `/seek`, and `/skip` for playback, and `/dj` for
pause, resume, repeat, volume, and stop controls. Old prefix commands are no longer
registered. For a first installation, run the launcher with `generate-config`,
set `owner` in the generated configuration, and configure one Discord token source
before starting the bot. `generate-config` refuses to overwrite an existing file;
choose another configuration path to generate a fresh template.

If `serversettings.json` is damaged, NeoMusicBot tries its `.bak` backup. If neither
can be read, startup stops and preserves the files for repair. It does not reset
channel restrictions to defaults. A deleted or inaccessible configured channel
also remains restricted until an administrator changes or clears it with `/config`.

See [the 2026-09-21 review](docs/review-2026-09-21.md) for the current implementation
status, fixes, verification, and remaining release work.

### Environment

The modern environment interface is:

- JMUSICBOT_CONFIG
- JMUSICBOT_DISCORD_TOKEN or JMUSICBOT_DISCORD_TOKEN_FILE
- JMUSICBOT_YTDLP_PATH
- JMUSICBOT_DENO_PATH
- JMUSICBOT_YOUTUBE_FALLBACK=auto|off
- JMUSICBOT_YOUTUBE_POT_PROVIDER=auto|off
- JMUSICBOT_YOUTUBE_COOKIES_FILE
- JMUSICBOT_BILIBILI_COOKIES_FILE
- JMUSICBOT_YTDLP_PLUGIN_DIR
- JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH
- JMUSICBOT_COMMAND_GUILD_ID for development-only guild command registration

Never commit config.txt, serversettings.json, .env files, or exported browser
cookies. If the same secret is configured through more than one source, startup
should fail instead of selecting one silently.

## Build

Building requires JDK 25. The only-script Maven Wrapper downloads and verifies
Maven 3.9.16; a global Maven installation is not required:

~~~sh
# Linux x86-64
sh ./mvnw --batch-mode --no-transfer-progress \
  -Pnative-linux-x86-64 clean verify
~~~

~~~powershell
# Windows x86-64
.\\mvnw.cmd --batch-mode --no-transfer-progress -Pnative-windows-x86-64 clean verify
~~~

The build produces a platform-classified shaded JAR, CycloneDX JSON/XML SBOMs,
and Maven-generated dependency license files and a machine-readable summary under
`target/generated-resources/dependency-licenses/`. The CycloneDX documents cover
the JVM/Maven dependency graph. Standalone yt-dlp, Deno, the provider plugin, and
the provider's Deno/npm installation are outside that Maven SBOM; their immutable
versions, commits, hashes, and lock files are recorded separately in the release
manifest and provider bundle. CI creates a platform ZIP containing the matching
JDAVE native, these pinned media tools, checksums, project/tool licenses, generated
Maven licenses under `licenses/maven/`, and a launcher. The Build and Test workflow
also executes an anonymous locked-version YouTube/PO-provider/Bilibili canary and
publishes a sanitized report tied to the tested commit. Release automation rejects
a missing or mismatched report, then republishes the already-tested ZIPs without
rebuilding.

## Run

The launcher never downloads or selects a release automatically. Extract the
complete platform ZIP and run its launcher from that directory:

~~~sh
sh ./run_neomusicbot.sh
~~~

The launcher always starts the sibling `NeoMusicBot.jar`. It does not accept a JAR
path override; any arguments are passed to the application.

On Windows, the release ZIP includes a launcher that configures the bundled tools:

~~~powershell
.\\run_neomusicbot.cmd
~~~

Use systemd, Docker, Windows Service Manager, or another supervisor if automatic
restart is required. Upgrades should replace the entire verified release artifact;
runtime self-update is intentionally unsupported.

## Development and security

Pull requests are expected to pass both platform builds, JUnit tests, media-tool
version/checksum checks, and SBOM generation. Keep dependency versions immutable in
production, redact media URLs and credentials from diagnostics, and test DAVE/native
memory behavior in a staging guild before deployment.

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
