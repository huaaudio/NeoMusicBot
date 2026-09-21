import io.github.huaaudio.neomusicbot.audio.BilibiliAudioSourceManager;
import io.github.huaaudio.neomusicbot.audio.YtDlpAudioSourceManager;
import io.github.huaaudio.neomusicbot.audio.YtDlpAudioTrack;
import io.github.huaaudio.neomusicbot.audio.YoutubeFallbackAudioSourceManager;
import io.github.huaaudio.neomusicbot.audio.media.MediaSource;
import io.github.huaaudio.neomusicbot.audio.media.YtDlpConfiguration;
import io.github.huaaudio.neomusicbot.audio.media.YtDlpMediaResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit online smoke check of the shipped source adapters, without Discord or cookies. */
public final class OnlineMediaProbe
{
    public static void main(String[] args) throws Exception
    {
        if(args.length != 2 && args.length != 4)
            throw new IllegalArgumentException("Arguments: yt-dlp deno [plugin-directory provider-home]");
        // Extractor/library errors can contain signed stream URLs. Only emit our fixed status fields.
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.OFF);
        boolean provider = args.length == 4;
        var configuration = new YtDlpConfiguration(args[0], args[1], null, null,
                provider ? Path.of(args[2]) : null, provider ? Path.of(args[3]) : null,
                true, provider, Duration.ofSeconds(30), Duration.ofSeconds(60));
        var manager = new DefaultAudioPlayerManager();
        try(var resolver = new YtDlpMediaResolver(configuration))
        {
            // Same adapters and primary/fallback composition as PlayerManager.init().
            var fallback = new YtDlpAudioSourceManager("youtube-yt-dlp", MediaSource.YOUTUBE, resolver, manager);
            manager.registerSourceManager(new YoutubeFallbackAudioSourceManager(
                    new YoutubeAudioSourceManager(true), fallback, resolver, 10));
            manager.registerSourceManager(fallback);
            manager.registerSourceManager(new BilibiliAudioSourceManager(resolver, manager));
            manager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
            boolean passed = true;
            passed &= probe(manager, "bilibili", "https://www.bilibili.com/video/BV13x41117TL", null);
            passed &= probe(manager, "bilibili-part2", "https://www.bilibili.com/video/BV1bK411W797?p=2", 2);
            passed &= probe(manager, "youtube", "https://www.youtube.com/watch?v=YE7VzlLtp-4", null);
            passed &= probe(manager, "soundcloud", "https://soundcloud.com/forss/flickermood", null);
            System.out.println("PROBE online=" + (passed ? "passed" : "failed"));
            System.out.println("PROBE discord.voice=not-tested");
            if(!passed) throw new IllegalStateException("Online media probe failed; no raw extractor output is printed");
        }
        finally { manager.shutdown(); }
    }

    private static boolean probe(DefaultAudioPlayerManager manager, String name, String url, Integer page)
    {
        var player = manager.createPlayer();
        AtomicBoolean playbackFailed = new AtomicBoolean();
        player.addListener(new AudioEventAdapter() {
            @Override public void onTrackException(com.sedmelluq.discord.lavaplayer.player.AudioPlayer p,
                                                    AudioTrack track, FriendlyException error)
            { playbackFailed.set(true); }
        });
        java.util.concurrent.Future<Void> loading = null;
        try
        {
            CompletableFuture<AudioTrack> loaded = new CompletableFuture<>();
            loading = manager.loadItem(url, new AudioLoadResultHandler() {
                @Override public void trackLoaded(AudioTrack track) { loaded.complete(track); }
                @Override public void playlistLoaded(AudioPlaylist list)
                { loaded.completeExceptionally(new IllegalStateException("Unexpected playlist")); }
                @Override public void noMatches()
                { loaded.completeExceptionally(new IllegalStateException("No matching track")); }
                @Override public void loadFailed(FriendlyException error) { loaded.completeExceptionally(error); }
            });
            AudioTrack track = loaded.get(90, TimeUnit.SECONDS);
            if(page != null && (!(track instanceof YtDlpAudioTrack media)
                    || !page.equals(media.getMediaKey().subIndex())))
                throw new IllegalStateException("Wrong Bilibili page");
            player.playTrack(track);
            int frames = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while(frames < 10 && !playbackFailed.get() && System.nanoTime() < deadline)
            {
                var frame = player.provide();
                if(frame != null && !frame.isTerminator() && frame.getDataLength() > 0) frames++;
                else Thread.sleep(10);
            }
            boolean passed = frames >= 10 && !playbackFailed.get();
            System.out.println("PROBE " + name + "=" + (passed ? "passed" : "failed") + " frames=" + frames);
            return passed;
        }
        catch(Exception failure)
        {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("PROBE " + name + "=failed reason=" + failure.getClass().getSimpleName());
            return false;
        }
        finally
        {
            if(loading != null) loading.cancel(true);
            player.destroy();
        }
    }
}
