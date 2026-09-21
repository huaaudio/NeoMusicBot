/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 */
/*
 * Copyright 2018 John Grosh (jagrosh).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.huaaudio.neomusicbot.playlist;

import io.github.huaaudio.neomusicbot.BotConfig;
import io.github.huaaudio.neomusicbot.audio.RequestMetadata;
import io.github.huaaudio.neomusicbot.utils.OtherUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PlaylistLoader
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistLoader.class);
    private final BotConfig config;
    
    public PlaylistLoader(BotConfig config)
    {
        this.config = config;
    }
    
    public synchronized List<String> getPlaylistNames()
    {
        try
        {
            Files.createDirectories(folder());
            try(var files = Files.list(folder()))
            {
                return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".txt"))
                        .map(name -> name.substring(0, name.length() - 4))
                        .filter(PlaylistLoader::validFileName)
                        .sorted().toList();
            }
        }
        catch(IOException failure)
        {
            LOG.warn("Could not list local playlists: {}", failure.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }
    
    public void createFolder()
    {
        try
        {
            Files.createDirectories(folder());
        }
        catch(IOException failure)
        {
            LOG.warn("Could not create playlist directory: {}", failure.getClass().getSimpleName());
        }
    }
    
    public boolean folderExists()
    {
        return Files.isDirectory(folder());
    }
    
    public synchronized void createPlaylist(String name) throws IOException
    {
        Path path = playlistPath(name);
        Files.createDirectories(folder());
        Files.createFile(path);
    }
    
    public synchronized void deletePlaylist(String name) throws IOException
    {
        Path path = playlistPath(name);
        requireRegularFile(path);
        Files.delete(path);
    }
    
    public synchronized void writePlaylist(String name, String text) throws IOException
    {
        Path path = playlistPath(name);
        Files.createDirectories(folder());
        if(Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            requireRegularFile(path);
        replaceContents(path, text);
    }

    /** Keep the original file, including comments and shuffle directives, verbatim. */
    public synchronized void appendPlaylist(String name, List<String> items) throws IOException
    {
        Path path = playlistPath(name);
        requireRegularFile(path);
        for(String item : items)
            if(item == null || item.isBlank() || item.contains("\n") || item.contains("\r"))
                throw new IOException("Playlist entries must each occupy one nonempty line");
        if(items.isEmpty())
            return;
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        String separator = original.isEmpty() || original.endsWith("\n") || original.endsWith("\r") ? "" : newline;
        replaceContents(path, original + separator + String.join(newline, items) + newline);
    }

    private Path folder()
    {
        return OtherUtil.getPath(config.getPlaylistsFolder()).toAbsolutePath().normalize();
    }

    private static boolean validFileName(String name)
    {
        // Keep existing Unicode/space-containing names readable, but never interpret a name as a path.
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
                && name.chars().noneMatch(c -> c < 32 || c == 127 || "/\\:*?\"<>|".indexOf(c) >= 0);
    }

    private Path playlistPath(String name) throws IOException
    {
        if(!validFileName(name))
            throw new IOException("Invalid local playlist name");
        return folder().resolve(name + ".txt");
    }

    private static void requireRegularFile(Path path) throws IOException
    {
        if(!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new NoSuchFileException("Local playlist does not exist");
        if(!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Playlist is not a regular file");
    }

    private static void replaceContents(Path path, String text) throws IOException
    {
        Path temporary = Files.createTempFile(path.getParent(), ".playlist-", ".tmp");
        try
        {
            if(Files.exists(path) && Files.getFileAttributeView(path, PosixFileAttributeView.class) != null)
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path));
            try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                ByteBuffer contents = StandardCharsets.UTF_8.encode(text);
                while(contents.hasRemaining()) channel.write(contents);
                channel.force(true);
            }
            // If the filesystem cannot replace atomically, fail without truncating the existing playlist.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }
    
    public synchronized Playlist getPlaylist(String name)
    {
        if(!validFileName(name))
            return null;
        try
        {
            Path path = playlistPath(name);
            requireRegularFile(path);
            boolean[] shuffle = {false};
            List<String> list = new ArrayList<>();
            Files.readAllLines(path, StandardCharsets.UTF_8).forEach(str ->
            {
                String s = str.trim();
                if(s.isEmpty())
                    return;
                if(s.startsWith("#") || s.startsWith("//"))
                {
                    s = s.replaceAll("\\s+", "");
                    if(s.equalsIgnoreCase("#shuffle") || s.equalsIgnoreCase("//shuffle"))
                        shuffle[0]=true;
                }
                else
                    list.add(s);
            });
            if(shuffle[0])
                shuffle(list);
            return new Playlist(name, list, shuffle[0]);
        }
        catch(IOException e)
        {
            return null;
        }
    }
    
    
    private static <T> void shuffle(List<T> list)
    {
        for(int first =0; first<list.size(); first++)
        {
            int second = (int)(Math.random()*list.size());
            T tmp = list.get(first);
            list.set(first, list.get(second));
            list.set(second, tmp);
        }
    }
    
    
    public class Playlist
    {
        private final String name;
        private final List<String> items;
        private final boolean shuffle;
        private final List<AudioTrack> tracks = new LinkedList<>();
        private final List<PlaylistLoadError> errors = new LinkedList<>();
        private boolean loaded = false;
        
        private Playlist(String name, List<String> items, boolean shuffle)
        {
            this.name = name;
            this.items = items;
            this.shuffle = shuffle;
        }
        
        public void loadTracks(AudioPlayerManager manager, Consumer<AudioTrack> consumer, Runnable callback)
        {
            if(loaded)
                return;
            loaded = true;
            if(items.isEmpty())
            {
                if(callback != null)
                    callback.run();
                return;
            }
            for(int i=0; i<items.size(); i++)
            {
                boolean last = i+1 == items.size();
                int index = i;
                manager.loadItemOrdered(name, items.get(i), new AudioLoadResultHandler() 
                {
                    private void done()
                    {
                        if(last)
                        {
                            if(shuffle)
                                shuffleTracks();
                            if(callback != null)
                                callback.run();
                        }
                    }

                    private void acceptTrack(AudioTrack at)
                    {
                        if(config.isTooLong(at))
                            errors.add(new PlaylistLoadError(index, items.get(index), "This track is longer than the allowed maximum"));
                        else
                        {
                            at.setUserData(RequestMetadata.EMPTY);
                            tracks.add(at);
                            consumer.accept(at);
                        }
                    }

                    @Override
                    public void trackLoaded(AudioTrack at)
                    {
                        acceptTrack(at);
                        done();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist ap) 
                    {
                        if(ap.getTracks().isEmpty())
                        {
                            noMatches();
                            return;
                        }
                        if(ap.isSearchResult())
                        {
                            acceptTrack(ap.getTracks().get(0));
                        }
                        else if(ap.getSelectedTrack()!=null)
                        {
                            acceptTrack(ap.getSelectedTrack());
                        }
                        else
                        {
                            List<AudioTrack> loaded = new ArrayList<>(ap.getTracks());
                            if(shuffle)
                                for(int first =0; first<loaded.size(); first++)
                                {
                                    int second = (int)(Math.random()*loaded.size());
                                    AudioTrack tmp = loaded.get(first);
                                    loaded.set(first, loaded.get(second));
                                    loaded.set(second, tmp);
                                }
                            loaded.removeIf(track -> config.isTooLong(track));
                            loaded.forEach(at -> at.setUserData(RequestMetadata.EMPTY));
                            tracks.addAll(loaded);
                            loaded.forEach(at -> consumer.accept(at));
                        }
                        done();
                    }

                    @Override
                    public void noMatches() 
                    {
                        errors.add(new PlaylistLoadError(index, items.get(index), "No matches found."));
                        done();
                    }

                    @Override
                    public void loadFailed(FriendlyException fe) 
                    {
                        errors.add(new PlaylistLoadError(index, items.get(index), "Failed to load track: "+fe.getLocalizedMessage()));
                        done();
                    }
                });
            }
        }
        
        public void shuffleTracks()
        {
            shuffle(tracks);
        }
        
        public String getName()
        {
            return name;
        }

        public List<String> getItems()
        {
            return items;
        }

        public List<AudioTrack> getTracks()
        {
            return tracks;
        }
        
        public List<PlaylistLoadError> getErrors()
        {
            return errors;
        }
    }
    
    public class PlaylistLoadError
    {
        private final int number;
        private final String item;
        private final String reason;
        
        private PlaylistLoadError(int number, String item, String reason)
        {
            this.number = number;
            this.item = item;
            this.reason = reason;
        }
        
        public int getIndex()
        {
            return number;
        }
        
        public String getItem()
        {
            return item;
        }
        
        public String getReason()
        {
            return reason;
        }
    }
}
