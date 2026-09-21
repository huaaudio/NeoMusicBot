package io.github.huaaudio.neomusicbot.audio.media;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Allows only stable, non-credentialed references to be written to local playlists. */
public final class PersistentMediaReferencePolicy
{
    private static final int MAX_LENGTH = 2048;
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern BILIBILI_ID = Pattern.compile(
            "(?:BV[A-Za-z0-9]{8,20}|av[0-9]{1,20})", Pattern.CASE_INSENSITIVE);
    private static final Pattern STABLE_VALUE = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]{0,8}");

    private PersistentMediaReferencePolicy()
    {
    }

    public static String normalize(String input)
    {
        if(input == null || input.isBlank() || input.length() > MAX_LENGTH || containsControl(input))
            throw new IllegalArgumentException("Playlist item is empty or contains control characters");
        String value = input.trim();
        if(YOUTUBE_ID.matcher(value).matches())
            return "https://www.youtube.com/watch?v=" + value;
        if(BILIBILI_ID.matcher(value).matches())
            return MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI, value, 1);

        URI uri = parse(value);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("Playlist items require credential-free HTTPS URLs");

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if(isDomain(host, "youtube.com") || isDomain(host, "youtube-nocookie.com")
                || host.equals("youtu.be"))
            return normalizeYoutube(uri, host);
        if(isDomain(host, "bilibili.com") || host.equals("b23.tv"))
            return normalizeBilibili(uri, host);
        if(isDomain(host, "soundcloud.com"))
            return rebuild(host, stablePath(uri), null);
        if(host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net"))
            return normalizeDiscordAttachment(uri, host);
        throw new IllegalArgumentException("Playlist item host is not an enabled media source");
    }

    private static String normalizeYoutube(URI uri, String host)
    {
        String path = stablePath(uri);
        if(host.equals("youtu.be"))
        {
            String id = firstPathSegment(path);
            if(!YOUTUBE_ID.matcher(id).matches())
                throw new IllegalArgumentException("Invalid YouTube video URL");
            return rebuild(host, '/' + id, null);
        }

        String query = filterQuery(uri.getRawQuery(), Set.of("v", "list", "index"));
        if("/watch".equals(path))
        {
            String videoId = queryValue(query, "v");
            String playlistId = queryValue(query, "list");
            if((videoId == null || !YOUTUBE_ID.matcher(videoId).matches()) && playlistId == null)
                throw new IllegalArgumentException("YouTube watch URLs need a stable video or playlist ID");
        }
        else if(path.startsWith("/shorts/") || path.startsWith("/live/"))
        {
            String id = firstPathSegment(path.substring(path.indexOf('/', 1)));
            if(!YOUTUBE_ID.matcher(id).matches())
                throw new IllegalArgumentException("Invalid YouTube video URL");
            query = null;
        }
        else if(!"/playlist".equals(path) || queryValue(query, "list") == null)
        {
            throw new IllegalArgumentException("Unsupported YouTube playlist reference");
        }
        return rebuild(host, path, query);
    }

    private static String normalizeBilibili(URI uri, String host)
    {
        String path = stablePath(uri);
        if(host.equals("b23.tv"))
        {
            if(firstPathSegment(path).isBlank())
                throw new IllegalArgumentException("Invalid b23.tv URL");
            return rebuild(host, path, filterBilibiliPage(uri.getRawQuery()));
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if(!lower.matches("/video/(?:bv[a-z0-9]{8,20}|av[0-9]{1,20})/?"))
            throw new IllegalArgumentException("Only Bilibili BV/av video URLs are persistent");
        return rebuild(host, path, filterBilibiliPage(uri.getRawQuery()));
    }

    private static String filterBilibiliPage(String rawQuery)
    {
        String query = filterQuery(rawQuery, Set.of("p"));
        if(query != null && query.indexOf('&') >= 0)
            throw new IllegalArgumentException("Bilibili playlist URLs may contain only one page number");
        return query;
    }

    private static String normalizeDiscordAttachment(URI uri, String host)
    {
        String path = stablePath(uri);
        if(uri.getRawQuery() != null || !path.startsWith("/attachments/")
                || path.length() == "/attachments/".length())
            throw new IllegalArgumentException("Signed or malformed Discord attachment URLs are not persistent");
        return rebuild(host, path, null);
    }

    private static String filterQuery(String rawQuery, Set<String> allowed)
    {
        if(rawQuery == null || rawQuery.isBlank())
            return null;
        List<String> result = new ArrayList<>();
        for(String part : rawQuery.split("&"))
        {
            int separator = part.indexOf('=');
            String name = (separator < 0 ? part : part.substring(0, separator)).toLowerCase(Locale.ROOT);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            if(!allowed.contains(name))
                continue;
            boolean integer = name.equals("index") || name.equals("p");
            if(!(integer ? POSITIVE_INTEGER : STABLE_VALUE).matcher(value).matches())
                throw new IllegalArgumentException("Playlist URL contains an invalid stable identifier");
            result.add(name + '=' + value);
        }
        return result.isEmpty() ? null : String.join("&", result);
    }

    private static String queryValue(String query, String name)
    {
        if(query == null)
            return null;
        for(String part : query.split("&"))
            if(part.startsWith(name + '='))
                return part.substring(name.length() + 1);
        return null;
    }

    private static URI parse(String value)
    {
        try
        {
            return new URI(value);
        }
        catch(URISyntaxException ex)
        {
            throw new IllegalArgumentException("Invalid playlist URL", ex);
        }
    }

    private static String stablePath(URI uri)
    {
        String path = uri.getRawPath();
        if(path == null || path.isBlank() || !uri.normalize().getRawPath().equals(path))
            throw new IllegalArgumentException("Invalid playlist URL path");
        return path;
    }

    private static String firstPathSegment(String path)
    {
        String value = path.startsWith("/") ? path.substring(1) : path;
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static String rebuild(String host, String path, String query)
    {
        try
        {
            return new URI("https", null, host, -1, path, query, null).toASCIIString();
        }
        catch(URISyntaxException ex)
        {
            throw new IllegalArgumentException("Invalid persistent playlist URL", ex);
        }
    }

    private static boolean isDomain(String host, String root)
    {
        return host.equals(root) || host.endsWith('.' + root);
    }

    private static boolean containsControl(String value)
    {
        for(int index = 0; index < value.length(); index++)
            if(Character.isISOControl(value.charAt(index)))
                return true;
        return false;
    }
}
