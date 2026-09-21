package com.jagrosh.jmusicbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseLauncherPolicyTest
{
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void launchersOnlyRunTheSiblingReleaseJarAndTools() throws IOException
    {
        String linux = read("scripts/run_neomusicbot.sh");
        String windows = read("scripts/run_neomusicbot.cmd");

        assertTrue(linux.contains("JAR_PATH=\"${SCRIPT_DIR}/NeoMusicBot.jar\""));
        assertTrue(linux.contains("TOOLS_DIR=\"${SCRIPT_DIR}/tools\""));
        assertFalse(linux.contains("JMUSICBOT_JAR"));
        assertFalse(linux.contains("JAR_PATH=$1"));
        assertFalse(linux.contains("../NeoMusicBot.jar"));
        assertFalse(linux.contains("${SCRIPT_DIR}/../tools"));

        assertTrue(windows.contains("set \"JAR_PATH=%SCRIPT_DIR%NeoMusicBot.jar\""));
        assertTrue(windows.contains("set \"TOOLS_DIR=%SCRIPT_DIR%tools\""));
        assertFalse(windows.contains("JMUSICBOT_JAR"));
        assertFalse(windows.contains("%~1"));
        assertFalse(windows.contains("..\\NeoMusicBot.jar"));
        assertFalse(windows.contains("..\\tools"));
    }

    @Test
    void releaseBundlesRequireGeneratedDependencyLicenses() throws IOException
    {
        String pom = read("pom.xml");
        String linux = read("scripts/ci/assemble_linux.sh");
        String windows = read("scripts/ci/assemble_windows.ps1");

        assertTrue(pom.contains("<artifactId>license-maven-plugin</artifactId>"));
        assertTrue(pom.contains("generated-resources/dependency-licenses"));
        assertTrue(linux.contains("dependency-licenses"));
        assertTrue(linux.contains("${licenses}/maven"));
        assertTrue(windows.contains("dependency-licenses"));
        assertTrue(windows.contains("Join-Path $licenses \"maven\""));
    }

    @Test
    void releaseBundlesUsePortableProviderDependencies() throws IOException
    {
        String linux = read("scripts/ci/assemble_linux.sh");
        String windows = read("scripts/ci/assemble_windows.ps1");

        assertTrue(linux.contains("install --node-modules-linker=hoisted"));
        assertTrue(linux.contains("cache --node-modules-linker=hoisted"));
        assertTrue(linux.contains("run --cached-only --node-modules-linker=hoisted"));
        assertTrue(windows.contains("install --node-modules-linker=hoisted"));
        assertTrue(windows.contains("cache --node-modules-linker=hoisted"));
        assertTrue(windows.contains("run --cached-only --node-modules-linker=hoisted"));
    }

    @Test
    void releaseRequiresLockedMediaCanaryFromTheSameCommit() throws IOException
    {
        String build = read(".github/workflows/build-and-test.yml");
        String canary = read("scripts/ci/media_canary.sh");
        String release = read(".github/workflows/make-release.yml");

        assertTrue(build.contains("locked_media_canary:"));
        assertTrue(build.contains("needs: build"));
        assertTrue(build.contains("name: NeoMusicBot-media-canary"));
        assertTrue(canary.contains("mode=locked"));
        assertTrue(canary.contains("test \"${actual_version}\" = \"${YTDLP_VERSION}\""));
        assertTrue(canary.contains("neomusicbot.commit=${GITHUB_SHA}"));
        assertTrue(canary.contains("--no-cache-dir"));
        assertTrue(release.contains("artifacts/NeoMusicBot-media-canary/candidate.txt"));
        assertTrue(release.contains("require_line \"neomusicbot.commit=${SOURCE_SHA}\""));
        assertTrue(release.contains("require_line \"youtube.mweb-provider=passed\""));
        assertTrue(release.contains("NeoMusicBot-media-canary.txt"));
    }

    private static String read(String path) throws IOException
    {
        return Files.readString(ROOT.resolve(path));
    }
}
