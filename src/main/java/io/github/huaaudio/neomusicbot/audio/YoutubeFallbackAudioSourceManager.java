package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.audio.media.MediaCollection;
import io.github.huaaudio.neomusicbot.audio.media.MediaEntry;
import io.github.huaaudio.neomusicbot.audio.media.MediaSource;
import io.github.huaaudio.neomusicbot.audio.media.MediaTrackKey;
import io.github.huaaudio.neomusicbot.audio.media.YtDlpException;
import io.github.huaaudio.neomusicbot.audio.media.YtDlpMediaResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/** YouTube-source v2 primary path with a narrowly classified yt-dlp fallback. */
public final class YoutubeFallbackAudioSourceManager implements AudioSourceManager
{
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIDEO_PATH = Pattern.compile("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})");

    private final YoutubeAudioSourceManager primary;
    private final YtDlpAudioSourceManager fallbackTracks;
    private final YtDlpMediaResolver resolver;
    private final int maximumEntries;

    public YoutubeFallbackAudioSourceManager(YoutubeAudioSourceManager primary,
                                             YtDlpAudioSourceManager fallbackTracks,
                                             YtDlpMediaResolver resolver,
                                             int maximumEntries)
    {
        this.primary = primary;
        this.fallbackTracks = fallbackTracks;
        this.resolver = resolver;
        this.maximumEntries = Math.max(1, Math.min(maximumEntries, 50));
    }

