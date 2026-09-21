/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh (jagrosh)
 */
/*
 * Copyright 2018 John Grosh (jagrosh)
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
package io.github.huaaudio.neomusicbot;

import io.github.huaaudio.neomusicbot.entities.Prompt;
import io.github.huaaudio.neomusicbot.utils.OtherUtil;
import io.github.huaaudio.neomusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.parser.ConfigDocumentFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;

/** Runtime configuration with explicit environment overrides for secrets. */
public class BotConfig
{
    private static final String CONTEXT = "Config";
    private static final String START_TOKEN = "/// START OF NEOMUSICBOT CONFIG ///";
    private static final String END_TOKEN = "/// END OF NEOMUSICBOT CONFIG ///";
    private static final String TOKEN_PLACEHOLDER = "BOT_TOKEN_HERE";
    private static final int MAX_TOKEN_FILE_BYTES = 4096;

    private final Prompt prompt;
    private Path path;
    private String token;
    private String playlistsFolder;
    private String logLevel;
    private String successEmoji;
    private String warningEmoji;
    private String errorEmoji;
    private boolean stayInChannel;
    private boolean songInGame;
    private boolean updateAlerts;
    private long owner;
    private long maxSeconds;
    private long aloneTimeUntilStop;
    private int maxYTPlaylistPages;
    private double skipRatio;
    private OnlineStatus status;
    private Activity game;
    private boolean valid;
    private boolean tokenFromEnvironment;

    public BotConfig(Prompt prompt)
    {
        this.prompt = prompt;
    }

    public void load()
    {
        valid = false;
        tokenFromEnvironment = false;
        path = getConfigPath();
        try
        {
            Config defaults = ConfigFactory.parseResources("reference.conf");
            Config file = Files.isRegularFile(path)
                    ? ConfigFactory.parseFile(path.toFile()) : ConfigFactory.empty();
            Config config = file.withFallback(defaults).resolve();

            token = resolveToken(config);
            owner = config.getLong("owner");
            successEmoji = config.getString("success");
            warningEmoji = config.getString("warning");
            errorEmoji = config.getString("error");
            game = OtherUtil.parseGame(config.getString("game"));
            status = OtherUtil.parseStatus(config.getString("status"));
            stayInChannel = config.getBoolean("stayinchannel");
            songInGame = config.getBoolean("songinstatus");
            updateAlerts = config.getBoolean("updatealerts");
            logLevel = config.getString("loglevel");
            maxSeconds = config.getLong("maxtime");
            maxYTPlaylistPages = config.getInt("maxytplaylistpages");
            aloneTimeUntilStop = config.getLong("alonetimeuntilstop");
            playlistsFolder = config.getString("playlistsfolder");
            skipRatio = config.getDouble("skipratio");
            if(!Double.isFinite(skipRatio) || skipRatio < 0 || skipRatio > 1)
                throw new IllegalArgumentException("skipratio must be a finite number from 0 to 1");

            boolean write = false;
            if(token == null || token.isBlank() || TOKEN_PLACEHOLDER.equalsIgnoreCase(token))
            {
                token = prompt.prompt("Please provide a Discord bot token. For unattended use, set "
                        + "NEOMUSICBOT_DISCORD_TOKEN_FILE.\nBot Token: ");
                if(token == null || token.isBlank())
                {
                    prompt.alert(Prompt.Level.WARNING, CONTEXT,
                            "No token was provided. Exiting.\n\nConfig Location: " + path);
                    return;
                }
                token = normalizeToken(token);
                write = !tokenFromEnvironment;
            }

            if(owner <= 0)
            {
                try
                {
                    owner = Long.parseLong(prompt.prompt("Owner ID is missing or invalid.\nOwner User ID: "));
                }
                catch(NumberFormatException | NullPointerException ex)
                {
                    owner = 0;
                }
                if(owner <= 0)
                {
                    prompt.alert(Prompt.Level.ERROR, CONTEXT,
                            "Invalid owner ID. Exiting.\n\nConfig Location: " + path);
                    return;
                }
                write = true;
            }

            if(write)
                writeToFile();
            valid = true;
        }
        catch(ConfigException | IOException | IllegalArgumentException ex)
        {
            prompt.alert(Prompt.Level.ERROR, CONTEXT,
                    "Could not load configuration (" + ex.getClass().getSimpleName()
                            + "). Check HOCON syntax, required values, token sources and file access."
                            + "\n\nConfig Location: " + path.toAbsolutePath());
        }
    }

