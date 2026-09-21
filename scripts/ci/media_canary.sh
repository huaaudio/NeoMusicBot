#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${DENO_VERSION:?DENO_VERSION is required}"
: "${DENO_ASSET:?DENO_ASSET is required}"
: "${DENO_SHA256:?DENO_SHA256 is required}"
: "${POT_PROVIDER_VERSION:?POT_PROVIDER_VERSION is required}"
: "${POT_PROVIDER_COMMIT:?POT_PROVIDER_COMMIT is required}"
: "${POT_PROVIDER_PLUGIN_SHA256:?POT_PROVIDER_PLUGIN_SHA256 is required}"
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

mkdir -p tools reports
if [[ -n "${YTDLP_VERSION:-}" ]]; then
    : "${YTDLP_ASSET:?YTDLP_ASSET is required in locked mode}"
    : "${YTDLP_SHA256:?YTDLP_SHA256 is required in locked mode}"
    repository="${YTDLP_REPOSITORY:-yt-dlp/yt-dlp}"
    test "${repository}" = "yt-dlp/yt-dlp"
    curl --fail --location --silent --show-error "https://github.com/${repository}/releases/download/${YTDLP_VERSION}/${YTDLP_ASSET}" --output tools/yt-dlp
    printf '%s  %s\n' "${YTDLP_SHA256}" tools/yt-dlp | sha256sum --check --strict -
    chmod 755 tools/yt-dlp
    actual_version="$(tools/yt-dlp --version)"
    test "${actual_version}" = "${YTDLP_VERSION}"
    printf '%s\n' "mode=locked" "repository=${repository}" "release.tag=${YTDLP_VERSION}" "neomusicbot.commit=${GITHUB_SHA}" "yt-dlp.version=${actual_version}" "yt-dlp.sha256=${YTDLP_SHA256}" > reports/candidate.txt
else
    : "${YTDLP_REPOSITORY:?YTDLP_REPOSITORY is required in channel mode}"
    : "${YTDLP_CHANNEL:?YTDLP_CHANNEL is required in channel mode}"
    gh api "repos/${YTDLP_REPOSITORY}/releases/latest" > "${RUNNER_TEMP}/release.json"
    tag="$(jq -er '.tag_name' "${RUNNER_TEMP}/release.json")"
    release_id="$(jq -er '.id' "${RUNNER_TEMP}/release.json")"
    asset="$(jq -ec '.assets[] | select(.name == "yt-dlp")' "${RUNNER_TEMP}/release.json")"
    asset_id="$(jq -er '.id' <<<"${asset}")"
    url="$(jq -er '.browser_download_url' <<<"${asset}")"
    digest="$(jq -er '.digest | select(type == "string" and startswith("sha256:"))' <<<"${asset}")"
    sha256="${digest#sha256:}"
    curl --fail --location --silent --show-error "${url}" --output tools/yt-dlp
    printf '%s  %s\n' "${sha256}" tools/yt-dlp | sha256sum --check --strict -
    chmod 755 tools/yt-dlp
    actual_version="$(tools/yt-dlp --version)"
    test -n "${actual_version}"
    printf '%s\n' "mode=channel" "channel=${YTDLP_CHANNEL}" "repository=${YTDLP_REPOSITORY}" "release.id=${release_id}" "release.tag=${tag}" "asset.id=${asset_id}" "neomusicbot.commit=${GITHUB_SHA}" "yt-dlp.version=${actual_version}" "yt-dlp.sha256=${sha256}" > reports/candidate.txt
fi
mkdir -p tools/yt-dlp-plugins
curl --fail --location --silent --show-error "https://github.com/denoland/deno/releases/download/v${DENO_VERSION}/${DENO_ASSET}" --output "${RUNNER_TEMP}/deno.zip"
printf '%s  %s\n' "${DENO_SHA256}" "${RUNNER_TEMP}/deno.zip" | sha256sum --check --strict -
unzip -q "${RUNNER_TEMP}/deno.zip" -d tools
chmod 755 tools/deno

plugin="tools/yt-dlp-plugins/bgutil-ytdlp-pot-provider.zip"
curl --fail --location --silent --show-error "https://github.com/Brainicism/bgutil-ytdlp-pot-provider/releases/download/${POT_PROVIDER_VERSION}/bgutil-ytdlp-pot-provider.zip" --output "${plugin}"
printf '%s  %s\n' "${POT_PROVIDER_PLUGIN_SHA256}" "${plugin}" | sha256sum --check --strict -
unzip -tq "${plugin}"

git clone --depth 1 --branch "${POT_PROVIDER_VERSION}" https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git tools/bgutil-provider
test "$(git -C tools/bgutil-provider rev-parse HEAD)" = "${POT_PROVIDER_COMMIT}"
provider="${GITHUB_WORKSPACE}/tools/bgutil-provider/server"
(
    cd "${provider}"
    env DENO_DIR="${provider}/.deno-dir" "${GITHUB_WORKSPACE}/tools/deno" install --allow-scripts=npm:canvas --frozen
    env DENO_DIR="${provider}/.deno-dir" "${GITHUB_WORKSPACE}/tools/deno" cache --frozen src/generate_once.ts
)

