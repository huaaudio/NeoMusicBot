package com.jagrosh.jmusicbot.playlist;

import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
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
                    if (!method.getName().equals("loadItemOrdered"))
                        throw new UnsupportedOperationException(method.getName());
                    load.accept((AudioLoadResultHandler) arguments[2]);
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
