/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
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
package io.github.huaaudio.neomusicbot.utils;

import io.github.huaaudio.neomusicbot.NeoMusicBot;
import io.github.huaaudio.neomusicbot.entities.Prompt;
import java.io.*;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import okhttp3.*;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class OtherUtil
{
    public final static String NEW_VERSION_AVAILABLE = "There is a new version of NeoMusicBot available!\n"
                    + "Current version: %s\n"
                    + "New Version: %s\n\n"
                    + "Please visit https://github.com/huaaudio/NeoMusicBot/releases to get the release.";
    private final static String WINDOWS_INVALID_PATH = "c:\\windows\\system32\\";
    private final static Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private static final OkHttpClient UPDATE_HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(10)).build();
    
    /**
     * gets a Path from a String
     * also fixes the windows tendency to try to start in system32
     * any time the bot tries to access this path, it will instead start in the location of the jar file
     * 
     * @param path the string path
     * @return the Path object
     */
    public static Path getPath(String path)
    {
        Path result = Paths.get(path);
        // special logic to prevent trying to access system32
        if(result.toAbsolutePath().toString().toLowerCase(Locale.ROOT).startsWith(WINDOWS_INVALID_PATH))
        {
            try
            {
                result = Paths.get(new File(NeoMusicBot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().getPath() + File.separator + path);
            }
            catch(URISyntaxException ignored) {}
        }
        return result;
    }
    
    /**
     * Loads a resource from the jar as a string
     * 
     * @param clazz class base object
     * @param name name of resource
     * @return string containing the contents of the resource
     */
    public static String loadResource(Object clazz, String name)
    {
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(clazz.getClass().getResourceAsStream(name))))
        {
            StringBuilder sb = new StringBuilder();
            reader.lines().forEach(line -> sb.append("\r\n").append(line));
            return sb.toString().trim();
        }
        catch(IOException ignored)
        {
            return null;
        }
    }
    
    /**
     * Parses an activity from a string
     * 
     * @param game the game, including the action such as 'playing' or 'watching'
     * @return the parsed activity
     */
    public static Activity parseGame(String game)
    {
        if(game==null || game.trim().isEmpty() || game.trim().equalsIgnoreCase("default"))
            return null;
        game = game.trim();
        String lower = game.toLowerCase(Locale.ROOT);
        if(lower.startsWith("playing"))
            return Activity.playing(makeNonEmpty(game.substring(7).trim()));
        if(lower.startsWith("listening to"))
            return Activity.listening(makeNonEmpty(game.substring(12).trim()));
        if(lower.startsWith("listening"))
            return Activity.listening(makeNonEmpty(game.substring(9).trim()));
        if(lower.startsWith("watching"))
            return Activity.watching(makeNonEmpty(game.substring(8).trim()));
        if(lower.startsWith("streaming"))
        {
            String[] parts = game.substring(9).trim().split("\\s+", 2);
            if(parts.length == 2)
            {
                return Activity.streaming(makeNonEmpty(parts[1]), "https://twitch.tv/"+parts[0]);
            }
        }
        return Activity.playing(game);
    }
   
    public static String makeNonEmpty(String str)
    {
        return str == null || str.isEmpty() ? "\u200B" : str;
    }
    
    public static OnlineStatus parseStatus(String status)
    {
        if(status==null || status.trim().isEmpty())
            return OnlineStatus.ONLINE;
        OnlineStatus st = OnlineStatus.fromKey(status);
        return st == null ? OnlineStatus.ONLINE : st;
    }
    
    public static void checkJavaVersion(Prompt prompt)
    {
        if(!System.getProperty("java.vm.name").contains("64"))
            prompt.alert(Prompt.Level.WARNING, "Java Version", 
                    "It appears that you may not be using a supported Java version. Please use 64-bit java.");
    }
    
    public static void checkVersion(Prompt prompt)
    {
        // Get current version number
        String version = getCurrentVersion();
        
        // Check for new version
        String latestVersion = getLatestVersion();
        
        if(isNewerVersion(version, latestVersion))
        {
            prompt.alert(Prompt.Level.WARNING, "NeoMusicBot Version", String.format(NEW_VERSION_AVAILABLE, version, latestVersion));
        }
    }

    /**
     * Returns true only when {@code candidateVersion} is strictly newer than
     * {@code currentVersion}. GitHub tags may use a leading {@code v}; build
     * metadata is ignored and prerelease identifiers follow SemVer ordering.
     */
    public static boolean isNewerVersion(String currentVersion, String candidateVersion)
    {
        ParsedVersion current = ParsedVersion.parse(currentVersion);
        ParsedVersion candidate = ParsedVersion.parse(candidateVersion);
        return current != null && candidate != null && candidate.compareTo(current) > 0;
    }

    private record ParsedVersion(BigInteger[] release, String[] prerelease)
            implements Comparable<ParsedVersion>
    {
        private static ParsedVersion parse(String value)
        {
            if(value == null)
                return null;
            Matcher matcher = VERSION_PATTERN.matcher(value.trim());
            if(!matcher.matches())
                return null;
            String[] components = matcher.group(1).split("\\.");
            BigInteger[] release = new BigInteger[components.length];
            try
            {
                for(int i = 0; i < components.length; i++)
                    release[i] = new BigInteger(components[i]);
            }
            catch(NumberFormatException ex)
            {
                return null;
            }
            String suffix = matcher.group(2);
            String[] prerelease = suffix == null ? null : suffix.split("\\.");
            if(prerelease != null)
                for(String identifier : prerelease)
                    if(identifier.length() > 1 && identifier.charAt(0) == '0'
                            && identifier.chars().allMatch(Character::isDigit))
                        return null;
            return new ParsedVersion(release, prerelease);
        }

        @Override
        public int compareTo(ParsedVersion other)
        {
            int length = Math.max(release.length, other.release.length);
            for(int i = 0; i < length; i++)
            {
                BigInteger left = i < release.length ? release[i] : BigInteger.ZERO;
                BigInteger right = i < other.release.length ? other.release[i] : BigInteger.ZERO;
                int compared = left.compareTo(right);
                if(compared != 0)
                    return compared;
            }

            if(prerelease == null)
                return other.prerelease == null ? 0 : 1;
            if(other.prerelease == null)
                return -1;
            int suffixLength = Math.max(prerelease.length, other.prerelease.length);
            for(int i = 0; i < suffixLength; i++)
            {
                if(i >= prerelease.length)
                    return -1;
                if(i >= other.prerelease.length)
                    return 1;
                int compared = compareIdentifier(prerelease[i], other.prerelease[i]);
                if(compared != 0)
                    return compared;
            }
            return 0;
        }

        private static int compareIdentifier(String left, String right)
        {
            boolean leftNumeric = left.chars().allMatch(Character::isDigit);
            boolean rightNumeric = right.chars().allMatch(Character::isDigit);
            if(leftNumeric && rightNumeric)
                return new BigInteger(left).compareTo(new BigInteger(right));
            if(leftNumeric != rightNumeric)
                return leftNumeric ? -1 : 1;
            return left.compareTo(right);
        }
    }
    
    public static String getCurrentVersion()
    {
        if(NeoMusicBot.class.getPackage()!=null && NeoMusicBot.class.getPackage().getImplementationVersion()!=null)
            return NeoMusicBot.class.getPackage().getImplementationVersion();
        else
            return "UNKNOWN";
    }
    
    public static String getLatestVersion()
    {
        return getLatestVersion(getCurrentVersion(), UPDATE_HTTP);
    }

    static String getLatestVersion(String currentVersion, OkHttpClient client)
    {
        ParsedVersion current = ParsedVersion.parse(currentVersion);
        boolean includePrereleases = current != null && current.prerelease() != null;
        String endpoint = "https://api.github.com/repos/huaaudio/NeoMusicBot/releases"
                + (includePrereleases ? "?per_page=100" : "/latest");
        Request request = new Request.Builder().get().url(endpoint)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NeoMusicBot-update-check").build();
        try(Response response = client.newCall(request).execute())
        {
            ResponseBody body = response.body();
            if(!response.isSuccessful() || body == null)
                return null;
            int limit = 2 * 1024 * 1024;
            byte[] content = body.byteStream().readNBytes(limit + 1);
            if(content.length > limit)
                return null;
            String json = new String(content, StandardCharsets.UTF_8);
            JSONArray releases = includePrereleases ? new JSONArray(json) : new JSONArray().put(new JSONObject(json));
            return newestPublishedRelease(releases, includePrereleases);
        }
        catch(IOException | JSONException ex)
        {
            return null;
        }
    }

    static String newestPublishedRelease(JSONArray releases, boolean includePrereleases)
    {
        String newest = null;
        for(int i = 0; i < releases.length(); i++)
        {
            JSONObject release = releases.optJSONObject(i);
            if(release == null || release.optBoolean("draft", true))
                continue;
            String tag = release.optString("tag_name", "");
            ParsedVersion version = ParsedVersion.parse(tag);
            if(version == null || (!includePrereleases
                    && (release.optBoolean("prerelease", true) || version.prerelease() != null)))
                continue;
            if(newest == null || isNewerVersion(newest, tag))
                newest = tag;
        }
        return newest;
    }

}
