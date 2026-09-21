package io.github.huaaudio.neomusicbot.playlist;

import io.github.huaaudio.neomusicbot.BotConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistStorageTest
{
    @TempDir Path directory;

    @Test
    void firstCreateBuildsTheConfiguredDirectory() throws Exception
    {
        Path folder = directory.resolve("nested/playlists");
        loader(folder).createPlaylist("first");
        assertTrue(Files.isRegularFile(folder.resolve("first.txt")));
    }

    @Test
    void listingAnUnavailableDirectoryDoesNotThrow() throws Exception
    {
        Path file = Files.writeString(directory.resolve("not-a-directory"), "keep");
        assertEquals(List.of(), loader(file).getPlaylistNames());
        assertEquals("keep", Files.readString(file));
    }

    @Test
    void directoriesWithTxtSuffixAreNotPlaylists() throws Exception
    {
        Files.createDirectory(directory.resolve("directory.txt"));
        Files.writeString(directory.resolve("real.txt"), "https://youtu.be/YE7VzlLtp-4");
        assertEquals(List.of("real"), loader(directory).getPlaylistNames());
    }

    @Test
    void storageBoundaryRejectsTraversal() throws Exception
    {
        Path folder = Files.createDirectory(directory.resolve("playlists"));
        Path outside = Files.writeString(directory.resolve("outside.txt"), "keep");
        assertThrows(IOException.class, () -> loader(folder).writePlaylist("../outside", "overwritten"));
        assertEquals("keep", Files.readString(outside));
        assertThrows(IOException.class, () -> loader(folder).deletePlaylist("../outside"));
        assertTrue(Files.exists(outside));
    }

    @Test
    void concurrentAppendsKeepEveryEntryExactlyOnce() throws Exception
    {
        PlaylistLoader loader = loader(directory);
        loader.createPlaylist("shared");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writes = new ArrayList<>();
        try(var executor = Executors.newFixedThreadPool(8))
        {
            for(int i = 0; i < 40; i++)
            {
                String entry = "https://example.invalid/" + i;
                writes.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    loader.appendPlaylist("shared", List.of(entry));
                    return null;
                }));
            }
            start.countDown();
            for(Future<?> write : writes) write.get(15, TimeUnit.SECONDS);
        }
        List<String> lines = Files.readAllLines(directory.resolve("shared.txt"));
        assertEquals(40, lines.size());
        assertEquals(40, new HashSet<>(lines).size());
    }

    @Test
    void missingOrInvalidAppendCannotRecreateOrPartiallyRewrite() throws Exception
    {
        PlaylistLoader loader = loader(directory);
        assertThrows(IOException.class, () -> loader.appendPlaylist("missing", List.of("entry")));
        assertFalse(Files.exists(directory.resolve("missing.txt")));
        Path file = Files.writeString(directory.resolve("existing.txt"), "#保留\nold\n");
        assertThrows(IOException.class, () -> loader.appendPlaylist("existing", List.of("new", "bad\nentry")));
        assertEquals("#保留\nold\n", Files.readString(file));
        try(var files = Files.list(directory))
        {
            assertEquals(List.of(file), files.toList(), "A failed update must not leave temporary files");
        }
    }

    static PlaylistLoader loader(Path folder)
    {
        return new PlaylistLoader(new BotConfig(null) {
            @Override public String getPlaylistsFolder() { return folder.toString(); }
        });
    }
}
