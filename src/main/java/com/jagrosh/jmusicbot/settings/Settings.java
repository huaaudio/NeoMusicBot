/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh <john.a.grosh@gmail.com>
 */
/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.settings;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;

/**
 * Per-guild settings. This type is deliberately independent from any command
 * framework; Slash commands and playback services share the same settings.
 */
public class Settings
{
    private final SettingsManager manager;
    protected volatile long textId;
    protected volatile long voiceId;
    protected volatile long roleId;
    private volatile int volume;
    private volatile String defaultPlaylist;
    private volatile RepeatMode repeatMode;
    private volatile QueueType queueType;
    private volatile double skipRatio;

    public Settings(SettingsManager manager, String textId, String voiceId, String roleId,
                    int volume, String defaultPlaylist, RepeatMode repeatMode,
                    double skipRatio, QueueType queueType)
    {
        this(manager, parseId(textId), parseId(voiceId), parseId(roleId), volume,
                defaultPlaylist, repeatMode, skipRatio, queueType);
    }

    public Settings(SettingsManager manager, long textId, long voiceId, long roleId,
                    int volume, String defaultPlaylist, RepeatMode repeatMode,
                    double skipRatio, QueueType queueType)
    {
        this.manager = manager;
        this.textId = textId;
        this.voiceId = voiceId;
        this.roleId = roleId;
        this.volume = volume;
        this.defaultPlaylist = defaultPlaylist;
        this.repeatMode = repeatMode;
        this.skipRatio = skipRatio;
        this.queueType = queueType;
    }

    private static long parseId(String id)
    {
        if (id == null)
            return 0;
        try
        {
            return Long.parseLong(id);
        }
        catch (NumberFormatException ignored)
        {
            return 0;
        }
    }

    public TextChannel getTextChannel(Guild guild)
    {
        return guild == null || textId == 0 ? null : guild.getTextChannelById(textId);
    }

    public VoiceChannel getVoiceChannel(Guild guild)
    {
        return guild == null || voiceId == 0 ? null : guild.getVoiceChannelById(voiceId);
    }

    public Role getRole(Guild guild)
    {
        return guild == null || roleId == 0 ? null : guild.getRoleById(roleId);
    }

    public int getVolume()
    {
        return volume;
    }

    public String getDefaultPlaylist()
    {
        return defaultPlaylist;
    }

    public RepeatMode getRepeatMode()
    {
        return repeatMode;
    }

    public double getSkipRatio()
    {
        return skipRatio;
    }

    public QueueType getQueueType()
    {
        return queueType;
    }

    public void setTextChannel(TextChannel channel)
    {
        manager.updateSettings(() -> textId = channel == null ? 0 : channel.getIdLong());
    }

    public void setVoiceChannel(VoiceChannel channel)
    {
        manager.updateSettings(() -> voiceId = channel == null ? 0 : channel.getIdLong());
    }

    public void setDJRole(Role role)
    {
        manager.updateSettings(() -> roleId = role == null ? 0 : role.getIdLong());
    }

    public void setVolume(int volume)
    {
        manager.updateSettings(() -> this.volume = volume);
    }

    public void setDefaultPlaylist(String defaultPlaylist)
    {
        manager.updateSettings(() -> this.defaultPlaylist = defaultPlaylist);
    }

    public void setRepeatMode(RepeatMode mode)
    {
        manager.updateSettings(() -> repeatMode = mode);
    }

    public void setSkipRatio(double skipRatio)
    {
        manager.updateSettings(() -> this.skipRatio = skipRatio);
    }

    public void setQueueType(QueueType queueType)
    {
        manager.updateSettings(() -> this.queueType = queueType);
    }
}
