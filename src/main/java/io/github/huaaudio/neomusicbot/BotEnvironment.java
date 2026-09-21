package io.github.huaaudio.neomusicbot;

import java.util.Map;

/** Canonical environment names with explicit compatibility for existing installs. */
public final class BotEnvironment
{
    private static final String PREFIX = "NEOMUSICBOT_";

    private BotEnvironment() { }

    public static String value(String name)
    {
        return value(name, System.getenv());
    }

    static String value(String name, Map<String, String> environment)
    {
        if(!name.startsWith(PREFIX))
            throw new IllegalArgumentException("Expected a NEOMUSICBOT environment variable");
        String alias = "JMUSICBOT_" + name.substring(PREFIX.length());
        String current = normalized(environment.get(name));
        String legacy = normalized(environment.get(alias));
        if(current != null && legacy != null)
            throw new IllegalArgumentException("Set only one of " + name + " and its legacy alias " + alias);
        return current == null ? legacy : current;
    }

    private static String normalized(String value)
    {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
