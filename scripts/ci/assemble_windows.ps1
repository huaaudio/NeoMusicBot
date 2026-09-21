$ErrorActionPreference = "Stop"

$requiredVariables = @(
    "YTDLP_VERSION",
    "YTDLP_ASSET",
    "YTDLP_SHA256",
    "DENO_VERSION",
    "DENO_ASSET",
    "DENO_SHA256",
    "POT_PROVIDER_VERSION",
    "POT_PROVIDER_COMMIT",
    "POT_PROVIDER_PLUGIN_SHA256",
    "ARTIFACT_SUFFIX",
    "GITHUB_SHA"
)
foreach ($name in $requiredVariables) {
    if (-not [Environment]::GetEnvironmentVariable($name)) {
        throw "$name is required"
    }
}

$bundle = Join-Path $PWD "target\release-bundle"
$tools = Join-Path $bundle "tools"
$plugins = Join-Path $tools "yt-dlp-plugins"
$licenses = Join-Path $bundle "licenses"
$providerSource = Join-Path $PWD "target\bgutil-provider-src"
New-Item -ItemType Directory -Force $tools, $plugins, $licenses | Out-Null

$mavenLicenses = Join-Path $PWD "target\generated-resources\dependency-licenses"
if (-not (Test-Path (Join-Path $mavenLicenses "licenses.xml") -PathType Leaf)) {
    throw "Generated Maven dependency licenses are missing"
}
Copy-Item $mavenLicenses (Join-Path $licenses "maven") -Recurse

function Assert-Sha256([string] $path, [string] $expected) {
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $path"
    }
}

$jar = Get-ChildItem "target" -File -Filter "NeoMusicBot-*-windows-x86-64.jar" | Select-Object -First 1
if (-not $jar) {
    throw "Windows platform jar is missing"
}
Copy-Item $jar.FullName (Join-Path $bundle "NeoMusicBot.jar")
Copy-Item "target\bom.json" (Join-Path $bundle "NeoMusicBot.cdx.json")
Copy-Item "target\bom.xml" (Join-Path $bundle "NeoMusicBot.cdx.xml")
Copy-Item "scripts\run_neomusicbot.cmd" (Join-Path $bundle "run_neomusicbot.cmd")

$ytDlp = Join-Path $tools "yt-dlp.exe"
Invoke-WebRequest -Uri "https://github.com/yt-dlp/yt-dlp/releases/download/${env:YTDLP_VERSION}/${env:YTDLP_ASSET}" -OutFile $ytDlp
Assert-Sha256 $ytDlp $env:YTDLP_SHA256

$denoZip = Join-Path $PWD "target\deno.zip"
Invoke-WebRequest -Uri "https://github.com/denoland/deno/releases/download/v${env:DENO_VERSION}/${env:DENO_ASSET}" -OutFile $denoZip
Assert-Sha256 $denoZip $env:DENO_SHA256
Expand-Archive $denoZip -DestinationPath $tools
$deno = Join-Path $tools "deno.exe"

$providerPlugin = Join-Path $plugins "bgutil-ytdlp-pot-provider.zip"
Invoke-WebRequest -Uri "https://github.com/Brainicism/bgutil-ytdlp-pot-provider/releases/download/${env:POT_PROVIDER_VERSION}/bgutil-ytdlp-pot-provider.zip" -OutFile $providerPlugin
Assert-Sha256 $providerPlugin $env:POT_PROVIDER_PLUGIN_SHA256
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pluginArchive = [System.IO.Compression.ZipFile]::OpenRead($providerPlugin)
try {
    if ($pluginArchive.Entries.Count -eq 0) {
        throw "Provider plugin archive is empty"
    }
} finally {
    $pluginArchive.Dispose()
}

