@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%NeoMusicBot.jar"

if not exist "%JAR_PATH%" (
  echo NeoMusicBot jar not found: %JAR_PATH% 1>&2
  echo Reinstall the complete verified release ZIP. 1>&2
  exit /b 66
)

set "TOOLS_DIR=%SCRIPT_DIR%tools"
if not defined NEOMUSICBOT_YTDLP_PATH if not defined JMUSICBOT_YTDLP_PATH if exist "%TOOLS_DIR%\yt-dlp.exe" (
  set "NEOMUSICBOT_YTDLP_PATH=%TOOLS_DIR%\yt-dlp.exe"
)
if not defined NEOMUSICBOT_DENO_PATH if not defined JMUSICBOT_DENO_PATH if exist "%TOOLS_DIR%\deno.exe" (
  set "NEOMUSICBOT_DENO_PATH=%TOOLS_DIR%\deno.exe"
)
if not defined NEOMUSICBOT_YTDLP_PLUGIN_DIR if not defined JMUSICBOT_YTDLP_PLUGIN_DIR if exist "%TOOLS_DIR%\yt-dlp-plugins" (
  set "NEOMUSICBOT_YTDLP_PLUGIN_DIR=%TOOLS_DIR%\yt-dlp-plugins"
)
if not defined NEOMUSICBOT_YOUTUBE_POT_PROVIDER_PATH if not defined JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH if exist "%TOOLS_DIR%\bgutil-provider\server" (
  set "NEOMUSICBOT_YOUTUBE_POT_PROVIDER_PATH=%TOOLS_DIR%\bgutil-provider\server"
)
set "PROVIDER_HOME=%NEOMUSICBOT_YOUTUBE_POT_PROVIDER_PATH%"
if not defined PROVIDER_HOME set "PROVIDER_HOME=%JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH%"
if not defined DENO_DIR if defined PROVIDER_HOME (
  if exist "%PROVIDER_HOME%\.deno-dir" (
    set "DENO_DIR=%PROVIDER_HOME%\.deno-dir"
  )
)
set "DENO_NO_PROMPT=1"
set "DENO_NO_UPDATE_CHECK=1"

if defined JAVA_HOME (
  set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_BIN=java"
)

"%JAVA_BIN%" --enable-native-access=ALL-UNNAMED -Dnogui=true -jar "%JAR_PATH%" %*
exit /b %ERRORLEVEL%
