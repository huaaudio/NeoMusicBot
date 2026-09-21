package com.jagrosh.jmusicbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.read.ListAppender;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ThirdPartyLoggingPolicyTest
{
    @Test
    void rootDebugCannotExposeSignedMediaUrlsFromThirdPartyLibraries() throws Exception
    {
        LoggerContext context = new LoggerContext();
        try
        {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            try(InputStream config = ThirdPartyLoggingPolicyTest.class
                    .getResourceAsStream("/logback.xml"))
            {
                assertNotNull(config);
                configurator.doConfigure(config);
            }

            Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.DEBUG);
            ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                    new ListAppender<>();
            captured.setContext(context);
            captured.start();
            root.addAppender(captured);

            String signed = "https://googlevideo.example/videoplayback?sig=QUERY_SECRET";
            Logger lavaplayer = context.getLogger("com.sedmelluq.discord.lavaplayer.track.playback");
            Logger youtube = context.getLogger("dev.lavalink.youtube.clients");
            Logger httpHeaders = context.getLogger("org.apache.http.headers");
            Logger httpWire = context.getLogger("org.apache.hc.client5.http.wire");

            assertEquals(Level.OFF, lavaplayer.getEffectiveLevel());
            assertEquals(Level.OFF, youtube.getEffectiveLevel());
            assertEquals(Level.OFF, httpHeaders.getEffectiveLevel());
            assertEquals(Level.OFF, httpWire.getEffectiveLevel());
            lavaplayer.error("stream failed {}", signed, new IllegalStateException(signed));
            youtube.warn("extractor failed {}", signed, new IllegalStateException(signed));
            httpHeaders.debug("Cookie: SESSDATA=COOKIE_SECRET {}", signed);
            httpWire.debug("Authorization: Bearer AUTH_SECRET {}", signed);

            assertFalse(captured.list.stream()
                    .map(event -> event.getFormattedMessage() + String.valueOf(event.getThrowableProxy()))
                    .anyMatch(message -> message.contains("QUERY_SECRET")));
        }
        finally
        {
            context.stop();
        }
    }
}
