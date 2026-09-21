#!/usr/bin/env bash

set -euo pipefail

: "${YTDLP_VERSION:?YTDLP_VERSION is required}"
: "${YTDLP_ASSET:?YTDLP_ASSET is required}"
: "${YTDLP_SHA256:?YTDLP_SHA256 is required}"
: "${DENO_VERSION:?DENO_VERSION is required}"
: "${DENO_ASSET:?DENO_ASSET is required}"
: "${DENO_SHA256:?DENO_SHA256 is required}"
: "${POT_PROVIDER_VERSION:?POT_PROVIDER_VERSION is required}"
: "${POT_PROVIDER_COMMIT:?POT_PROVIDER_COMMIT is required}"
: "${POT_PROVIDER_PLUGIN_SHA256:?POT_PROVIDER_PLUGIN_SHA256 is required}"
: "${ARTIFACT_SUFFIX:?ARTIFACT_SUFFIX is required}"
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

bundle="target/release-bundle"
tools="${bundle}/tools"
plugins="${tools}/yt-dlp-plugins"
licenses="${bundle}/licenses"
provider_source="target/bgutil-provider-src"
mkdir -p "${tools}" "${plugins}" "${licenses}"

maven_licenses="target/generated-resources/dependency-licenses"
test -f "${maven_licenses}/licenses.xml"
cp -a -- "${maven_licenses}" "${licenses}/maven"

jar_path="$(find target -maxdepth 1 -type f -name 'NeoMusicBot-*-linux-x86-64.jar' -print -quit)"
test -n "${jar_path}"
cp -- "${jar_path}" "${bundle}/NeoMusicBot.jar"
cp -- target/bom.json "${bundle}/NeoMusicBot.cdx.json"
cp -- target/bom.xml "${bundle}/NeoMusicBot.cdx.xml"
cp -- scripts/run_neomusicbot.sh "${bundle}/run_neomusicbot.sh"
chmod 755 "${bundle}/run_neomusicbot.sh"

curl --fail --location --silent --show-error "https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_VERSION}/${YTDLP_ASSET}" --output "${tools}/yt-dlp"
printf '%s  %s\n' "${YTDLP_SHA256}" "${tools}/yt-dlp" | sha256sum --check --strict -
chmod 755 "${tools}/yt-dlp"

curl --fail --location --silent --show-error "https://github.com/denoland/deno/releases/download/v${DENO_VERSION}/${DENO_ASSET}" --output target/deno.zip
printf '%s  %s\n' "${DENO_SHA256}" target/deno.zip | sha256sum --check --strict -
unzip -q target/deno.zip -d "${tools}"
chmod 755 "${tools}/deno"

curl --fail --location --silent --show-error "https://github.com/Brainicism/bgutil-ytdlp-pot-provider/releases/download/${POT_PROVIDER_VERSION}/bgutil-ytdlp-pot-provider.zip" --output "${plugins}/bgutil-ytdlp-pot-provider.zip"
printf '%s  %s\n' "${POT_PROVIDER_PLUGIN_SHA256}" "${plugins}/bgutil-ytdlp-pot-provider.zip" | sha256sum --check --strict -
unzip -tq "${plugins}/bgutil-ytdlp-pot-provider.zip"

git clone --depth 1 --branch "${POT_PROVIDER_VERSION}" https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git "${provider_source}"
test "$(git -C "${provider_source}" rev-parse HEAD)" = "${POT_PROVIDER_COMMIT}"
(
    cd "${provider_source}/server"
    export DENO_DIR="${PWD}/.deno-dir"
    "${GITHUB_WORKSPACE}/${tools}/deno" install --node-modules-linker=hoisted --allow-scripts=npm:canvas --frozen
    "${GITHUB_WORKSPACE}/${tools}/deno" cache --node-modules-linker=hoisted --frozen src/generate_once.ts
)
test -d "${provider_source}/server/node_modules"
test -d "${provider_source}/server/.deno-dir"
mkdir -p "${tools}/bgutil-provider"
cp -a "${provider_source}/server" "${tools}/bgutil-provider/server"

cp -- LICENSE "${licenses}/NeoMusicBot-Apache-2.0.txt"
cp -- "${provider_source}/LICENSE" "${licenses}/bgutil-provider-GPL-3.0.txt"
curl --fail --location --silent --show-error "https://raw.githubusercontent.com/yt-dlp/yt-dlp/${YTDLP_VERSION}/LICENSE" --output "${licenses}/yt-dlp-Unlicense.txt"
curl --fail --location --silent --show-error "https://raw.githubusercontent.com/denoland/deno/v${DENO_VERSION}/LICENSE.md" --output "${licenses}/Deno-MIT.txt"

printf '%s\n' "NeoMusicBot commit=${GITHUB_SHA}" "platform=${ARTIFACT_SUFFIX}" "yt-dlp.version=${YTDLP_VERSION}" "yt-dlp.sha256=${YTDLP_SHA256}" "yt-dlp-ejs=bundled-with-standalone" "deno.version=${DENO_VERSION}" "deno.sha256=${DENO_SHA256}" "bgutil-provider.version=${POT_PROVIDER_VERSION}" "bgutil-provider.commit=${POT_PROVIDER_COMMIT}" "bgutil-provider.plugin.sha256=${POT_PROVIDER_PLUGIN_SHA256}" > "${bundle}/THIRD_PARTY_VERSIONS.txt"

test "$("${tools}/yt-dlp" --version)" = "${YTDLP_VERSION}"
"${tools}/deno" --version
provider="${GITHUB_WORKSPACE}/${tools}/bgutil-provider/server"
cache="${GITHUB_WORKSPACE}/target/bgutil-runtime-cache"
mkdir -p "${cache}/bgutil-ytdlp-pot-provider"
provider_version="$(env DENO_DIR="${provider}/.deno-dir" DENO_NO_PROMPT=1 DENO_NO_UPDATE_CHECK=1 FORCE_COLOR=false HOME="${RUNNER_TEMP}" XDG_CACHE_HOME="${cache}" "${GITHUB_WORKSPACE}/${tools}/deno" run --cached-only --node-modules-linker=hoisted --allow-env --allow-net "--allow-ffi=${provider}/node_modules" "--allow-write=${cache}/bgutil-ytdlp-pot-provider" "--allow-read=${cache}/bgutil-ytdlp-pot-provider,${provider}/node_modules" "${provider}/src/generate_once.ts" --version)"
test "${provider_version}" = "${POT_PROVIDER_VERSION}"
test -f "${provider}/deno.lock"

(
    cd "${bundle}"
    zip -q -r "${GITHUB_WORKSPACE}/target/NeoMusicBot-${ARTIFACT_SUFFIX}.zip" .
)
cd target
sha256sum "NeoMusicBot-${ARTIFACT_SUFFIX}.zip" > "NeoMusicBot-${ARTIFACT_SUFFIX}.zip.sha256"
