/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DiscordAttachmentAudioSourceManagerTest
{
    @Test
    public void acceptsOnlyDiscordAttachmentPaths()
    {
        URI cdn = DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                "https://cdn.discordapp.com/attachments/123/456/audio.ogg?ex=1&is=2&hm=3");
        URI media = DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                "https://media.discordapp.net/attachments/123/456/audio.mp3");

        assertEquals("cdn.discordapp.com", cdn.getHost());
        assertEquals("media.discordapp.net", media.getHost());
    }

    @Test
    public void rejectsHttpAndMaliciousHosts()
    {
        assertThrows(IllegalArgumentException.class, () ->
                DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                        "http://cdn.discordapp.com/attachments/123/456/audio.ogg"));
        assertThrows(IllegalArgumentException.class, () ->
                DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                        "https://cdn.discordapp.com.evil.example/attachments/123/456/audio.ogg"));
        assertThrows(IllegalArgumentException.class, () ->
                DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                        "https://evil.example/attachments/123/456/audio.ogg"));
        assertThrows(IllegalArgumentException.class, () ->
                DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                        "https://cdn.discordapp.com/not-attachments/123/audio.ogg"));
    }

    @Test
    public void rejectsControlAndAuthorityInjection()
    {
        assertThrows(IllegalArgumentException.class, () ->
                DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                        "https://cdn.discordapp.com/attachments/123/456/audio.ogg\nhttps://evil.example"));
        assertThrows(IllegalArgumentException.class, () ->
                DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                        "https://evil.example@cdn.discordapp.com/attachments/123/456/audio.ogg"));
        assertThrows(IllegalArgumentException.class, () ->
                DiscordAttachmentAudioSourceManager.parseAllowlistedUrl(
                        "https://cdn.discordapp.com:444/attachments/123/456/audio.ogg"));
    }
}
