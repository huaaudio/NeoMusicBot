/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 */
package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.audio.media.MediaCollection;
import io.github.huaaudio.neomusicbot.audio.media.MediaEntry;
import io.github.huaaudio.neomusicbot.audio.media.MediaSource;
import io.github.huaaudio.neomusicbot.audio.media.YtDlpException;
import io.github.huaaudio.neomusicbot.audio.media.YtDlpMediaResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Bilibili URL matcher backed by yt-dlp. Stable BV/av + page keys are queued;
 * no page scraping result or signed CDN address is retained.
 */
public final class BilibiliAudioSourceManager extends YtDlpAudioSourceManager
{
    private static final Pattern BARE_ID = Pattern.compile(
            "(?:BV[A-Za-z0-9]{8,20}|av[0-9]{1,20})", Pattern.CASE_INSENSITIVE);

    private final YtDlpMediaResolver resolver;

    public BilibiliAudioSourceManager(YtDlpMediaResolver resolver, AudioPlayerManager playerManager)
    {
        super("bilibili", MediaSource.BILIBILI, resolver, playerManager);
        this.resolver = resolver;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
    {
        if (reference.identifier == null || !looksLikeBilibili(reference.identifier))
            return null;
        try
        {
            MediaCollection collection = resolver.resolveReference(MediaSource.BILIBILI, reference.identifier, 1);
            if (collection.entries().isEmpty())
                return AudioReference.NO_TRACK;
            MediaEntry entry = collection.entries().get(0);
            return createTrack(entry);
        }
        catch (IllegalArgumentException ex)
        {
            throw new FriendlyException("The Bilibili URL is not accepted by the media security policy", COMMON, ex);
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

    static boolean looksLikeBilibili(String value)
    {
        String normalized = value.trim();
        if (BARE_ID.matcher(normalized).matches())
            return true;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.matches("^(?:https?://)?(?:[^/]+\\.)?bilibili\\.com/video/(?:bv[a-z0-9]+|av[0-9]+)(?:[/?#].*)?$")
                || lower.matches("^(?:https?://)?b23\\.tv/[a-z0-9_-]+(?:[/?#].*)?$");
    }
}
