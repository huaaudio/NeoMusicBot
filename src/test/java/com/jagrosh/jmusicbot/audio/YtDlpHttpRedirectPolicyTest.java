package com.jagrosh.jmusicbot.audio;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YtDlpHttpRedirectPolicyTest
{
    @Test
    public void requestConfigurationDisablesEveryRedirectMode()
    {
        RequestConfig config = YtDlpAudioSourceManager.configureHttpRequests(RequestConfig.DEFAULT);
        assertFalse(config.isRedirectsEnabled());
        assertFalse(config.isCircularRedirectsAllowed());
        assertFalse(config.isRelativeRedirectsAllowed());
    }

    @Test
    public void playbackHttpClientDoesNotFollowAThirtyTwoRedirect() throws Exception
    {
        AtomicInteger requests = new AtomicInteger();
        ExecutorService responder = Executors.newSingleThreadExecutor();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress(loopback, 0));
            int port = server.getLocalPort();
            Future<?> responses = responder.submit(() -> serveRedirectProbe(server, port, requests));

            RequestConfig requestConfig = YtDlpAudioSourceManager.configureHttpRequests(RequestConfig.DEFAULT);
            try (CloseableHttpClient client = YtDlpAudioSourceManager.configureHttpBuilder(
                    HttpClientBuilder.create(), List.of()).setDefaultRequestConfig(requestConfig).build();
                 CloseableHttpResponse response = client.execute(
                         new HttpGet("http://127.0.0.1:" + port + "/media")))
            {
                assertEquals(302, response.getStatusLine().getStatusCode());
            }

            await(responses);
            assertEquals(1, requests.get());
        }
        finally
        {
            responder.shutdownNow();
        }
    }

    @Test
    public void resolverHeadersAreSentOnManifestVariantAndSegmentRequests() throws Exception
    {
        ExecutorService responder = Executors.newSingleThreadExecutor();
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress(loopback, 0));
            Future<?> responses = responder.submit(() -> serveHeaderProbes(server, captured));

            RequestConfig requestConfig = YtDlpAudioSourceManager.configureHttpRequests(RequestConfig.DEFAULT);
            try (CloseableHttpClient client = YtDlpAudioSourceManager.configureHttpBuilder(
                    HttpClientBuilder.create(), List.of(
                            new BasicHeader("User-Agent", "resolver-agent"),
                            new BasicHeader("Referer", "https://www.youtube.com/")))
                    .setDefaultRequestConfig(requestConfig).build())
            {
                for (String path : List.of("/manifest.m3u8", "/quality.m3u8", "/segment.ts"))
                {
                    try (CloseableHttpResponse ignored = client.execute(
                            new HttpGet("http://127.0.0.1:" + server.getLocalPort() + path)))
                    {
                    }
                }
            }

            await(responses);
            assertEquals(3, captured.size());
            for (String request : captured)
            {
                assertTrue(request.contains("User-Agent: resolver-agent"));
                assertTrue(request.contains("Referer: https://www.youtube.com/"));
            }
        }
        finally
        {
            responder.shutdownNow();
        }
    }

    private static void serveRedirectProbe(ServerSocket server, int port, AtomicInteger requests)
    {
        try
        {
            try (Socket first = server.accept())
            {
                requests.incrementAndGet();
                consumeRequest(first);
                writeResponse(first, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + port
                        + "/redirect-target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            }

            server.setSoTimeout(1_000);
            try (Socket second = server.accept())
            {
                requests.incrementAndGet();
                consumeRequest(second);
                writeResponse(second, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            }
            catch (SocketTimeoutException ignored)
            {
                // Expected: redirect handling is disabled, so no second request arrives.
            }
        }
        catch (IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }

    private static void consumeRequest(Socket socket) throws IOException
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
        for (String line; (line = reader.readLine()) != null && !line.isEmpty(); )
        {
            // Consume headers before replying.
        }
    }

    private static void serveHeaderProbes(ServerSocket server, List<String> captured)
    {
        try
        {
            for (int i = 0; i < 3; i++)
            {
                try (Socket socket = server.accept())
                {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder request = new StringBuilder();
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty(); )
                        request.append(line).append('\n');
                    captured.add(request.toString());
                    writeResponse(socket,
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                }
            }
        }
        catch (IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }

    private static void writeResponse(Socket socket, String response) throws IOException
    {
        OutputStream output = socket.getOutputStream();
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void await(Future<?> response) throws Exception
    {
        try
        {
            response.get(5, TimeUnit.SECONDS);
        }
        catch (ExecutionException ex)
        {
            if (ex.getCause() instanceof RuntimeException runtime)
                throw runtime;
            throw ex;
        }
    }
}
