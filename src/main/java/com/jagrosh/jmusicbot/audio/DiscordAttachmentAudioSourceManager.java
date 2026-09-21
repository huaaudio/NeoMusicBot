package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.audio.media.MediaUrlPolicy;
import com.jagrosh.jmusicbot.audio.media.YtDlpException;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.ThreadLocalHttpInterfaceManager;

import java.io.DataInput;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;

/**
 * HTTP playback restricted to Discord's attachment CDNs. Generic web URLs and
 * redirects are intentionally unsupported so this source cannot become an SSRF
 * primitive when slash-command attachments are loaded.
 */
public final class DiscordAttachmentAudioSourceManager extends HttpAudioSourceManager
{
    private static final int MAX_URL_LENGTH = 4096;

    private final ThreadLocalHttpInterfaceManager filteredManager;

    public DiscordAttachmentAudioSourceManager()
    {
        super(SafeMediaContainerRegistries.directAudio());
        configureBuilder(builder -> YtDlpAudioSourceManager.configureSecureHttpBuilder(builder, List.of()));
        configureRequests(YtDlpAudioSourceManager::configureHttpRequests);
        filteredManager = YtDlpAudioSourceManager.createSecureHttpInterfaceManager(List.of());
    }

    @Override
    public String getSourceName()
    {
        return "discord-attachment";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
    {
        if (reference.identifier == null || !looksLikeWebReference(reference.identifier))
            return null;
        try
        {
            URI uri = validateAttachmentUrl(reference.identifier);
            AudioItem item = super.loadItem(manager,
                    new AudioReference(uri.toASCIIString(), reference.title));
            return requireDirectAudio(item);
        }
        catch (IllegalArgumentException | YtDlpException ex)
        {
            throw new FriendlyException("Only HTTPS Discord attachment URLs are accepted", COMMON, ex);
        }
    }

    @Override
    public HttpInterface getHttpInterface()
    {
        return filteredManager.getInterface();
    }

    static AudioItem requireDirectAudio(AudioItem item)
    {
        if (item instanceof AudioReference)
            throw new FriendlyException("Discord attachment playlists are not supported", COMMON, null);
        return item;
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException
    {
        try
        {
            validateAttachmentUrl(trackInfo.identifier);
        }
        catch (RuntimeException ex)
        {
            throw new IOException("Serialized track is not an allow-listed Discord attachment", ex);
        }
        return super.decodeTrack(trackInfo, input);
    }

    static URI validateAttachmentUrl(String value)
    {
        URI uri = parseAllowlistedUrl(value);
        return MediaUrlPolicy.validateResolvedStream(uri.toASCIIString());
    }

    /** Pure syntax/allow-list check, separated from DNS validation for tests. */
    static URI parseAllowlistedUrl(String value)
    {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH || containsControl(value))
            throw new IllegalArgumentException("Invalid Discord attachment URL");

        final URI uri;
        try
        {
            uri = new URI(value);
        }
        catch (URISyntaxException ex)
        {
            throw new IllegalArgumentException("Invalid Discord attachment URL", ex);
        }

        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("Discord attachments require HTTPS");

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!normalizedHost.equals("cdn.discordapp.com") && !normalizedHost.equals("media.discordapp.net"))
            throw new IllegalArgumentException("Discord attachment host is not allow-listed");

        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/attachments/") || path.length() == "/attachments/".length()
                || !uri.normalize().getRawPath().equals(path))
            throw new IllegalArgumentException("Discord attachment path is not allow-listed");
        return uri;
    }

    private static boolean looksLikeWebReference(String value)
    {
        return value.regionMatches(true, 0, "https://", 0, 8)
                || value.regionMatches(true, 0, "http://", 0, 7);
    }

    private static boolean containsControl(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            if (Character.isISOControl(value.charAt(i)))
                return true;
        }
        return false;
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        try
        {
            filteredManager.close();
        }
        catch (IOException ignored)
        {
        }
    }
}
