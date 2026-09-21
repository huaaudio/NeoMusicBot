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
package com.jagrosh.jmusicbot;

import ch.qos.logback.classic.Level;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.jagrosh.jmusicbot.commands.slash.SlashCommandListener;
import com.jagrosh.jmusicbot.entities.Prompt;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.utils.OtherUtil;
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
public final class JMusicBot
{
    public static final Logger LOG = LoggerFactory.getLogger(JMusicBot.class);

    public static final Permission[] RECOMMENDED_PERMS = {
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.NICKNAME_CHANGE
    };

    private JMusicBot()
    {
    }

    public static void main(String[] args)
    {
        if (args.length > 0 && "generate-config".equalsIgnoreCase(args[0]))
        {
            if(!BotConfig.writeDefaultConfig())
                System.exit(1);
            return;
        }
        startBot();
    }

    private static void startBot()
    {
        Prompt prompt = new Prompt("NeoMusicBot");
        if (Runtime.version().feature() < 25)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot",
                    "This release requires Java 25 or newer. Detected Java "
                            + Runtime.version().feature() + ".");
            return;
        }
        OtherUtil.checkVersion(prompt);
        OtherUtil.checkJavaVersion(prompt);

        BotConfig config = new BotConfig(prompt);
        config.load();
        if (!config.isValid())
            return;

        LOG.info("Loaded config from {}", config.getConfigLocation());
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                .setLevel(Level.toLevel(config.getLogLevel(), Level.INFO));

        SettingsManager settings = new SettingsManager();
        Bot bot = new Bot(config, settings);
        SlashCommandListener slashCommands = new SlashCommandListener(bot);

        if (!prompt.isNoGUI())
        {
            try
            {
                GUI gui = new GUI(bot);
                bot.setGUI(gui);
                gui.init();
            }
            catch (Exception e)
            {
                LOG.error("Could not start the GUI. Run with -Dnogui=true on a headless server.", e);
            }
        }
        boolean started = false;

        try
        {
            MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
            MessageRequest.setDefaultMentionRepliedUser(false);
            JDABuilder builder = JDABuilder.create(config.getToken(), EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                    .enableCache(CacheFlag.VOICE_STATE)
                    .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.EMOJI, CacheFlag.ONLINE_STATUS)
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(new JDaveSessionFactory()))
                    .setAutoReconnect(true)
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
                    "The configuration is invalid: " + ex.getMessage() + "\nConfig Location: "
                            + config.getConfigLocation());
        }
        catch (ErrorResponseException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "NeoMusicBot",
                    "Discord rejected the login request: " + ex.getErrorResponse());
        }
        finally
        {
            if(!started)
                bot.shutdown();
        }
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
