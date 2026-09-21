package io.github.huaaudio.neomusicbot;

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
        // Behavior (including mismatched commits and failing media reports) is
        // exercised by scripts/ci/test_prerelease.py. This test ensures Actions
        // actually invokes those gates before any tag or publication mutation.
        int sourceGate = release.indexOf("prepare_prerelease.py source ");
        int artifactGate = release.indexOf("prepare_prerelease.py prepare artifacts dist");
        int createTag = release.indexOf("gh api --method POST ");
        int downloadGate = release.indexOf("prepare_prerelease.py download dist downloaded");
        int publish = release.indexOf("gh release edit ");
        assertTrue(sourceGate >= 0 && artifactGate > sourceGate && createTag > artifactGate);
        assertTrue(downloadGate > createTag && publish > downloadGate);
        assertTrue(release.contains("--draft=false --prerelease --latest=false"));
        assertFalse(release.contains("continue-on-error"));
        assertTrue(build.contains("python -m unittest discover -s scripts/ci"));
    }

    private static String read(String path) throws IOException
    {
        return Files.readString(ROOT.resolve(path));
    }
}
