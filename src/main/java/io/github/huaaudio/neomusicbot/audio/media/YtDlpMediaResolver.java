package io.github.huaaudio.neomusicbot.audio.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.huaaudio.neomusicbot.audio.media.YtDlpException.Kind;

/**
 * Bounded JSON-only yt-dlp integration. It never invokes a shell, downloads a
 * file, invokes ffmpeg, or persists a signed CDN address.
 */
public final class YtDlpMediaResolver implements MediaResolver, AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(YtDlpMediaResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int STDOUT_LIMIT = 8 * 1024 * 1024;
    private static final int STDERR_LIMIT = 256 * 1024;
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final long MAX_COOKIE_FILE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_REPORTED_MEDIA_BYTES = 8L * 1024 * 1024 * 1024;
    private static final long YOUTUBE_START_INTERVAL_NANOS = Duration.ofSeconds(5).toNanos();
    private static final String AUDIO_FORMAT =
            "bestaudio[acodec^=opus]/bestaudio[acodec^=mp4a]/bestaudio[acodec^=aac]/bestaudio";
    private static final Pattern BILIBILI_ID = Pattern.compile("(?i)(BV[A-Za-z0-9]{8,20}|av[0-9]{1,20})");
    private static final Pattern BILIBILI_PAGE_ID = Pattern.compile(
            "(?i)(?:BV[A-Za-z0-9]{8,20}|av[0-9]{1,20})_p([1-9][0-9]*)");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final YtDlpConfiguration configuration;
    private final ProcessStarter processStarter;
    private final EphemeralProcessCache processCache;
    private final ResolverLifecycle lifecycle = new ResolverLifecycle();
    private final Semaphore processSlots = new Semaphore(2, true);
    private final Semaphore potProviderSlot = new Semaphore(1, true);
    private final ExecutorService streamReaders;
    private final ConcurrentHashMap<MediaTrackKey, CachedExtraction> cache = new ConcurrentHashMap<>();
    private final Set<ActiveProcess> activeProcesses = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextYoutubeStart = new AtomicLong();

    public YtDlpMediaResolver(YtDlpConfiguration configuration)
    {
        this(configuration, ProcessBuilder::start);
    }

    @FunctionalInterface
    interface ProcessStarter
    {
        Process start(ProcessBuilder builder) throws IOException;
    }

    YtDlpMediaResolver(YtDlpConfiguration configuration, ProcessStarter processStarter)
    {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.processCache = EphemeralProcessCache.create();
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = runnable ->
        {
            Thread thread = new Thread(runnable, "yt-dlp-stream-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        streamReaders = Executors.newCachedThreadPool(factory);
    }

    @Override
    public MediaMetadata resolveMetadata(MediaTrackKey key)
    {
        return getExtraction(key, false).entry().metadata();
    }

    @Override
    public ResolvedMedia resolveStream(MediaTrackKey key)
    {
        return getExtraction(key, true).media();
    }

    @Override
    public void invalidate(MediaTrackKey key)
    {
        cache.remove(key);
    }

    public MediaCollection resolveReference(MediaSource source, String input, int maximumEntries)
    {
        int limit = Math.max(1, Math.min(maximumEntries, 50));
        String normalized = MediaUrlPolicy.normalizeExtractorInput(source, input, limit);
        boolean search = MediaUrlPolicy.isSearch(input);
        boolean collection = search || (source == MediaSource.YOUTUBE && MediaUrlPolicy.isYoutubePlaylist(normalized));
        JsonNode root = runJson(source, normalized, collection, limit);
        if (collection)
            return parseCollection(source, root, limit, search);

        MediaTrackKey expected = keyFromInput(source, normalized).orElse(null);
        Extraction extraction = parseFullExtraction(source, root, expected, pageFromUri(normalized));
        putCache(extraction);
        return new MediaCollection(extraction.entry().metadata().title(), List.of(extraction.entry()), false);
    }

    public boolean isYoutubeFallbackEnabled()
    {
        return configuration.youtubeFallbackEnabled();
    }

    private Extraction getExtraction(MediaTrackKey key, boolean requireFreshStream)
    {
        if (lifecycle.isClosed())
            throw new YtDlpException(Kind.CONFIGURATION, "The media resolver has been shut down");
        Instant now = Instant.now();
        CachedExtraction existing = cache.get(key);
        if (existing != null && existing.isUsable(now, requireFreshStream))
            return existing.extraction();

        String input = MediaUrlPolicy.normalizeExtractorInput(key.source(), key.webUri().toString(), 1);
        JsonNode root = runJson(key.source(), input, false, 1);
        Extraction extracted = parseFullExtraction(key.source(), root, key, key.subIndex());
        putCache(extracted);
        return extracted;
    }

    private void putCache(Extraction extraction)
    {
        if (cache.size() >= MAX_CACHE_ENTRIES)
        {
            Instant now = Instant.now();
            cache.entrySet().removeIf(entry -> !entry.getValue().isUsable(now, false));
            if (cache.size() >= MAX_CACHE_ENTRIES)
                cache.clear();
        }
        cache.put(extraction.entry().key(), new CachedExtraction(extraction, Instant.now()));
    }

    private JsonNode runJson(MediaSource source, String input, boolean collection, int limit)
    {
        try
        {
            return runJsonAttempt(source, input, collection, limit, false);
        }
        catch (YtDlpException firstFailure)
        {
            if (source != MediaSource.YOUTUBE || !configuration.potProviderEnabled()
                    || !firstFailure.canRetryWithPotProvider())
                throw firstFailure;
            boolean acquired = false;
            try
            {
                acquired = potProviderSlot.tryAcquire(configuration.itemTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!acquired)
                    throw new YtDlpException(Kind.TIMEOUT, "Timed out waiting for the PO token provider");
                return runJsonAttempt(source, input, collection, limit, true);
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                throw new YtDlpException(Kind.INTERRUPTED, "Interrupted while waiting for the PO token provider", ex);
            }
            finally
            {
                if (acquired)
                    potProviderSlot.release();
            }
        }
    }

    private JsonNode runJsonAttempt(MediaSource source, String input, boolean collection,
                                    int limit, boolean usePotProvider)
    {
        Duration timeout = collection ? configuration.collectionTimeout() : configuration.itemTimeout();
        ProcessResult result = execute(buildCommand(source, input, collection, limit, usePotProvider), source, timeout);
        if (result.exitCode() != 0)
            throw classifyFailure(result.stderr());
        if (result.stdoutOverflow())
            throw new YtDlpException(Kind.OUTPUT_LIMIT, "yt-dlp JSON exceeded the configured output limit");
        if (result.stderrOverflow())
            log.warn("yt-dlp diagnostic output exceeded the configured limit for {}", source.name().toLowerCase());
        return parseJsonDocument(result.stdout());
    }

    static JsonNode parseJsonDocument(String output)
    {
        try
        {
            JsonNode root = JSON.readTree(output);
            if (root == null || !root.isObject())
                throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp did not return a JSON object");
            return root;
        }
        catch (YtDlpException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned malformed JSON", ex);
        }
    }

    private List<String> buildCommand(MediaSource source, String input, boolean collection,
                                      int limit, boolean usePotProvider)
    {
        List<String> command = new ArrayList<>();
        command.add(configuration.executable());
        command.add("--ignore-config");
        command.add("--no-config-locations");
        command.add("--no-update");
        command.add("--no-cache-dir");
        command.add("--no-plugin-dirs");
        command.add("--no-remote-components");
        command.add("--no-progress");
        command.add("--color");
        command.add("never");
        command.add("--simulate");
        command.add("--dump-single-json");
        command.add("--js-runtimes");
        command.add("deno:" + configuration.denoExecutable());
        if (configuration.pluginDirectory() != null)
        {
            command.add("--plugin-dirs");
            command.add(configuration.pluginDirectory().toString());
        }

        Path cookies = source == MediaSource.YOUTUBE
                ? configuration.youtubeCookies() : configuration.bilibiliCookies();
        if (cookies != null)
        {
            validateCookieFile(cookies, source);
            command.add("--cookies");
            command.add(cookies.toString());
        }

        if (collection)
        {
            command.add("--flat-playlist");
            command.add("--playlist-items");
            command.add(":" + limit);
        }
        else
        {
            command.add("--no-playlist");
            command.add("--format");
            command.add(AUDIO_FORMAT);
        }

        if (source == MediaSource.YOUTUBE)
        {
            command.add("--extractor-args");
            command.add(usePotProvider
                    ? "youtube:player_client=mweb;fetch_pot=always"
                    : "youtube:fetch_pot=never");
            if (usePotProvider && configuration.potProviderHome() != null)
            {
                command.add("--extractor-args");
                command.add("youtubepot-bgutilscript:server_home=" + configuration.potProviderHome());
            }
        }
        command.add("--");
        command.add(input);
        return command;
    }

    static void validateCookieFile(Path cookies, MediaSource source)
    {
        String message = source.name() + " cookie file must be a small, owner-only Netscape cookie file";
        try
        {
            if (!Files.isRegularFile(cookies, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(cookies))
                throw new YtDlpException(Kind.CONFIGURATION, message);
            long size = Files.size(cookies);
            if (size <= 0 || size > MAX_COOKIE_FILE_BYTES)
                throw new YtDlpException(Kind.CONFIGURATION, message);

            CookieFilePermissionPolicy.validateOwnerOnly(cookies, message);

            try (BufferedReader reader = Files.newBufferedReader(cookies, StandardCharsets.UTF_8))
            {
                String firstLine = reader.readLine();
                if (firstLine == null)
                    throw new YtDlpException(Kind.CONFIGURATION, message);
                if (firstLine.startsWith("\uFEFF"))
                    firstLine = firstLine.substring(1);
                if (!firstLine.equals("# Netscape HTTP Cookie File"))
                    throw new YtDlpException(Kind.CONFIGURATION, message);
            }
        }
        catch (YtDlpException ex)
        {
            throw ex;
        }
        catch (IOException | SecurityException ex)
        {
            throw new YtDlpException(Kind.CONFIGURATION, message, ex);
        }
    }

    private ProcessResult execute(List<String> command, MediaSource source, Duration timeout)
    {
        if (lifecycle.isClosed())
            throw new YtDlpException(Kind.CONFIGURATION, "The media resolver has been shut down");
        boolean acquired = false;
        ActiveProcess active = null;
        try
        {
            acquired = processSlots.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired)
                throw new YtDlpException(Kind.TIMEOUT, "Timed out waiting for an yt-dlp process slot");
            if (source == MediaSource.YOUTUBE)
                awaitYoutubeStartInterval();
            if (lifecycle.isClosed())
                throw new YtDlpException(Kind.CONFIGURATION, "The media resolver has been shut down");

            ProcessBuilder builder = new ProcessBuilder(command);
            active = lifecycle.start(() ->
            {
                configureChildEnvironment(builder.environment());
                return new ActiveProcess(processStarter.start(builder));
            }, activeProcesses::add);
            Process process = active.process;
            if (lifecycle.isClosed())
                throw new YtDlpException(Kind.CONFIGURATION, "The media resolver has been shut down");

            Future<CapturedOutput> stdout = streamReaders.submit(
                    () -> readCapped(process.getInputStream(), STDOUT_LIMIT));
            Future<CapturedOutput> stderr = streamReaders.submit(
                    () -> readCapped(process.getErrorStream(), STDERR_LIMIT));
            active.attachCaptures(stdout, stderr);

            if (!active.waitFor(timeout))
                throw new YtDlpException(Kind.TIMEOUT, "yt-dlp timed out");
            if (lifecycle.isClosed())
                throw new YtDlpException(Kind.CONFIGURATION, "The media resolver has been shut down");

            CapturedOutput capturedOut = awaitCapture(stdout, active);
            CapturedOutput capturedErr = awaitCapture(stderr, active);
            return new ProcessResult(process.exitValue(), capturedOut.text(), capturedErr.text(),
                    capturedOut.overflow(), capturedErr.overflow());
        }
        catch (YtDlpException ex)
        {
            if (active != null)
                active.terminate();
            throw ex;
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            if (active != null)
                active.terminate();
            throw new YtDlpException(Kind.INTERRUPTED, "yt-dlp execution was interrupted", ex);
        }
        catch (IOException ex)
        {
            if (active != null)
                active.terminate();
            throw new YtDlpException(Kind.CONFIGURATION,
                    "Could not start the configured yt-dlp executable", ex);
        }
        catch (RuntimeException ex)
        {
            if (active != null)
                active.terminate();
            throw new YtDlpException(Kind.INTERNAL, "yt-dlp execution failed", ex);
        }
        finally
        {
            if (active != null)
            {
                if (active.hasLiveProcessOrCaptures())
                    active.terminate();
                activeProcesses.remove(active);
            }
            if (acquired)
                processSlots.release();
        }
    }

    private void awaitYoutubeStartInterval() throws InterruptedException
    {
        while (true)
        {
            long now = System.nanoTime();
            long current = nextYoutubeStart.get();
            long start = Math.max(now, current);
            if (nextYoutubeStart.compareAndSet(current, start + YOUTUBE_START_INTERVAL_NANOS))
            {
                long wait = start - now;
                if (wait > 0)
                    TimeUnit.NANOSECONDS.sleep(wait);
                return;
            }
        }
    }

    void configureChildEnvironment(Map<String, String> environment)
    {
        configureChildEnvironment(environment, System.getenv());
    }

    void configureChildEnvironment(Map<String, String> environment, Map<String, String> parent)
    {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(parent, "parent");
        environment.clear();
        copyEnvironment(parent, environment, "PATH", "Path", "HOME", "USERPROFILE", "SystemRoot", "WINDIR",
                "PATHEXT", "TEMP", "TMP", "TMPDIR", "APPDATA", "LOCALAPPDATA", "DENO_DIR",
                "SSL_CERT_FILE", "SSL_CERT_DIR", "TOKEN_TTL");
        if (!environment.containsKey("DENO_DIR") && configuration.potProviderHome() != null)
        {
            Path packagedDenoDirectory = configuration.potProviderHome().resolve(".deno-dir");
            if (Files.isDirectory(packagedDenoDirectory))
                environment.put("DENO_DIR", packagedDenoDirectory.toString());
        }
        environment.put("XDG_CACHE_HOME", processCache.directory().toString());
        environment.put("NO_COLOR", "1");
        environment.put("PYTHONUTF8", "1");
        environment.put("DENO_NO_PROMPT", "1");
        environment.put("DENO_NO_UPDATE_CHECK", "1");
    }

    private static void copyEnvironment(Map<String, String> source, Map<String, String> target, String... names)
    {
        for (String name : names)
        {
            String value = source.get(name);
            if (value != null)
                target.put(name, value);
        }
    }

    private static CapturedOutput readCapped(InputStream input, int limit) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
        byte[] buffer = new byte[8192];
        boolean overflow = false;
        int read;
        while ((read = input.read(buffer)) != -1)
        {
            int remaining = limit - output.size();
            if (remaining > 0)
                output.write(buffer, 0, Math.min(remaining, read));
            if (read > remaining)
                overflow = true;
        }
        return new CapturedOutput(output.toString(StandardCharsets.UTF_8), overflow);
    }

    private static CapturedOutput awaitCapture(Future<CapturedOutput> future, ActiveProcess active)
    {
        try
        {
            return future.get(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            active.terminate();
            throw new YtDlpException(Kind.INTERRUPTED, "Interrupted while reading yt-dlp output", ex);
        }
        catch (ExecutionException | TimeoutException ex)
        {
            active.terminate();
            future.cancel(true);
            throw new YtDlpException(Kind.INVALID_OUTPUT, "Could not read yt-dlp output", ex);
        }
    }

    private static final class ActiveProcess
    {
        private final Process process;
        private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        private volatile Future<CapturedOutput> stdout;
        private volatile Future<CapturedOutput> stderr;

        private ActiveProcess(Process process)
        {
            this.process = process;
            observeDescendants();
        }

        private void attachCaptures(Future<CapturedOutput> stdout, Future<CapturedOutput> stderr)
        {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private boolean waitFor(Duration timeout) throws InterruptedException
        {
            long timeoutNanos = Math.max(1, timeout.toNanos());
            long deadline = System.nanoTime() + timeoutNanos;
            while (true)
            {
                observeDescendants();
                if (!process.isAlive())
                {
                    observeDescendants();
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return false;
                long waitMillis = Math.max(1,
                        Math.min(25, TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS))
                {
                    observeDescendants();
                    return true;
                }
            }
        }

        private boolean hasLiveProcessOrCaptures()
        {
            Future<CapturedOutput> out = stdout;
            Future<CapturedOutput> err = stderr;
            return process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)
                    || (out != null && !out.isDone()) || (err != null && !err.isDone());
        }

        private void observeDescendants()
        {
            try
            {
                process.toHandle().descendants().forEach(descendants::add);
            }
            catch (RuntimeException ignored)
            {
                // The process may disappear while its descendants are sampled.
            }
        }

        private synchronized void terminate()
        {
            boolean[] interrupted = {Thread.interrupted()};
            try
            {
                observeDescendants();
                requestStop(false);
                awaitStopped(250, interrupted);
                for (int attempt = 0; attempt < 3 && hasLiveProcess(); attempt++)
                {
                    observeDescendants();
                    requestStop(true);
                    awaitStopped(500, interrupted);
                }
            }
            finally
            {
                closeQuietly(process.getOutputStream());
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                Future<CapturedOutput> out = stdout;
                Future<CapturedOutput> err = stderr;
                if (out != null && !out.isDone())
                    out.cancel(true);
                if (err != null && !err.isDone())
                    err.cancel(true);
                if (interrupted[0])
                    Thread.currentThread().interrupt();
            }
        }

        private void requestStop(boolean force)
        {
            for (ProcessHandle child : descendants)
                stop(child, force);
            stop(process.toHandle(), force);
        }

        private static void stop(ProcessHandle handle, boolean force)
        {
            try
            {
                if (handle.isAlive())
                {
                    if (force)
                        handle.destroyForcibly();
                    else
                        handle.destroy();
                }
            }
            catch (RuntimeException ignored)
            {
                // A process may disappear or reject a signal while the tree is sampled.
            }
        }

        private boolean hasLiveProcess()
        {
            return process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
        }

        private void awaitStopped(long timeoutMillis, boolean[] interrupted)
        {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (hasLiveProcess() && System.nanoTime() < deadline)
            {
                observeDescendants();
                try
                {
                    Thread.sleep(10);
                }
                catch (InterruptedException ex)
                {
                    interrupted[0] = true;
                }
            }
        }

        private static void closeQuietly(AutoCloseable closeable)
        {
            try
            {
                closeable.close();
            }
            catch (Exception ignored)
            {
            }
        }
    }

    static YtDlpException classifyFailure(String stderr)
    {
        String diagnostic = SensitiveLogSanitizer.sanitize(stderr);
        String lower = diagnostic.toLowerCase(Locale.ROOT);
        Kind kind;
        // YouTube's bot-check begins with "Sign in to confirm", so it must
        // take precedence over the genuine login/age/private classifications.
        if (containsAny(lower, "confirm you're not a bot", "confirm you’re not a bot",
                "confirm you are not a bot", "unusual traffic", "automated traffic",
                "automated queries", "bot-check", "bot check", "captcha",
                "player challenge", "javascript challenge", "js challenge",
                "http error 403", "403 forbidden", "http error 410", "410 gone",
                "no video formats found", "no formats found", "requested format is not available",
                "restricted client", "po token", "pot provider", "signature extraction failed",
                "nsig extraction failed"))
            kind = Kind.RETRYABLE;
        else if (containsAny(lower, "private video", "members-only", "members only", "login required",
                "sign in to confirm", "age-restricted", "authentication required"))
            kind = Kind.AUTHENTICATION_REQUIRED;
        else if (containsAny(lower, "not available in your country", "geo-restricted", "georestricted", "region"))
            kind = Kind.REGION_BLOCKED;
        else if (containsAny(lower, "copyright", "has been removed", "video is unavailable", "video unavailable"))
            kind = Kind.UNAVAILABLE;
        else if (containsAny(lower, "404", "not found"))
            kind = Kind.NOT_FOUND;
        else
            kind = Kind.INTERNAL;
        return new YtDlpException(kind, "yt-dlp failed: " + diagnostic);
    }

    private static boolean containsAny(String value, String... fragments)
    {
        for (String fragment : fragments)
        {
            if (value.contains(fragment))
                return true;
        }
        return false;
    }

    private MediaCollection parseCollection(MediaSource source, JsonNode root, int limit, boolean search)
    {
        ensureExtractor(source, root, true);
        JsonNode entriesNode = root.path("entries");
        if (!entriesNode.isArray())
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp collection did not contain entries");
        List<MediaEntry> entries = new ArrayList<>();
        for (JsonNode node : entriesNode)
        {
            if (entries.size() >= limit)
                break;
            if (node == null || node.isNull())
                continue;
            try
            {
                ensureExtractor(source, node, false);
                MediaTrackKey key = keyFromNode(source, node, null, null);
                entries.add(new MediaEntry(key, metadataFromNode(key, node)));
            }
            catch (YtDlpException | IllegalArgumentException ex)
            {
                log.debug("Discarded an invalid flat yt-dlp collection entry: {}", ex.getMessage());
            }
        }
        if (entries.isEmpty())
            throw new YtDlpException(Kind.UNAVAILABLE, "yt-dlp collection contained no playable entries");
        return new MediaCollection(text(root, "title", search ? "YouTube search" : "YouTube playlist"),
                entries, search);
    }

    private Extraction parseFullExtraction(MediaSource source, JsonNode root,
                                           MediaTrackKey expected, Integer requestedPage)
    {
        JsonNode node = selectRequestedEntry(root, requestedPage);
        ensureExtractor(source, node, false);
        MediaTrackKey key = keyFromNode(source, node, expected, requestedPage);
        return new Extraction(new MediaEntry(key, metadataFromNode(key, node)), mediaFromNode(node));
    }

    /** Visible to package tests so multi-P selection cannot silently regress. */
    static JsonNode selectRequestedEntry(JsonNode root, Integer requestedPage)
    {
        JsonNode entries = root.path("entries");
        if (!entries.isArray())
            return root;
        if (requestedPage != null)
        {
            boolean hasExplicitIndices = false;
            for (JsonNode entry : entries)
            {
                if (entry != null && !entry.isNull() && entry.path("playlist_index").asInt(-1) > 0)
                {
                    hasExplicitIndices = true;
                    if (entry.path("playlist_index").asInt() == requestedPage)
                        return entry;
                }
            }
            int zeroBased = requestedPage - 1;
            if (!hasExplicitIndices && zeroBased >= 0 && zeroBased < entries.size())
            {
                JsonNode indexed = entries.get(zeroBased);
                if (indexed != null && !indexed.isNull())
                    return indexed;
            }
            throw new YtDlpException(Kind.UNAVAILABLE, "Requested Bilibili page was not returned by yt-dlp");
        }
        for (JsonNode entry : entries)
        {
            if (entry != null && !entry.isNull())
                return entry;
        }
        throw new YtDlpException(Kind.UNAVAILABLE, "yt-dlp returned an empty collection");
    }

    static MediaTrackKey keyFromNode(MediaSource source, JsonNode node,
                                     MediaTrackKey expected, Integer requestedPage)
    {
        if (expected != null && expected.source() != source)
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned a mismatched media source");
        if (source == MediaSource.YOUTUBE)
        {
            String id = text(node, "id", null);
            if (id == null || !YOUTUBE_ID.matcher(id).matches())
                throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned an invalid YouTube identifier");
            if (expected != null && !expected.canonicalId().equals(id))
                throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned a different YouTube video");
            return expected == null ? new MediaTrackKey(source, id, null) : expected;
        }

        List<String> outputIds = bilibiliOutputIds(node);
        if (outputIds.isEmpty())
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned no stable Bilibili identifier");
        String canonical = outputIds.get(0);

        if (expected != null)
        {
            String expectedId = normalizeBilibiliId(expected.canonicalId());
            for (String outputId : outputIds)
            {
                if (sameBilibiliIdType(outputId, expectedId) && !outputId.equals(expectedId))
                    throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned a different Bilibili video");
            }

            boolean directlyLinked = outputIds.contains(expectedId);
            if (!directlyLinked)
            {
                boolean verifiedConversion = !sameBilibiliIdType(canonical, expectedId)
                        && bilibiliOriginalIds(node).contains(expectedId)
                        && isBilibiliExtractor(node);
                if (!verifiedConversion)
                    throw new YtDlpException(Kind.INVALID_OUTPUT,
                            "yt-dlp Bilibili identifier could not be linked to the requested video");
            }
        }

        Integer page = requestedPage != null ? requestedPage : expected == null ? null : expected.subIndex();
        Integer outputPage = bilibiliOutputPage(node);
        if (page != null && outputPage != null && !page.equals(outputPage))
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned a different Bilibili page");
        if (page == null)
            page = outputPage;
        return new MediaTrackKey(source, canonical, page);
    }

    private static Integer bilibiliOutputPage(JsonNode node)
    {
        List<Integer> pages = new ArrayList<>();
        Matcher id = BILIBILI_PAGE_ID.matcher(text(node, "id", ""));
        if (id.matches())
        {
            try
            {
                pages.add(Integer.parseInt(id.group(1)));
            }
            catch (NumberFormatException ex)
            {
                throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned an invalid Bilibili page");
            }
        }
        String webpage = text(node, "webpage_url", null);
        if (webpage != null)
        {
            try
            {
                Integer page = pageFromUri(MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI, webpage, 1));
                if (page != null)
                    pages.add(page);
            }
            catch (IllegalArgumentException ignored)
            {
                // An unrelated URL cannot establish the selected Bilibili page.
            }
        }
        if (node.path("playlist_index").canConvertToInt())
        {
            int index = node.path("playlist_index").asInt();
            if (index > 0)
                pages.add(index);
        }
        if (pages.stream().distinct().count() > 1)
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned conflicting Bilibili pages");
        return pages.isEmpty() ? null : pages.get(0);
    }

    private static List<String> bilibiliOutputIds(JsonNode node)
    {
        List<String> ids = new ArrayList<>();
        addBilibiliUrlIds(ids, text(node, "webpage_url", null));
        addBilibiliIds(ids, text(node, "id", null));
        addBilibiliIds(ids, text(node, "display_id", null));
        addBilibiliIds(ids, text(node, "webpage_url_basename", null));
        return ids;
    }

    private static List<String> bilibiliOriginalIds(JsonNode node)
    {
        List<String> ids = new ArrayList<>();
        addBilibiliUrlIds(ids, text(node, "original_url", null));
        return ids;
    }

    private static void addBilibiliUrlIds(List<String> ids, String value)
    {
        if (value == null)
            return;
        try
        {
            addBilibiliIds(ids, MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI, value, 1));
        }
        catch (IllegalArgumentException ignored)
        {
            // A non-Bilibili URL cannot prove an av-to-BV identity conversion.
        }
    }

    private static void addBilibiliIds(List<String> ids, String value)
    {
        if (value == null)
            return;
        Matcher matcher = BILIBILI_ID.matcher(value);
        while (matcher.find())
        {
            String id = normalizeBilibiliId(matcher.group(1));
            if (!ids.contains(id))
                ids.add(id);
        }
    }

    private static boolean sameBilibiliIdType(String first, String second)
    {
        return first.startsWith("BV") == second.startsWith("BV");
    }

    private static boolean isBilibiliExtractor(JsonNode node)
    {
        String extractor = text(node, "extractor_key", text(node, "ie_key", text(node, "extractor", "")))
                .toLowerCase(Locale.ROOT);
        return extractor.contains("bilibili") || extractor.contains("bili");
    }

    private static String normalizeBilibiliId(String value)
    {
        return value.regionMatches(true, 0, "BV", 0, 2)
                ? "BV" + value.substring(2) : "av" + value.substring(2);
    }

    private static MediaMetadata metadataFromNode(MediaTrackKey key, JsonNode node)
    {
        long duration = 0;
        if (node.path("duration").isNumber())
        {
            double seconds = node.path("duration").asDouble();
            if (Double.isFinite(seconds) && seconds > 0 && seconds < Long.MAX_VALUE / 1000.0)
                duration = Math.round(seconds * 1000.0);
        }
        boolean live = node.path("is_live").asBoolean(false)
                || "is_live".equals(node.path("live_status").asText());
        return new MediaMetadata(text(node, "title", "Unknown title"),
                text(node, "uploader", text(node, "channel", "Unknown uploader")), duration,
                key.webUri(), safeArtworkUri(node), live);
    }

    static URI safeArtworkUri(JsonNode node)
    {
        String value = text(node, "thumbnail", null);
        if (value == null && node.path("thumbnails").isArray())
        {
            for (JsonNode thumbnail : node.path("thumbnails"))
            {
                String candidate = text(thumbnail, "url", null);
                if (candidate != null)
                    value = candidate;
            }
        }
        if (value == null)
            return null;
        try
        {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                return null;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return new URI("https://" + uri.getRawAuthority() + path);
        }
        catch (URISyntaxException ignored)
        {
            return null;
        }
    }

    static ResolvedMedia mediaFromNode(JsonNode root)
    {
        JsonNode selected = selectedFormat(root);
        String url = text(selected, "url", null);
        if (url == null)
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp did not return a playable stream URL");
        String codec = text(selected, "acodec", text(root, "acodec", null));
        if (codec == null || "none".equalsIgnoreCase(codec))
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp selected a format without audio");
        String videoCodec = text(selected, "vcodec", text(root, "vcodec", null));
        if (videoCodec != null && !"none".equalsIgnoreCase(videoCodec))
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp selected a muxed video format");
        String protocol = text(selected, "protocol", text(root, "protocol", null));
        String extension = text(selected, "ext", text(root, "ext", null));
        if (!isSupportedMediaFormat(protocol, extension))
            throw new YtDlpException(Kind.INVALID_OUTPUT,
                    "yt-dlp selected an unsupported media protocol or container");
        long size = selected.path("filesize").asLong(selected.path("filesize_approx").asLong(0));
        if (size < 0 || size > MAX_REPORTED_MEDIA_BYTES)
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp returned an unreasonable media size");

        URI uri = MediaUrlPolicy.validateResolvedStream(url);
        Instant expiresAt = expirationFromUri(uri).orElseGet(() -> Instant.now().plus(Duration.ofMinutes(10)));
        if (expiresAt.isBefore(Instant.now().plusSeconds(15)))
            throw new YtDlpException(Kind.RETRYABLE, "yt-dlp returned an already-expiring media URL");
        JsonNode headerNode = selected.path("http_headers").isObject()
                ? selected.path("http_headers") : root.path("http_headers");
        boolean live = root.path("is_live").asBoolean(false)
                || "is_live".equals(root.path("live_status").asText());
        return new ResolvedMedia(uri, safeHeaders(headerNode), expiresAt,
                text(selected, "format_id", text(root, "format_id", "unknown")), live);
    }

    private static JsonNode selectedFormat(JsonNode root)
    {
        if (root.hasNonNull("url") && hasPlayableAudioCodec(root))
            return root;
        for (String name : List.of("requested_downloads", "requested_formats", "formats"))
        {
            JsonNode formats = root.path(name);
            if (!formats.isArray())
                continue;
            JsonNode candidate = null;
            for (JsonNode format : formats)
            {
                if (format.hasNonNull("url") && hasPlayableAudioCodec(format))
                    candidate = format;
            }
            if (candidate != null)
                return candidate;
        }
        return root;
    }

    private static boolean hasPlayableAudioCodec(JsonNode format)
    {
        String codec = text(format, "acodec", null);
        String videoCodec = text(format, "vcodec", null);
        return codec != null && !"none".equalsIgnoreCase(codec)
                && (videoCodec == null || "none".equalsIgnoreCase(videoCodec));
    }

    private static boolean isSupportedMediaFormat(String protocol, String extension)
    {
        if (protocol == null || extension == null)
            return false;
        String normalizedProtocol = protocol.toLowerCase(Locale.ROOT);
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        boolean supportedProtocol = normalizedProtocol.equals("https")
                || normalizedProtocol.equals("m3u8")
                || normalizedProtocol.equals("m3u8_native");
        boolean supportedContainer = switch (normalizedExtension)
        {
            case "opus", "webm", "weba", "mka", "matroska", "m4a", "mp4", "m4s",
                    "aac", "ogg", "oga", "mp3", "mpeg", "mpga", "flac", "wav",
                    "ts", "m3u8" -> true;
            default -> false;
        };
        return supportedProtocol && supportedContainer;
    }

    private static Map<String, String> safeHeaders(JsonNode node)
    {
        if (!node.isObject())
            return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext() && result.size() < 8)
        {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            String value = field.getValue().isTextual() ? field.getValue().asText() : null;
            if (isAllowedStreamHeader(name) && value != null && value.length() <= 1024
                    && value.indexOf('\r') < 0 && value.indexOf('\n') < 0)
                result.put(name, value);
        }
        return result;
    }

    private static boolean isAllowedStreamHeader(String name)
    {
        return name.equalsIgnoreCase("Accept") || name.equalsIgnoreCase("Accept-Language")
                || name.equalsIgnoreCase("User-Agent") || name.equalsIgnoreCase("Referer")
                || name.equalsIgnoreCase("Origin");
    }

    private static Optional<Instant> expirationFromUri(URI uri)
    {
        String query = uri.getRawQuery();
        if (query == null)
            return Optional.empty();
        for (String part : query.split("&"))
        {
            int equals = part.indexOf('=');
            if (equals < 1)
                continue;
            String name = part.substring(0, equals).toLowerCase(Locale.ROOT);
            if (!name.equals("expire") && !name.equals("expires") && !name.equals("exp"))
                continue;
            try
            {
                long epoch = Long.parseLong(part.substring(equals + 1));
                if (epoch > 0)
                    return Optional.of(Instant.ofEpochSecond(epoch));
            }
            catch (Exception ignored)
            {
            }
        }
        return Optional.empty();
    }

    private static void ensureExtractor(MediaSource source, JsonNode node, boolean collection)
    {
        String extractor = text(node, "extractor_key", text(node, "ie_key", text(node, "extractor", "")))
                .toLowerCase(Locale.ROOT);
        if (extractor.isEmpty() && collection)
            return;
        boolean valid = source == MediaSource.YOUTUBE
                ? extractor.contains("youtube")
                : extractor.contains("bilibili") || extractor.contains("bili");
        if (!valid)
            throw new YtDlpException(Kind.INVALID_OUTPUT, "yt-dlp used an unexpected extractor");
    }

    private static Optional<MediaTrackKey> keyFromInput(MediaSource source, String normalized)
    {
        if (source == MediaSource.YOUTUBE)
        {
            try
            {
                URI uri = new URI(normalized);
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                String id = null;
                if (host.equals("youtu.be"))
                {
                    String path = uri.getPath();
                    id = path != null && path.length() > 1 ? path.substring(1).split("/")[0] : null;
                }
                else
                {
                    id = queryParameter(uri, "v").orElse(null);
                    if (id == null)
                    {
                        Matcher matcher = Pattern.compile("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})")
                                .matcher(uri.getPath());
                        if (matcher.find())
                            id = matcher.group(1);
                    }
                }
                return id != null && YOUTUBE_ID.matcher(id).matches()
                        ? Optional.of(new MediaTrackKey(source, id, null)) : Optional.empty();
            }
            catch (Exception ignored)
            {
                return Optional.empty();
            }
        }
        Matcher matcher = BILIBILI_ID.matcher(normalized);
        if (!matcher.find())
            return Optional.empty();
        return Optional.of(new MediaTrackKey(source, normalizeBilibiliId(matcher.group(1)), pageFromUri(normalized)));
    }

    private static Integer pageFromUri(String value)
    {
        try
        {
            return queryParameter(new URI(value), "p")
                    .map(Integer::parseInt).filter(page -> page > 0).orElse(null);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static Optional<String> queryParameter(URI uri, String wanted)
    {
        String query = uri.getRawQuery();
        if (query == null)
            return Optional.empty();
        for (String part : query.split("&"))
        {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).equals(wanted))
                return Optional.of(URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    private static String text(JsonNode node, String field, String fallback)
    {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isValueNode() || value.isNull())
            return fallback;
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    @Override
    public void close()
    {
        lifecycle.close(() ->
        {
            for (ActiveProcess active : activeProcesses)
                active.terminate();
            activeProcesses.clear();
            streamReaders.shutdownNow();
            cache.clear();
            closeProcessCacheWithoutMasking(processCache);
        });
    }

    static void closeProcessCacheWithoutMasking(AutoCloseable processCache)
    {
        try
        {
            processCache.close();
        }
        catch (Exception ignored)
        {
            log.warn("Could not completely erase the temporary media resolver cache");
        }
    }

    private record Extraction(MediaEntry entry, ResolvedMedia media) {}

    private record CachedExtraction(Extraction extraction, Instant cachedAt)
    {
        boolean isUsable(Instant now, boolean requireFreshStream)
        {
            return !cachedAt.plus(Duration.ofMinutes(5)).isBefore(now)
                    && (!requireFreshStream || extraction.media().expiresAt().isAfter(now.plus(Duration.ofMinutes(2))));
        }
    }

    private record CapturedOutput(String text, boolean overflow) {}

    private record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean stdoutOverflow, boolean stderrOverflow) {}
}
