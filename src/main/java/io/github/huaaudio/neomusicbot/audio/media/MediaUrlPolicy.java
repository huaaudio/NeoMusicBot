package io.github.huaaudio.neomusicbot.audio.media;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Input allow-list and output SSRF policy for the external resolver boundary. */
public final class MediaUrlPolicy
{
    private static final int MAX_INPUT_LENGTH = 4096;
    private static final Pattern BILIBILI_BARE_ID = Pattern.compile(
            "(?:BV[A-Za-z0-9]{8,20}|av[0-9]{1,20})", Pattern.CASE_INSENSITIVE);

    private MediaUrlPolicy()
    {
    }

    public static String normalizeExtractorInput(MediaSource source, String input, int searchLimit)
    {
        if (input == null)
            throw new IllegalArgumentException("Media URL is missing");
        if (input.length() > MAX_INPUT_LENGTH || containsControl(input))
            throw new IllegalArgumentException("Invalid media URL");
        String value = input.trim();
        if (value.isEmpty())
            throw new IllegalArgumentException("Invalid media URL");

        if (source == MediaSource.BILIBILI && BILIBILI_BARE_ID.matcher(value).matches())
        {
            String canonical = value.regionMatches(true, 0, "BV", 0, 2)
                    ? "BV" + value.substring(2) : "av" + value.substring(2);
            return "https://www.bilibili.com/video/" + canonical;
        }

        if (source == MediaSource.YOUTUBE && (value.startsWith("ytsearch:") || value.startsWith("ytmsearch:")))
        {
            int separator = value.indexOf(':');
            String query = value.substring(separator + 1).trim();
            if (query.isEmpty() || query.length() > 500 || containsControl(query))
                throw new IllegalArgumentException("Invalid YouTube search query");
            String prefix = value.startsWith("ytmsearch:") ? "ytmsearch" : "ytsearch";
            return prefix + Math.max(1, Math.min(searchLimit, 50)) + ':' + query;
        }

        if (!value.contains("://"))
            value = "https://" + value;

        final URI uri;
        try
        {
            uri = new URI(value);
        }
        catch (URISyntaxException ex)
        {
            throw new IllegalArgumentException("Invalid media URL", ex);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("Only allow-listed HTTPS media URLs are accepted");

        String host = normalizedHost(uri.getHost());
        boolean allowed = switch (source)
        {
            case YOUTUBE -> isDomain(host, "youtube.com") || isDomain(host, "youtube-nocookie.com")
                    || host.equals("youtu.be");
            case BILIBILI -> isDomain(host, "bilibili.com") || host.equals("b23.tv");
        };
        if (!allowed)
            throw new IllegalArgumentException("Media URL host is not allow-listed");

        try
        {
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            // These components are already escaped. The multi-argument URI
            // constructor would escape every '%' again (for example %32 -> %2532).
            return new URI("https://" + host + path + query).toASCIIString();
        }
        catch (URISyntaxException ex)
        {
            throw new IllegalArgumentException("Invalid media URL", ex);
        }
    }

    public static URI validateResolvedStream(String value)
    {
        final URI uri;
        try
        {
            uri = new URI(value);
        }
        catch (Exception ex)
        {
            throw new YtDlpException(YtDlpException.Kind.INVALID_OUTPUT, "Extractor returned an invalid stream URL", ex);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new YtDlpException(YtDlpException.Kind.INVALID_OUTPUT,
                    "Extractor returned a stream outside the HTTPS policy");

        try
        {
            resolvePublicAddresses(uri.getHost());
        }
        catch (UnknownHostException ex)
        {
            throw new YtDlpException(YtDlpException.Kind.RETRYABLE,
                    "Could not resolve a public extracted media host", ex);
        }
        return uri;
    }

    /**
     * Resolves a host and returns only addresses which are safe for outbound
     * media connections. Apache HTTP uses the returned array directly, closing
     * the DNS-rebinding gap between validation and connection establishment.
     */
    public static InetAddress[] resolvePublicAddresses(String host) throws UnknownHostException
    {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0)
            throw new UnknownHostException("Media host returned no addresses");
        for (InetAddress address : addresses)
        {
            if (!isPublic(address))
                throw new UnknownHostException("Media host resolved to a non-public address");
        }
        return addresses;
    }

    static boolean isPublic(InetAddress address)
    {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress())
            return false;

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address)
            return isPublicIpv4(bytes);
        if (address instanceof Inet6Address)
        {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if ((first & 0xfe) == 0xfc) // fc00::/7 unique local
                return false;
            if (first == 0x20 && second == 0x01)
            {
                if ((bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) // 2001:db8::/32
                    return false;
                if (bytes[2] == 0 && bytes[3] == 0) // 2001::/32 Teredo
                    return false;
            }
            if (isIpv4Mapped(bytes) || isIpv4Compatible(bytes))
                return false;
            if (isNat64WellKnownPrefix(bytes))
                return isPublicIpv4(lastIpv4Bytes(bytes));
            if (isNat64LocalUsePrefix(bytes))
                return false;
            if (first == 0x20 && second == 0x02) // 6to4: reject when its embedded relay target is non-public
                return isPublicIpv4(new byte[]{bytes[2], bytes[3], bytes[4], bytes[5]});
        }
        return true;
    }

    private static boolean isPublicIpv4(byte[] bytes)
    {
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        int c = bytes[2] & 0xff;
        return a != 0 && a < 224
                && !(a == 10)
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && b == 168)
                && !(a == 192 && b == 0 && (c == 0 || c == 2))
                && !(a == 198 && (b == 18 || b == 19))
                && !(a == 198 && b == 51 && c == 100)
                && !(a == 203 && b == 0 && c == 113);
    }

    private static boolean isNat64WellKnownPrefix(byte[] bytes)
    {
        return (bytes[0] & 0xff) == 0x00 && (bytes[1] & 0xff) == 0x64
                && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b
                && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0
                && bytes[8] == 0 && bytes[9] == 0 && bytes[10] == 0 && bytes[11] == 0;
    }

    private static boolean isNat64LocalUsePrefix(byte[] bytes)
    {
        return (bytes[0] & 0xff) == 0x00 && (bytes[1] & 0xff) == 0x64
                && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b
                && bytes[4] == 0 && bytes[5] == 1;
    }

    private static byte[] lastIpv4Bytes(byte[] bytes)
    {
        return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
    }

    private static boolean isIpv4Mapped(byte[] bytes)
    {
        for (int i = 0; i < 10; i++)
        {
            if (bytes[i] != 0)
                return false;
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static boolean isIpv4Compatible(byte[] bytes)
    {
        for (int i = 0; i < 12; i++)
        {
            if (bytes[i] != 0)
                return false;
        }
        return true;
    }

    public static boolean isSearch(String input)
    {
        return input != null && (input.startsWith("ytsearch:") || input.startsWith("ytmsearch:"));
    }

    public static boolean isYoutubePlaylist(String input)
    {
        if (input == null || isSearch(input))
            return false;
        try
        {
            URI uri = new URI(input.contains("://") ? input : "https://" + input);
            String query = uri.getRawQuery();
            return query != null && (query.startsWith("list=") || query.contains("&list="));
        }
        catch (URISyntaxException ignored)
        {
            return false;
        }
    }

    private static boolean isDomain(String host, String domain)
    {
        return host.equals(domain) || host.endsWith('.' + domain);
    }

    private static String normalizedHost(String host)
    {
        return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private static boolean containsControl(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c == '\0' || c == '\r' || c == '\n' || Character.isISOControl(c))
                return true;
        }
        return false;
    }
}
