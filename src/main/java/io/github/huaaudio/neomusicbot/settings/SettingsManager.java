/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh (john.a.grosh@gmail.com)
 */
/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package io.github.huaaudio.neomusicbot.settings;

import io.github.huaaudio.neomusicbot.utils.OtherUtil;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.dv8tion.jda.api.entities.Guild;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local per-guild settings store. Writes are coalesced onto one writer and use
 * a flushed temporary file, atomic replacement, and a last-known-good backup.
 */
public class SettingsManager implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger("Settings");
    private static final String SETTINGS_FILE = "serversettings.json";

    private final Map<Long, Settings> settings = new ConcurrentHashMap<>();
    private final Path path;
    private final Path temporaryPath;
    private final Path backupPath;
    private final ExecutorService writer;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean writeQueued = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean primaryHealthy;
    private volatile Thread writerThread;

    public SettingsManager()
    {
        this(OtherUtil.getPath(SETTINGS_FILE));
    }

    SettingsManager(Path path)
    {
        this.path = path.toAbsolutePath().normalize();
        this.temporaryPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
        this.backupPath = this.path.resolveSibling(this.path.getFileName() + ".bak");
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "settings-writer");
            thread.setDaemon(false);
            writerThread = thread;
            return thread;
        });
        load();
    }

    private void load()
    {
        boolean primaryMissing = false;
        try
        {
            loadFrom(path);
            primaryHealthy = true;
            LOG.info("serversettings.json loaded from {}", path);
            return;
        }
        catch(NoSuchFileException ex)
        {
            primaryMissing = true;
            LOG.info("serversettings.json will be created in {}", path);
        }
        catch(IOException | JSONException | IllegalArgumentException ex)
        {
            LOG.warn("Primary server settings are unreadable ({}); trying the backup", ex.getClass().getSimpleName());
        }

        try
        {
            loadFrom(backupPath);
            primaryHealthy = false;
            LOG.warn("Recovered server settings from {}", backupPath);
            persist(snapshot());
            return;
        }
        catch(NoSuchFileException ex)
        {
            if(!primaryMissing)
                throw new IllegalStateException("Server settings are invalid and no backup is available. "
                        + "The original file was preserved; repair it before restarting.");
        }
        catch(IOException | JSONException | IllegalArgumentException ex)
        {
            throw new IllegalStateException("Server settings could not be recovered ("
                    + ex.getClass().getSimpleName() + "). Existing files were preserved; repair them before restarting.");
        }

        settings.clear();
        try
        {
            persist(new JSONObject());
        }
        catch(IOException ex)
        {
            throw new IllegalStateException("Cannot create the initial server settings file ("
                    + ex.getClass().getSimpleName() + "). Check the data directory permissions.");
        }
    }

    private void loadFrom(Path source) throws IOException, JSONException, NumberFormatException
    {
        JSONObject loaded = new JSONObject(Files.readString(source, StandardCharsets.UTF_8));
        Map<Long, Settings> parsed = new ConcurrentHashMap<>();
        for(String id : loaded.keySet())
            parsed.put(Long.parseLong(id), parseGuild(loaded.getJSONObject(id)));
        settings.clear();
        settings.putAll(parsed);
    }

    private Settings parseGuild(JSONObject value)
    {
        if(!value.has("repeat_mode") && value.optBoolean("repeat", false))
            value.put("repeat_mode", RepeatMode.ALL);

        return new Settings(this,
                value.has("text_channel_id") ? value.getString("text_channel_id") : null,
                value.has("voice_channel_id") ? value.getString("voice_channel_id") : null,
                value.has("dj_role_id") ? value.getString("dj_role_id") : null,
                parseVolume(value),
                value.has("default_playlist") ? value.getString("default_playlist") : null,
                value.has("repeat_mode") ? value.getEnum(RepeatMode.class, "repeat_mode") : RepeatMode.OFF,
                value.has("skip_ratio") ? value.getDouble("skip_ratio") : -1,
                value.has("queue_type") ? value.getEnum(QueueType.class, "queue_type") : QueueType.FAIR);
    }

    private static int parseVolume(JSONObject value)
    {
        if(!value.has("volume")) return 100;
        try
        {
            // optInt/getInt can silently default, truncate fractions, or wrap
            // a large integer. Keep compatible numeric strings, without loss.
            return new BigDecimal(value.get("volume").toString()).intValueExact();
        }
        catch(NumberFormatException | ArithmeticException invalid)
        {
            throw new IllegalArgumentException("volume must be an integer from 0 to 150");
        }
    }

    public Settings getSettings(Guild guild)
    {
        if(guild == null)
            throw new IllegalArgumentException("guild cannot be null");
        return getSettings(guild.getIdLong());
    }

    public Settings getSettings(long guildId)
    {
        return settings.computeIfAbsent(guildId, ignored -> createDefaultSettings());
    }

    private Settings createDefaultSettings()
    {
        return new Settings(this, 0, 0, 0, 100, null, RepeatMode.OFF, -1, QueueType.FAIR);
    }

    void updateSettings(Runnable update)
    {
        Objects.requireNonNull(update, "update");
        synchronized(lifecycleLock)
        {
            if(closed.get())
            {
                LOG.warn("Ignoring a settings update after the settings store was closed");
                return;
            }
            update.run();
            dirty.set(true);
            enqueueWriterLocked();
        }
    }

    private void enqueueWriterLocked()
    {
        if(writeQueued.compareAndSet(false, true))
            writer.execute(this::drainWrites);
    }

    private void drainWrites()
    {
        boolean writeFailed = false;
        try
        {
            do
            {
                dirty.set(false);
                try
                {
                    persist(snapshot());
                }
                catch(IOException ex)
                {
                    LOG.warn("Failed to write server settings", ex);
                    dirty.set(true);
                    writeFailed = true;
                    break;
                }
            }
            while(dirty.get());
        }
        finally
        {
            try
            {
                beforeDrainRelease();
            }
            catch(RuntimeException ex)
            {
                LOG.warn("Settings writer release hook failed", ex);
            }
            synchronized(lifecycleLock)
            {
                writeQueued.set(false);
                if(dirty.get() && !closed.get() && !writeFailed)
                    enqueueWriterLocked();
            }
        }
    }

    /** Package hook used by deterministic lifecycle tests. */
    void beforeDrainRelease()
    {
    }

    boolean isClosed()
    {
        return closed.get();
    }

    private void flushDirtyOnClose()
    {
        if(!dirty.getAndSet(false))
            return;
        try
        {
            persist(snapshot());
        }
        catch(IOException ex)
        {
            dirty.set(true);
            LOG.warn("Failed to flush final server settings during shutdown", ex);
        }
    }

    private JSONObject snapshot()
    {
        JSONObject root = new JSONObject();
        settings.forEach((guildId, setting) -> {
            JSONObject value = new JSONObject();
            if(setting.textId != 0)
                value.put("text_channel_id", Long.toString(setting.textId));
            if(setting.voiceId != 0)
                value.put("voice_channel_id", Long.toString(setting.voiceId));
            if(setting.roleId != 0)
                value.put("dj_role_id", Long.toString(setting.roleId));
            if(setting.getVolume() != 100)
                value.put("volume", setting.getVolume());
            if(setting.getDefaultPlaylist() != null)
                value.put("default_playlist", setting.getDefaultPlaylist());
            if(setting.getRepeatMode() != RepeatMode.OFF)
                value.put("repeat_mode", setting.getRepeatMode());
            if(setting.getSkipRatio() != -1)
                value.put("skip_ratio", setting.getSkipRatio());
            if(setting.getQueueType() != QueueType.FAIR)
                value.put("queue_type", setting.getQueueType().name());
            root.put(Long.toString(guildId), value);
        });
        return root;
    }

    private void persist(JSONObject root) throws IOException
    {
        Path parent = path.getParent();
        if(parent != null)
            Files.createDirectories(parent);
        byte[] bytes = root.toString(4).getBytes(StandardCharsets.UTF_8);

        try(FileChannel channel = FileChannel.open(temporaryPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
        {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining())
                channel.write(buffer);
            channel.force(true);
        }

        if(primaryHealthy && Files.isRegularFile(path))
        {
            Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            try(FileChannel backup = FileChannel.open(backupPath, StandardOpenOption.WRITE))
            {
                backup.force(true);
            }
        }

        try
        {
            Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch(AtomicMoveNotSupportedException ex)
        {
            LOG.warn("Atomic settings replacement is not supported by {}; using same-filesystem replacement", path);
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
        primaryHealthy = true;
        forceDirectory(parent);
    }

    private static void forceDirectory(Path directory)
    {
        if(directory == null)
            return;
        try(FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch(IOException | UnsupportedOperationException ex)
        {
            LOG.debug("Directory fsync is not supported for {}", directory, ex);
        }
    }

    public boolean flush(Duration timeout)
    {
        Future<?> barrier;
        synchronized(lifecycleLock)
        {
            if(closed.get())
                return !dirty.get() && writer.isTerminated();
            if(Thread.currentThread() == writerThread)
                return !dirty.get() && !writeQueued.get();
            if(dirty.get())
                enqueueWriterLocked();
            barrier = writer.submit(() -> { });
        }
        try
        {
            barrier.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return !dirty.get() && !writeQueued.get();
        }
        catch(Exception ex)
        {
            LOG.warn("Timed out while flushing server settings", ex);
            return false;
        }
    }

    @Override
    public void close()
    {
        synchronized(lifecycleLock)
        {
            if(closed.compareAndSet(false, true))
            {
                // This barrier runs after every already-submitted drain. It
                // catches an update that arrived after a drain's last dirty
                // check but before that drain released writeQueued.
                writer.execute(this::flushDirtyOnClose);
                writer.shutdown();
            }
        }

        // A close initiated by the writer must return so the queued final
        // flush can execute; external callers wait for durable completion.
        if(Thread.currentThread() == writerThread)
            return;
        try
        {
            if(!writer.awaitTermination(10, TimeUnit.SECONDS))
                writer.shutdownNow();
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
