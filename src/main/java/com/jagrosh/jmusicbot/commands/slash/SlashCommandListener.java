package com.jagrosh.jmusicbot.commands.slash;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.JMusicBot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.GuildPlaybackSession;
import com.jagrosh.jmusicbot.audio.PlaybackLoadToken;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.audio.media.MediaResolverReadiness;
import com.jagrosh.jmusicbot.audio.media.SensitiveLogSanitizer;
import com.jagrosh.jmusicbot.audio.media.PersistentMediaReferencePolicy;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.JDAInfo;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native JDA Slash command router. */
public final class SlashCommandListener extends ListenerAdapter
{
    private static final Logger LOG = LoggerFactory.getLogger(SlashCommandListener.class);
    private static final int QUEUE_PAGE_SIZE = 10;
    private static final long COMPONENT_TTL_SECONDS = 45;
    private static final long DIAGNOSTIC_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int VERSION_OUTPUT_LIMIT = 4 * 1024;
    private static final String LOCKED_YT_DLP_VERSION = "2026.08.19";
    private static final String LOCKED_DENO_VERSION = "2.9.7";
    private static final Pattern SAFE_PLAYLIST_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?]+)\\?[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPONENT_ID = Pattern.compile(
            "^mb:([0-9a-f]{32}):(pick|prev|next)$");
    private static final Pattern YT_DLP_VERSION = Pattern.compile(
            "(?m)^\\s*([0-9]{4}\\.[0-9]{1,2}\\.[0-9]{1,2}(?:[A-Za-z0-9.+-]{0,24})?)\\s*$");
    private static final Pattern DENO_VERSION = Pattern.compile(
            "(?im)^\\s*deno\\s+([0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?)\\b");

    private final Bot bot;
    private final Map<String, ComponentSession> componentSessions = new ConcurrentHashMap<>();
    private final Object diagnosticsLock = new Object();
    private volatile DiagnosticSnapshot cachedDiagnostics;

    public SlashCommandListener(Bot bot)
    {
        this.bot = bot;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event)
    {
        event.getJDA().getGuilds().forEach(guild ->
        {
            guild.getAudioManager().setAutoReconnect(true);
            guild.getAudioManager().setSelfDeafened(true);
        });

        String developmentGuild = System.getenv("JMUSICBOT_COMMAND_GUILD_ID");
        if (developmentGuild != null && !developmentGuild.isBlank())
        {
            Guild guild;
            try
            {
                guild = event.getJDA().getGuildById(developmentGuild.trim());
            }
            catch (IllegalArgumentException ex)
            {
                LOG.error("JMUSICBOT_COMMAND_GUILD_ID is not a valid Discord snowflake");
                return;
            }
            if (guild == null)
            {
                LOG.error("JMUSICBOT_COMMAND_GUILD_ID does not identify a guild visible to this bot");
                return;
            }
            guild.updateCommands().addCommands(SlashCommandSchema.create()).queue(
                    ignored -> LOG.info("Registered development Slash commands in guild {}", guild.getId()),
                    error -> LOG.error("Failed to register development Slash commands", error));
        }
        else
        {
            event.getJDA().updateCommands().addCommands(SlashCommandSchema.create()).queue(
                    ignored -> LOG.info("Registered global Slash commands"),
                    error -> LOG.error("Failed to register global Slash commands", error));
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event)
    {
        if (!"name".equals(event.getFocusedOption().getName())
                || !("playlist".equals(event.getName()) || "owner".equals(event.getName())))
        {
            event.replyChoices(List.of()).queue();
            return;
        }
        if("owner".equals(event.getName())
                && event.getUser().getIdLong() != bot.getConfig().getOwnerId())
        {
            event.replyChoices(List.of()).queue();
            return;
        }


        String value = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = bot.getPlaylistLoader().getPlaylistNames().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(value))
                .limit(25)
                .map(name -> new Command.Choice(name, name))
                .toList();
        event.replyChoices(choices).queue();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event)
    {
        try
        {
            switch (event.getName())
            {
                case "about" -> about(event);
                case "ping" -> event.reply("Discord gateway latency: `" + event.getJDA().getGatewayPing() + " ms`").queue();
                case "help" -> help(event);
                case "play" -> play(event, false);
                case "search" -> search(event);
                case "now-playing" -> nowPlaying(event);
                case "queue" -> queue(event);
                case "remove" -> remove(event);
                case "seek" -> seek(event);
                case "shuffle" -> shuffle(event);
                case "skip" -> skip(event);
                case "playlist" -> playlist(event);
                case "dj" -> dj(event);
                case "config" -> config(event);
                case "owner" -> owner(event);
                default -> error(event, "Unknown command. Discord may still have an old command registration cached.");
            }
        }
        catch (RuntimeException ex)
        {
            LOG.error("Unhandled Slash command failure for /{}: {}",
                    event.getFullCommandName(), sanitizedFailure(ex));
            error(event, "The command failed unexpectedly. Check the bot log for the correlation timestamp.");
        }
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event)
    {
        ComponentLookup lookup = componentSession(event, Set.of("prev", "next"));
        if (lookup == null)
            return;
        if (!(lookup.session() instanceof QueueSession session))
        {
            expireInteraction(event, lookup.id());
            return;
        }

        event.deferEdit().queue(ignored ->
        {
            synchronized (session)
            {
                if (componentSessions.get(lookup.id()) != session
                        || System.currentTimeMillis() >= session.expiresAtMillis())
                {
                    event.getHook().editOriginalComponents().queue();
                    return;
                }
                int delta = "prev".equals(lookup.action()) ? -1 : 1;
                session.setPage(Math.max(1, Math.min(session.pages(), session.page() + delta)));
                event.getHook().editOriginal(renderQueue(session))
                        .setComponents(queueControls(lookup.id(), session))
                        .queue(ignoredEdit -> { },
                                failure -> LOG.warn("Could not update queue pagination", failure));
            }
        }, failure -> LOG.warn("Could not acknowledge queue pagination", failure));
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event)
    {
        ComponentLookup lookup = componentSession(event, Set.of("pick"));
        if (lookup == null)
            return;
        if (!(lookup.session() instanceof SearchSession session) || event.getValues().size() != 1)
        {
            expireInteraction(event, lookup.id());
            return;
        }

        int index;
        try
        {
            index = Integer.parseInt(event.getValues().get(0));
        }
        catch (NumberFormatException ex)
        {
            expireInteraction(event, lookup.id());
            return;
        }
        if (index < 0 || index >= session.choices().size())
        {
            expireInteraction(event, lookup.id());
            return;
        }

        if(!session.handler().isLoadValid(session.loadToken()))
        {
            componentSessions.remove(lookup.id(), session);
            componentError(event, "This search was canceled because playback was stopped or replaced.");
            return;
        }
        MusicContext context = componentMusicContext(event);
        if (context == null || context.handler() != session.handler())
            return;

        SearchChoice choice = session.choices().get(index);
        AudioTrack track = choice.prototype().makeClone();
        if (bot.getConfig().isTooLong(track))
        {
            componentError(event, "That result exceeds the configured maximum duration.");
            return;
        }
        if (!componentSessions.remove(lookup.id(), session))
        {
            expireInteraction(event, lookup.id());
            return;
        }

        event.deferEdit().queue(ignored ->
        {
            QueuedTrack queued = new QueuedTrack(track,
                    RequestMetadata.fromRequest(event.getUser(), "search:" + choice.stableId()));
            Integer position = context.handler().addTrackAndCompleteLoad(session.loadToken(), queued, false);
            if(position == null)
            {
                event.getHook().editOriginal(warn(
                                "This search was canceled because playback was stopped or replaced."))
                        .setComponents().queue();
                return;
            }

            event.getHook().editOriginal(ok("Added **" + markdown(track.getInfo().title) + "** `"
                    + TimeUtil.formatTime(track.getDuration()) + "` "
                    + (position < 0 ? "and started playback." : "at queue position `" + (position + 1) + "`.")))
                    .setComponents()
                    .queue(ignoredEdit -> { },
                            failure -> LOG.warn("Could not update selected search result", failure));
        }, failure ->
        {
            context.handler().finishPendingLoad(session.loadToken());
            LOG.warn("Could not acknowledge selected search result", failure);
        });
    }

    private void about(SlashCommandInteractionEvent event)
    {
        String invite = event.getJDA().getInviteUrl(JMusicBot.RECOMMENDED_PERMS);
        event.reply("**NeoMusicBot " + markdown(OtherUtil.getCurrentVersion()) + "**\n"
                + "Self-hosted Discord music playback with DAVE encryption and native Slash commands.\n"
                + "[Invite this bot](" + invite + ")").queue();
    }

    private void help(SlashCommandInteractionEvent event)
    {
        event.reply("**NeoMusicBot commands**\n"
                + "`/play`, `/search`, `/now-playing`, `/queue`, `/remove`, `/seek`, `/shuffle`, `/skip`\n"
                + "`/playlist list|play` — local playlists\n"
                + "`/dj …` — DJ playback controls\n"
                + "`/config …` — server configuration (Manage Server)\n"
                + "`/owner …` — bot-owner maintenance").setEphemeral(true).queue();
    }

    private void play(SlashCommandInteractionEvent event, boolean front)
    {
        String query = string(event, "query");
        Attachment attachment = attachment(event, "attachment");
        if (query != null && attachment != null)
        {
            error(event, "Provide either a query or an attachment, not both.");
            return;
        }

        MusicContext context = musicContext(event, false, false);
        if (context == null)
            return;

        if (query == null && attachment == null)
        {
            AudioHandler.PlaybackState state = context.handler().getPlaybackState();
            AudioTrack current = state.current();
            if (current == null || !state.paused())
            {
                error(event, "Provide a song title, URL, or audio attachment.");
                return;
            }
            if (!isDj(event))
            {
                error(event, "Only a DJ can resume paused playback.");
                return;
            }
            if(!ensureListening(event, context.settings(), context.handler()))
                return;
            if(!context.handler().setPausedIfCurrent(current, false))
            {
                error(event, "The current track changed before it could be resumed. Try again.");
                return;
            }
            event.reply(ok("Resumed **" + markdown(current.getInfo().title) + "**.")).queue();
            return;
        }

        if(!ensureListening(event, context.settings(), context.handler()))
            return;
        String identifier = query != null ? unwrap(query) : attachment.getUrl();
        PlaybackLoadToken loadToken = context.handler().reserveLoad();
        event.deferReply(true).queue(
                ignored -> load(event, context, loadToken, identifier, front, true, false),
                failure -> context.handler().finishPendingLoad(loadToken));
    }

    private void search(SlashCommandInteractionEvent event)
    {
        MusicContext context = musicContext(event, false, false);
        if (context == null)
            return;
        String query = string(event, "query");
        PlaybackLoadToken loadToken = context.handler().reserveLoad();
        event.deferReply(true).queue(ignored ->
        {
            try
            {
                bot.getPlayerManager().loadItemOrdered(event.getGuild().getIdLong(), "ytsearch:" + query,
                        new AudioLoadResultHandler()
                        {
                            @Override
                            public void trackLoaded(AudioTrack track)
                            {
                                showSearchResults(event, context.handler(), loadToken, List.of(track), query);
                            }

                            @Override
                            public void playlistLoaded(AudioPlaylist playlist)
                            {
                                showSearchResults(event, context.handler(), loadToken,
                                        playlist.getTracks(), query);
                            }

                            @Override
                            public void noMatches()
                            {
                                if(context.handler().finishPendingLoad(loadToken))
                                    edit(event, warn("No YouTube results found for `" + markdown(query) + "`."));
                            }

                            @Override
                            public void loadFailed(FriendlyException exception)
                            {
                                LOG.warn("YouTube search failed [{}]: {}",
                                        exception.severity, sanitizedFailure(exception));
                                if(context.handler().finishPendingLoad(loadToken))
                                    edit(event, fail("YouTube search failed. Try a direct URL or try again later."));
                            }
                        });
            }
            catch(RuntimeException ex)
            {
                LOG.warn("Could not start YouTube search: {}", sanitizedFailure(ex));
                if(context.handler().finishPendingLoad(loadToken))
                    edit(event, fail("YouTube search could not be started."));
            }
        }, failure -> context.handler().finishPendingLoad(loadToken));
    }

    private void showSearchResults(SlashCommandInteractionEvent event, AudioHandler handler,
                                   PlaybackLoadToken loadToken, List<AudioTrack> tracks, String query)
    {
        if(!handler.isLoadValid(loadToken))
            return;
        List<SearchChoice> choices = tracks.stream()
                .limit(5)
                .filter(track -> track.getIdentifier() != null && !track.getIdentifier().isBlank())
                .map(track -> new SearchChoice(track.getIdentifier(), track.makeClone()))
                .toList();
        if (choices.isEmpty())
        {
            handler.finishPendingLoad(loadToken);
            edit(event, warn("No YouTube results found for `" + markdown(query) + "`."));
            return;
        }

        String id = newSessionId();
        long expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(COMPONENT_TTL_SECONDS);
        SearchSession session = new SearchSession(event.getUser().getIdLong(), event.getGuild().getIdLong(),
                expiresAt, event.getHook(), handler, loadToken, List.copyOf(choices));
        componentSessions.put(id, session);

        StringSelectMenu.Builder menu = StringSelectMenu.create(componentId(id, "pick"))
                .setPlaceholder("Choose one track to queue")
                .setRequiredRange(1, 1);
        for (int i = 0; i < choices.size(); i++)
        {
            AudioTrack track = choices.get(i).prototype();
            String title = componentText(track.getInfo().title, 100);
            if (title.isBlank())
                title = "Untitled result " + (i + 1);
            String description = componentText(track.getInfo().author + " · "
                    + TimeUtil.formatTime(track.getDuration()), 100);
            menu.addOption(title, Integer.toString(i), description);
        }

        event.getHook().editOriginal(formatSearchResults(choices, query))
                .setComponents(ActionRow.of(menu.build()))
                .queue(ignored -> scheduleExpiration(id), failure ->
                {
                    componentSessions.remove(id, session);
                    handler.finishPendingLoad(loadToken);
                    LOG.warn("Could not publish YouTube search controls", failure);
                });
    }

    private String formatSearchResults(List<SearchChoice> choices, String query)
    {
        StringBuilder output = new StringBuilder("**YouTube results for `")
                .append(markdown(query)).append("`**");
        for (int i = 0; i < choices.size(); i++)
        {
            AudioTrack track = choices.get(i).prototype();
            output.append("\n`[").append(i + 1).append("]` **")
                    .append(markdown(track.getInfo().title)).append("** — `")
                    .append(TimeUtil.formatTime(track.getDuration())).append("` — ")
                    .append(safeUri(track.getInfo().uri));
        }
        output.append("\nChoose one result below within ").append(COMPONENT_TTL_SECONDS).append(" seconds.");
        return limit(output.toString());
    }

    private void nowPlaying(SlashCommandInteractionEvent event)
    {
        MusicContext context = musicContext(event, false, false);
        if (context == null)
            return;
        AudioHandler.PlaybackState playback = context.handler().getPlaybackState();
        AudioTrack track = playback.current();
        if (track == null)
        {
            event.reply("Nothing is currently playing.").setEphemeral(true).queue();
            return;
        }
        String state = playback.paused() ? "Paused" : "Playing";
        event.reply("**" + state + ": " + markdown(track.getInfo().title) + "**\n`"
                + TimeUtil.formatTime(playback.position()) + " / " + TimeUtil.formatTime(playback.duration())
                + "` · volume `" + playback.volume() + "`").queue();
    }

    private void queue(SlashCommandInteractionEvent event)
    {
        MusicContext context = musicContext(event, false, false);
        if (context == null)
            return;
        List<QueueEntry> entries = context.handler().getQueueSnapshot().stream()
                .map(queued -> new QueueEntry(queued.getTrack().getInfo().title,
                        queued.getTrack().getDuration(), queued.getIdentifier()))
                .toList();
        if (entries.isEmpty())
        {
            event.reply("The queue is empty.").setEphemeral(true).queue();
            return;
        }

        int requestedPage = integer(event, "page", 1);
        int pages = (entries.size() + QUEUE_PAGE_SIZE - 1) / QUEUE_PAGE_SIZE;
        int page = Math.min(requestedPage, pages);
        event.deferReply().queue(ignored -> showQueue(event, List.copyOf(entries), page));
    }

    private void showQueue(SlashCommandInteractionEvent event, List<QueueEntry> entries, int page)
    {
        String id = newSessionId();
        long expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(COMPONENT_TTL_SECONDS);
        QueueSession session = new QueueSession(event.getUser().getIdLong(), event.getGuild().getIdLong(),
                expiresAt, event.getHook(), entries, page);
        componentSessions.put(id, session);
        event.getHook().editOriginal(renderQueue(session))
                .setComponents(queueControls(id, session))
                .queue(ignored -> scheduleExpiration(id), failure ->
                {
                    componentSessions.remove(id, session);
                    LOG.warn("Could not publish queue pagination controls", failure);
                });
    }

    private String renderQueue(QueueSession session)
    {
        int pages = session.pages();
        int page = Math.max(1, Math.min(session.page(), pages));
        int start = (page - 1) * QUEUE_PAGE_SIZE;
        int end = Math.min(start + QUEUE_PAGE_SIZE, session.entries().size());
        StringBuilder output = new StringBuilder("**Queue — page ").append(page).append('/').append(pages).append("**");
        for (int i = start; i < end; i++)
        {
            QueueEntry entry = session.entries().get(i);
            output.append("\n`[").append(i + 1).append("]` **")
                    .append(markdown(entry.title())).append("** — `")
                    .append(TimeUtil.formatTime(entry.duration())).append('`');
            if (entry.ownerId() != 0)
                output.append(" — <@").append(entry.ownerId()).append('>');
        }
        return limit(output.toString());
    }

    private ActionRow queueControls(String id, QueueSession session)
    {
        int pages = session.pages();
        return ActionRow.of(
                Button.secondary(componentId(id, "prev"), "Previous").withDisabled(session.page() <= 1),
                Button.secondary(componentId(id, "next"), "Next").withDisabled(session.page() >= pages));
    }

    private void remove(SlashCommandInteractionEvent event)
    {
        MusicContext context = musicContext(event, false, true);
        if (context == null)
            return;
        boolean all = bool(event, "all");
        OptionMapping positionOption = event.getOption("position");
        if (all && positionOption != null)
        {
            error(event, "Choose a position or `all`, not both.");
            return;
        }
        if (all)
        {
            int count = context.handler().removeAllQueued(event.getUser().getIdLong());
            event.reply(count == 0 ? warn("You have no tracks in the queue.")
                    : ok("Removed `" + count + "` of your queued tracks.")).queue();
            return;
        }
        if (positionOption == null)
        {
            error(event, "Provide a queue position or set `all`.");
            return;
        }

        int position = positionOption.getAsInt();
        List<QueuedTrack> snapshot = context.handler().getQueueSnapshot();
        if (position < 1 || position > snapshot.size())
        {
            error(event, "That queue position no longer exists.");
            return;
        }
        QueuedTrack selected = snapshot.get(position - 1);
        boolean dj = isDj(event);
        if (selected.getIdentifier() != event.getUser().getIdLong() && !dj)
        {
            error(event, "You can only remove tracks you requested.");
            return;
        }

        QueuedTrack removed = context.handler().removeQueuedTrackIfMatches(
                position - 1, selected, event.getUser().getIdLong(), dj);
        if (removed == null)
        {
            error(event, "The queue changed before the track could be removed. Try again.");
            return;
        }
        event.reply(ok("Removed **" + markdown(removed.getTrack().getInfo().title) + "**.")).queue();
    }

    private void seek(SlashCommandInteractionEvent event)
    {
        MusicContext context = musicContext(event, true, true);
        if (context == null)
            return;
        AudioHandler.PlaybackState playback = context.handler().getPlaybackState();
        AudioTrack track = playback.current();
        if (track == null)
        {
            error(event, "The current track changed. Try again.");
            return;
        }
        if (!playback.seekable())
        {
            error(event, "The current track is not seekable.");
            return;
        }
        RequestMetadata metadata = metadata(track);
        if (metadata.getOwner() != event.getUser().getIdLong() && !isDj(event))
        {
            error(event, "Only the requester or a DJ can seek this track.");
            return;
        }
        TimeUtil.SeekTime parsed = TimeUtil.parseTime(string(event, "position"));
        if (parsed == null)
        {
            error(event, "Invalid time. Examples: `1:30`, `+20s`, `-10`.");
            return;
        }
        long target = parsed.relative ? playback.position() + parsed.milliseconds : parsed.milliseconds;
        if (target < 0 || target > playback.duration())
        {
            error(event, "Seek target is outside the track duration.");
            return;
        }
        if (!context.handler().seekIfCurrent(track, target))
        {
            error(event, "The current track changed before it could be seeked. Try again.");
            return;
        }
        event.reply(ok("Seeked to `" + TimeUtil.formatTime(target) + " / "
                + TimeUtil.formatTime(playback.duration()) + "`.")).queue();
    }

    private void shuffle(SlashCommandInteractionEvent event)
    {
        MusicContext context = musicContext(event, false, true);
        if (context == null)
            return;
        int count = context.handler().shuffleQueued(event.getUser().getIdLong());
        if (count == 0)
            event.reply(warn("You have no queued tracks to shuffle.")).setEphemeral(true).queue();
        else if (count == 1)
            event.reply(warn("You only have one queued track.")).setEphemeral(true).queue();
        else
            event.reply(ok("Shuffled your `" + count + "` queued tracks.")).queue();
    }

    private void skip(SlashCommandInteractionEvent event)
    {
        MusicContext context = musicContext(event, true, true);
        if (context == null)
            return;
        AudioTrack track = context.handler().getPlaybackState().current();
        if (track == null)
        {
            error(event, "The current track changed. Try again.");
            return;
        }
        RequestMetadata metadata = metadata(track);
        double ratio = context.settings().getSkipRatio() == -1
                ? bot.getConfig().getSkipRatio() : context.settings().getSkipRatio();
        if (metadata.getOwner() == event.getUser().getIdLong() || ratio == 0)
        {
            if (!context.handler().skipCurrentIfMatches(track))
            {
                error(event, "The current track changed before it could be skipped. Try again.");
                return;
            }
            event.reply(ok("Skipped **" + markdown(track.getInfo().title) + "**.")).queue();
            return;
        }

        AudioHandler.VoteUpdate update = context.handler().addVoteIfCurrent(track, event.getUser().getId());
        if (update == null)
        {
            error(event, "The current track changed before the vote was recorded. Try again.");
            return;
        }
        Set<String> votes = update.votes();
        AudioChannelUnion channel = event.getMember().getVoiceState().getChannel();
        long listeners = channel.getMembers().stream()
                .filter(member -> !member.getUser().isBot())
                .filter(member -> member.getVoiceState() != null && !member.getVoiceState().isDeafened())
                .count();
        long validVotes = channel.getMembers().stream()
                .filter(member -> !member.getUser().isBot())
                .filter(member -> member.getVoiceState() != null && !member.getVoiceState().isDeafened())
                .filter(member -> votes.contains(member.getId()))
                .count();
        long required = (long) Math.ceil(listeners * ratio);
        String message = update.added() ? ok("Vote recorded") : warn("You already voted");
        message += " — `" + validVotes + "/" + required + "` needed.";
        if (validVotes >= required)
        {
            if (!context.handler().skipCurrentIfMatches(track))
            {
                error(event, "The current track changed before it could be skipped. Try again.");
                return;
            }
            message += "\n" + ok("Skipped **" + markdown(track.getInfo().title) + "**.");
        }
        event.reply(message).queue();
    }

    private void playlist(SlashCommandInteractionEvent event)
    {
        String subcommand = event.getSubcommandName();
        if ("list".equals(subcommand))
        {
            event.deferReply(true).queue(ignored ->
            {
                List<String> names = bot.getPlaylistLoader().getPlaylistNames();
                edit(event, names.isEmpty() ? "No local playlists are available."
                        : limit("**Local playlists**\n`" + String.join("`, `", names) + "`"));
            });
            return;
        }

        MusicContext context = musicContext(event, false, true);
        if (context == null)
            return;
        String name = string(event, "name");
        PlaybackLoadToken loadToken = context.handler().reserveLoad();
        event.deferReply(true).queue(ignored ->
        {
            try
            {
                Playlist value = bot.getPlaylistLoader().getPlaylist(name);
                if (value == null)
                {
                    context.handler().finishPendingLoad(loadToken);
                    edit(event, fail("Playlist `" + markdown(name) + "` was not found."));
                    return;
                }
                if (value.getItems().isEmpty())
                {
                    context.handler().finishPendingLoad(loadToken);
                    edit(event, warn("Playlist `" + markdown(name) + "` is empty."));
                    return;
                }
                AtomicInteger loaded = new AtomicInteger();
                value.loadTracks(bot.getPlayerManager(), track ->
                {
                    QueuedTrack queued = new QueuedTrack(track,
                            RequestMetadata.fromRequest(event.getUser(), "playlist:" + name));
                    if(context.handler().addTrackForPendingLoad(loadToken, queued))
                        loaded.incrementAndGet();
                }, () ->
                {
                    if(!context.handler().finishPendingLoad(loadToken))
                    {
                        edit(event, warn("This playlist load was canceled."));
                        return;
                    }
                    int count = loaded.get();
                    String message = count == 0
                            ? warn("No tracks were loaded from `" + markdown(name) + "`.")
                            : ok("Loaded `" + count + "` tracks from **" + markdown(name) + "**.");
                    if (!value.getErrors().isEmpty())
                        message += " `" + value.getErrors().size() + "` entries failed.";
                    edit(event, message);
                });
            }
            catch(RuntimeException ex)
            {
                if(context.handler().finishPendingLoad(loadToken))
                    edit(event, fail("The playlist load could not be started."));
            }
        }, failure -> context.handler().finishPendingLoad(loadToken));
    }

    private void dj(SlashCommandInteractionEvent event)
    {
        if (!isDj(event))
        {
            error(event, "This command requires the configured DJ role or Manage Server permission.");
            return;
        }
        String subcommand = event.getSubcommandName();
        if ("repeat".equals(subcommand))
        {
            Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
            RepeatMode mode = RepeatMode.valueOf(string(event, "mode").toUpperCase(Locale.ROOT));
            AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
            handler.updateSettings(() -> settings.setRepeatMode(mode));
            event.reply(ok("Repeat mode is now `" + mode.getUserFriendlyName() + "`.")).queue();
            return;
        }
        if ("play-next".equals(subcommand))
        {
            MusicContext context = musicContext(event, false, true);
            if (context == null)
                return;
            String query = string(event, "query");
            PlaybackLoadToken loadToken = context.handler().reserveLoad();
            event.deferReply(true).queue(
                    ignored -> load(event, context, loadToken, query, true, false, false),
                    failure -> context.handler().finishPendingLoad(loadToken));
            return;
        }

        MusicContext context = musicContext(event,
                Set.of("force-skip", "pause", "skip-to").contains(subcommand), false);
        if (context == null)
            return;
        switch (subcommand)
        {
            case "force-skip" ->
            {
                AudioTrack current = context.handler().getPlaybackState().current();
                if (current == null || !context.handler().skipCurrentIfMatches(current))
                {
                    error(event, "The current track changed before it could be skipped. Try again.");
                    return;
                }
                event.reply(ok("Skipped **" + markdown(current.getInfo().title) + "**.")).queue();
            }
            case "force-remove" ->
            {
                User user = event.getOption("user").getAsUser();
                int count = context.handler().removeAllQueued(user.getIdLong());
                event.reply(count == 0 ? warn("That user has no queued tracks.")
                        : ok("Removed `" + count + "` tracks requested by **" + markdown(user.getEffectiveName()) + "**.")).queue();
            }
            case "move" ->
            {
                int from = integer(event, "from", 0);
                int to = integer(event, "to", 0);
                List<QueuedTrack> snapshot = context.handler().getQueueSnapshot();
                int size = snapshot.size();
                if (from < 1 || to < 1 || from > size || to > size || from == to)
                {
                    error(event, "Both positions must be distinct values between 1 and " + size + ".");
                    return;
                }
                QueuedTrack expectedFrom = snapshot.get(from - 1);
                QueuedTrack expectedTo = snapshot.get(to - 1);
                QueuedTrack moved = context.handler().moveQueuedTrackIfMatches(
                        from - 1, to - 1, expectedFrom, expectedTo);
                if (moved == null)
                {
                    error(event, "The queue changed before the track could be moved. Try again.");
                    return;
                }
                event.reply(ok("Moved **" + markdown(moved.getTrack().getInfo().title) + "** from `"
                        + from + "` to `" + to + "`.")).queue();
            }
            case "pause" ->
            {
                AudioHandler.PlaybackState playback = context.handler().getPlaybackState();
                if (playback.paused())
                    event.reply(warn("Playback is already paused.")).setEphemeral(true).queue();
                else if(!context.handler().setPausedIfCurrent(playback.current(), true))
                {
                    error(event, "The current track changed before it could be paused. Try again.");
                }
                else
                {
                    event.reply(ok("Playback paused.")).queue();
                }
            }
            case "resume" ->
            {
                AudioHandler.PlaybackState playback = context.handler().getPlaybackState();
                AudioTrack track = playback.current();
                if (track == null || !playback.paused())
                    event.reply(warn("Playback is not paused.")).setEphemeral(true).queue();
                else if(!context.handler().setPausedIfCurrent(track, false))
                {
                    error(event, "The current track changed before it could be resumed. Try again.");
                }
                else
                {
                    event.reply(ok("Playback resumed.")).queue();
                }
            }
            case "skip-to" ->
            {
                AudioTrack current = context.handler().getPlaybackState().current();
                int position = integer(event, "position", 0);
                List<QueuedTrack> snapshot = context.handler().getQueueSnapshot();
                if (current == null || position < 1 || position > snapshot.size())
                {
                    error(event, "That track or queue position no longer exists.");
                    return;
                }
                QueuedTrack target = snapshot.get(position - 1);
                List<QueuedTrack> expectedPrefix = List.copyOf(snapshot.subList(0, position));
                if (!context.handler().skipToIfMatches(current, expectedPrefix))
                {
                    error(event, "Playback or the queue changed before the skip could be applied. Try again.");
                    return;
                }
                event.reply(ok("Skipped to **" + markdown(target.getTrack().getInfo().title) + "**.")).queue();
            }
            case "stop" ->
            {
                context.handler().stopAndClear();
                bot.closeAudioConnection(event.getGuild().getIdLong());
                event.reply(ok("Stopped playback, cleared the queue, and disconnected.")).queue();
            }
            case "volume" ->
            {
                OptionMapping value = event.getOption("value");
                if (value == null)
                    event.reply("Current volume is `" + context.handler().getPlaybackState().volume()
                            + "`.").setEphemeral(true).queue();
                else
                {
                    int volume = value.getAsInt();
                    int old = context.handler().setVolumeAndSettings(
                            volume, () -> context.settings().setVolume(volume));
                    event.reply(ok("Volume changed from `" + old + "` to `" + volume + "`.")).queue();
                }
            }
            default -> error(event, "Unknown DJ subcommand.");
        }
    }

    private void config(SlashCommandInteractionEvent event)
    {
        if (!hasManageServer(event))
        {
            error(event, "This command requires Manage Server permission.");
            return;
        }
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        String subcommand = event.getSubcommandName();
        switch (subcommand)
        {
            case "show" ->
            {
                TextChannel text = settings.getTextChannel(event.getGuild());
                VoiceChannel voice = settings.getVoiceChannel(event.getGuild());
                Role role = settings.getRole(event.getGuild());
                double ratio = settings.getSkipRatio() == -1 ? bot.getConfig().getSkipRatio() : settings.getSkipRatio();
                event.reply("**NeoMusicBot settings for " + markdown(event.getGuild().getName()) + "**\n"
                        + "Queue: `" + settings.getQueueType().getUserFriendlyName() + "`\n"
                        + "DJ role: " + (role == null ? "admins only" : role.getAsMention()) + "\n"
                        + "Skip ratio: `" + Math.round(ratio * 100) + "%`\n"
                        + "Text channel: " + (text == null ? (settings.hasTextChannelRestriction() ? "configured channel unavailable" : "any") : text.getAsMention()) + "\n"
                        + "Voice channel: " + (voice == null ? (settings.hasVoiceChannelRestriction() ? "configured channel unavailable" : "any") : voice.getAsMention()) + "\n"
                        + "Volume: `" + settings.getVolume() + "`\n"
                        + "Repeat: `" + settings.getRepeatMode().getUserFriendlyName() + "`")
                        .setEphemeral(true).queue();
            }
            case "queue-type" ->
            {
                QueueType type = QueueType.valueOf(string(event, "type").toUpperCase(Locale.ROOT));
                AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
                handler.setQueueTypeAndSettings(type, () -> settings.setQueueType(type));
                event.reply(ok("Queue type set to `" + type.getUserFriendlyName() + "`.")).setEphemeral(true).queue();
            }
            case "dj-role" ->
            {
                Role role = event.getOption("role") == null ? null : event.getOption("role").getAsRole();
                if (!exclusiveOrClear(event, role != null))
                    return;
                bot.getPlayerManager().setUpHandler(event.getGuild())
                        .updateSettings(() -> settings.setDJRole(role));
                event.reply(ok(role == null ? "DJ role cleared." : "DJ role set to " + role.getAsMention() + '.'))
                        .setEphemeral(true).queue();
            }
            case "skip-ratio" ->
            {
                int percent = integer(event, "percent", 55);
                bot.getPlayerManager().setUpHandler(event.getGuild())
                        .updateSettings(() -> settings.setSkipRatio(percent / 100.0));
                event.reply(ok("Skip ratio set to `" + percent + "%`.")).setEphemeral(true).queue();
            }
            case "text-channel" ->
            {
                TextChannel channel = event.getOption("channel") == null ? null
                        : event.getOption("channel").getAsChannel().asTextChannel();
                if (!exclusiveOrClear(event, channel != null))
                    return;
                bot.getPlayerManager().setUpHandler(event.getGuild())
                        .updateSettings(() -> settings.setTextChannel(channel));
                event.reply(ok(channel == null ? "Music commands are allowed in any text channel."
                        : "Music commands are restricted to " + channel.getAsMention() + '.'))
                        .setEphemeral(true).queue();
            }
            case "voice-channel" ->
            {
                VoiceChannel channel = event.getOption("channel") == null ? null
                        : event.getOption("channel").getAsChannel().asVoiceChannel();
                if (!exclusiveOrClear(event, channel != null))
                    return;
                bot.getPlayerManager().setUpHandler(event.getGuild())
                        .updateSettings(() -> settings.setVoiceChannel(channel));
                event.reply(ok(channel == null ? "Playback is allowed in any voice channel."
                        : "Playback is restricted to " + channel.getAsMention() + '.'))
                        .setEphemeral(true).queue();
            }
            default -> error(event, "Unknown configuration subcommand.");
        }
    }

    private boolean exclusiveOrClear(SlashCommandInteractionEvent event, boolean hasValue)
    {
        boolean clear = bool(event, "clear");
        if (clear == hasValue)
        {
            error(event, "Provide a value or set `clear`, not both.");
            return false;
        }
        return true;
    }

    private void owner(SlashCommandInteractionEvent event)
    {
        if (event.getUser().getIdLong() != bot.getConfig().getOwnerId())
        {
            error(event, "This command is restricted to the configured bot owner.");
            return;
        }
        switch (event.getSubcommandName())
        {
            case "playlist" -> ownerPlaylist(event);
            case "presence" -> ownerPresence(event);
            case "default-playlist" -> ownerDefaultPlaylist(event);
            case "avatar" -> ownerAvatar(event);
            case "username" -> ownerUsername(event);
            case "debug" -> ownerDebug(event);
            case "shutdown" -> event.reply(warn("Shutting down MusicBot...")).setEphemeral(true).queue(
                    ignored -> bot.getThreadpool().schedule(bot::shutdown, 1, TimeUnit.SECONDS));
            default -> error(event, "Unknown owner subcommand.");
        }
    }

    private void ownerPlaylist(SlashCommandInteractionEvent event)
    {
        String action = string(event, "action");
        String name = string(event, "name");
        if ("list".equals(action))
        {
            event.deferReply(true).queue(ignored ->
            {
                List<String> names = bot.getPlaylistLoader().getPlaylistNames();
                edit(event, names.isEmpty() ? "No local playlists are available."
                        : limit("**Local playlists**\n`" + String.join("`, `", names) + "`"));
            });
            return;
        }
        if (!validPlaylistName(name))
        {
            error(event, "Playlist names must be 1–64 safe letters, digits, dots, underscores, or dashes.");
            return;
        }

        event.deferReply(true).queue(ignored ->
        {
            try
            {
                switch (action)
                {
                    case "create" ->
                    {
                        if (bot.getPlaylistLoader().getPlaylist(name) != null)
                            edit(event, fail("Playlist already exists."));
                        else
                        {
                            bot.getPlaylistLoader().createPlaylist(name);
                            edit(event, ok("Created playlist `" + markdown(name) + "`."));
                        }
                    }
                    case "delete" ->
                    {
                        if (bot.getPlaylistLoader().getPlaylist(name) == null)
                            edit(event, fail("Playlist does not exist."));
                        else
                        {
                            bot.getPlaylistLoader().deletePlaylist(name);
                            edit(event, ok("Deleted playlist `" + markdown(name) + "`."));
                        }
                    }
                    case "append" ->
                    {
                        Playlist playlist = bot.getPlaylistLoader().getPlaylist(name);
                        String items = string(event, "items");
                        if (playlist == null || items == null || items.isBlank())
                        {
                            edit(event, fail("Append requires an existing playlist and one or more URLs."));
                            return;
                        }
                        List<String> all = new ArrayList<>(playlist.getItems());
                        for (String item : items.trim().split("\\s+"))
                        {
                            if (item.isBlank())
                                continue;
                            try
                            {
                                all.add(PersistentMediaReferencePolicy.normalize(item));
                            }
                            catch(IllegalArgumentException ex)
                            {
                                edit(event, fail("A playlist item is not a stable allow-listed media URL."));
                                return;
                            }
                        }
                        bot.getPlaylistLoader().writePlaylist(name, String.join(System.lineSeparator(), all));
                        edit(event, ok("Appended items to playlist `" + markdown(name) + "`."));
                    }
                    default -> edit(event, fail("Unknown playlist operation."));
                }
            }
            catch (IOException ex)
            {
                LOG.warn("Local playlist operation failed: {}", sanitizedFailure(ex));
                edit(event, fail("The playlist operation failed on disk."));
            }
        });
    }

    private void ownerPresence(SlashCommandInteractionEvent event)
    {
        String type = string(event, "type");
        String text = string(event, "text");
        if (!"clear".equals(type) && (text == null || text.isBlank()))
        {
            error(event, "Activity text is required unless the activity is cleared.");
            return;
        }
        Activity activity = switch (type)
        {
            case "playing" -> Activity.playing(text);
            case "listening" -> Activity.listening(text);
            case "watching" -> Activity.watching(text);
            case "clear" -> null;
            default -> throw new IllegalArgumentException("Unsupported activity type");
        };
        String statusValue = string(event, "status");
        if (statusValue != null)
        {
            OnlineStatus status = switch (statusValue)
            {
                case "online" -> OnlineStatus.ONLINE;
                case "idle" -> OnlineStatus.IDLE;
                case "dnd" -> OnlineStatus.DO_NOT_DISTURB;
                case "invisible" -> OnlineStatus.INVISIBLE;
                default -> OnlineStatus.ONLINE;
            };
            event.getJDA().getPresence().setStatus(status);
        }
        event.getJDA().getPresence().setActivity(activity);
        event.reply(ok("Presence updated.")).setEphemeral(true).queue();
    }

    private void ownerDefaultPlaylist(SlashCommandInteractionEvent event)
    {
        String name = string(event, "name");
        boolean clear = bool(event, "clear");
        event.deferReply(true).queue(ignored ->
        {
            try
            {
                bot.getThreadpool().execute(() ->
                {
                    if (clear == (name != null))
                    {
                        edit(event, fail("Provide a playlist name or set `clear`, not both."));
                        return;
                    }
                    if (!clear && bot.getPlaylistLoader().getPlaylist(name) == null)
                    {
                        edit(event, fail("That local playlist does not exist."));
                        return;
                    }
                    Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
                    AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
                    try
                    {
                        handler.getSession().execute(() ->
                        {
                            handler.updateDefaultPlaylistSetting(
                                    () -> settings.setDefaultPlaylist(clear ? null : name));
                            edit(event, ok(clear ? "Default playlist cleared."
                                    : "Default playlist set to `" + markdown(name) + "`."));
                        });
                    }
                    catch(RejectedExecutionException ex)
                    {
                        edit(event, fail("The default playlist cannot be changed while MusicBot is shutting down."));
                    }
                });
            }
            catch(RejectedExecutionException ex)
            {
                edit(event, fail("The default playlist cannot be changed while MusicBot is shutting down."));
            }
        });
    }

    private void ownerAvatar(SlashCommandInteractionEvent event)
    {
        Attachment image = attachment(event, "image");
        if (image.getSize() > 8L * 1024 * 1024
                || image.getContentType() == null || !image.getContentType().startsWith("image/"))
        {
            error(event, "Provide an image attachment no larger than 8 MiB.");
            return;
        }
        event.deferReply(true).queue(ignored -> image.getProxy().download().thenAccept(stream ->
        {
            try (InputStream input = stream)
            {
                Icon icon = Icon.from(input);
                event.getJDA().getSelfUser().getManager().setAvatar(icon).queue(
                        success -> edit(event, ok("Avatar updated.")),
                        failure -> edit(event, fail("Discord rejected the avatar update.")));
            }
            catch (IOException ex)
            {
                LOG.warn("Could not read avatar attachment: {}", sanitizedFailure(ex));
                edit(event, fail("Could not read the avatar attachment."));
            }
        }).exceptionally(error ->
        {
            LOG.warn("Could not download avatar attachment: {}", sanitizedFailure(error));
            edit(event, fail("Could not download the avatar attachment."));
            return null;
        }));
    }

    private void ownerUsername(SlashCommandInteractionEvent event)
    {
        String name = string(event, "name");
        event.deferReply(true).queue(ignored ->
        {
            try
            {
                event.getJDA().getSelfUser().getManager().setName(name).queue(
                        success -> edit(event, ok("Username updated.")),
                        failure -> edit(event, fail("Discord rejected the username update or it is rate-limited.")));
            }
            catch (IllegalArgumentException ex)
            {
                edit(event, fail("The username must be between 2 and 32 characters."));
            }
        });
    }

    private void ownerDebug(SlashCommandInteractionEvent event)
    {
        event.deferReply(true).queue(ignored ->
        {
            try
            {
                bot.getThreadpool().execute(() ->
                {
                    MediaResolverReadiness readiness = bot.getPlayerManager().getMediaResolverReadiness();
                    DiagnosticSnapshot versions = diagnosticSnapshot(readiness);
                    String jdaveVersion = packageVersion(JDaveSessionFactory.class, "0.1.8 (locked)");
                    boolean ytDlpReady = readiness.ytDlpAvailable()
                            && lockedVersionReady(versions.ytDlpVersion(), LOCKED_YT_DLP_VERSION);
                    boolean denoReady = readiness.denoAvailable()
                            && lockedVersionReady(versions.denoVersion(), LOCKED_DENO_VERSION);
                    boolean fallbackReady = readiness.youtubeFallbackReady() && ytDlpReady && denoReady;
                    String provider = !readiness.potProviderEnabled() ? "off"
                            : readiness.potProviderReady() && ytDlpReady && denoReady
                                    ? "2.0.0 (assets present)" : "2.0.0 (unavailable)";
                    String youtubeFallback = featureState(readiness.youtubeFallbackEnabled(),
                            fallbackReady);
                    String ejs = fallbackReady
                            ? "configured (standalone bundle + Deno)" : "unavailable";
                    String bilibili = ytDlpReady ? "ready" : "unavailable";

                    edit(event, "**Sanitized runtime diagnostics**\n"
                            + "MusicBot: `" + markdown(OtherUtil.getCurrentVersion()) + "`\n"
                            + "JDA: `" + markdown(JDAInfo.VERSION) + "`\n"
                            + "JDAVE: `" + markdown(jdaveVersion) + "`\n"
                            + "Java: `" + markdown(System.getProperty("java.version")) + "`\n"
                            + "Gateway latency: `" + event.getJDA().getGatewayPing() + " ms`\n"
                            + "Guilds: `" + event.getJDA().getGuilds().size() + "`\n"
                            + "yt-dlp: `" + markdown(versionState(versions.ytDlpVersion(),
                                    readiness.ytDlpAvailable(), LOCKED_YT_DLP_VERSION)) + "`\n"
                            + "Deno: `" + markdown(versionState(versions.denoVersion(),
                                    readiness.denoAvailable(), LOCKED_DENO_VERSION)) + "`\n"
                            + "EJS/runtime: `" + ejs + "`\n"
                            + "PO provider: `" + provider + "`\n"
                            + "YouTube Java source: `ready`\n"
                            + "YouTube fallback: `" + youtubeFallback + "`\n"
                            + "Bilibili source: `" + bilibili + "`");
                });
            }
            catch (RejectedExecutionException ex)
            {
                edit(event, fail("Diagnostics are unavailable while MusicBot is shutting down."));
            }
        });
    }

    private DiagnosticSnapshot diagnosticSnapshot(MediaResolverReadiness readiness)
    {
        long now = System.currentTimeMillis();
        DiagnosticSnapshot snapshot = cachedDiagnostics;
        if (snapshot != null && snapshot.expiresAtMillis() > now)
            return snapshot;

        synchronized (diagnosticsLock)
        {
            snapshot = cachedDiagnostics;
            if (snapshot != null && snapshot.expiresAtMillis() > now)
                return snapshot;

            String ytDlp = readiness.ytDlpAvailable()
                    ? probeVersion(executable("JMUSICBOT_YTDLP_PATH", "yt-dlp"), YT_DLP_VERSION)
                    : "missing";
            String deno = readiness.denoAvailable()
                    ? probeVersion(executable("JMUSICBOT_DENO_PATH", "deno"), DENO_VERSION)
                    : "missing";
            snapshot = new DiagnosticSnapshot(ytDlp, deno, now + DIAGNOSTIC_CACHE_MILLIS);
            cachedDiagnostics = snapshot;
            return snapshot;
        }
    }

    private static String executable(String environmentName, String fallback)
    {
        String value = System.getenv(environmentName);
        String executable = value == null || value.isBlank() ? fallback : value.trim();
        return executable.indexOf('\0') >= 0 || executable.indexOf('\r') >= 0
                || executable.indexOf('\n') >= 0 ? null : executable;
    }

    private static String probeVersion(String executable, Pattern acceptedVersion)
    {
        if (executable == null)
            return "unavailable";
        Process process = null;
        FutureTask<String> outputReader = null;
        try
        {
            ProcessBuilder builder = new ProcessBuilder(List.of(executable, "--version"))
                    .redirectErrorStream(true);
            configureProbeEnvironment(builder.environment(), System.getenv());
            process = builder.start();
            Process started = process;
            outputReader = new FutureTask<>(() -> readVersionOutput(started.getInputStream()));
            Thread.ofVirtual().name("media-version-probe").start(outputReader);

            if (!process.waitFor(5, TimeUnit.SECONDS))
            {
                terminateProcessTree(process);
                outputReader.cancel(true);
                return "timeout";
            }
            String output = outputReader.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || output == null)
                return "unavailable";
            var matcher = acceptedVersion.matcher(output);
            return matcher.find() ? matcher.group(1) : "unknown";
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
        catch (Exception ex)
        {
            return "unavailable";
        }
        finally
        {
            if (process != null && process.isAlive())
                terminateProcessTree(process);
            if (outputReader != null && !outputReader.isDone())
                outputReader.cancel(true);
        }
    }


    static void configureProbeEnvironment(Map<String, String> environment,
                                          Map<String, String> parentEnvironment)
    {
        environment.clear();
        String[] allowed = {
                "PATH", "Path", "HOME", "USERPROFILE", "SystemRoot", "WINDIR", "PATHEXT",
                "TEMP", "TMP", "TMPDIR", "APPDATA", "LOCALAPPDATA", "XDG_CACHE_HOME", "DENO_DIR",
                "SSL_CERT_FILE", "SSL_CERT_DIR", "TOKEN_TTL"
        };
        for(String name : allowed)
        {
            String value = parentEnvironment.get(name);
            if(value != null)
                environment.put(name, value);
        }
        environment.put("NO_COLOR", "1");
        environment.put("PYTHONUTF8", "1");
        environment.put("DENO_NO_PROMPT", "1");
        environment.put("DENO_NO_UPDATE_CHECK", "1");
    }

    private static String readVersionOutput(InputStream stream) throws IOException
    {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            byte[] buffer = new byte[256];
            int read;
            while ((read = input.read(buffer)) != -1)
            {
                if (output.size() + read > VERSION_OUTPUT_LIMIT)
                    return null;
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static void terminateProcessTree(Process process)
    {
        try
        {
            process.toHandle().descendants().forEach(handle ->
            {
                try
                {
                    handle.destroy();
                    if (handle.isAlive())
                        handle.destroyForcibly();
                }
                catch (RuntimeException ignored)
                {
                }
            });
            process.destroy();
            if (process.isAlive())
                process.destroyForcibly();
        }
        catch (RuntimeException ignored)
        {
            process.destroyForcibly();
        }
    }

    private static String packageVersion(Class<?> type, String fallback)
    {
        String version = type.getPackage() == null ? null : type.getPackage().getImplementationVersion();
        return version != null && version.matches("[A-Za-z0-9._+-]{1,48}") ? version : fallback;
    }

    static boolean lockedVersionReady(String version, String lockedVersion)
    {
        return lockedVersion.equals(version);
    }

    static String versionState(String version, boolean available, String lockedVersion)
    {
        if (!available)
            return "missing";
        if (lockedVersionReady(version, lockedVersion))
            return version + " (ready)";
        if (version != null && version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?"))
            return version + " (unsupported; expected " + lockedVersion + ")";
        return version == null ? "unavailable" : version;
    }

    private static String featureState(boolean enabled, boolean ready)
    {
        return !enabled ? "off" : ready ? "ready" : "unavailable";
    }

    private void load(SlashCommandInteractionEvent event, MusicContext context, PlaybackLoadToken loadToken,
                      String identifier, boolean front, boolean allowPlaylist, boolean searched)
    {
        if(!context.handler().isLoadValid(loadToken))
            return;
        try
        {
            bot.getPlayerManager().loadItemOrdered(event.getGuild().getIdLong(), identifier,
                    new AudioLoadResultHandler()
                    {
                        private void addSingle(AudioTrack track)
                        {
                            if (bot.getConfig().isTooLong(track))
                            {
                                if(context.handler().finishPendingLoad(loadToken))
                                    edit(event, warn("**" + markdown(track.getInfo().title)
                                            + "** exceeds the configured maximum duration."));
                                return;
                            }
                            QueuedTrack queued = new QueuedTrack(track,
                                    RequestMetadata.fromRequest(event.getUser(), identifier));
                            Integer position = context.handler().addTrackAndCompleteLoad(loadToken, queued, front);
                            if(position == null)
                            {
                                edit(event, warn("This media request was canceled."));
                                return;
                            }
                            edit(event, ok("Added **" + markdown(track.getInfo().title) + "** `"
                                    + TimeUtil.formatTime(track.getDuration()) + "` "
                                    + (position < 0 ? "and started playback."
                                    : "at queue position `" + (position + 1) + "`.")));
                        }

                        @Override
                        public void trackLoaded(AudioTrack track)
                        {
                            addSingle(track);
                        }

                        @Override
                        public void playlistLoaded(AudioPlaylist playlist)
                        {
                            if (playlist.getTracks().isEmpty())
                            {
                                if(context.handler().finishPendingLoad(loadToken))
                                    edit(event, warn("The playlist contains no playable tracks."));
                                return;
                            }
                            if (playlist.isSearchResult() || !allowPlaylist)
                            {
                                AudioTrack selected = playlist.getSelectedTrack() == null
                                        ? playlist.getTracks().get(0) : playlist.getSelectedTrack();
                                addSingle(selected);
                                return;
                            }

                            List<QueuedTrack> accepted = new ArrayList<>();
                            for (AudioTrack track : playlist.getTracks())
                            {
                                if (!bot.getConfig().isTooLong(track))
                                    accepted.add(new QueuedTrack(track,
                                            RequestMetadata.fromRequest(event.getUser(), identifier)));
                            }
                            if(accepted.isEmpty())
                            {
                                if(context.handler().finishPendingLoad(loadToken))
                                    edit(event, warn("No playlist tracks passed the duration limit."));
                                return;
                            }
                            Integer firstPosition = context.handler().addTracksAndCompleteLoad(
                                    loadToken, accepted, front);
                            if(firstPosition == null)
                            {
                                edit(event, warn("This media request was canceled."));
                                return;
                            }
                            edit(event, ok("Added `" + accepted.size() + "` tracks from **"
                                    + markdown(playlist.getName()) + "**."));
                        }

                        @Override
                        public void noMatches()
                        {
                            if (!searched && !looksLikeUrl(identifier)
                                    && context.handler().isLoadValid(loadToken))
                                load(event, context, loadToken, "ytsearch:" + identifier,
                                        front, allowPlaylist, true);
                            else if(context.handler().finishPendingLoad(loadToken))
                                edit(event, warn("No playable media matched the request."));
                        }

                        @Override
                        public void loadFailed(FriendlyException exception)
                        {
                            LOG.warn("Media load failed [{}]: {}",
                                    exception.severity, sanitizedFailure(exception));
                            if(context.handler().finishPendingLoad(loadToken))
                                edit(event, fail("Media loading failed. Try another URL or try again later."));
                        }
                    });
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Could not start media load: {}", sanitizedFailure(ex));
            if(context.handler().finishPendingLoad(loadToken))
                edit(event, fail("Media loading could not be started."));
        }
    }

    private MusicContext musicContext(SlashCommandInteractionEvent event, boolean requirePlaying,
                                      boolean requireListening)
    {
        if (!event.isFromGuild() || event.getGuild() == null || event.getMember() == null)
        {
            error(event, "This command can only be used in a server.");
            return null;
        }
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        if (!settings.allowsTextChannel(event.getChannelIdLong()))
        {
            error(event, "Music commands are restricted to the configured text channel. Ask an admin to update /config if it is unavailable.");
            return null;
        }

        AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
        if (requirePlaying && handler.getPlaybackState().current() == null)
        {
            error(event, "Music must be playing to use this command.");
            return null;
        }

        if (requireListening && !ensureListening(event, settings, handler))
            return null;
        return new MusicContext(settings, handler);
    }

    private boolean ensureListening(SlashCommandInteractionEvent event, Settings settings, AudioHandler handler)
    {
        GuildVoiceState userState = event.getMember().getVoiceState();
        if (userState == null || !userState.inAudioChannel() || userState.isDeafened())
        {
            error(event, "Join a voice channel and make sure you are not deafened first.");
            return false;
        }
        AudioChannelUnion userChannel = userState.getChannel();
        if (userChannel.getType() != ChannelType.VOICE)
        {
            error(event, "Stage channels are not supported; join a standard voice channel.");
            return false;
        }
        VoiceChannel afk = event.getGuild().getAfkChannel();
        if (afk != null && afk.getIdLong() == userChannel.getIdLong())
        {
            error(event, "Music commands cannot be used from the AFK channel.");
            return false;
        }

        if (!settings.allowsVoiceChannel(userChannel.getIdLong()))
        {
            error(event, "Join the configured voice channel. Ask an admin to update /config if it is unavailable.");
            return false;
        }

        long channelId = userChannel.getIdLong();
        GuildPlaybackSession.VoiceReservation reservation = handler.reserveVoiceChannel(channelId);
        if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.CONFLICT)
        {
            VoiceChannel target = event.getGuild().getVoiceChannelById(reservation.channelId());
            error(event, target == null ? "I am already connecting to another voice channel."
                    : "You must be listening in " + target.getAsMention() + '.');
            return false;
        }
        if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.CLOSING)
        {
            error(event, "The previous voice connection is still closing; try again in a moment.");
            return false;
        }
        if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.ALREADY_RESERVED)
            return true;

        if (!event.getGuild().getSelfMember().hasPermission(userChannel,
                Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
        {
            handler.rollbackVoiceReservation(channelId);
            error(event, "I cannot connect and speak in " + userChannel.getAsMention() + '.');
            return false;
        }
        try
        {
            event.getGuild().getAudioManager().setSelfDeafened(true);
            event.getGuild().getAudioManager().setAutoReconnect(true);
            event.getGuild().getAudioManager().openAudioConnection(userChannel);
            return true;
        }
        catch(RuntimeException ex)
        {
            handler.rollbackVoiceReservation(channelId);
            LOG.warn("Could not open voice connection: {}", sanitizedFailure(ex));
            error(event, "The voice connection could not be opened.");
            return false;
        }
    }

    private MusicContext componentMusicContext(StringSelectInteractionEvent event)
    {
        if (!event.isFromGuild() || event.getGuild() == null || event.getMember() == null)
        {
            componentError(event, "This control can only be used in a server.");
            return null;
        }
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        if (!settings.allowsTextChannel(event.getChannelIdLong()))
        {
            componentError(event, "Music controls are restricted to the configured text channel. Ask an admin to update /config if it is unavailable.");
            return null;
        }
        AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
        if (!ensureComponentListening(event, settings, handler))
            return null;
        return new MusicContext(settings, handler);
    }

    private boolean ensureComponentListening(StringSelectInteractionEvent event, Settings settings,
                                             AudioHandler handler)
    {
        GuildVoiceState userState = event.getMember().getVoiceState();
        if (userState == null || !userState.inAudioChannel() || userState.isDeafened())
        {
            componentError(event, "Join a voice channel and make sure you are not deafened first.");
            return false;
        }
        AudioChannelUnion userChannel = userState.getChannel();
        if (userChannel.getType() != ChannelType.VOICE)
        {
            componentError(event, "Stage channels are not supported; join a standard voice channel.");
            return false;
        }
        VoiceChannel afk = event.getGuild().getAfkChannel();
        if (afk != null && afk.getIdLong() == userChannel.getIdLong())
        {
            componentError(event, "Music controls cannot be used from the AFK channel.");
            return false;
        }

        if (!settings.allowsVoiceChannel(userChannel.getIdLong()))
        {
            componentError(event, "Join the configured voice channel. Ask an admin to update /config if it is unavailable.");
            return false;
        }

        long channelId = userChannel.getIdLong();
        GuildPlaybackSession.VoiceReservation reservation = handler.reserveVoiceChannel(channelId);
        if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.CONFLICT)
        {
            VoiceChannel target = event.getGuild().getVoiceChannelById(reservation.channelId());
            componentError(event, target == null ? "I am already connecting to another voice channel."
                    : "You must be listening in " + target.getAsMention() + '.');
            return false;
        }
        if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.CLOSING)
        {
            componentError(event, "The previous voice connection is still closing; try again in a moment.");
            return false;
        }
        if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.ALREADY_RESERVED)
            return true;

        if (!event.getGuild().getSelfMember().hasPermission(userChannel,
                Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
        {
            handler.rollbackVoiceReservation(channelId);
            componentError(event, "I cannot connect and speak in " + userChannel.getAsMention() + '.');
            return false;
        }
        try
        {
            event.getGuild().getAudioManager().setSelfDeafened(true);
            event.getGuild().getAudioManager().setAutoReconnect(true);
            event.getGuild().getAudioManager().openAudioConnection(userChannel);
            return true;
        }
        catch(RuntimeException ex)
        {
            handler.rollbackVoiceReservation(channelId);
            LOG.warn("Could not open voice connection for search control: {}", sanitizedFailure(ex));
            componentError(event, "The voice connection could not be opened.");
            return false;
        }
    }

    private ComponentLookup componentSession(GenericComponentInteractionCreateEvent event, Set<String> actions)
    {
        var matcher = COMPONENT_ID.matcher(event.getComponentId());
        if (!matcher.matches())
            return null;

        String id = matcher.group(1);
        String action = matcher.group(2);
        ComponentSession session = componentSessions.get(id);
        if (!actions.contains(action) || session == null
                || System.currentTimeMillis() >= session.expiresAtMillis())
        {
            expireInteraction(event, id);
            return null;
        }
        if (!event.isFromGuild() || event.getGuild() == null
                || event.getGuild().getIdLong() != session.guildId())
        {
            componentError(event, "This control is not valid in this server.");
            return null;
        }
        if (event.getUser().getIdLong() != session.userId())
        {
            componentError(event, "Only the person who opened this control can use it.");
            return null;
        }
        return new ComponentLookup(id, action, session);
    }

    private void expireInteraction(GenericComponentInteractionCreateEvent event, String id)
    {
        ComponentSession removed = componentSessions.remove(id);
        releaseComponentSession(removed);
        event.getMessage().editMessageComponents().queue(
                ignored -> { }, failure -> LOG.debug("Could not clear expired components", failure));
        componentError(event, "This control has expired. Run the command again.");
    }

    private void componentError(GenericComponentInteractionCreateEvent event, String message)
    {
        event.reply(fail(message)).setEphemeral(true).queue();
    }

    private String newSessionId()
    {
        String id;
        do
        {
            id = UUID.randomUUID().toString().replace("-", "");
        }
        while (componentSessions.containsKey(id));
        return id;
    }

    private static String componentId(String id, String action)
    {
        return "mb:" + id + ':' + action;
    }

    private void scheduleExpiration(String id)
    {
        try
        {
            bot.getThreadpool().schedule(() ->
            {
                ComponentSession session = componentSessions.remove(id);
                releaseComponentSession(session);
                if (session != null && !session.hook().isExpired())
                    session.hook().editOriginalComponents().queue(
                            ignored -> { }, failure -> LOG.debug("Could not clear expired components", failure));
            }, COMPONENT_TTL_SECONDS, TimeUnit.SECONDS);
        }
        catch (RejectedExecutionException ignored)
        {
            ComponentSession session = componentSessions.remove(id);
            releaseComponentSession(session);
            if (session != null && !session.hook().isExpired())
                session.hook().editOriginalComponents().queue();
        }
    }

    private void releaseComponentSession(ComponentSession session)
    {
        if(session instanceof SearchSession search)
        {
            try
            {
                search.handler().getSession().execute(
                        () -> search.handler().finishPendingLoad(search.loadToken()));
            }
            catch(RejectedExecutionException ignored)
            {
                // The bot is shutting down and the playback executor is no longer accepting work.
            }
        }
    }

    private static String componentText(String value, int maxLength)
    {
        String safe = redact(value).replace("\r", " ").replace("\n", " ")
                .replace("@", "＠").trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
    }

    private boolean isDj(SlashCommandInteractionEvent event)
    {
        if (event.getUser().getIdLong() == bot.getConfig().getOwnerId())
            return true;
        Member member = event.getMember();
        if (member == null || event.getGuild() == null)
            return false;
        if (member.hasPermission(Permission.MANAGE_SERVER))
            return true;
        Role role = bot.getSettingsManager().getSettings(event.getGuild()).getRole(event.getGuild());
        return role != null && (member.getRoles().contains(role) || role.isPublicRole());
    }

    private boolean hasManageServer(SlashCommandInteractionEvent event)
    {
        return event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER);
    }

    private static RequestMetadata metadata(AudioTrack track)
    {
        if (track == null)
            return RequestMetadata.EMPTY;
        RequestMetadata metadata = track.getUserData(RequestMetadata.class);
        return metadata == null ? RequestMetadata.EMPTY : metadata;
    }

    private static String string(SlashCommandInteractionEvent event, String name)
    {
        OptionMapping option = event.getOption(name);
        if (option == null)
            return null;
        String value = option.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static int integer(SlashCommandInteractionEvent event, String name, int fallback)
    {
        OptionMapping option = event.getOption(name);
        return option == null ? fallback : option.getAsInt();
    }

    private static boolean bool(SlashCommandInteractionEvent event, String name)
    {
        OptionMapping option = event.getOption(name);
        return option != null && option.getAsBoolean();
    }

    private static Attachment attachment(SlashCommandInteractionEvent event, String name)
    {
        OptionMapping option = event.getOption(name);
        return option == null ? null : option.getAsAttachment();
    }

    private static String unwrap(String query)
    {
        return query.startsWith("<") && query.endsWith(">") && query.length() > 2
                ? query.substring(1, query.length() - 1) : query;
    }

    private static boolean looksLikeUrl(String value)
    {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean validPlaylistName(String name)
    {
        return name != null && !name.contains("..") && SAFE_PLAYLIST_NAME.matcher(name).matches();
    }

    private static boolean envConfigured(String name)
    {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private void error(SlashCommandInteractionEvent event, String message)
    {
        String safe = fail(message);
        if (event.isAcknowledged())
            edit(event, safe);
        else
            event.reply(safe).setEphemeral(true).queue();
    }

    private void edit(SlashCommandInteractionEvent event, String message)
    {
        event.getHook().editOriginal(limit(redact(message))).queue(
                ignored -> { }, failure -> LOG.warn("Could not edit Slash command response", failure));
    }

    static String sanitizedFailure(Throwable error)
    {
        if (error == null)
            return "UnknownFailure: no details";
        String type = error.getClass().getSimpleName();
        String message = redact(error.getMessage()).trim();
        return type + ": " + (message.isEmpty() ? "no details" : message);
    }

    private String ok(String message)
    {
        return bot.getConfig().getSuccess() + ' ' + message;
    }

    private String warn(String message)
    {
        return bot.getConfig().getWarning() + ' ' + message;
    }

    private String fail(String message)
    {
        return bot.getConfig().getError() + ' ' + message;
    }

    private static String markdown(String value)
    {
        if (value == null)
            return "unknown";
        return redact(value).replace("`", "'")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("@", "@\u200B")
                .replace("\u202E", "");
    }

    private static String safeUri(String value)
    {
        if (value == null || !looksLikeUrl(value))
            return "`no public URL`";
        return '<' + URL_QUERY.matcher(value).replaceAll("$1?<redacted>") + '>';
    }

    private static String redact(String value)
    {
        if (value == null)
            return "";
        return SensitiveLogSanitizer.sanitize(value);
    }

    private static String limit(String value)
    {
        String safe = value == null ? "" : value;
        return safe.length() <= 2_000 ? safe : safe.substring(0, 1_994) + " (…)";
    }

    private interface ComponentSession
    {
        long userId();
        long guildId();
        long expiresAtMillis();
        InteractionHook hook();
    }

    private record SearchChoice(String stableId, AudioTrack prototype)
    {
    }

    private record SearchSession(long userId, long guildId, long expiresAtMillis, InteractionHook hook,
                                 AudioHandler handler, PlaybackLoadToken loadToken, List<SearchChoice> choices)
            implements ComponentSession
    {
    }

    private static final class QueueSession implements ComponentSession
    {
        private final long userId;
        private final long guildId;
        private final long expiresAtMillis;
        private final InteractionHook hook;
        private final List<QueueEntry> entries;
        private int page;

        private QueueSession(long userId, long guildId, long expiresAtMillis,
                             InteractionHook hook, List<QueueEntry> entries, int page)
        {
            this.userId = userId;
            this.guildId = guildId;
            this.expiresAtMillis = expiresAtMillis;
            this.hook = hook;
            this.entries = List.copyOf(entries);
            this.page = page;
        }

        @Override
        public long userId()
        {
            return userId;
        }

        @Override
        public long guildId()
        {
            return guildId;
        }

        @Override
        public long expiresAtMillis()
        {
            return expiresAtMillis;
        }

        @Override
        public InteractionHook hook()
        {
            return hook;
        }

        private List<QueueEntry> entries()
        {
            return entries;
        }

        private int page()
        {
            return page;
        }

        private void setPage(int page)
        {
            this.page = page;
        }

        private int pages()
        {
            return Math.max(1, (entries.size() + QUEUE_PAGE_SIZE - 1) / QUEUE_PAGE_SIZE);
        }
    }

    private record QueueEntry(String title, long duration, long ownerId)
    {
    }

    private record ComponentLookup(String id, String action, ComponentSession session)
    {
    }

    private record DiagnosticSnapshot(String ytDlpVersion, String denoVersion, long expiresAtMillis)
    {
    }

    private record MusicContext(Settings settings, AudioHandler handler)
    {
    }
}
