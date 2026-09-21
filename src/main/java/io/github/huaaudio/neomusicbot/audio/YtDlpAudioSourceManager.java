package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.audio.media.MediaEntry;
import io.github.huaaudio.neomusicbot.audio.media.MediaMetadata;
import io.github.huaaudio.neomusicbot.audio.media.MediaUrlPolicy;
import io.github.huaaudio.neomusicbot.audio.media.MediaResolver;
import io.github.huaaudio.neomusicbot.audio.media.MediaSource;
import io.github.huaaudio.neomusicbot.audio.media.MediaTrackKey;
import io.github.huaaudio.neomusicbot.audio.media.ResolvedMedia;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.ThreadLocalHttpInterfaceManager;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Serialization and playback adapter for stable keys resolved by yt-dlp.
 * Its loadItem method is intentionally inert; URL matching belongs to the
 * primary YouTube wrapper or Bilibili source manager.
 */
public class YtDlpAudioSourceManager implements AudioSourceManager
{
    private static final int SERIAL_VERSION = 1;
    private static final int MAX_REDIRECT_REFERENCES = 3;

    private final String sourceName;
    private final MediaSource mediaSource;
    private final MediaResolver resolver;
    private final AudioPlayerManager playerManager;

    public YtDlpAudioSourceManager(String sourceName, MediaSource mediaSource,
                                   MediaResolver resolver, AudioPlayerManager playerManager)
    {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.mediaSource = Objects.requireNonNull(mediaSource, "mediaSource");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.playerManager = Objects.requireNonNull(playerManager, "playerManager");
    }

    @Override
    public String getSourceName()
    {
        return sourceName;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
    {
        return null;
    }

    public AudioTrack createTrack(MediaEntry entry)
    {
        if (entry.key().source() != mediaSource)
            throw new IllegalArgumentException("Media source does not match this track manager");
        MediaMetadata metadata = entry.metadata();
        AudioTrackInfo trackInfo = new AudioTrackInfo(
                metadata.title(),
                metadata.author(),
                metadata.durationMillis(),
                entry.key().serializedId(),
                metadata.live(),
                metadata.webUri().toString(),
                metadata.artworkUri() == null ? null : metadata.artworkUri().toString(),
                null);
        return new YtDlpAudioTrack(trackInfo, entry.key(), this);
    }

    PlaybackDelegate openPlaybackDelegate(MediaTrackKey key)
    {
        ResolvedMedia media = resolver.resolveStream(key);
        List<Header> defaultHeaders = new ArrayList<>();
        for (Map.Entry<String, String> header : media.headers().entrySet())
            defaultHeaders.add(new BasicHeader(header.getKey(), header.getValue()));

        ThreadLocalHttpInterfaceManager hlsHttpManager = createSecureHttpInterfaceManager(defaultHeaders);
        HttpAudioSourceManager http = null;
        try
        {
            http = new FilteredHttpAudioSourceManager(
                    SafeMediaContainerRegistries.directAudioAndSafeHls(hlsHttpManager), defaultHeaders);
            AudioItem item = http.loadItem(playerManager,
                    new AudioReference(media.uri().toString(), key.serializedId()));
            int references = 0;
            while (item instanceof AudioReference redirect && references++ < MAX_REDIRECT_REFERENCES)
            {
                MediaUrlPolicy.validateResolvedStream(redirect.identifier);
                item = http.loadItem(playerManager, redirect);
            }
            if (!(item instanceof InternalAudioTrack track))
                throw new FriendlyException("The resolved media stream has an unsupported container", SUSPICIOUS, null);
            return new PlaybackDelegate(track, http, hlsHttpManager);
        }
        catch (RuntimeException ex)
        {
            if (http != null)
                http.shutdown();
            closeQuietly(hlsHttpManager);
            throw ex;
        }
    }

    static HttpClientBuilder configureHttpBuilder(HttpClientBuilder builder, List<Header> defaultHeaders)
    {
        return builder.setDefaultHeaders(defaultHeaders).disableRedirectHandling();
    }

    static HttpClientBuilder configureSecureHttpBuilder(HttpClientBuilder builder, List<Header> defaultHeaders)
    {
        return configureHttpBuilder(builder, defaultHeaders)
                .disableCookieManagement()
                .setDnsResolver(MediaUrlPolicy::resolvePublicAddresses);
    }

    static RequestConfig configureHttpRequests(RequestConfig config)
    {
        return RequestConfig.copy(config)
                .setRedirectsEnabled(false)
                .setCircularRedirectsAllowed(false)
                .setRelativeRedirectsAllowed(false)
                .setConnectionRequestTimeout(5_000)
                .setConnectTimeout(10_000)
                .setSocketTimeout(30_000)
                .build();
    }

    static ThreadLocalHttpInterfaceManager createSecureHttpInterfaceManager(List<Header> defaultHeaders)
    {
        HttpClientBuilder builder = HttpClientTools.createSharedCookiesHttpBuilder();
        configureSecureHttpBuilder(builder, defaultHeaders);
        ThreadLocalHttpInterfaceManager manager = new ThreadLocalHttpInterfaceManager(
                builder, configureHttpRequests(HttpClientTools.DEFAULT_REQUEST_CONFIG));
        manager.setHttpContextFilter(PublicMediaHttpContextFilter.INSTANCE);
        return manager;
    }

    private static void closeQuietly(HttpInterfaceManager manager)
    {
        try
        {
            manager.close();
        }
        catch (IOException ignored)
        {
        }
    }

    void invalidate(MediaTrackKey key)
    {
        resolver.invalidate(key);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track)
    {
        return track instanceof YtDlpAudioTrack resolved && resolved.getSourceManager() == this;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException
    {
        if (!(track instanceof YtDlpAudioTrack resolved) || resolved.getSourceManager() != this)
            throw new IOException("Cannot encode a track owned by a different source manager");
        MediaTrackKey key = resolved.getMediaKey();
        output.writeByte(SERIAL_VERSION);
        output.writeUTF(key.canonicalId());
        output.writeInt(key.subIndex() == null ? 0 : key.subIndex());
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException
    {
        int version = input.readUnsignedByte();
        if (version != SERIAL_VERSION)
            throw new IOException("Unsupported resolved-track serialization version: " + version);
        try
        {
            String canonicalId = input.readUTF();
            int page = input.readInt();
            MediaTrackKey key = new MediaTrackKey(mediaSource, canonicalId, page == 0 ? null : page);
            return new YtDlpAudioTrack(trackInfo, key, this);
        }
        catch (IllegalArgumentException ex)
        {
            throw new IOException("Invalid stable media key", ex);
        }
    }

    @Override
    public void shutdown()
    {
        // The shared resolver is owned and closed by PlayerManager.
    }

    record PlaybackDelegate(InternalAudioTrack track, HttpAudioSourceManager sourceManager,
                            HttpInterfaceManager hlsHttpManager) implements AutoCloseable
    {
        @Override
        public void close()
        {
            sourceManager.shutdown();
            closeQuietly(hlsHttpManager);
        }
    }
}
