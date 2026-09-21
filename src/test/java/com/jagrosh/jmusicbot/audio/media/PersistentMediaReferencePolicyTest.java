package com.jagrosh.jmusicbot.audio.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PersistentMediaReferencePolicyTest
{
    @Test
    void persistsOnlyStableSourceIdentifiersAndDropsTransientQueryData()
    {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL_SAFE-123&index=2",
                PersistentMediaReferencePolicy.normalize(
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL_SAFE-123&index=2&t=30s&si=secret"));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                PersistentMediaReferencePolicy.normalize("dQw4w9WgXcQ"));
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD",
                PersistentMediaReferencePolicy.normalize("BV1xx411c7mD"));
        assertEquals("https://b23.tv/example?p=2",
                PersistentMediaReferencePolicy.normalize(
                        "https://b23.tv/example?p=2&share_source=copy_link"));
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=3",
                PersistentMediaReferencePolicy.normalize(
                        "https://www.bilibili.com/video/BV1xx411c7mD?p=3&spm_id_from=333"));
        assertEquals("https://soundcloud.com/artist/track",
                PersistentMediaReferencePolicy.normalize(
                        "https://soundcloud.com/artist/track?token=must-not-persist"));
        assertEquals("https://cdn.discordapp.com/attachments/1/2/audio.opus",
                PersistentMediaReferencePolicy.normalize(
                        "https://cdn.discordapp.com/attachments/1/2/audio.opus"));
    }

    @Test
    void rejectsSignedCredentialedAndNonSourceUrls()
    {
        assertThrows(IllegalArgumentException.class, () -> PersistentMediaReferencePolicy.normalize(
                "https://rr1---sn.example.googlevideo.com/videoplayback?sig=secret"));
        assertThrows(IllegalArgumentException.class, () -> PersistentMediaReferencePolicy.normalize(
                "https://cdn.discordapp.com/attachments/1/2/audio.opus?ex=1&hm=secret"));
        assertThrows(IllegalArgumentException.class, () -> PersistentMediaReferencePolicy.normalize(
                "https://user:password@youtube.com/watch?v=dQw4w9WgXcQ"));
        assertThrows(IllegalArgumentException.class, () -> PersistentMediaReferencePolicy.normalize(
                "https://youtube.com.evil.invalid/watch?v=dQw4w9WgXcQ"));
        assertThrows(IllegalArgumentException.class, () -> PersistentMediaReferencePolicy.normalize(
                "https://youtube.com/watch?v=dQw4w9WgXcQ\n--exec"));
        assertThrows(IllegalArgumentException.class, () -> PersistentMediaReferencePolicy.normalize(
                "https://b23.tv/example?p=0"));
        assertThrows(IllegalArgumentException.class, () -> PersistentMediaReferencePolicy.normalize(
                "https://b23.tv/example?p=2&p=3"));
    }
}
