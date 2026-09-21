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
package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.entities.Prompt;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;

/** Runtime configuration with explicit environment overrides for secrets. */
public class BotConfig
{
    private static final String CONTEXT = "Config";
    private static final String START_TOKEN = "/// START OF JMUSICBOT CONFIG ///";
    private static final String END_TOKEN = "/// END OF JMUSICBOT CONFIG ///";
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
    private boolean dbots;
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
            dbots = owner == 113156185389092864L;

            boolean write = false;
            if(token == null || token.isBlank() || TOKEN_PLACEHOLDER.equalsIgnoreCase(token))
            {
                token = prompt.prompt("Please provide a Discord bot token. For unattended use, set "
                        + "JMUSICBOT_DISCORD_TOKEN_FILE.\nBot Token: ");
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
                    ex.getMessage() + "\n\nConfig Location: " + path.toAbsolutePath());
        }
    }

    private String resolveToken(Config config) throws IOException
    {
        String direct = environment("JMUSICBOT_DISCORD_TOKEN");
        String tokenFile = environment("JMUSICBOT_DISCORD_TOKEN_FILE");
        if(direct != null && tokenFile != null)
            throw new IllegalArgumentException(
                    "Set only one of JMUSICBOT_DISCORD_TOKEN and JMUSICBOT_DISCORD_TOKEN_FILE");

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
                throw new IOException("JMUSICBOT_DISCORD_TOKEN_FILE is not a readable regular file");
            if(Files.size(source) > MAX_TOKEN_FILE_BYTES)
                throw new IOException("JMUSICBOT_DISCORD_TOKEN_FILE is unexpectedly large");
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
        byte[] bytes = loadDefaultConfig()
                .replace(TOKEN_PLACEHOLDER, persistedToken)
                .replace("0 // OWNER ID", Long.toString(owner))
                .trim().getBytes(StandardCharsets.UTF_8);
        Path parent = path.toAbsolutePath().getParent();
        if(parent != null)
            Files.createDirectories(parent);
        Files.write(path, bytes);
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
        String environment = environment("JMUSICBOT_CONFIG");
        if(environment != null)
            return Path.of(environment).toAbsolutePath().normalize();
        String configured = System.getProperty("config.file",
                System.getProperty("config", "config.txt"));
        return OtherUtil.getPath(configured).toAbsolutePath().normalize();
    }

    public static void writeDefaultConfig()
    {
        Prompt prompt = new Prompt(null, null, true, true);
        Path path = getConfigPath();
        try
        {
            Path parent = path.getParent();
            if(parent != null)
                Files.createDirectories(parent);
            Files.writeString(path, loadDefaultConfig(), StandardCharsets.UTF_8);
            prompt.alert(Prompt.Level.INFO, "NeoMusicBot Config",
                    "Wrote the default config to " + path);
        }
        catch(Exception ex)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot Config",
                    "Could not write the default config: " + ex.getMessage());
        }
    }

    private static String environment(String name)
    {
        String value = System.getenv(name);
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
    public boolean getDBots() { return dbots; }
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
