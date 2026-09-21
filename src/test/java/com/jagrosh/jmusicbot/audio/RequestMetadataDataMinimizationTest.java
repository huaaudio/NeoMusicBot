package com.jagrosh.jmusicbot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RequestMetadataDataMinimizationTest
{
    @Test
    void requestInfoRetainsOnlyTheParsedStartTimestamp()
    {
        RequestMetadata.RequestInfo info = new RequestMetadata.RequestInfo(
                "https://youtube.com/watch?v=dQw4w9WgXcQ&t=1m2s&sig=must-not-persist");

        assertEquals(62_000L, info.startTimestamp);
        assertFalse(Arrays.stream(RequestMetadata.RequestInfo.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .anyMatch(field -> field.getType() == String.class));
    }
}