cache="${RUNNER_TEMP}/bgutil-cache"
mkdir -p "${cache}/bgutil-ytdlp-pot-provider"
version="$(env DENO_DIR="${provider}/.deno-dir" DENO_NO_PROMPT=1 DENO_NO_UPDATE_CHECK=1 FORCE_COLOR=false HOME="${RUNNER_TEMP}" XDG_CACHE_HOME="${cache}" tools/deno run --cached-only --allow-env --allow-net "--allow-ffi=${provider}/node_modules" "--allow-write=${cache}/bgutil-ytdlp-pot-provider" "--allow-read=${cache}/bgutil-ytdlp-pot-provider,${provider}/node_modules" "${provider}/src/generate_once.ts" --version)"
test "${version}" = "${POT_PROVIDER_VERSION}"
printf '%s\n' "deno.version=${DENO_VERSION}" "deno.sha256=${DENO_SHA256}" "bgutil-provider.version=${POT_PROVIDER_VERSION}" "bgutil-provider.commit=${POT_PROVIDER_COMMIT}" "bgutil-provider.plugin.sha256=${POT_PROVIDER_PLUGIN_SHA256}" >> reports/candidate.txt

ytdlp="${GITHUB_WORKSPACE}/tools/yt-dlp"
deno="${GITHUB_WORKSPACE}/tools/deno"
plugins="${GITHUB_WORKSPACE}/tools/yt-dlp-plugins"
export DENO_DIR="${provider}/.deno-dir"
export DENO_NO_PROMPT=1
export DENO_NO_UPDATE_CHECK=1
export FORCE_COLOR=false
export XDG_CACHE_HOME="${cache}"

audio_format="bestaudio[acodec^=opus]/bestaudio[acodec^=mp4a]/bestaudio[acodec^=aac]/bestaudio"
common_args=(
    --ignore-config
    --no-config-locations
    --no-update
    --no-cache-dir
    --no-plugin-dirs
    --no-remote-components
    --no-progress
    --color never
    --simulate
    --dump-single-json
    --js-runtimes "deno:${deno}"
    --plugin-dirs "${plugins}"
    --no-playlist
    --format "${audio_format}"
)

validate_audio_json() {
    local expected_id="$1"
    local expected_extractor="$2"
    local json_file="$3"
    jq -e --arg id "${expected_id}" --arg extractor "${expected_extractor}" '
        .id == $id
        and (((.extractor_key // "") | ascii_downcase) | contains($extractor))
        and ((if ((.requested_downloads | type) == "array" and
                  (.requested_downloads | length) > 0)
              then .requested_downloads[0] else . end) as $format
             | ($format.url | type) == "string"
             and ($format.url | startswith("https://"))
             and ($format.acodec | type) == "string"
             and $format.acodec != "none"
             and (($format.vcodec // "none") == "none")
             and (["https", "m3u8", "m3u8_native"] | index($format.protocol)) != null
             and (["opus", "webm", "weba", "mka", "matroska", "m4a", "mp4", "m4s",
                   "aac", "ogg", "oga", "mp3", "mpeg", "mpga", "flac", "wav", "ts",
                   "m3u8"] | index($format.ext)) != null)
    ' "${json_file}" > /dev/null
}

if ! "${ytdlp}" "${common_args[@]}" --extractor-args "youtube:fetch_pot=never" -- "https://www.youtube.com/watch?v=YE7VzlLtp-4" > "${RUNNER_TEMP}/youtube-anonymous.json" 2> "${RUNNER_TEMP}/youtube-anonymous.log"; then
    echo "Anonymous YouTube canary failed; raw output was withheld because it may contain signed media URLs." >&2
    exit 1
fi
validate_audio_json "YE7VzlLtp-4" "youtube" "${RUNNER_TEMP}/youtube-anonymous.json"
printf '%s\n' "youtube.anonymous=passed" >> reports/candidate.txt

sleep 5
if ! "${ytdlp}" "${common_args[@]}" --extractor-args "youtube:player_client=mweb;fetch_pot=always" --extractor-args "youtubepot-bgutilscript:server_home=${provider}" -- "https://www.youtube.com/watch?v=YE7VzlLtp-4" > "${RUNNER_TEMP}/youtube-provider.json" 2> "${RUNNER_TEMP}/youtube-provider.log"; then
    echo "mweb PO-provider canary failed; raw output was withheld because it may contain token material." >&2
    exit 1
fi
validate_audio_json "YE7VzlLtp-4" "youtube" "${RUNNER_TEMP}/youtube-provider.json"
printf '%s\n' "youtube.mweb-provider=passed" >> reports/candidate.txt

sleep 5
if ! "${ytdlp}" "${common_args[@]}" -- "https://www.bilibili.com/video/BV13x41117TL" > "${RUNNER_TEMP}/bilibili.json" 2> "${RUNNER_TEMP}/bilibili.log"; then
    echo "Anonymous Bilibili canary failed; raw output was withheld because it may contain signed media URLs." >&2
    exit 1
fi
validate_audio_json "BV13x41117TL" "bili" "${RUNNER_TEMP}/bilibili.json"
printf '%s\n' "bilibili.anonymous=passed" >> reports/candidate.txt

rm -f "${RUNNER_TEMP}/youtube-anonymous.json" "${RUNNER_TEMP}/youtube-anonymous.log" "${RUNNER_TEMP}/youtube-provider.json" "${RUNNER_TEMP}/youtube-provider.log" "${RUNNER_TEMP}/bilibili.json" "${RUNNER_TEMP}/bilibili.log"