    @Override
    public String getSourceName()
    {
        return primary.getSourceName();
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
    {
        if (reference.identifier == null)
            return null;
        boolean supported = looksLikeYoutube(reference.identifier);
        try
        {
            AudioItem item = primary.loadItem(manager, reference);
            if (item == null || item == AudioReference.NO_TRACK)
                return item;
            return wrapPrimaryItem(item);
        }
        catch (RuntimeException ex)
        {
            if (!supported || !shouldFallback(ex))
                throw ex;
        }

        if (!resolver.isYoutubeFallbackEnabled())
            return AudioReference.NO_TRACK;

        try
        {
            String input = VIDEO_ID.matcher(reference.identifier).matches()
                    ? "https://www.youtube.com/watch?v=" + reference.identifier
                    : reference.identifier;
            MediaCollection collection = resolver.resolveReference(MediaSource.YOUTUBE, input, maximumEntries);
            List<AudioTrack> tracks = new ArrayList<>();
            for (MediaEntry entry : collection.entries())
                tracks.add(fallbackTracks.createTrack(entry));
            if (tracks.isEmpty())
                return AudioReference.NO_TRACK;
            if (tracks.size() == 1 && !collection.searchResult())
                return tracks.get(0);
            return new BasicAudioPlaylist(collection.name(), tracks, null, collection.searchResult());
        }
        catch (IllegalArgumentException ex)
        {
            throw new FriendlyException("The YouTube URL is not accepted by the media security policy", COMMON, ex);
        }
        catch (YtDlpException ex)
        {
            FriendlyException.Severity severity = switch (ex.getKind())
            {
                case NOT_FOUND, UNAVAILABLE, AUTHENTICATION_REQUIRED, REGION_BLOCKED -> COMMON;
                default -> SUSPICIOUS;
            };
            throw new FriendlyException(ex.getMessage(), severity, ex);
        }
    }

    private AudioItem wrapPrimaryItem(AudioItem item)
    {
        if (item instanceof AudioTrack track)
            return wrapPrimaryTrack(track);
        if (!(item instanceof AudioPlaylist playlist))
            return item;

        List<AudioTrack> wrapped = new ArrayList<>(playlist.getTracks().size());
        Map<AudioTrack, AudioTrack> mapping = new IdentityHashMap<>();
        for (AudioTrack track : playlist.getTracks())
        {
            AudioTrack replacement = wrapPrimaryTrack(track);
            wrapped.add(replacement);
            mapping.put(track, replacement);
        }
        AudioTrack selected = playlist.getSelectedTrack() == null
                ? null : mapping.get(playlist.getSelectedTrack());
        return new BasicAudioPlaylist(playlist.getName(), wrapped, selected, playlist.isSearchResult());
    }

    private AudioTrack wrapPrimaryTrack(AudioTrack track)
    {
        if (!(track instanceof InternalAudioTrack internal))
            return track;
        try
        {
            keyForTrack(track.getInfo());
            return new YoutubeFallbackAudioTrack(track.getInfo(), internal, this);
        }
        catch (IllegalArgumentException ignored)
        {
            return track;
        }
    }

    MediaTrackKey keyForTrack(AudioTrackInfo trackInfo)
    {
        String id = trackInfo.identifier;
        if (id != null && VIDEO_ID.matcher(id).matches())
            return new MediaTrackKey(MediaSource.YOUTUBE, id, null);
        if (trackInfo.uri != null)
        {
            try
            {
                URI uri = URI.create(trackInfo.uri);
                if ("youtu.be".equalsIgnoreCase(uri.getHost()) && uri.getPath() != null && uri.getPath().length() > 1)
                {
                    id = uri.getPath().substring(1).split("/")[0];
                }
                else
                {
                    String query = uri.getRawQuery();
                    if (query != null)
                    {
                        for (String part : query.split("&"))
                        {
                            if (part.startsWith("v="))
                                id = part.substring(2);
                        }
                    }
                    if (id == null)
                    {
                        Matcher matcher = VIDEO_PATH.matcher(uri.getPath());
                        if (matcher.find())
                            id = matcher.group(1);
                    }
                }
            }
            catch (Exception ignored)
            {
                // The stable identifier check below produces a safe error.
            }
        }
        if (id == null || !VIDEO_ID.matcher(id).matches())
            throw new IllegalArgumentException("youtube-source track did not expose a stable video identifier");
        return new MediaTrackKey(MediaSource.YOUTUBE, id, null);
    }

    YtDlpAudioSourceManager.PlaybackDelegate openFallback(MediaTrackKey key)
    {
        return fallbackTracks.openPlaybackDelegate(key);
    }

    void invalidateFallback(MediaTrackKey key)
    {
        fallbackTracks.invalidate(key);
    }

    static boolean shouldFallback(Throwable failure)
    {
        StringBuilder message = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause())
        {
            if (current.getMessage() != null)
                message.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
        }
        String value = message.toString();
        if (containsAny(value, "private", "members-only", "members only", "copyright", "removed", " 404",
                "not found", "not available in your country", "region", "login required", "age-restricted",
                "age restricted"))
            return false;
        if (containsAny(value, " 403", " 410", "signature", "cipher", "decipher", "javascript", " js ",
                "challenge", "confirm you're not a bot", "confirm you’re not a bot", "no playable format",
                "no formats", "restricted client", "client unavailable", "no supported clients",
                "no available clients", "unsupported client", "player response", "extract", "internal"))
            return true;
        if (failure instanceof FriendlyException friendly)
            return friendly.severity != COMMON;
        // youtube-source may surface an unchecked parser/client exception rather
        // than FriendlyException; these internal failures are eligible.
        return true;
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

    private static boolean looksLikeYoutube(String value)
    {
        String lower = value.toLowerCase(Locale.ROOT);
        return VIDEO_ID.matcher(value).matches() || lower.startsWith("ytsearch:") || lower.startsWith("ytmsearch:")
                || lower.matches("^(?:https?://)?(?:[^/]+\\.)?(?:youtube\\.com|youtube-nocookie\\.com)/.*")
                || lower.matches("^(?:https?://)?youtu\\.be/.*");
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track)
    {
        if (track instanceof YoutubeFallbackAudioTrack wrapped)
            return primary.isTrackEncodable(wrapped.getPrimaryTrack());
        return primary.isTrackEncodable(track);
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException
    {
        if (track instanceof YoutubeFallbackAudioTrack wrapped)
            primary.encodeTrack(wrapped.getPrimaryTrack(), output);
        else
            primary.encodeTrack(track, output);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException
    {
        AudioTrack decoded = primary.decodeTrack(trackInfo, input);
        return decoded == null ? null : wrapPrimaryTrack(decoded);
    }

    @Override
    public void shutdown()
    {
        primary.shutdown();
    }
}
