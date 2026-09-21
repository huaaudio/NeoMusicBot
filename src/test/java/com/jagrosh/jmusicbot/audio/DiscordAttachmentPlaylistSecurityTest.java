package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class DiscordAttachmentPlaylistSecurityTest
{
    @Test
    public void directAttachmentRegistryContainsNoPlaylistProbes()
    {
        assertFalse(SafeMediaContainerRegistries.directAudio().getAll().stream()
                .anyMatch(SafeMediaContainerRegistries::isPlaylistProbe));
        assertThrows(FriendlyException.class, () ->
                DiscordAttachmentAudioSourceManager.requireDirectAudio(
                        new AudioReference("https://127.0.0.1/private", "malicious")));
    }

    @Test
    public void maliciousM3uCannotTriggerASecondHttpProbe() throws Exception
    {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (ServerSocket entry = bound(loopback); ServerSocket target = bound(loopback))
        {
            target.setSoTimeout(1_000);
            String playlist = "#EXTM3U\n#EXTINF:10,private\nhttp://127.0.0.1:"
                    + target.getLocalPort() + "/metadata\n";
            Future<?> response = responder.submit(() -> serveOnce(entry, playlist));

            DefaultAudioPlayerManager playerManager = new DefaultAudioPlayerManager();
            HttpAudioSourceManager source =
                    new HttpAudioSourceManager(SafeMediaContainerRegistries.directAudio());
            try
            {
                AudioItem item = null;
                try
                {
                    item = source.loadItem(playerManager, new AudioReference(
                            "http://127.0.0.1:" + entry.getLocalPort() + "/attachment.m3u", null));
                }
                catch (FriendlyException ignored)
                {
                    // Unsupported direct-audio input is the expected secure outcome.
                }
                assertFalse(item instanceof AudioReference);
                response.get(5, TimeUnit.SECONDS);
                assertThrows(SocketTimeoutException.class, target::accept);
            }
            finally
            {
                source.shutdown();
                playerManager.shutdown();
            }
        }
        finally
        {
            responder.shutdownNow();
        }
    }

    private static ServerSocket bound(InetAddress address) throws IOException
    {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(address, 0));
        return server;
    }

    private static void serveOnce(ServerSocket server, String body)
    {
        try (Socket socket = server.accept())
        {
            while (socket.getInputStream().read() != -1)
            {
                if (socket.getInputStream().available() == 0)
                    break;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: audio/x-mpegurl\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }
}