    private String resolveToken(Config config) throws IOException
    {
        String direct = environment("NEOMUSICBOT_DISCORD_TOKEN");
        String tokenFile = environment("NEOMUSICBOT_DISCORD_TOKEN_FILE");
        if(direct != null && tokenFile != null)
            throw new IllegalArgumentException(
                    "Set only one of NEOMUSICBOT_DISCORD_TOKEN and NEOMUSICBOT_DISCORD_TOKEN_FILE");

        String configured = config.hasPath("token") ? config.getString("token") : TOKEN_PLACEHOLDER;
        boolean configuredSecret = configured != null && !configured.isBlank()
                && !TOKEN_PLACEHOLDER.equalsIgnoreCase(configured);
        if((direct != null || tokenFile != null) && configuredSecret)
            throw new IllegalArgumentException(
                    "Discord token is configured in both the config file and the environment; keep one source");

        if(direct != null)
        {
            tokenFromEnvironment = true;
            return normalizeToken(direct);
        }
        if(tokenFile != null)
        {
            tokenFromEnvironment = true;
            Path source = Path.of(tokenFile).toAbsolutePath().normalize();
            if(!Files.isRegularFile(source) || !Files.isReadable(source))
                throw new IOException("NEOMUSICBOT_DISCORD_TOKEN_FILE is not a readable regular file");
            if(Files.size(source) > MAX_TOKEN_FILE_BYTES)
                throw new IOException("NEOMUSICBOT_DISCORD_TOKEN_FILE is unexpectedly large");
            return normalizeToken(Files.readString(source, StandardCharsets.UTF_8));
        }
        return normalizeToken(configured);
    }

    private static String normalizeToken(String value)
    {
        if(value == null)
            return null;
        String normalized = value.strip();
        if(normalized.indexOf('\0') >= 0 || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Discord token must contain exactly one line");
        return normalized;
    }

    private void writeToFile() throws IOException
    {
        String persistedToken = tokenFromEnvironment ? TOKEN_PLACEHOLDER : token;
        String original = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8)
                : loadDefaultConfig();
        String updated = ConfigDocumentFactory.parseString(original)
                .withValue("token", ConfigValueFactory.fromAnyRef(persistedToken))
                .withValue("owner", ConfigValueFactory.fromAnyRef(owner)).render();
        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.getFileAttributeView(parent, PosixFileAttributeView.class) == null
                ? Files.createTempFile(parent, ".neomusicbot-config-", ".tmp")
                : Files.createTempFile(parent, ".neomusicbot-config-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try
        {
            try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try
            {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch(AtomicMoveNotSupportedException ex)
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    static String loadDefaultConfig()
    {
        try(InputStream stream = BotConfig.class.getResourceAsStream("/reference.conf"))
        {
            if(stream == null)
                return "token = " + TOKEN_PLACEHOLDER + System.lineSeparator() + "owner = 0 // OWNER ID";
            String original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = original.indexOf(START_TOKEN);
            int end = original.indexOf(END_TOKEN);
            if(start < 0 || end <= start)
                throw new IllegalStateException("reference.conf is missing configuration boundary markers");
            return original.substring(start + START_TOKEN.length(), end).trim();
        }
        catch(IOException ex)
        {
            throw new IllegalStateException("Could not read the default configuration", ex);
        }
    }

    static Path getConfigPath()
    {
        String environment = environment("NEOMUSICBOT_CONFIG");
        if(environment != null)
            return Path.of(environment).toAbsolutePath().normalize();
        String configured = System.getProperty("config.file",
                System.getProperty("config", "config.txt"));
        return OtherUtil.getPath(configured).toAbsolutePath().normalize();
    }

    public static boolean writeDefaultConfig()
    {
        Prompt prompt = new Prompt(null, null, true, true);
        Path path = getConfigPath();
        try
        {
            Path parent = path.getParent();
            if(parent != null)
                Files.createDirectories(parent);
            Files.writeString(path, loadDefaultConfig(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            prompt.alert(Prompt.Level.INFO, "NeoMusicBot Config",
                    "Wrote the default config to " + path);
            return true;
        }
        catch(FileAlreadyExistsException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot Config",
                    "Configuration already exists; nothing was changed. Choose another config path "
                            + "to generate a new template: " + path);
            return false;
        }
        catch(Exception ex)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot Config",
                    "Could not write the default config (" + ex.getClass().getSimpleName() + ").");
            return false;
        }
    }

    private static String environment(String name)
    {
        String value = BotEnvironment.value(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    public boolean isValid() { return valid; }
    public String getConfigLocation() { return path.toString(); }
    public String getToken() { return token; }
    public double getSkipRatio() { return skipRatio; }
    public long getOwnerId() { return owner; }
    public String getSuccess() { return successEmoji; }
    public String getWarning() { return warningEmoji; }
    public String getError() { return errorEmoji; }
    public Activity getGame() { return game; }
    public boolean isGameNone() { return game != null && game.getName().equalsIgnoreCase("none"); }
    public OnlineStatus getStatus() { return status; }
    public boolean getStay() { return stayInChannel; }
    public boolean getSongInStatus() { return songInGame; }
    public String getPlaylistsFolder() { return playlistsFolder; }
    public boolean useUpdateAlerts() { return updateAlerts; }
    public String getLogLevel() { return logLevel; }
    public long getMaxSeconds() { return maxSeconds; }
    public int getMaxYTPlaylistPages() { return maxYTPlaylistPages; }
    public String getMaxTime() { return TimeUtil.formatTime(maxSeconds * 1000); }
    public long getAloneTimeUntilStop() { return aloneTimeUntilStop; }

    public boolean isTooLong(AudioTrack track)
    {
        return maxSeconds > 0 && Math.round(track.getDuration() / 1000.0) > maxSeconds;
    }
}
