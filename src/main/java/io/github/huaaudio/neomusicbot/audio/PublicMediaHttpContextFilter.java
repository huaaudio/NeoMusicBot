package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.audio.media.MediaUrlPolicy;
import io.github.huaaudio.neomusicbot.audio.media.YtDlpException;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;

import java.net.URI;

/** Enforces the outbound media URL policy immediately before every request. */
final class PublicMediaHttpContextFilter implements HttpContextFilter
{
    static final PublicMediaHttpContextFilter INSTANCE = new PublicMediaHttpContextFilter();

    private PublicMediaHttpContextFilter()
    {
    }

    @Override
    public void onContextOpen(HttpClientContext context)
    {
    }

    @Override
    public void onContextClose(HttpClientContext context)
    {
    }

    @Override
    public void onRequest(HttpClientContext context, HttpUriRequest request, boolean isRepetition)
    {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))
            throw new YtDlpException(YtDlpException.Kind.INVALID_OUTPUT,
                    "Media playback attempted a disallowed HTTP method");
        URI uri = request.getURI();
        if (uri == null)
            throw new YtDlpException(YtDlpException.Kind.INVALID_OUTPUT,
                    "Media playback attempted a request without a URL");
        MediaUrlPolicy.validateResolvedStream(uri.toASCIIString());
    }

    @Override
    public boolean onRequestResponse(HttpClientContext context, HttpUriRequest request, HttpResponse response)
    {
        return false;
    }

    @Override
    public boolean onRequestException(HttpClientContext context, HttpUriRequest request, Throwable error)
    {
        return false;
    }
}
