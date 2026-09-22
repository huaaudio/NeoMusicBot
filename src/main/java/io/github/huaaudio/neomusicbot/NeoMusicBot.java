/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh (jagrosh)
 */
/*
 * Copyright 2016 John Grosh (jagrosh).
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

import ch.qos.logback.classic.Level;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import io.github.huaaudio.neomusicbot.commands.slash.SlashCommandListener;
import io.github.huaaudio.neomusicbot.diagnostics.RuntimeSelfTest;
import io.github.huaaudio.neomusicbot.entities.Prompt;
import io.github.huaaudio.neomusicbot.gui.GUI;
import io.github.huaaudio.neomusicbot.settings.SettingsManager;
import io.github.huaaudio.neomusicbot.utils.OtherUtil;
import java.util.EnumSet;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application entry point. */
public final class NeoMusicBot
{
    public static final Logger LOG = LoggerFactory.getLogger(NeoMusicBot.class);

    public static final Permission[] RECOMMENDED_PERMS = {
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.NICKNAME_CHANGE
    };

    private NeoMusicBot()
    {
    }

    public static void main(String[] args)
    {
        if(args.length > 0)
        {
            int result = args.length == 1 ? switch(args[0].toLowerCase(java.util.Locale.ROOT))
            {
                case "generate-config" -> BotConfig.writeDefaultConfig() ? 0 : 1;
                case "--self-test" -> RuntimeSelfTest.run(System.out);
                case "--version" -> {
                    System.out.println("NeoMusicBot " + OtherUtil.getCurrentVersion());
                    yield 0;
                }
                case "--help" -> {
                    System.out.println("Usage: NeoMusicBot [generate-config|--version|--self-test|--help]");
                    System.out.println("No arguments: start the Discord bot using the configured credentials.");
                    yield 0;
                }
                default -> 2;
            } : 2;
            if(result == 2) System.err.println("Unknown arguments. Use --help for supported commands.");
            if(result != 0) System.exit(result);
            return;
        }
        if(!startBot()) System.exit(1);
    }

    private static boolean startBot()
    {
        Prompt prompt = new Prompt("NeoMusicBot");
        if (Runtime.version().feature() < 25)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot",
                    "This release requires Java 25 or newer. Detected Java "
                            + Runtime.version().feature() + ".");
            return false;
        }
        OtherUtil.checkJavaVersion(prompt);

        BotConfig config = new BotConfig(prompt);
        config.load();
        if (!config.isValid())
            return false;

        LOG.info("Loaded config from {}", config.getConfigLocation());
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.toLevel(config.getLogLevel(), Level.INFO));

        SettingsManager settings = null;
        Bot bot = null;
        boolean started = false;

        try
        {
            settings = new SettingsManager();
            bot = new Bot(config, settings);
            SlashCommandListener slashCommands = new SlashCommandListener(bot);
            if(!prompt.isNoGUI())
            {
                try
                {
                    GUI.open(bot);
                }
                catch(InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while starting the GUI", interrupted);
                }
                catch(Exception failure)
                {
                    LOG.error("Could not start the GUI. Run with -Dnogui=true on a headless server ({}).",
                            failure.getClass().getSimpleName());
                }
            }
            MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
            MessageRequest.setDefaultMentionRepliedUser(false);
            JDABuilder builder = JDABuilder.create(config.getToken(), EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                    .enableCache(CacheFlag.VOICE_STATE)
                    .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.EMOJI, CacheFlag.ONLINE_STATUS)
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(new JDaveSessionFactory()))
                    .setAutoReconnect(true)
                    .setEnableShutdownHook(false) // Bot owns the ordered shutdown of all services.
                    .setActivity(config.isGameNone() ? null : config.getGame())
                    .setStatus(normalizeStatus(config.getStatus()))
                    .addEventListeners(slashCommands, new Listener(bot))
                    .setBulkDeleteSplittingEnabled(true);

            JDA jda = builder.build();
            bot.setJDA(jda);
            started = true;
        }
        catch (InvalidTokenException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot",
                    "The Discord token is invalid. Check the configured bot token.\nConfig Location: "
                            + config.getConfigLocation());
        }
        catch (IllegalArgumentException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot",
                    "The runtime configuration is invalid. Check media tool settings and Discord options.\nConfig Location: "
                            + config.getConfigLocation());
        }
        catch (ErrorResponseException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot",
                    "Discord rejected the login request: " + ex.getErrorResponse());
        }
        catch(RuntimeException | LinkageError failure)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot",
                    "Startup failed (" + failure.getClass().getSimpleName()
                            + "). Check server settings and platform native libraries.");
        }
        finally
        {
            if(!started)
            {
                if(bot != null) bot.shutdown();
                else if(settings != null) settings.close();
            }
        }
        return started;
    }

    private static OnlineStatus normalizeStatus(OnlineStatus status)
    {
        if (status == null || status == OnlineStatus.UNKNOWN)
            return OnlineStatus.ONLINE;
        if (status == OnlineStatus.INVISIBLE || status == OnlineStatus.OFFLINE)
            return OnlineStatus.INVISIBLE;
        return status;
    }
}
