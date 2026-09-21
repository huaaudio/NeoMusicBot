package io.github.huaaudio.neomusicbot.commands.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlashCommandSecurityTest
{
    @Test
    void versionProbeReceivesOnlyItsMinimalEnvironment()
    {
        Map<String, String> parent = new HashMap<>();
        parent.put("PATH", "/usr/bin");
        parent.put("HOME", "/home/musicbot");
        parent.put("JMUSICBOT_DISCORD_TOKEN", "discord-secret");
        parent.put("NEOMUSICBOT_DISCORD_TOKEN", "discord-secret");
        parent.put("JMUSICBOT_YOUTUBE_COOKIES_FILE", "/secret/youtube.txt");
        parent.put("JMUSICBOT_BILIBILI_COOKIES_FILE", "/secret/bilibili.txt");
        parent.put("PO_TOKEN", "po-secret");
        Map<String, String> child = new HashMap<>();
        child.put("INHERITED_SECRET", "must-be-cleared");

        SlashCommandListener.configureProbeEnvironment(child, parent);

        assertEquals("/usr/bin", child.get("PATH"));
        assertEquals("/home/musicbot", child.get("HOME"));
        assertEquals("1", child.get("DENO_NO_UPDATE_CHECK"));
        assertFalse(child.containsKey("JMUSICBOT_DISCORD_TOKEN"));
        assertFalse(child.containsKey("NEOMUSICBOT_DISCORD_TOKEN"));
        assertFalse(child.containsKey("JMUSICBOT_YOUTUBE_COOKIES_FILE"));
        assertFalse(child.containsKey("JMUSICBOT_BILIBILI_COOKIES_FILE"));
        assertFalse(child.containsKey("PO_TOKEN"));
        assertFalse(child.containsKey("INHERITED_SECRET"));
    }
}
