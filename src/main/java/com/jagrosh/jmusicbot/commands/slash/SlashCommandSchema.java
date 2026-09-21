package com.jagrosh.jmusicbot.commands.slash;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

/** The complete public Slash command schema. */
final class SlashCommandSchema
{
    private SlashCommandSchema()
    {
    }

    static List<CommandData> create()
    {
        List<CommandData> commands = new ArrayList<>();
        commands.add(Commands.slash("about", "Show information about MusicBot"));
        commands.add(Commands.slash("ping", "Show the Discord gateway latency"));
        commands.add(Commands.slash("help", "Show the available MusicBot commands"));

        commands.add(guild(Commands.slash("play", "Play a URL, attachment, or YouTube search result")
                .addOption(OptionType.STRING, "query", "Song title or supported URL")
                .addOption(OptionType.ATTACHMENT, "attachment", "Audio attachment to play")));
        commands.add(guild(Commands.slash("search", "Search YouTube without changing the queue")
                .addOption(OptionType.STRING, "query", "Search terms", true)));
        commands.add(guild(Commands.slash("now-playing", "Show the current track")));
        commands.add(guild(Commands.slash("queue", "Show a page of the current queue")
                .addOptions(new OptionData(OptionType.INTEGER, "page", "Queue page")
                        .setRequiredRange(1, 1_000))));
        commands.add(guild(Commands.slash("remove", "Remove one of your queued tracks")
                .addOptions(new OptionData(OptionType.INTEGER, "position", "One-based queue position")
                        .setRequiredRange(1, 10_000))
                .addOption(OptionType.BOOLEAN, "all", "Remove all tracks you requested")));
        commands.add(guild(Commands.slash("seek", "Seek the current track")
                .addOption(OptionType.STRING, "position", "Examples: 1:30, +20s, -10", true)));
        commands.add(guild(Commands.slash("shuffle", "Shuffle the tracks you requested")));
        commands.add(guild(Commands.slash("skip", "Vote to skip the current track")));

        commands.add(guild(Commands.slash("playlist", "List or play a local playlist")
                .addSubcommands(
                        new SubcommandData("list", "List available local playlists"),
                        new SubcommandData("play", "Play a local playlist")
                                .addOptions(playlistName("name", true)))));

        commands.add(guild(Commands.slash("dj", "DJ playback controls")
                .addSubcommands(
                        new SubcommandData("force-skip", "Skip the current track immediately"),
                        new SubcommandData("force-remove", "Remove all queue entries from a user")
                                .addOption(OptionType.USER, "user", "User whose entries should be removed", true),
                        new SubcommandData("move", "Move a queued track")
                                .addOptions(integer("from", "Current one-based position", true, 1, 10_000),
                                        integer("to", "New one-based position", true, 1, 10_000)),
                        new SubcommandData("pause", "Pause playback"),
                        new SubcommandData("resume", "Resume playback"),
                        new SubcommandData("play-next", "Queue a single track next")
                                .addOption(OptionType.STRING, "query", "Song title or supported URL", true),
                        new SubcommandData("repeat", "Set repeat mode")
                                .addOptions(new OptionData(OptionType.STRING, "mode", "Repeat mode", true)
                                        .addChoice("Off", "off")
                                        .addChoice("All", "all")
                                        .addChoice("Single", "single")),
                        new SubcommandData("skip-to", "Skip to a queue position")
                                .addOptions(integer("position", "One-based queue position", true, 1, 10_000)),
                        new SubcommandData("stop", "Stop playback, clear the queue, and disconnect"),
                        new SubcommandData("volume", "Show or set playback volume")
                                .addOptions(integer("value", "Volume from 0 to 150", false, 0, 150)))));

        SlashCommandData config = guild(Commands.slash("config", "Configure MusicBot for this server")
                .addSubcommands(
                        new SubcommandData("show", "Show this server's MusicBot settings"),
                        new SubcommandData("queue-type", "Set fair or linear queueing")
                                .addOptions(new OptionData(OptionType.STRING, "type", "Queue algorithm", true)
                                        .addChoice("Fair", "fair")
                                        .addChoice("Linear", "linear")),
                        new SubcommandData("dj-role", "Set or clear the DJ role")
                                .addOption(OptionType.ROLE, "role", "Role allowed to use DJ controls")
                                .addOption(OptionType.BOOLEAN, "clear", "Clear the configured role"),
                        new SubcommandData("skip-ratio", "Set the percentage of listeners needed to skip")
                                .addOptions(integer("percent", "Percentage from 0 to 100", true, 0, 100)),
                        new SubcommandData("text-channel", "Restrict music commands to a text channel")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Allowed text channel")
                                        .setChannelTypes(ChannelType.TEXT))
                                .addOption(OptionType.BOOLEAN, "clear", "Allow commands in any channel"),
                        new SubcommandData("voice-channel", "Restrict playback to a voice channel")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Allowed voice channel")
                                        .setChannelTypes(ChannelType.VOICE))
                                .addOption(OptionType.BOOLEAN, "clear", "Allow playback in any voice channel")))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
        commands.add(config);

        commands.add(guild(Commands.slash("owner", "Bot-owner maintenance commands")
                .addSubcommands(
                        new SubcommandData("playlist", "Manage a local playlist")
                                .addOptions(new OptionData(OptionType.STRING, "action", "Operation", true)
                                                .addChoice("List", "list")
                                                .addChoice("Create", "create")
                                                .addChoice("Delete", "delete")
                                                .addChoice("Append", "append"),
                                        playlistName("name", false),
                                        new OptionData(OptionType.STRING, "items", "URLs separated by whitespace")),
                        new SubcommandData("presence", "Set the bot activity and status")
                                .addOptions(new OptionData(OptionType.STRING, "type", "Activity type", true)
                                                .addChoice("Clear", "clear")
                                                .addChoice("Playing", "playing")
                                                .addChoice("Listening", "listening")
                                                .addChoice("Watching", "watching"),
                                        new OptionData(OptionType.STRING, "text", "Activity text"),
                                        new OptionData(OptionType.STRING, "status", "Online status")
                                                .addChoice("Online", "online")
                                                .addChoice("Idle", "idle")
                                                .addChoice("Do not disturb", "dnd")
                                                .addChoice("Invisible", "invisible")),
                        new SubcommandData("default-playlist", "Set or clear this server's autoplay playlist")
                                .addOptions(playlistName("name", false))
                                .addOption(OptionType.BOOLEAN, "clear", "Clear the autoplay playlist"),
                        new SubcommandData("avatar", "Set the bot avatar from an attachment")
                                .addOption(OptionType.ATTACHMENT, "image", "PNG, JPEG, WebP, or GIF image", true),
                        new SubcommandData("username", "Change the bot username")
                                .addOption(OptionType.STRING, "name", "New username", true),
                        new SubcommandData("debug", "Show sanitized runtime diagnostics"),
                        new SubcommandData("shutdown", "Shut down MusicBot cleanly"))));

        return List.copyOf(commands);
    }

    private static SlashCommandData guild(SlashCommandData command)
    {
        return command.setContexts(InteractionContextType.GUILD);
    }

    private static OptionData playlistName(String name, boolean required)
    {
        return new OptionData(OptionType.STRING, name, "Playlist name", required).setAutoComplete(true);
    }

    private static OptionData integer(String name, String description, boolean required, long min, long max)
    {
        return new OptionData(OptionType.INTEGER, name, description, required).setRequiredRange(min, max);
    }
}
