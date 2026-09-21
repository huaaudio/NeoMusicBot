/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.audio.media.YtDlpException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PublicMediaHttpContextFilterTest
{
    @Test
    public void rejectsPrivateAndMetadataTargetsBeforeClientExecution() throws Exception
    {
        CountingClient client = new CountingClient();
        try (HttpInterface http = new HttpInterface(client, HttpClientContext.create(), false,
                PublicMediaHttpContextFilter.INSTANCE))
        {
            assertTrue(http.acquire());
            assertThrows(YtDlpException.class,
                    () -> http.execute(new HttpGet("https://127.0.0.1/private.ts")));
            assertThrows(YtDlpException.class,
                    () -> http.execute(new HttpGet("https://169.254.169.254/latest/meta-data")));
            assertThrows(YtDlpException.class,
                    () -> http.execute(new HttpGet("http://1.1.1.1/insecure.ts")));
            assertEquals(0, client.executions.get());
        }
    }

    private static final class CountingClient extends CloseableHttpClient
    {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request,
                                                  HttpContext context) throws IOException
        {
            executions.incrementAndGet();
            throw new IOException("Network execution should have been filtered");
        }

        @Override
        public HttpParams getParams()
        {
            return null;
        }

        @Override
        public ClientConnectionManager getConnectionManager()
        {
            return null;
        }

        @Override
        public void close()
        {
        }
    }
}
