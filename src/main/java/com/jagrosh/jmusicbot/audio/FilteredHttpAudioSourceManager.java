package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.ThreadLocalHttpInterfaceManager;
import org.apache.http.Header;

import java.io.IOException;
import java.util.List;

/** HTTP source whose playback requests always use the public-only context filter. */
final class FilteredHttpAudioSourceManager extends HttpAudioSourceManager
{
    private final ThreadLocalHttpInterfaceManager filteredManager;

    FilteredHttpAudioSourceManager(MediaContainerRegistry registry, List<Header> defaultHeaders)
    {
        super(registry);
        configureBuilder(builder -> YtDlpAudioSourceManager.configureSecureHttpBuilder(builder, defaultHeaders));
        configureRequests(YtDlpAudioSourceManager::configureHttpRequests);
        filteredManager = YtDlpAudioSourceManager.createSecureHttpInterfaceManager(defaultHeaders);
    }

    @Override
    public HttpInterface getHttpInterface()
    {
        return filteredManager.getInterface();
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        try
        {
            filteredManager.close();
        }
        catch (IOException ignored)
        {
            // The shared client is already unusable; there is no recovery path during shutdown.
        }
    }
}
