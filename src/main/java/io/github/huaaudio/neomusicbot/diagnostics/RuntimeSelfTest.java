package io.github.huaaudio.neomusicbot.diagnostics;

import club.minnced.discord.jdave.ffi.LibDave;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Offline native/codec check. The local source exists only for bundled test tones. */
public final class RuntimeSelfTest
{
    private RuntimeSelfTest() { }

    public static int run(PrintStream output)
    {
        try
        {
            if(LibDave.getMaxSupportedProtocolVersion() <= 0)
                throw new IllegalStateException("No supported DAVE protocol");
            output.println("native.dave=passed");
            byte[] opus = VoiceRuntimeSelfTest.verifyOpus();
            output.println("native.opus=passed");
            VoiceRuntimeSelfTest.verifyTransportEncryption(opus);
            output.println("crypto.rtp=passed");
            for(String format : new String[]{"m4a", "ogg", "mp3"})
            {
                int frames = decodeTone(format);
                output.println("audio." + format + "=passed frames=" + frames);
            }
            output.println("self-test=passed");
            output.println("Discord voice and online media were not tested.");
            return 0;
        }
        catch(Exception | LinkageError failure)
        {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            output.println("self-test=failed reason=" + failure.getClass().getSimpleName());
            return 1;
        }
    }

    private static int decodeTone(String extension) throws Exception
    {
        Path file = Files.createTempFile("neomusicbot-self-test-", "." + extension);
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        var player = manager.createPlayer();
        try
        {
            try(var input = RuntimeSelfTest.class.getResourceAsStream("/self-test/tone." + extension))
            {
                if(input == null) throw new IOException("Bundled tone is missing");
                Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            manager.registerSourceManager(new LocalAudioSourceManager());
            CompletableFuture<AudioTrack> loaded = new CompletableFuture<>();
            manager.loadItem(file.toString(), new AudioLoadResultHandler()
            {
                @Override public void trackLoaded(AudioTrack track) { loaded.complete(track); }
                @Override public void playlistLoaded(AudioPlaylist playlist)
                { loaded.completeExceptionally(new IOException("Unexpected playlist")); }
                @Override public void noMatches()
                { loaded.completeExceptionally(new IOException("Tone was not recognized")); }
                @Override public void loadFailed(FriendlyException exception)
                { loaded.completeExceptionally(exception); }
            });
            player.playTrack(loaded.get(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int frames = 0;
            while(player.getPlayingTrack() != null && System.nanoTime() < deadline)
            {
                var frame = player.provide();
                if(frame != null && !frame.isTerminator() && frame.getDataLength() > 0) frames++;
                else Thread.sleep(5);
            }
            if(frames < 10 || player.getPlayingTrack() != null)
                throw new IOException("Tone did not decode completely");
            return frames;
        }
        finally
        {
            player.destroy();
            manager.shutdown();
            Files.deleteIfExists(file);
        }
    }
}
