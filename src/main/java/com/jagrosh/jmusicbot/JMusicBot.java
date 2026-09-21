package com.jagrosh.jmusicbot;

/** Transitional entry point for scripts that explicitly name the former main class. */
@Deprecated(forRemoval = true)
public final class JMusicBot
{
    private JMusicBot() { }

    public static void main(String[] args)
    {
        io.github.huaaudio.neomusicbot.NeoMusicBot.main(args);
    }
}
