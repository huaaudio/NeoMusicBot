package io.github.huaaudio.neomusicbot.playlist;

import io.github.huaaudio.neomusicbot.BotConfig;
import io.github.huaaudio.neomusicbot.audio.RequestMetadata;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistLoaderTest
{
    @TempDir Path directory;

    @Test
    void nestedSearchCompletesExactlyOnceAndUsesTypedMetadata() throws Exception
    {
        PlaylistLoader.Playlist playlist = playlist("ytsearch:example");
        AudioTrack track = track();
        AtomicInteger completions = new AtomicInteger();
        List<AudioTrack> accepted = new ArrayList<>();
        playlist.loadTracks(manager(handler -> handler.playlistLoaded(
                new BasicAudioPlaylist("search", List.of(track), null, true))),
                accepted::add, completions::incrementAndGet);

        assertEquals(List.of(track), accepted);
        assertEquals(1, completions.get());
        assertSame(RequestMetadata.EMPTY, track.getUserData(RequestMetadata.class));
    }

    @Test
    void emptySearchCompletesWithAnErrorInsteadOfThrowing() throws Exception
    {
        PlaylistLoader.Playlist playlist = playlist("ytsearch:missing");
        AtomicInteger completions = new AtomicInteger();
        playlist.loadTracks(manager(handler -> handler.playlistLoaded(
                new BasicAudioPlaylist("empty", List.of(), null, true))),
                track -> fail("No track should be returned"), completions::incrementAndGet);
        assertEquals(1, completions.get());
        assertEquals(1, playlist.getErrors().size());
    }

    @Test
    void emptyLocalPlaylistStillCompletes() throws Exception
    {
        AtomicInteger completions = new AtomicInteger();
        playlist("").loadTracks(manager(handler -> fail("Nothing to load")),
                track -> fail("Nothing to play"), completions::incrementAndGet);
        assertEquals(1, completions.get());
    }

    @Test
    void rejectedManagerCompletesEveryEntryInsteadOfOrphaningThePlaylist() throws Exception
    {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.shutdown();
        // Force the real manager's rejection branch deterministically. Its eager
        // queue policy may otherwise silently requeue work after shutdown.
        var poolField = DefaultAudioPlayerManager.class.getDeclaredField("trackInfoExecutorService");
        poolField.setAccessible(true);
        ((ThreadPoolExecutor) poolField.get(manager)).setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        PlaylistLoader.Playlist playlist = playlist("first\nsecond\nthird");
        AtomicInteger completions = new AtomicInteger();
        playlist.loadTracks(manager, track -> fail("Stopped manager cannot load tracks"),
                completions::incrementAndGet);
        assertEquals(1, completions.get());
        assertEquals(3, playlist.getErrors().size());
    }

    @Test
    void realManagerStillCompletesWhenTheTrackConsumerThrows() throws Exception
    {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager() {
            @Override public AudioItem loadItemSync(AudioReference reference) { return track(); }
        };
        try
        {
            PlaylistLoader.Playlist playlist = playlist("track");
            CountDownLatch completed = new CountDownLatch(1);
            playlist.loadTracks(manager, track -> { throw new IllegalStateException("consumer failed"); },
                    completed::countDown);
            assertTrue(completed.await(3, TimeUnit.SECONDS), "Consumer failure must still release the load token");
            assertEquals(1, playlist.getErrors().size());
            assertTrue(playlist.getTracks().isEmpty());
        }
        finally { manager.shutdown(); }
    }

    @Test
    void failedNestedTrackDoesNotPreventRemainingTracksOrCompletion() throws Exception
    {
        PlaylistLoader.Playlist playlist = playlist("nested");
        AudioTrack first = track(), second = track();
        List<AudioTrack> accepted = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        assertDoesNotThrow(() -> playlist.loadTracks(manager(handler -> handler.playlistLoaded(
                new BasicAudioPlaylist("nested", List.of(first, second), null, false))), track -> {
            if(track == first) throw new IllegalStateException("consumer failed");
            accepted.add(track);
        }, completions::incrementAndGet));
        assertEquals(List.of(second), accepted);
        assertEquals(List.of(second), playlist.getTracks());
        assertEquals(1, playlist.getErrors().size());
        assertEquals(1, completions.get());
    }

    @Test
    void manySynchronousResultsCompleteWithoutRecursiveStackGrowth() throws Exception
    {
        PlaylistLoader.Playlist playlist = playlist("missing\n".repeat(10_000));
        AtomicInteger completions = new AtomicInteger();
        playlist.loadTracks(manager(AudioLoadResultHandler::noMatches), track -> fail("No track expected"),
                completions::incrementAndGet);
        assertEquals(10_000, playlist.getErrors().size());
        assertEquals(1, completions.get());
    }

    @Test
    void duplicateResultCallbacksDoNotQueueTheSameTrackTwice() throws Exception
    {
        PlaylistLoader.Playlist playlist = playlist("track");
        AudioTrack track = track();
        List<AudioTrack> accepted = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        playlist.loadTracks(manager(handler -> {
            handler.trackLoaded(track);
            handler.trackLoaded(track);
        }), accepted::add, completions::incrementAndGet);
        assertEquals(List.of(track), accepted);
        assertEquals(1, completions.get());
    }

    @Test
    void asynchronousEntriesRemainOrderedAndPreserveTheSelectedPart() throws Exception
    {
        PlaylistLoader.Playlist playlist = playlist("multipart\nlast");
        List<AudioLoadResultHandler> pending = new ArrayList<>();
        List<AudioTrack> accepted = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        AudioTrack firstPart = track(), selectedPart = track(), last = track();
        playlist.loadTracks(manager(pending::add), accepted::add, completions::incrementAndGet);
        assertEquals(1, pending.size());
        pending.get(0).playlistLoaded(new BasicAudioPlaylist("multipart",
                List.of(firstPart, selectedPart), selectedPart, false));
        assertEquals(List.of(selectedPart), accepted);
        assertEquals(2, pending.size());
        assertEquals(0, completions.get());
        pending.get(1).trackLoaded(last);
        assertEquals(List.of(selectedPart, last), accepted);
        assertEquals(List.of(selectedPart, last), playlist.getTracks());
        assertEquals(1, completions.get());
    }

    private PlaylistLoader.Playlist playlist(String contents) throws Exception
    {
        Files.writeString(directory.resolve("test.txt"), contents);
        return new PlaylistLoader(new BotConfig(null)
        {
            @Override public String getPlaylistsFolder() { return directory.toString(); }
            @Override public boolean isTooLong(AudioTrack track) { return false; }
        }).getPlaylist("test");
    }

    private static AudioPlayerManager manager(Consumer<AudioLoadResultHandler> load)
    {
        return (AudioPlayerManager) Proxy.newProxyInstance(AudioPlayerManager.class.getClassLoader(),
                new Class<?>[]{AudioPlayerManager.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("loadItemOrdered") && !method.getName().equals("loadItem"))
                        throw new UnsupportedOperationException(method.getName());
                    load.accept((AudioLoadResultHandler) arguments[arguments.length - 1]);
                    return CompletableFuture.completedFuture(null);
                });
    }

    private static AudioTrack track()
    {
        return new BaseAudioTrack(new AudioTrackInfo(
                "title", "author", 1_000L, "id", false, "https://example.invalid/audio"))
        {
            @Override public void process(LocalAudioTrackExecutor executor) {}
        };
    }
}
