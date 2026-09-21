#!/bin/sh
# Modified by Huaaudio for independent Bilibili/Discord development (2026).

set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
JAR_PATH="${SCRIPT_DIR}/NeoMusicBot.jar"

if [ ! -f "${JAR_PATH}" ]; then
    echo "NeoMusicBot jar not found: ${JAR_PATH}" >&2
    echo "Reinstall the complete verified release ZIP." >&2
    exit 66
fi

TOOLS_DIR="${SCRIPT_DIR}/tools"

if [ -z "${JMUSICBOT_YTDLP_PATH:-}" ] && [ -x "${TOOLS_DIR}/yt-dlp" ]; then
    JMUSICBOT_YTDLP_PATH="${TOOLS_DIR}/yt-dlp"
    export JMUSICBOT_YTDLP_PATH
fi
if [ -z "${JMUSICBOT_DENO_PATH:-}" ] && [ -x "${TOOLS_DIR}/deno" ]; then
    JMUSICBOT_DENO_PATH="${TOOLS_DIR}/deno"
    export JMUSICBOT_DENO_PATH
fi
if [ -z "${JMUSICBOT_YTDLP_PLUGIN_DIR:-}" ] && [ -d "${TOOLS_DIR}/yt-dlp-plugins" ]; then
    JMUSICBOT_YTDLP_PLUGIN_DIR="${TOOLS_DIR}/yt-dlp-plugins"
    export JMUSICBOT_YTDLP_PLUGIN_DIR
fi
if [ -z "${JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH:-}" ] \
    && [ -d "${TOOLS_DIR}/bgutil-provider/server" ]; then
    JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH="${TOOLS_DIR}/bgutil-provider/server"
    export JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH
fi
if [ -n "${JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH:-}" ] \
    && [ -d "${JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH}/.deno-dir" ] \
    && [ -z "${DENO_DIR:-}" ]; then
    DENO_DIR="${JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH}/.deno-dir"
    export DENO_DIR
fi
if [ -n "${JMUSICBOT_DENO_PATH:-}" ]; then
    DENO_NO_PROMPT=1
    DENO_NO_UPDATE_CHECK=1
    export DENO_NO_PROMPT DENO_NO_UPDATE_CHECK
fi

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
else
    JAVA_BIN=java
fi

if ! command -v "${JAVA_BIN}" >/dev/null 2>&1; then
    echo "Java 25 was not found. Set JAVA_HOME or put java on PATH." >&2
    exit 69
fi

# This script intentionally performs no downloads or automatic updates.
# Use systemd, Docker, or another service manager when restart supervision is needed.
exec "${JAVA_BIN}" \
    --enable-native-access=ALL-UNNAMED \
    -Dnogui=true \
    -jar "${JAR_PATH}" \
    "$@"
