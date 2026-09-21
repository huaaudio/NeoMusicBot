package com.jagrosh.jmusicbot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotConfigDefaultsTest
{
    @Test
    void generatedDefaultContainsEveryRuntimeKeyAndNoLegacyCommandConfig()
    {
        Config defaults = ConfigFactory.parseString(BotConfig.loadDefaultConfig()).resolve();
        for(String key : List.of("token", "owner", "success", "warning", "error", "game", "status",
                "stayinchannel", "songinstatus", "updatealerts", "loglevel", "maxtime",
                "maxytplaylistpages", "alonetimeuntilstop", "playlistsfolder", "skipratio"))
            assertTrue(defaults.hasPath(key), () -> "Missing default config key: " + key);

        for(String removed : List.of("prefix", "lyrics", "eval", "oauth"))
            assertFalse(defaults.hasPath(removed), () -> "Legacy config key remains: " + removed);
    }
}
