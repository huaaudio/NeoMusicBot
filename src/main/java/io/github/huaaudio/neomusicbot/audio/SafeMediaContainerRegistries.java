package io.github.huaaudio.neomusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;

import java.util.ArrayList;
import java.util.List;

/** Container registries which cannot turn untrusted playlists into arbitrary requests. */
final class SafeMediaContainerRegistries
{
    private static final String PLAYLIST_PACKAGE =
            "com.sedmelluq.discord.lavaplayer.container.playlists";
    private static final List<MediaContainerProbe> DIRECT_AUDIO_PROBES =
            MediaContainerRegistry.DEFAULT_REGISTRY.getAll().stream()
                    .filter(probe -> !isPlaylistProbe(probe))
                    .toList();
    private static final MediaContainerRegistry DIRECT_AUDIO =
            new MediaContainerRegistry(DIRECT_AUDIO_PROBES);

    private SafeMediaContainerRegistries()
    {
    }

    static MediaContainerRegistry directAudio()
    {
        return DIRECT_AUDIO;
    }

    static MediaContainerRegistry directAudioAndSafeHls(HttpInterfaceManager hlsHttpManager)
    {
        List<MediaContainerProbe> probes = new ArrayList<>(DIRECT_AUDIO_PROBES);
        probes.add(new SafeM3uPlaylistContainerProbe(hlsHttpManager));
        return new MediaContainerRegistry(List.copyOf(probes));
    }

    static boolean isPlaylistProbe(MediaContainerProbe probe)
    {
        return probe.getClass().getPackageName().startsWith(PLAYLIST_PACKAGE);
    }
}