git clone --depth 1 --branch $env:POT_PROVIDER_VERSION https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git $providerSource
$actualCommit = (git -C $providerSource rev-parse HEAD).Trim()
if ($actualCommit -ne $env:POT_PROVIDER_COMMIT) {
    throw "Provider tag does not resolve to the pinned commit"
}
Push-Location (Join-Path $providerSource "server")
try {
    $env:DENO_DIR = Join-Path $providerSource "server\.deno-dir"
    & $deno install --node-modules-linker=hoisted --allow-scripts=npm:canvas --frozen
    if ($LASTEXITCODE -ne 0) {
        throw "Provider dependency installation failed"
    }
    & $deno cache --node-modules-linker=hoisted --frozen "src\generate_once.ts"
    if ($LASTEXITCODE -ne 0) {
        throw "Provider cache preparation failed"
    }
} finally {
    Pop-Location
}
if (-not (Test-Path (Join-Path $providerSource "server\node_modules") -PathType Container)) {
    throw "Provider node_modules was not created"
}
if (-not (Test-Path (Join-Path $providerSource "server\.deno-dir") -PathType Container)) {
    throw "Provider Deno cache was not created"
}
$providerBundle = Join-Path $tools "bgutil-provider"
New-Item -ItemType Directory -Force $providerBundle | Out-Null
Copy-Item (Join-Path $providerSource "server") (Join-Path $providerBundle "server") -Recurse

Copy-Item "LICENSE" (Join-Path $licenses "NeoMusicBot-Apache-2.0.txt")
Copy-Item (Join-Path $providerSource "LICENSE") (Join-Path $licenses "bgutil-provider-GPL-3.0.txt")
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/yt-dlp/yt-dlp/${env:YTDLP_VERSION}/LICENSE" -OutFile (Join-Path $licenses "yt-dlp-Unlicense.txt")
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/denoland/deno/v${env:DENO_VERSION}/LICENSE.md" -OutFile (Join-Path $licenses "Deno-MIT.txt")

@"
NeoMusicBot commit=$env:GITHUB_SHA
platform=$env:ARTIFACT_SUFFIX
yt-dlp.version=$env:YTDLP_VERSION
yt-dlp.sha256=$env:YTDLP_SHA256
yt-dlp-ejs=bundled-with-standalone
deno.version=$env:DENO_VERSION
deno.sha256=$env:DENO_SHA256
bgutil-provider.version=$env:POT_PROVIDER_VERSION
bgutil-provider.commit=$env:POT_PROVIDER_COMMIT
bgutil-provider.plugin.sha256=$env:POT_PROVIDER_PLUGIN_SHA256
"@ | Set-Content -Encoding utf8 (Join-Path $bundle "THIRD_PARTY_VERSIONS.txt")

if ((& $ytDlp --version) -ne $env:YTDLP_VERSION) {
    throw "Unexpected yt-dlp version"
}
& $deno --version
if ($LASTEXITCODE -ne 0) {
    throw "Deno self-check failed"
}

$provider = Join-Path $providerBundle "server"
$nodeModules = Join-Path $provider "node_modules"
$runtimeCache = Join-Path $PWD "target\bgutil-runtime-cache"
$scriptCache = Join-Path $runtimeCache "bgutil-ytdlp-pot-provider"
New-Item -ItemType Directory -Force $scriptCache | Out-Null
$env:DENO_DIR = Join-Path $provider ".deno-dir"
$env:DENO_NO_PROMPT = "1"
$env:DENO_NO_UPDATE_CHECK = "1"
$env:FORCE_COLOR = "false"
$env:XDG_CACHE_HOME = $runtimeCache
$providerVersion = (& $deno run --cached-only --node-modules-linker=hoisted --allow-env --allow-net "--allow-ffi=$nodeModules" "--allow-write=$scriptCache" "--allow-read=$scriptCache,$nodeModules" (Join-Path $provider "src\generate_once.ts") --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $providerVersion -ne $env:POT_PROVIDER_VERSION) {
    throw "Provider script-mode self-check failed"
}
if (-not (Test-Path (Join-Path $provider "deno.lock") -PathType Leaf)) {
    throw "Provider lock file is missing"
}

$archive = Join-Path $PWD "target\NeoMusicBot-${env:ARTIFACT_SUFFIX}.zip"
Compress-Archive -Path "$bundle\*" -DestinationPath $archive -CompressionLevel Optimal
$archiveHash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
"$archiveHash  NeoMusicBot-${env:ARTIFACT_SUFFIX}.zip" | Set-Content -Encoding ascii "$archive.sha256"
