package com.jagrosh.jmusicbot.audio.media;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Secret-free readiness data suitable for /owner debug output. */
public record MediaResolverReadiness(
        boolean ytDlpAvailable,
        boolean denoAvailable,
        boolean youtubeFallbackEnabled,
        boolean youtubeFallbackReady,
        boolean potProviderEnabled,
        boolean potProviderReady)
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

    public static MediaResolverReadiness inspect(YtDlpConfiguration configuration)
    {
        boolean ytDlp = executableAvailable(configuration.executable());
        boolean deno = executableAvailable(configuration.denoExecutable());
        boolean providerAssets = providerAssetsAvailable(
                configuration.pluginDirectory(), configuration.potProviderHome());
        return new MediaResolverReadiness(
                ytDlp,
                deno,
                configuration.youtubeFallbackEnabled(),
                configuration.youtubeFallbackEnabled() && ytDlp && deno,
                configuration.potProviderEnabled(),
                configuration.potProviderEnabled() && ytDlp && deno && providerAssets);
    }

    public String summary()
    {
        return "yt-dlp=" + state(ytDlpAvailable)
                + ", deno=" + state(denoAvailable)
                + ", youtube-fallback=" + featureState(youtubeFallbackEnabled, youtubeFallbackReady)
                + ", po-provider=" + featureState(potProviderEnabled, potProviderReady);
    }

    private static String state(boolean ready)
    {
        return ready ? "ready" : "missing";
    }

    private static String featureState(boolean enabled, boolean ready)
    {
        return !enabled ? "off" : ready ? "ready" : "unavailable";
    }

    static boolean providerAssetsAvailable(Path pluginDirectory, Path providerHome)
    {
        if (pluginDirectory == null || providerHome == null)
            return false;
        try
        {
            if (!pluginAssetsAvailable(pluginDirectory) || !Files.isDirectory(providerHome))
                return false;
            for (String file : PROVIDER_FILES)
            {
                if (!nonEmptyRegularFile(providerHome.resolve(file)))
                    return false;
            }
            for (String module : PROVIDER_MODULES)
            {
                if (!Files.isDirectory(providerHome.resolve("node_modules").resolve(module)))
                    return false;
            }
            Path denoDirectory = providerHome.resolve(".deno-dir");
            if (!Files.isDirectory(denoDirectory))
                return false;
            try (Stream<Path> files = Files.walk(denoDirectory))
            {
                return files.anyMatch(Files::isRegularFile);
            }
        }
        catch (IOException | UncheckedIOException | SecurityException exception)
        {
            return false;
        }
    }

    private static boolean pluginAssetsAvailable(Path pluginDirectory) throws IOException
    {
        if (!Files.isDirectory(pluginDirectory))
            return false;

        boolean extracted = true;
        for (String entry : PLUGIN_ENTRIES)
        {
            if (!nonEmptyRegularFile(pluginDirectory.resolve(entry)))
            {
                extracted = false;
                break;
            }
        }
        if (extracted)
            return true;

        Path archive = pluginDirectory.resolve("bgutil-ytdlp-pot-provider.zip");
        if (!nonEmptyRegularFile(archive))
            return false;
        try (ZipFile zip = new ZipFile(archive.toFile()))
        {
            for (String name : PLUGIN_ENTRIES)
            {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0)
                    return false;
            }
            return true;
        }
    }

    private static boolean nonEmptyRegularFile(Path path) throws IOException
    {
        return Files.isRegularFile(path) && Files.size(path) > 0;
    }

    static boolean executableAvailable(String executable)
    {
        if (executable == null || executable.isBlank())
            return false;
        Path configured = Path.of(executable);
        if (configured.isAbsolute() || executable.contains("/") || executable.contains("\\"))
            return Files.isRegularFile(configured) && Files.isExecutable(configured);

        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank())
            return false;
        List<String> suffixes = new ArrayList<>();
        suffixes.add("");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
        {
            String pathExt = System.getenv("PATHEXT");
            if (pathExt != null)
            {
                for (String extension : pathExt.split(";"))
                    suffixes.add(extension.toLowerCase(Locale.ROOT));
            }
            suffixes.add(".exe");
        }
        for (String directory : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator)))
        {
            if (directory.isBlank())
                continue;
            for (String suffix : suffixes)
            {
                Path candidate = Path.of(directory, executable + suffix);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
                    return true;
            }
        }
        return false;
    }
}
