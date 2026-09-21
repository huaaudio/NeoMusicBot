package com.jagrosh.jmusicbot.audio.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaResolverReadinessTest
{
    private static final List<String> PLUGIN_ENTRIES = List.of(
            "yt_dlp_plugins/extractor/getpot_bgutil.py",
            "yt_dlp_plugins/extractor/getpot_bgutil_http.py",
            "yt_dlp_plugins/extractor/getpot_bgutil_script.py");
    private static final List<String> PROVIDER_FILES = List.of(
            "src/generate_once.ts",
            "src/session_manager.ts",
            "src/utils.ts",
            "deno.lock",
            "package.json",
            "package-lock.json");
    private static final List<String> PROVIDER_MODULES = List.of(
            "axios",
            "bgutils-js",
            "canvas",
            "commander",
            "jsdom",
            "proxy-agent",
            "youtubei.js");

    @TempDir
    Path temporaryDirectory;

    @Test
    void providerReadinessRequiresCompleteLockedAssets() throws Exception
    {
        String executable = javaExecutable();
        Path provider = Files.createDirectory(temporaryDirectory.resolve("provider"));
        Path pluginFile = Files.createFile(temporaryDirectory.resolve("plugins"));

        MediaResolverReadiness invalidFile = MediaResolverReadiness.inspect(configuration(
                executable, pluginFile, provider));
        assertTrue(invalidFile.youtubeFallbackReady());
        assertFalse(invalidFile.potProviderReady());

        Files.delete(pluginFile);
        Path pluginDirectory = Files.createDirectory(pluginFile);
        MediaResolverReadiness emptyDirectories = MediaResolverReadiness.inspect(configuration(
                executable, pluginDirectory, provider));
        assertFalse(emptyDirectories.potProviderReady());

        createPluginArchive(pluginDirectory);
        createProviderHome(provider);
        MediaResolverReadiness complete = MediaResolverReadiness.inspect(configuration(
                executable, pluginDirectory, provider));
        assertTrue(complete.potProviderReady());

        Files.delete(provider.resolve("src/generate_once.ts"));
        MediaResolverReadiness missingScript = MediaResolverReadiness.inspect(configuration(
                executable, pluginDirectory, provider));
        assertFalse(missingScript.potProviderReady());
    }

    @Test
    void extractedPluginLayoutIsAlsoAccepted() throws Exception
    {
        Path pluginDirectory = Files.createDirectory(temporaryDirectory.resolve("extracted-plugins"));
        for (String entry : PLUGIN_ENTRIES)
        {
            Path file = pluginDirectory.resolve(entry);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "plugin", StandardCharsets.UTF_8);
        }
        Path provider = Files.createDirectory(temporaryDirectory.resolve("extracted-provider"));
        createProviderHome(provider);

        assertTrue(MediaResolverReadiness.providerAssetsAvailable(pluginDirectory, provider));
    }

    private static void createPluginArchive(Path directory) throws Exception
    {
        Path archive = directory.resolve("bgutil-ytdlp-pot-provider.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive)))
        {
            for (String entry : PLUGIN_ENTRIES)
            {
                output.putNextEntry(new ZipEntry(entry));
                output.write("plugin".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static void createProviderHome(Path provider) throws Exception
    {
        for (String file : PROVIDER_FILES)
        {
            Path path = provider.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "locked", StandardCharsets.UTF_8);
        }
        for (String module : PROVIDER_MODULES)
            Files.createDirectories(provider.resolve("node_modules").resolve(module));
        Path denoCacheFile = provider.resolve(".deno-dir/cache/entry");
        Files.createDirectories(denoCacheFile.getParent());
        Files.writeString(denoCacheFile, "cached", StandardCharsets.UTF_8);
    }

    private static String javaExecutable()
    {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java").toString();
    }

    private static YtDlpConfiguration configuration(String executable, Path plugins, Path provider)
    {
        return new YtDlpConfiguration(executable, executable, null, null, plugins, provider,
                true, true, Duration.ofSeconds(30), Duration.ofSeconds(60));
    }
}
